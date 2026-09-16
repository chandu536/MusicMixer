package com.musicmixer.app.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "StreamDownloader"

data class DownloadResult(
    val file: File,
    val title: String,
    val durationMs: Long,
    val thumbnailUrl: String?
)

object StreamDownloader {

    private var initialized = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                    .build()
            )
        }
        .build()

    private fun ensureInit() {
        if (!initialized) {
            NewPipe.init(NewPipeDownloaderImpl.getInstance())
            initialized = true
        }
    }

    suspend fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        ensureInit()

        val isYoutube = url.contains("youtube.com") || url.contains("youtu.be")

        // Try NewPipe first; for YouTube also try cobalt fallback on failure
        var streamUrl: String
        var title = "track_${System.currentTimeMillis()}"
        var durationMs = 0L
        var thumbnailUrl: String? = null
        var ext = "m4a"

        try {
            Log.d(TAG, "NewPipe: extracting $url")
            val info = StreamInfo.getInfo(url)
            title       = info.name.ifBlank { title }
            durationMs  = info.duration * 1000L
            thumbnailUrl = runCatching { info.thumbnails?.firstOrNull()?.url }.getOrNull()

            val streams: List<AudioStream> = info.audioStreams
            if (streams.isEmpty()) throw IllegalStateException("No audio streams")

            val best = streams.maxByOrNull { it.averageBitrate } ?: streams.first()
            streamUrl = best.content
            ext = when {
                streamUrl.contains("webm", ignoreCase = true) ||
                best.format?.toString()?.contains("webm", ignoreCase = true) == true -> "webm"
                streamUrl.contains("opus", ignoreCase = true) -> "opus"
                else -> "m4a"
            }
            Log.d(TAG, "NewPipe OK: $title | ${best.averageBitrate}kbps .$ext")

        } catch (newpipeEx: Exception) {
            Log.w(TAG, "NewPipe failed: ${newpipeEx.message}")

            if (!isYoutube) throw newpipeEx  // for non-YouTube, surface the error

            // ── YouTube fallback: cobalt.tools public API ─────────────────
            Log.d(TAG, "Trying cobalt.tools fallback for YouTube…")
            val cobaltResult = tryCobalt(url)
                ?: throw IllegalStateException(
                    "Could not extract stream.\n\nTry:\n" +
                    "• Paste a SoundCloud or direct audio link\n" +
                    "• YouTube Music links may work better than youtube.com\n" +
                    "• Short videos / clips tend to work; age-restricted videos do not"
                )
            streamUrl    = cobaltResult.first
            title        = cobaltResult.second.ifBlank { title }
            ext          = "mp4"   // cobalt returns audio in mp4 container
        }

        // ── Download the stream ───────────────────────────────────────────
        val outDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9 _\\-]"), "").trim().take(60)
        val outFile = File(outDir, "${safeTitle}_${System.currentTimeMillis()}.$ext")

        val req = Request.Builder()
            .url(streamUrl)
            .header("Referer", "https://www.youtube.com/")
            .build()
        val response = httpClient.newCall(req).execute()
        if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")
        val body = response.body ?: throw IllegalStateException("Empty response body")
        val total = body.contentLength()

        FileOutputStream(outFile).use { fos ->
            body.byteStream().use { input ->
                val buf = ByteArray(16_384)
                var downloaded = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            }
        }

        Log.d(TAG, "Download complete: ${outFile.name} (${outFile.length() / 1024}KB)")
        DownloadResult(outFile, title, durationMs, thumbnailUrl)
    }

    /**
     * cobalt.tools is a free open-source API that can extract YouTube audio.
     * Returns Pair(streamUrl, title) or null on failure.
     */
    private fun tryCobalt(url: String): Pair<String, String>? {
        return try {
            val body = JSONObject().apply {
                put("url", url)
                put("downloadMode", "audio")
                put("audioFormat", "best")
                put("filenameStyle", "basic")
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("https://api.cobalt.tools/")
                .post(body)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val json = JSONObject(resp.body?.string() ?: return null)
            val status = json.optString("status")
            val streamUrl = when (status) {
                "stream", "redirect", "tunnel" -> json.optString("url")
                    .takeIf { it.isNotBlank() }
                else -> null
            } ?: return null

            val title = json.optString("filename", "").removeSuffix(".mp4").removeSuffix(".webm")
            Log.d(TAG, "Cobalt OK: status=$status url=$streamUrl")
            Pair(streamUrl, title)
        } catch (e: Exception) {
            Log.e(TAG, "Cobalt failed: ${e.message}")
            null
        }
    }

    fun isSupportedUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.startsWith("http") || l.contains("youtube") || l.contains("soundcloud") ||
               l.contains("bandcamp") || l.contains("vimeo") || l.contains("mixcloud")
    }

    fun platformLabel(url: String): String {
        val l = url.lowercase()
        return when {
            l.contains("youtube.com") || l.contains("youtu.be") -> "YouTube"
            l.contains("soundcloud.com") -> "SoundCloud"
            l.contains("bandcamp.com")   -> "Bandcamp"
            l.contains("vimeo.com")      -> "Vimeo"
            l.contains("mixcloud.com")   -> "Mixcloud"
            else -> "URL"
        }
    }
}
