package com.autotube.ai.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Retries [block] on retryable failures (IOException, HTTP 408/429/5xx) with exponential backoff and
 * jitter. Non-retryable errors and cancellation are rethrown immediately.
 */
suspend fun <T> withRetry(
    attempts: Int = 3,
    initialDelayMs: Long = 1_500,
    maxDelayMs: Long = 20_000,
    onRetry: (attempt: Int, error: Throwable) -> Unit = { _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    var delayMs = initialDelayMs
    var last: Throwable? = null
    for (attempt in 1..attempts) {
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            last = e
            if (attempt == attempts || !e.isRetryable()) throw e
            onRetry(attempt, e)
            delay(delayMs + Random.nextLong(0, delayMs / 3 + 1))
            delayMs = min(maxDelayMs, delayMs * 2)
        }
    }
    throw last ?: IllegalStateException("withRetry: no attempts")
}
