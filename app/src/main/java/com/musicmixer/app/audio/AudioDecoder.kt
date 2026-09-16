package com.musicmixer.app.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.musicmixer.app.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "AudioDecoder"

object AudioDecoder {

    /** Extract duration and decode waveform peaks for a URI. Returns null on failure. */
    suspend fun loadTrackMeta(context: Context, uri: Uri, colorIndex: Int): AudioTrack? =
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L
                val rawName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                retriever.release()

                val displayName = rawName?.takeIf { it.isNotBlank() }
                    ?: context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    } ?: uri.lastPathSegment ?: "Track"

                // Fast peak extraction: seek to N evenly spaced points, read one small chunk each
                val peaks = decodePeaksFast(context, uri, durationMs)

                AudioTrack(
                    uri = uri,
                    displayName = displayName
                        .removeSuffix(".mp3").removeSuffix(".wav")
                        .removeSuffix(".ogg").removeSuffix(".m4a").removeSuffix(".aac"),
                    durationMs = durationMs,
                    trimStartMs = 0L,
                    trimEndMs = durationMs,
                    waveformPeaks = peaks,
                    colorIndex = colorIndex
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load $uri", e)
                null
            }
        }

    /**
     * FAST peak extraction: instead of decoding every PCM frame,
     * we seek to [PEAK_COUNT] evenly-spaced positions and read only
     * a single decoded chunk at each position.
     *
     * For a 5-min song this goes from ~8s → ~0.3s on device.
     */
    private const val PEAK_COUNT = 120   // enough for a smooth waveform thumbnail

    private fun decodePeaksFast(context: Context, uri: Uri, durationMs: Long): FloatArray {
        val peaks = FloatArray(PEAK_COUNT)
        if (durationMs <= 0L) return peaks

        val extractor = MediaExtractor()
        var codec: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIdx = i; format = fmt; break
                }
            }
            if (audioTrackIdx < 0 || format == null) return peaks

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)

            extractor.selectTrack(audioTrackIdx)
            codec = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = android.media.MediaCodec.BufferInfo()
            val stepUs = (durationMs * 1000L) / PEAK_COUNT   // microseconds per bucket

            for (bucket in 0 until PEAK_COUNT) {
                val seekUs = bucket * stepUs
                extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                // Feed one input buffer
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size > 0) {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // Drain one output buffer — retry a few times since codec may need warmup
                var peak = 0f
                var attempts = 0
                while (peak == 0f && attempts < 8) {
                    attempts++
                    val outIdx = codec.dequeueOutputBuffer(bufInfo, 5_000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufInfo.size > 0) {
                            val shortBuf = outBuf.asShortBuffer()
                            val count = bufInfo.size / 2
                            var i = 0
                            while (i < count) {
                                val s = kotlin.math.abs(shortBuf.get().toFloat()) / 32768f
                                if (s > peak) peak = s
                                i += channels  // advance one full frame
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    } else if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // ignore format change
                    }
                }
                peaks[bucket] = peak
            }

            codec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Fast peak decode failed for $uri", e)
        } finally {
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }

        // Normalize so the loudest peak = 1.0
        val max = peaks.maxOrNull() ?: 1f
        if (max > 0f) for (i in peaks.indices) peaks[i] = peaks[i] / max
        return peaks
    }
}
