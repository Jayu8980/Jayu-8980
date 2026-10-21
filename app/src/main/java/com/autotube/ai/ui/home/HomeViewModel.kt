package com.autotube.ai.ui.home

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.core.model.LlmProviderType
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.SttProviderType
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.core.model.TtsProviderType
import com.autotube.ai.core.model.VideoFormat
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the user is typing/choosing on the Create screen. */
data class HomeForm(
    val niche: String = "",
    val notes: String = "",
    val format: VideoFormat = VideoFormat.SHORTS,
    val shortsSec: Int = 60,
    val longFormSec: Int = 480,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val showAdvanced: Boolean = false,
) {
    val targetSec: Int get() = if (format == VideoFormat.SHORTS) shortsSec else longFormSec
}

/** Which provider each step will use with the current settings and keys. */
data class ProviderPlan(
    val offline: Boolean = true,
    val script: String = "",
    val voice: String = "",
    val captions: String = "",
    val visuals: String = "",
    val music: String = "",
)

data class DeviceWarnings(
    val lowStorage: Boolean = false,
    val freeMb: Long = 0,
    val requiredMb: Int = 0,
    val batterySaver: Boolean = false,
    val notificationsDisabled: Boolean = false,
    val offline: Boolean = false,
)

data class HomeUiState(
    val form: HomeForm = HomeForm(),
    val plan: ProviderPlan = ProviderPlan(),
    val device: DeviceWarnings = DeviceWarnings(),
    val recent: List<Project> = emptyList(),
    val creating: Boolean = false,
) {
    val canCreate: Boolean get() = form.niche.trim().length >= 2 && !creating
}

sealed interface HomeEvent {
    data class OpenProgress(val projectId: Long) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

/** Create screen: builds a [VideoRequest], creates the project and starts the pipeline. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val projectRepository: ProjectRepository,
    private val workScheduler: WorkScheduler,
    private val deviceStatus: DeviceStatus,
) : ViewModel() {

    private val form = MutableStateFlow(formFrom(settingsRepository.current()))
    private val device = MutableStateFlow(DeviceWarnings())
    private val creating = MutableStateFlow(false)
    private var lastDefaults: AppSettings = settingsRepository.current()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events: Flow<HomeEvent> = _events.receiveAsFlow()

    private val plan: Flow<ProviderPlan> =
        combine(settingsRepository.settings, settingsRepository.apiKeysPresent) { s, keys -> planProviders(s, keys) }

    private val recent: Flow<List<Project>> =
        projectRepository.observeProjects().map { list -> list.sortedByDescending { it.createdAt }.take(3) }

    val uiState: StateFlow<HomeUiState> =
        combine(form, plan, device, recent, creating) { f, p, d, r, c ->
            HomeUiState(form = f, plan = p, device = d, recent = r, creating = c)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(form = form.value))

    init {
        // Follow changed defaults from Settings for fields the user has not changed by hand.
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                val old = lastDefaults
                form.update { f ->
                    f.copy(
                        format = if (f.format == old.defaultFormat) s.defaultFormat else f.format,
                        shortsSec = if (f.shortsSec == old.defaultShortsSec) s.defaultShortsSec.coerceIn(15, 60) else f.shortsSec,
                        longFormSec = if (f.longFormSec == old.defaultLongFormSec) s.defaultLongFormSec.coerceIn(60, 1200) else f.longFormSec,
                        language = if (f.language == old.defaultLanguage) s.defaultLanguage else f.language,
                    )
                }
                lastDefaults = s
            }
        }
        refreshDevice()
    }

    fun onNicheChange(value: String) = form.update { it.copy(niche = value.take(200)) }
    fun onNotesChange(value: String) = form.update { it.copy(notes = value.take(1000)) }
    fun onFormatChange(value: VideoFormat) = form.update { it.copy(format = value) }
    fun onShortsSecChange(value: Int) = form.update { it.copy(shortsSec = value.coerceIn(15, 60)) }
    fun onLongFormSecChange(value: Int) = form.update { it.copy(longFormSec = value.coerceIn(60, 1200)) }
    fun onLanguageChange(value: AppLanguage) = form.update { it.copy(language = value) }
    fun toggleAdvanced() = form.update { it.copy(showAdvanced = !it.showAdvanced) }

    /** Re-reads storage, battery saver and notification state (called on resume). */
    fun refreshDevice() {
        viewModelScope.launch {
            val required = settingsRepository.current().minFreeStorageMb
            val warnings = withContext(Dispatchers.IO) {
                val free = runCatching { deviceStatus.freeStorageMb() }.getOrDefault(Long.MAX_VALUE)
                DeviceWarnings(
                    lowStorage = free < required,
                    freeMb = free,
                    requiredMb = required,
                    batterySaver = runCatching { deviceStatus.isPowerSaveMode() }.getOrDefault(false),
                    notificationsDisabled = !NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    offline = !runCatching { deviceStatus.isOnline() }.getOrDefault(true),
                )
            }
            device.value = warnings
        }
    }

