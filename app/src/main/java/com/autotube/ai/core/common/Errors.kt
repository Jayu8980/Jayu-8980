package com.autotube.ai.core.common

import com.autotube.ai.core.model.PipelineStep

/**
 * Error with a message a non-technical user can read. Throw it from any step; the orchestrator turns
 * it into a [com.autotube.ai.core.model.PipelineError] for the error screen.
 */
open class PipelineException(
    val userTitle: String,
    val userMessage: String,
    val retryable: Boolean = true,
    val step: PipelineStep? = null,
    cause: Throwable? = null,
) : Exception("$userTitle: $userMessage", cause)

/** A required API key is missing and no offline fallback exists for this action. */
class MissingApiKeyException(keyLabel: String, step: PipelineStep? = null) : PipelineException(
    userTitle = "API key needed",
    userMessage = "Add your $keyLabel in Settings, or switch this step to an on-device option.",
    retryable = false,
    step = step,
)

/** HTTP error from a remote API, with the status code kept for retry decisions. */
class ApiException(
    val service: String,
    val code: Int,
    val body: String,
    cause: Throwable? = null,
) : PipelineException(
    userTitle = "$service request failed",
    userMessage = when (code) {
        401, 403 -> "$service rejected the API key ($code). Check the key in Settings."
        402 -> "$service says the account has no credit left ($code)."
        404 -> "$service could not find what was requested ($code)."
        408 -> "$service timed out. It will be retried."
        429 -> "$service rate limit reached. Waiting and retrying."
        in 500..599 -> "$service is having problems ($code). It will be retried."
        else -> "$service returned an error ($code)."
    },
    retryable = code == 408 || code == 409 || code == 429 || code >= 500,
    cause = cause,
) {
    override val message: String get() = "$service HTTP $code: ${body.take(500)}"
}

class NetworkPolicyException(message: String) : PipelineException(
    userTitle = "Waiting for Wi-Fi",
    userMessage = message,
    retryable = true,
)

class LowStorageException(freeMb: Long, neededMb: Long) : PipelineException(
    userTitle = "Not enough storage",
    userMessage = "Only $freeMb MB free; at least $neededMb MB is needed. Free some space and retry.",
    retryable = true,
)

fun Throwable.isRetryable(): Boolean = when (this) {
    is PipelineException -> retryable
    is java.io.IOException -> true
    else -> false
}
