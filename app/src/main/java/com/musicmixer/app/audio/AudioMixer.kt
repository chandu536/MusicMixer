package com.musicmixer.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.musicmixer.app.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "AudioMixer"
private const val TARGET_SAMPLE_RATE = 44100
private const val TARGET_CHANNELS = 2
private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM

/**
 * Mixes multiple AudioTrack objects (with their trim points) into a single PCM WAV file.
 * Tracks are concatenated sequentially (one after another).
 *
 * onProgress(0..100) is called on the main thread.
 */
object AudioMixer {

    suspend fun mix(
        context: Context,
        tracks: List<AudioTrack>,
        masterVolume: Float = 1f,
        applyFade: Boolean = false,
        onProgress: suspend (Int, String) -> Unit
    ): File = withContext(Dispatchers.IO) {

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "mix_${System.currentTimeMillis()}.wav")

        // We'll collect all PCM samples from each track (trimmed) into a combined buffer
        // then write the WAV header + samples.

        // First pass: determine total sample count (including silence delays)
        var totalSamples = 0L
        for (track in tracks) {
            val trimMs = track.trimmedDurationMs + track.delayBeforeMs + track.delayAfterMs
            totalSamples += (trimMs / 1000.0 * TARGET_SAMPLE_RATE).toLong() * TARGET_CHANNELS
        }

        FileOutputStream(outFile).use { fos ->
            // Write placeholder WAV header (will seek-back later)
            writeWavHeader(fos, totalSamples * BYTES_PER_SAMPLE)

            var globalProgress = 0
            for ((trackIdx, track) in tracks.withIndex()) {
                if (!coroutineContext.isActive) break
                val trackLabel = track.displayName
                onProgress(globalProgress, "Processing: $trackLabel")

                // Write silence before the clip
                if (track.delayBeforeMs > 0) {
                    fos.write(silenceBytes(track.delayBeforeMs))
                }

                decodeTrimmedPcm(
                    context = context,
                    track = track,
                    masterVolume = masterVolume * (track.volumePercent / 100f),
                    applyFade = applyFade
                ) { chunk ->
                    fos.write(chunk)
                }

                // Write silence after the clip
                if (track.delayAfterMs > 0) {
                    fos.write(silenceBytes(track.delayAfterMs))
                }

                globalProgress = ((trackIdx + 1f) / tracks.size * 95).roundToInt()
                onProgress(globalProgress, "Track ${trackIdx + 1}/${tracks.size} done")
            }

            onProgress(98, "Finalising WAV…")
        }

