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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AudioPlayer"

/**
 * Lightweight previewer for a single URI segment (trimStartMs..trimEndMs).
 */
class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private var job: Job? = null
    var isPlaying: Boolean = false
        private set

    /** Fired from the IO thread during playback. Arg = position in ms from start of clip. */
    var onPositionMs: ((Long) -> Unit)? = null

    suspend fun play(
        context: Context,
        uri: Uri,
        trimStartMs: Long,
        trimEndMs: Long
    ) = withContext(Dispatchers.IO) {
        isPlaying = true

        val extractor = MediaExtractor()
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
            extractor.seekTo(trimStartMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrDefault(1)
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
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
            audioTrack = at
            at.play()

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            val trimEndUs = trimEndMs * 1000L
            var inputDone = false
            var outputDone = false

            while (!outputDone && isPlaying) {
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
                    if (outBuf != null && bufInfo.size > 0 &&
                        bufInfo.presentationTimeUs <= trimEndUs) {
                        val bytes = ByteArray(bufInfo.size)
                        outBuf.get(bytes)
                        at.write(bytes, 0, bytes.size)
                        // Fire position callback (clip-relative ms)
                        val posMs = (bufInfo.presentationTimeUs / 1000L) - trimStartMs
                        onPositionMs?.invoke(posMs.coerceAtLeast(0L))
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop()
            codec.release()
        } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
        } finally {
            extractor.release()
            releaseAudioTrack()
            isPlaying = false
        }
    }

    fun stop() {
        isPlaying = false
        releaseAudioTrack()
        job?.cancel()
    }

    private fun releaseAudioTrack() {
        val at = audioTrack ?: return
        audioTrack = null
        try { at.stop() } catch (_: Exception) {}
        try { at.release() } catch (_: Exception) {}
    }
}
