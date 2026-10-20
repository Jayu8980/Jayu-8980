package com.autotube.ai.data.google

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Private OkHttp client for Google OAuth / YouTube calls, deliberately separate from the shared
 * network client so uploads get long timeouts and no automatic redirect following (YouTube's
 * resumable protocol answers "308 Resume Incomplete", which must reach our code untouched).
 */
@Singleton
class GoogleHttp @Inject constructor() {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Suspends until the call completes. Cancelling the coroutine cancels the HTTP call.
 * The caller owns (and must close) the returned [Response].
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    // Failing to cancel is harmless: the call is abandoned and its response closed in onResponse.
    cont.invokeOnCancellation { runCatching { this@await.cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
