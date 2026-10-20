package com.autotube.ai.data.remote.http

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Builds the app's single [OkHttpClient].
 *
 * - connect timeout 30 s; read/write 10 min (long LLM and TTS calls, big uploads/downloads);
 * - no overall call timeout (long downloads must not be cut off);
 * - retries on connection failure;
 * - in debug builds a BASIC [HttpLoggingInterceptor] whose output never contains API keys:
 *   key headers are redacted and key-like query parameters (`key=`, `token=` ...) are masked.
 */
object HttpClientFactory {

    private const val LOG_TAG = "AutoTubeHttp"

    private val REDACTED_HEADERS = listOf("x-api-key", "Authorization", "xi-api-key")

    private val SECRET_QUERY_PARAM =
        Regex("(?i)([?&](?:key|api_key|apikey|token|access_token|auth)=)[^&\\s]+")

    fun create(debug: Boolean, userAgent: String): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(userAgentInterceptor(sanitizeHeaderValue(userAgent)))
        if (debug) builder.addInterceptor(loggingInterceptor())
        return builder.build()
    }

    /** Masks API keys that travel in query strings (Pixabay, YouTube Data API). */
    fun redactSecrets(text: String): String = SECRET_QUERY_PARAM.replace(text, "\$1<redacted>")

    private fun userAgentInterceptor(userAgent: String): Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.header("User-Agent") != null || userAgent.isEmpty()) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("User-Agent", userAgent).build())
        }
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor {
        val logger = object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                Log.d(LOG_TAG, redactSecrets(message))
            }
        }
        return HttpLoggingInterceptor(logger).apply {
            level = HttpLoggingInterceptor.Level.BASIC
            REDACTED_HEADERS.forEach { redactHeader(it) }
        }
    }

    /** OkHttp rejects non-ASCII header values; keep only printable ASCII. */
    private fun sanitizeHeaderValue(value: String): String =
        value.filter { it.code in 0x20..0x7E }.trim()
}
