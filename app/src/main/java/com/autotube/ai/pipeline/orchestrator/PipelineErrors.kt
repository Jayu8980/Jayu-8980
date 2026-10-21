package com.autotube.ai.pipeline.orchestrator

import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.isRetryable
import com.autotube.ai.core.model.PipelineError
import com.autotube.ai.core.model.PipelineStep

/** Turns any failure into a [PipelineError] a non-technical user can read. */
internal object PipelineErrors {

    private const val TECHNICAL_MAX_CHARS = 4_000
    private const val STACK_FRAMES = 12

    fun from(error: Throwable, step: PipelineStep?): PipelineError {
        val technical = technicalSummary(error)
        if (error is PipelineException) {
            return PipelineError(
                step = error.step ?: step,
                title = error.userTitle,
                message = error.userMessage,
                technical = technical,
                retryable = error.retryable,
            )
        }
        val (title, message) = genericMessage(error, step)
        return PipelineError(
            step = step,
            title = title,
            message = message,
            technical = technical,
            // Unknown errors are usually worth one more try from the error screen.
            retryable = error.isRetryable() || error !is IllegalArgumentException,
        )
    }

    /** Exception chain plus the head of the stack trace; never contains request headers or keys. */
    fun technicalSummary(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            val e: Throwable = current
            if (depth > 0) append("\nCaused by: ")
            append(e.javaClass.name)
            val msg = e.message
            if (msg != null) append(": ").append(msg.take(600))
            for (frame in e.stackTrace.take(if (depth == 0) STACK_FRAMES else 4)) {
                append("\n    at ").append(frame.toString())
            }
            val next = e.cause
            current = if (next === e) null else next
            depth++
        }
    }.take(TECHNICAL_MAX_CHARS)

    private fun genericMessage(error: Throwable, step: PipelineStep?): Pair<String, String> {
        if (error is OutOfMemoryError) {
            return "Out of memory" to
                "The phone ran out of memory. Close other apps and try again, or choose a shorter video."
        }
        if (error is java.io.IOException) {
            return "Connection or file problem" to
                "A network or storage error interrupted ${step?.title?.lowercase() ?: "the job"}. Check your connection and free space, then retry."
        }
        return when (step) {
            null -> "Could not start" to "Something went wrong before the video job could start. Please try again."
            PipelineStep.RESEARCH -> "Topic research failed" to "Could not find topic ideas for this niche. Try again or type a more specific niche."
            PipelineStep.SCRIPT -> "Script writing failed" to "The script could not be written. Try again, or switch to offline templates in Settings."
            PipelineStep.LENGTH_CHECK -> "Length check failed" to "The script could not be fitted to the chosen length. Try again or pick a different length."
            PipelineStep.VOICE -> "Voiceover failed" to "The voice could not be generated. Try again or switch to the on-device voice in Settings."
            PipelineStep.CAPTIONS -> "Captions failed" to "Word timings for the captions could not be created. Try again or switch to on-device timing."
            PipelineStep.VISUALS -> "Finding visuals failed" to "Not enough pictures or clips could be prepared for the scenes. Try again."
            PipelineStep.EDIT_PLAN -> "Editing plan failed" to "The AI editor could not plan the cuts. Try again."
            PipelineStep.MUSIC -> "Sound mix failed" to "Music and sound effects could not be mixed with the voice. Try again."
            PipelineStep.RENDER -> "Rendering failed" to "Rendering failed on this device's video encoder. Close other apps and try again."
            PipelineStep.QUALITY_CHECK -> "Quality check failed" to "The finished video could not be checked. Try again."
            PipelineStep.SEO -> "Export failed" to "The title, thumbnail or gallery copy could not be created. Try again."
            PipelineStep.UPLOAD -> "Upload failed" to "The video could not be uploaded to YouTube. Try again later."
        }
    }
}