        // Rewrite WAV header with final sizes
        fixWavHeader(outFile)
        onProgress(100, "Done!")
        outFile
    }

    // ──────────────────────────────────────────────────────────────────────
    // Decode a single track (trimmed), resample to TARGET_SAMPLE_RATE,
    // and yield raw PCM bytes in chunks via [onChunk].
    // ──────────────────────────────────────────────────────────────────────
    private suspend fun decodeTrimmedPcm(
        context: Context,
        track: AudioTrack,
        masterVolume: Float,
        applyFade: Boolean,
        onChunk: (ByteArray) -> Unit
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, track.uri, null)

            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIdx = i
                    format = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || format == null) return

            extractor.selectTrack(audioTrackIdx)

            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val mimeType = format.getString(MediaFormat.KEY_MIME)!!

            // Seek to trim start
            extractor.seekTo(track.trimStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            val trimEndUs = track.trimEndMs * 1000L
            val trimStartUs = track.trimStartMs * 1000L
            var inputDone = false
            var outputDone = false

            // Accumulate raw PCM here, then resample if needed
            val pcmBuffer = ArrayList<Short>(65536)

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        val sampleTimeUs = extractor.sampleTime
                        if (size < 0 || sampleTimeUs > trimEndUs) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufInfo.size > 0) {
                        val presentationUs = bufInfo.presentationTimeUs
                        // Only take samples within trim window
                        if (presentationUs >= trimStartUs && presentationUs <= trimEndUs) {
                            val shortBuf = outBuf.asShortBuffer()
                            val count = bufInfo.size / 2
                            repeat(count) { pcmBuffer.add(shortBuf.get()) }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop()
            codec.release()

            // Resample srcChannels → TARGET_CHANNELS, srcSampleRate → TARGET_SAMPLE_RATE
            val resampled = resampleAndMix(
                pcm = pcmBuffer.toShortArray(),
                srcChannels = srcChannels,
                srcRate = srcSampleRate,
                dstChannels = TARGET_CHANNELS,
                dstRate = TARGET_SAMPLE_RATE
            )

            // Apply fade + volume
            val totalFrames = resampled.size / TARGET_CHANNELS
            val fadeSamples = if (applyFade) min(totalFrames / 10, (TARGET_SAMPLE_RATE * 0.5).toInt()) else 0

            val outBytes = ByteArray(resampled.size * 2)
            val outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in resampled.indices) {
                val frame = i / TARGET_CHANNELS
                var gain = masterVolume
                if (fadeSamples > 0) {
                    when {
                        frame < fadeSamples -> gain *= (frame.toFloat() / fadeSamples)
                        frame > totalFrames - fadeSamples -> gain *= ((totalFrames - frame).toFloat() / fadeSamples)
                    }
                }
                val sample = (resampled[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
                outBuf.putShort(sample)
            }
            onChunk(outBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Decode failed for ${track.displayName}", e)
        } finally {
            extractor.release()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Generate a block of silent (zero) PCM bytes for the given duration
    // ──────────────────────────────────────────────────────────────────────
    private fun silenceBytes(durationMs: Long): ByteArray {
        val samples = (durationMs / 1000.0 * TARGET_SAMPLE_RATE).toLong() * TARGET_CHANNELS
        return ByteArray((samples * BYTES_PER_SAMPLE).toInt())
    }

    // ──────────────────────────────────────────────────────────────────────
    // Simple linear resample + channel conversion
    // ──────────────────────────────────────────────────────────────────────
    private fun resampleAndMix(
        pcm: ShortArray,
        srcChannels: Int,
        srcRate: Int,
        dstChannels: Int,
        dstRate: Int
    ): ShortArray {
        val srcFrames = pcm.size / srcChannels
        val ratio = srcRate.toDouble() / dstRate
        val dstFrames = (srcFrames / ratio).toInt()
        val out = ShortArray(dstFrames * dstChannels)

        for (dstFrame in 0 until dstFrames) {
            val srcFrame = (dstFrame * ratio).toInt().coerceIn(0, srcFrames - 1)
            // Mix down or up channels
            for (dstCh in 0 until dstChannels) {
                val srcCh = if (srcChannels == 1) 0 else dstCh.coerceIn(0, srcChannels - 1)
                out[dstFrame * dstChannels + dstCh] = pcm[srcFrame * srcChannels + srcCh]
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────────────
    // WAV header (44 bytes)
    // ──────────────────────────────────────────────────────────────────────
    private fun writeWavHeader(fos: FileOutputStream, dataBytes: Long) {
        val header = buildWavHeader(dataBytes)
        fos.write(header)
    }

    private fun buildWavHeader(dataBytes: Long): ByteArray {
        val byteRate = (TARGET_SAMPLE_RATE * TARGET_CHANNELS * BYTES_PER_SAMPLE).toLong()
        val blockAlign = (TARGET_CHANNELS * BYTES_PER_SAMPLE)
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt((dataBytes + 36).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)            // subchunk size
        buf.putShort(1)           // PCM format
        buf.putShort(TARGET_CHANNELS.toShort())
        buf.putInt(TARGET_SAMPLE_RATE)
        buf.putInt(byteRate.toInt())
        buf.putShort(blockAlign.toShort())
        buf.putShort((BYTES_PER_SAMPLE * 8).toShort())
        buf.put("data".toByteArray())
        buf.putInt(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return buf.array()
    }

    private fun fixWavHeader(file: File) {
        val fileSize = file.length()
        val dataBytes = fileSize - 44
        val header = buildWavHeader(dataBytes)
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(0)
        raf.write(header)
        raf.close()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Compress a WAV file for WhatsApp by downsampling to mono 22050 Hz PCM.
    // No MediaCodec / MediaMuxer — pure stream processing, works on all devices.
    // Stereo 44100 Hz 16-bit → mono 22050 Hz 16-bit ≈ 4× size reduction.
    // Always compresses regardless of file size so the button always works.
    // Returns a new smaller WAV File.
    // ──────────────────────────────────────────────────────────────────────
    suspend fun compressForWhatsApp(
        context: Context,
        wavFile: File,
        @Suppress("UNUSED_PARAMETER") maxBytes: Long = 15L * 1024 * 1024,
        onProgress: suspend (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {

        Log.d(TAG, "compressForWhatsApp: input=${wavFile.name} size=${wavFile.length()} bytes")
        onProgress(0)

        val outDir  = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "mix_wa_${System.currentTimeMillis()}.wav")

        // Source WAV is TARGET_SAMPLE_RATE (44100), TARGET_CHANNELS (2), 16-bit PCM.
        // Destination: mono (1 ch), 22050 Hz, 16-bit PCM.
        val DST_RATE      = 22050
        val DST_CHANNELS  = 1
        val SRC_FRAME_BYTES = TARGET_CHANNELS * BYTES_PER_SAMPLE  // 4 bytes per source frame

        val srcFileLen = wavFile.length()
        if (srcFileLen < 44) {
            Log.e(TAG, "compressForWhatsApp: source file too small (${srcFileLen} bytes), aborting")
            throw IllegalStateException("Source WAV is invalid (${srcFileLen} bytes)")
        }
        val pcmBytes = srcFileLen - 44L   // raw PCM bytes in source

        java.io.FileInputStream(wavFile).use { fis ->
            fis.skip(44)   // skip WAV header

            FileOutputStream(outFile).use { fos ->
                // Write placeholder header — we'll fix sizes at the end
                val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                hdr.put("RIFF".toByteArray())
                hdr.putInt(0)                               // placeholder RIFF size
                hdr.put("WAVE".toByteArray())
                hdr.put("fmt ".toByteArray())
                hdr.putInt(16)
                hdr.putShort(1)                             // PCM
                hdr.putShort(DST_CHANNELS.toShort())
                hdr.putInt(DST_RATE)
                hdr.putInt(DST_RATE * DST_CHANNELS * BYTES_PER_SAMPLE)
                hdr.putShort((DST_CHANNELS * BYTES_PER_SAMPLE).toShort())
                hdr.putShort((BYTES_PER_SAMPLE * 8).toShort())
                hdr.put("data".toByteArray())
                hdr.putInt(0)                               // placeholder data size
                fos.write(hdr.array())

                // Read source in 4096-frame chunks.
                // Keep every other source frame (decimate ×2: 44100 → 22050 Hz).
                // Average L+R channels → mono.
                val CHUNK_FRAMES = 4096
                val readBuf  = ByteArray(CHUNK_FRAMES * SRC_FRAME_BYTES)
                val writeBuf = ByteArray(CHUNK_FRAMES / 2 * DST_CHANNELS * BYTES_PER_SAMPLE)

                var totalRead = 0L
                var lastPct   = 0
                var keepFrame = true

                while (true) {
                    val n = fis.read(readBuf)
                    if (n <= 0) break

                    val srcBuf = ByteBuffer.wrap(readBuf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                    val dstBuf = ByteBuffer.wrap(writeBuf).order(ByteOrder.LITTLE_ENDIAN)
                    dstBuf.clear()

                    var i = 0
                    while (i + SRC_FRAME_BYTES <= n) {
                        val left  = srcBuf.getShort(i).toInt()
                        val right = srcBuf.getShort(i + 2).toInt()
                        if (keepFrame) {
                            val mono = ((left + right) / 2).coerceIn(-32768, 32767).toShort()
                            dstBuf.putShort(mono)
                        }
                        keepFrame = !keepFrame
                        i += SRC_FRAME_BYTES
                    }

                    fos.write(writeBuf, 0, dstBuf.position())

                    totalRead += n
                    val pct = if (pcmBytes > 0) ((totalRead * 100L) / pcmBytes).toInt().coerceIn(0, 99)
                              else 0
                    if (pct >= lastPct + 5) { lastPct = pct; onProgress(pct) }
                }
            }
        }

        // Patch RIFF and data chunk sizes in the header with correct little-endian values
        val actualPcm  = outFile.length() - 44L
        val riffSize   = (actualPcm + 36L).toInt()
        val dataSize   = actualPcm.toInt()
        java.io.RandomAccessFile(outFile, "rw").use { raf ->
            // RIFF size at byte 4 — little-endian
            raf.seek(4)
            raf.write(riffSize and 0xFF)
            raf.write((riffSize shr 8) and 0xFF)
            raf.write((riffSize shr 16) and 0xFF)
            raf.write((riffSize shr 24) and 0xFF)
            // data size at byte 40 — little-endian
            raf.seek(40)
            raf.write(dataSize and 0xFF)
            raf.write((dataSize shr 8) and 0xFF)
            raf.write((dataSize shr 16) and 0xFF)
            raf.write((dataSize shr 24) and 0xFF)
        }

        Log.d(TAG, "compressForWhatsApp: output=${outFile.name} size=${outFile.length()} bytes " +
                   "(${String.format("%.1f", outFile.length() / 1_048_576f)} MB)")
        onProgress(100)
        outFile
    }
}