    fun createVideo() {
        val f = form.value
        val niche = f.niche.trim()
        if (niche.length < 2) {
            _events.trySend(HomeEvent.Message("Type a topic or niche first."))
            return
        }
        if (creating.value) return
        creating.value = true
        viewModelScope.launch {
            try {
                val request = VideoRequest(
                    niche = niche,
                    format = f.format,
                    targetDurationSec = f.targetSec,
                    language = f.language,
                    notes = f.notes.trim(),
                )
                val id = projectRepository.createProject(request)
                workScheduler.startPipeline(id)
                form.update { it.copy(niche = "", notes = "") }
                _events.send(HomeEvent.OpenProgress(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(HomeEvent.Message("Could not start the video: ${e.message ?: "unknown error"}"))
            } finally {
                creating.value = false
            }
        }
    }

    private fun formFrom(s: AppSettings) = HomeForm(
        format = s.defaultFormat,
        shortsSec = s.defaultShortsSec.coerceIn(15, 60),
        longFormSec = s.defaultLongFormSec.coerceIn(60, 1200),
        language = s.defaultLanguage,
    )
}

/** Mirrors the providers' fallback rules so the user sees what will actually be used. */
internal fun planProviders(s: AppSettings, keys: Set<ApiKey>): ProviderPlan {
    val anthropic = ApiKey.ANTHROPIC in keys
    val openAi = ApiKey.OPENAI in keys
    val model = !s.onDeviceModelPath.isNullOrBlank()
    val forced = s.forceOffline
    val localLlm = if (model) "On-device LLM" else "Offline templates"

    val script = when {
        forced -> if (model && s.llmProvider != LlmProviderType.OFFLINE_TEMPLATES) "On-device LLM" else "Offline templates"
        else -> when (s.llmProvider) {
            LlmProviderType.CLAUDE -> when {
                anthropic -> "Claude"
                openAi -> "OpenAI"
                else -> localLlm
            }
            LlmProviderType.OPENAI -> when {
                openAi -> "OpenAI"
                anthropic -> "Claude"
                else -> localLlm
            }
            LlmProviderType.ON_DEVICE -> when {
                model -> "On-device LLM"
                anthropic -> "Claude"
                openAi -> "OpenAI"
                else -> "Offline templates"
            }
            LlmProviderType.OFFLINE_TEMPLATES -> "Offline templates"
        }
    }

    val voice = if (forced) {
        "On-device voice"
    } else {
        when (s.ttsProvider) {
            TtsProviderType.ELEVENLABS -> if (ApiKey.ELEVENLABS in keys) "ElevenLabs" else "On-device voice"
            TtsProviderType.OPENAI -> if (openAi) "OpenAI TTS" else "On-device voice"
            TtsProviderType.ON_DEVICE -> "On-device voice"
        }
    }

    val captions = if (!forced && s.sttProvider == SttProviderType.WHISPER && openAi) "Whisper" else "On-device timing"

    val visualParts = mutableListOf<String>()
    if (!forced) {
        if (s.useStockFootage) {
            val stock = listOfNotNull(
                "Pexels".takeIf { ApiKey.PEXELS in keys },
                "Pixabay".takeIf { ApiKey.PIXABAY in keys },
            )
            if (stock.isNotEmpty()) visualParts += stock.joinToString(" + ")
        }
        when (s.textToVideoProvider) {
            TextToVideoProviderType.NONE -> Unit
            TextToVideoProviderType.REPLICATE -> if (ApiKey.REPLICATE in keys) visualParts += "AI video"
            TextToVideoProviderType.CUSTOM -> if (s.customVideoEndpoint.isNotBlank()) visualParts += "AI video"
        }
        when (s.imageGenProvider) {
            ImageGenProviderType.NONE -> Unit
            ImageGenProviderType.OPENAI -> if (openAi) visualParts += "AI images"
            ImageGenProviderType.REPLICATE -> if (ApiKey.REPLICATE in keys) visualParts += "AI images"
        }
    }
    if (s.useGalleryMedia) visualParts += "Your gallery"
    visualParts += "On-device art"

    val hasLlmKey = anthropic || openAi
    val hasTtsKey = openAi || ApiKey.ELEVENLABS in keys
    return ProviderPlan(
        offline = forced || (!hasLlmKey && !hasTtsKey),
        script = script,
        voice = voice,
        captions = captions,
        visuals = visualParts.distinct().joinToString(", "),
        music = "On-device music & SFX",
    )
}
