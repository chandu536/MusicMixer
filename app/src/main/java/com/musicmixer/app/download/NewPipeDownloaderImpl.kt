package com.musicmixer.app.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed Downloader implementation for NewPipe Extractor.
 * NewPipe Extractor requires a concrete Downloader to make HTTP requests.
 */
class NewPipeDownloaderImpl private constructor(builder: OkHttpClient.Builder) :
    Downloader() {

    private val client: OkHttpClient = builder
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(ReCaptchaException::class, java.io.IOException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = Request.Builder().url(url)

        headers.forEach { (key, values) ->
            values.forEach { value -> requestBuilder.addHeader(key, value) }
        }

        val requestBody: okhttp3.RequestBody? = dataToSend?.let {
            okhttp3.RequestBody.create(null, it)
        }

        requestBuilder.method(httpMethod, requestBody)

        val response: Response = client.newCall(requestBuilder.build()).execute()

        if (response.code == 429) {
            throw ReCaptchaException("reCaptcha required", url)
        }

        val responseBodyToReturn = response.body?.string()
        val latestUrl = response.request.url.toString()

        return ExtractorResponse(
            response.code,
            response.message,
            response.headers.toMultimap(),
            responseBodyToReturn,
            latestUrl
        )
    }

    companion object {
        @Volatile
        private var instance: NewPipeDownloaderImpl? = null

        fun getInstance(): NewPipeDownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: NewPipeDownloaderImpl(
                    OkHttpClient.Builder().addNetworkInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent",
                                    "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                                .build()
                        )
                    }
                ).also { instance = it }
            }
        }
    }
}
