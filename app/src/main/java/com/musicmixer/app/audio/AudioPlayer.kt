package com.musicmixer.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

private const val TAG = "AudioPlayer"

/**
 * Lightweight previewer for a single URI segment (trimStartMs..trimEndMs).
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var job: Job? = null
    /** Source sample rate of the currently playing file — needed to compute playback rate. */
    @Volatile private var baseSampleRate: Int = 0
    var isPlaying: Boolean = false
        private set

    /** Volume 0.0..1.5 applied to AudioTrack output (1.0 = 100 %). */
    var volume: Float = 1f

    /**
     * Playback speed multiplier (1/8, 1/4, 1/2, 1, 2, 3).
     * Set via [setSpeed] — do not assign directly while playing.
     */
    @Volatile var speed: Float = 1f

    /** Fired from the IO thread during playback. Arg = position in ms from start of clip. */
    var onPositionMs: ((Long) -> Unit)? = null

    /**
     * Change playback speed in real-time.
     * Safe to call from any thread while play() is running.
     */
    @Suppress("FunctionName")
    fun setSpeed_live(newSpeed: Float) {
        speed = newSpeed
        val sr = baseSampleRate
        if (sr > 0) {
            val targetRate = (sr * newSpeed).toInt().coerceIn(1, sr * 8)
            try { audioTrack?.playbackRate = targetRate } catch (_: Exception) {}
        }
    }

    suspend fun play(
        context: Context,
        uri: Uri,
        trimStartMs: Long,
        trimEndMs: Long
    ) = withContext(Dispatchers.IO) {
        isPlaying = true

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; format = fmt; break
                }
            }
            if (trackIdx < 0 || format == null) return@withContext

            extractor.selectTrack(trackIdx)
            // Use SEEK_TO_PREVIOUS_SYNC for most accurate seek position
            extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val sampleRate  = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels    = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf      = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)

            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()

            // Store base rate so setSpeed() can compute the target playback rate
            baseSampleRate = sampleRate
            // Apply volume
            at.setVolume(volume.coerceIn(0f, AudioTrack.getMaxVolume()))
            // Apply initial speed: AudioTrack.playbackRate = sampleRate × speed
            // Writing at sampleRate but playing at sampleRate*speed makes audio faster/slower
            val initialRate = (sampleRate * speed).toInt().coerceIn(1, sampleRate * 8)
            at.playbackRate = initialRate
            audioTrack = at
            at.play()

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo   = MediaCodec.BufferInfo()
            val trimEndUs = trimEndMs * 1000L
            val trimStartUs = trimStartMs * 1000L
            var inputDone  = false
            var outputDone = false

            while (!outputDone && isPlaying) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf  = codec.getInputBuffer(inIdx)!!
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
                        val presentUs = bufInfo.presentationTimeUs
                        // Skip frames before trim start (can happen after SEEK_TO_PREVIOUS_SYNC)
                        if (presentUs >= trimStartUs && presentUs <= trimEndUs) {
                            val bytes = ByteArray(bufInfo.size)
                            outBuf.get(bytes)
                            at.write(bytes, 0, bytes.size)
                            val posMs = (presentUs / 1000L) - trimStartMs
                            onPositionMs?.invoke(posMs.coerceAtLeast(0L))
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            codec.stop()
            codec.release()
            codec = null

        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
            releaseAudioTrack()
            isPlaying = false
        }
    }

    fun stop() {
        isPlaying = false
        baseSampleRate = 0
        // Flush + stop immediately so the AT write loop doesn't keep draining buffered audio
        val at = audioTrack
        audioTrack = null
        if (at != null) {
            try { at.pause() }  catch (_: Exception) {}
            try { at.flush() }  catch (_: Exception) {}
            try { at.stop()  }  catch (_: Exception) {}
            try { at.release() } catch (_: Exception) {}
        }
        job?.cancel()
    }

    private fun releaseAudioTrack() {
        val at = audioTrack ?: return
        audioTrack = null
        try { at.pause()   } catch (_: Exception) {}
        try { at.flush()   } catch (_: Exception) {}
        try { at.stop()    } catch (_: Exception) {}
        try { at.release() } catch (_: Exception) {}
    }
}
