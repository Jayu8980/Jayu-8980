package com.autotube.ai.data.remote.llm

import com.autotube.ai.core.model.LlmProviderType
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the LLM for each call from the current settings and stored keys (both can change at any time).
 *
 * Text: forced offline -> on-device model if imported, else none. Otherwise the provider chosen in
 * settings when usable; if it is not, Claude -> OpenAI -> on-device -> none. "Offline templates"
 * always means none, so callers use their deterministic templates.
 *
 * Vision: never offline; the first configured cloud client that supports images, trying the
 * user's chosen cloud provider first.
 */
@Singleton
class LlmProviderImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val claude: ClaudeLlmClient,
    private val openAi: OpenAiLlmClient,
    private val onDevice: OnDeviceLlmClient,
) : LlmProvider {

    override suspend fun text(): LlmClient? {
        val settings = settingsRepository.current()
        if (settings.forceOffline) return onDevice.takeIf { it.isModelAvailable() }

        val preferred: LlmClient? = when (settings.llmProvider) {
            LlmProviderType.CLAUDE -> claude.takeIf { it.isConfigured() }
            LlmProviderType.OPENAI -> openAi.takeIf { it.isConfigured() }
            LlmProviderType.ON_DEVICE -> onDevice.takeIf { it.isModelAvailable() }
            LlmProviderType.OFFLINE_TEMPLATES -> return null
        }
        return preferred
            ?: claude.takeIf { it.isConfigured() }
            ?: openAi.takeIf { it.isConfigured() }
            ?: onDevice.takeIf { it.isModelAvailable() }
    }

    override suspend fun vision(): LlmClient? {
        val settings = settingsRepository.current()
        if (settings.forceOffline) return null
        val ordered: List<LlmClient> = if (settings.llmProvider == LlmProviderType.OPENAI) {
            listOfNotNull(openAi.takeIf { it.isConfigured() }, claude.takeIf { it.isConfigured() })
        } else {
            listOfNotNull(claude.takeIf { it.isConfigured() }, openAi.takeIf { it.isConfigured() })
        }
        return ordered.firstOrNull { it.supportsVision }
    }
}
