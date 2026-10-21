package com.autotube.ai.data.remote.llm

import android.content.Context
import android.util.Log
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.SettingsRepository
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Fully offline LLM through MediaPipe LLM Inference with an imported Gemma `.task` model
 * (path in [com.autotube.ai.core.model.AppSettings.onDeviceModelPath]).
 *
 * The engine is created lazily, cached per model path and closed when the path changes. Calls are
 * serialized with a [Mutex] (one generation at a time) and run on [Dispatchers.Default]. The model's
 * context is small (4096 tokens for prompt + answer), so long prompts are trimmed: the system prompt
 * keeps its beginning, the user prompt keeps its beginning and its end (where output-format rules live).
 */
@Singleton
class OnDeviceLlmClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) : LlmClient {

    override val displayName: String = "On-device Gemma"
    override val supportsVision: Boolean = false

    private val mutex = Mutex()
    private var engine: LlmInference? = null
    private var enginePath: String? = null

    /** Absolute path of a usable model file, or null. */
    fun modelPath(): String? {
        val path = settingsRepository.current().onDeviceModelPath?.trim()
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        return if (file.isFile && file.length() > 0) file.absolutePath else null
    }

    fun isModelAvailable(): Boolean = modelPath() != null

    override suspend fun complete(request: LlmRequest): String {
        val path = modelPath() ?: throw PipelineException(
            userTitle = "On-device AI model missing",
            userMessage = "Import a Gemma .task model in Settings, or add a Claude or OpenAI key.",
            retryable = false,
        )
        val prompt = buildPrompt(request)
        val raw = mutex.withLock {
            withContext(Dispatchers.Default) {
                val llm = obtainEngine(path)
                try {
                    llm.generateResponse(prompt).orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "On-device generation failed", e)
                    throw PipelineException(
                        userTitle = "On-device AI failed",
                        userMessage = "The on-device model could not finish its answer. Retrying usually helps; " +
                            "closing other apps frees memory.",
                        retryable = true,
                        cause = e,
                    )
                }
            }
        }
        val text = cleanOutput(raw)
        if (text.isEmpty()) {
            throw PipelineException(
                userTitle = "Empty AI answer",
                userMessage = "The on-device model returned no text. Retrying usually fixes this.",
                retryable = true,
            )
        }
        return text
    }

    /** Frees the native engine (for example when the user deletes the model). */
    suspend fun release() {
        mutex.withLock { closeEngine() }
    }

    /** Must be called while holding [mutex]. */
    private fun obtainEngine(path: String): LlmInference {
        val cached = engine
        if (cached != null && enginePath == path) return cached
        closeEngine()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(ENGINE_MAX_TOKENS)
            .build()
        val created = try {
            LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            throw loadFailure(e)
        } catch (e: OutOfMemoryError) {
            throw loadFailure(e)
        }
        engine = created
        enginePath = path
        Log.i(TAG, "Loaded on-device model ${File(path).name}")
        return created
    }

    private fun loadFailure(cause: Throwable): PipelineException {
        Log.w(TAG, "Could not load on-device model", cause)
        return PipelineException(
            userTitle = "On-device AI model could not load",
            userMessage = "Make sure the imported file is a MediaPipe LLM model (.task) and the phone has enough free memory.",
            retryable = false,
            cause = cause,
        )
    }

    private fun closeEngine() {
        engine?.let { runCatching { it.close() } }
        engine = null
        enginePath = null
    }

    private fun buildPrompt(request: LlmRequest): String {
        val system = buildString {
            append(request.system.trim())
            if (request.json) {
                if (isNotEmpty()) append("\n\n")
                append(JSON_ONLY_INSTRUCTION)
            }
        }
        val user = request.user.trim()

        val outputReserve = request.maxTokens.coerceIn(MIN_OUTPUT_RESERVE, MAX_OUTPUT_RESERVE)
        val inputBudget = ENGINE_MAX_TOKENS - outputReserve - TEMPLATE_OVERHEAD_TOKENS
        val systemTokens = estimateTokens(system)
        val userTokens = estimateTokens(user)

        val (fittedSystem, fittedUser) = if (systemTokens + userTokens <= inputBudget) {
            system to user
        } else {
            val systemAllowed = min(systemTokens, max(inputBudget / 3, inputBudget - userTokens))
            val userAllowed = inputBudget - systemAllowed
            keepHead(system, systemTokens, systemAllowed) to keepHeadAndTail(user, userTokens, userAllowed)
        }

        return buildString {
            append("<start_of_turn>user\n")
            if (fittedSystem.isNotEmpty()) append(fittedSystem).append("\n\n")
            append(fittedUser)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }
    }

    /** Rough Gemma token estimate: ~3.5 chars per token for ASCII, ~1.5 for other scripts (Devanagari). */
    private fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 0x80) ascii++ else other++
        return (ascii / 3.5 + other / 1.5).toInt() + 1
    }

    private fun allowedChars(text: String, tokens: Int, allowedTokens: Int): Int {
        if (tokens <= 0) return text.length
        return (text.length.toLong() * allowedTokens.coerceAtLeast(0) / tokens).toInt().coerceIn(0, text.length)
    }

    private fun keepHead(text: String, tokens: Int, allowedTokens: Int): String {
        if (tokens <= allowedTokens) return text
        val chars = allowedChars(text, tokens, allowedTokens)
        return text.take(chars).trimEnd()
    }

    private fun keepHeadAndTail(text: String, tokens: Int, allowedTokens: Int): String {
        if (tokens <= allowedTokens) return text
        val chars = allowedChars(text, tokens, allowedTokens) - ELISION.length
        if (chars <= 0) return text.takeLast(max(0, allowedChars(text, tokens, allowedTokens)))
        val head = (chars * 0.6).toInt()
        val tail = chars - head
        return text.take(head).trimEnd() + ELISION + text.takeLast(tail).trimStart()
    }

    private fun cleanOutput(raw: String): String =
        raw.replace("<end_of_turn>", "")
            .replace("<start_of_turn>model", "")
            .replace("<eos>", "")
            .trim()

    private companion object {
        const val TAG = "OnDeviceLlmClient"
        const val ENGINE_MAX_TOKENS = 4096
        const val MIN_OUTPUT_RESERVE = 512
        const val MAX_OUTPUT_RESERVE = 1536
        const val TEMPLATE_OVERHEAD_TOKENS = 32
        const val ELISION = "\n...\n"
        const val JSON_ONLY_INSTRUCTION = "Respond with only valid JSON, no prose, no code fences."
    }
}
