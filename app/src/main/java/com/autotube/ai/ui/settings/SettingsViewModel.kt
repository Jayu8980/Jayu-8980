package com.autotube.ai.ui.settings

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.LlmProviderType
import com.autotube.ai.domain.AuthResult
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeAuth
import com.autotube.ai.ui.components.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Settings-screen state that does not live in [AppSettings]. */
data class SettingsLocalState(
    val voices: List<VoiceOption> = emptyList(),
    val voicesLoading: Boolean = false,
    val voicesLanguage: AppLanguage? = null,
    /** 0..1 while a model is being imported, null otherwise. */
    val modelImportProgress: Float? = null,
    val musicImportMood: Emotion = Emotion.CALM,
    val musicImportProgress: Float? = null,
    val importedMusic: Map<Emotion, Int> = emptyMap(),
    val storageUsedBytes: Long? = null,
    val freeBytes: Long? = null,
    val clearingTemp: Boolean = false,
    val signingIn: Boolean = false,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keyDrafts: Map<ApiKey, String> = emptyMap(),
    val keysPresent: Set<ApiKey> = emptySet(),
    val local: SettingsLocalState = SettingsLocalState(),
) {
    val onDeviceModelName: String? get() = settings.onDeviceModelPath?.let { File(it).name }
    val signedInLabel: String?
        get() = settings.googleAccountEmail?.let { if ('@' in it) it else "Google account connected" }
}

sealed interface SettingsEvent {
    data class LaunchConsent(val pendingIntent: PendingIntent) : SettingsEvent
    data class Message(val text: String) : SettingsEvent
}

/** All app settings, API keys, imports (model / music), storage and Google account. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val projectRepository: ProjectRepository,
    private val projectFiles: ProjectFiles,
    private val youTubeAuth: YouTubeAuth,
    private val voiceCatalog: OnDeviceVoiceCatalog,
    private val copier: UriFileCopier,
) : ViewModel() {

    private val keyDrafts = MutableStateFlow<Map<ApiKey, String>>(emptyMap())
    private val local = MutableStateFlow(SettingsLocalState())
    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = _events.receiveAsFlow()
    private var modelJob: Job? = null
    private var musicJob: Job? = null

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        keyDrafts,
        settingsRepository.apiKeysPresent,
        local,
    ) { s, drafts, present, l ->
        SettingsUiState(settings = s, keyDrafts = drafts, keysPresent = present, local = l)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(settings = settingsRepository.current()))

    init {
        viewModelScope.launch {
            keyDrafts.value = withContext(Dispatchers.IO) {
                ApiKey.entries.associateWith { settingsRepository.apiKey(it).orEmpty() }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.map { it.defaultLanguage }.distinctUntilChanged().collect { loadVoices(it) }
        }
        refreshMusicCounts()
        refreshStorage()
    }

    // ------------------------------------------------------------ settings

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                settingsRepository.update(transform)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not save the setting: ${e.message ?: "unknown error"}"))
            }
        }
    }

    // ------------------------------------------------------------ API keys

    fun onKeyDraftChange(key: ApiKey, value: String) {
        keyDrafts.update { it + (key to value) }
    }

    /** Saves one key if it differs from what is stored. */
    fun saveKey(key: ApiKey, announce: Boolean = false) {
        val value = keyDrafts.value[key].orEmpty().trim()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stored = settingsRepository.apiKey(key).orEmpty()
                    if (stored != value) settingsRepository.setApiKey(key, value.ifBlank { null })
                }
                if (announce) _events.send(SettingsEvent.Message(if (value.isBlank()) "${key.label} removed." else "${key.label} saved."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not save the key: ${e.message ?: "unknown error"}"))
            }
        }
    }

    fun saveAllKeys() {
        val drafts = keyDrafts.value
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiKey.entries.forEach { key ->
                        val value = drafts[key].orEmpty().trim()
                        if (settingsRepository.apiKey(key).orEmpty() != value) settingsRepository.setApiKey(key, value.ifBlank { null })
                    }
                }
                _events.send(SettingsEvent.Message("API keys saved."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not save the keys: ${e.message ?: "unknown error"}"))
            }
        }
    }

    // ------------------------------------------------------------ on-device model

    fun importModel(uri: Uri) {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            local.update { it.copy(modelImportProgress = 0f) }
            try {
                val picked = withContext(Dispatchers.IO) { copier.describe(uri) }
                val lower = picked.displayName.lowercase()
                if (!(lower.endsWith(".task") || lower.endsWith(".bin") || lower.endsWith(".litertlm"))) {
                    _events.send(SettingsEvent.Message("Pick a MediaPipe model file ending in .task (for example a Gemma .task file)."))
                    return@launch
                }
                val free = withContext(Dispatchers.IO) { projectFiles.freeBytes() }
                if (picked.sizeBytes > 0 && picked.sizeBytes + 200L * 1024 * 1024 > free) {
                    _events.send(SettingsEvent.Message("Not enough free space: the model needs ${Format.bytes(picked.sizeBytes)}."))
                    return@launch
                }
                val dir = projectFiles.modelsDir()
                val file = copier.copy(uri, dir, picked.displayName, picked.sizeBytes) { p ->
                    local.update { it.copy(modelImportProgress = p) }
                }
                val previous = settingsRepository.current().onDeviceModelPath
                settingsRepository.update {
                    it.copy(
                        onDeviceModelPath = file.absolutePath,
                        llmProvider = if (it.llmProvider == LlmProviderType.OFFLINE_TEMPLATES) LlmProviderType.ON_DEVICE else it.llmProvider,
                    )
                }
                if (previous != null && previous != file.absolutePath) {
                    withContext(Dispatchers.IO) {
                        val old = File(previous)
                        if (old.parentFile?.canonicalPath == dir.canonicalPath) old.delete()
                    }
                }
                _events.send(SettingsEvent.Message("Model imported (${Format.bytes(file.length())})."))
                refreshStorage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not import the model: ${e.message ?: "unknown error"}"))
            } finally {
                local.update { it.copy(modelImportProgress = null) }
            }
        }
    }

    fun removeModel() {
        viewModelScope.launch {
            val path = settingsRepository.current().onDeviceModelPath ?: return@launch
            settingsRepository.update {
                it.copy(
                    onDeviceModelPath = null,
                    llmProvider = if (it.llmProvider == LlmProviderType.ON_DEVICE) LlmProviderType.OFFLINE_TEMPLATES else it.llmProvider,
                )
            }
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (file.parentFile?.canonicalPath == projectFiles.modelsDir().canonicalPath) file.delete()
            }
            _events.send(SettingsEvent.Message("On-device model removed."))
            refreshStorage()
        }
    }

    // ------------------------------------------------------------ voices

    fun loadVoices(language: AppLanguage = settingsRepository.current().defaultLanguage) {
        viewModelScope.launch {
            local.update { it.copy(voicesLoading = true) }
            val voices = try {
                voiceCatalog.voicesFor(language)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            local.update { it.copy(voices = voices, voicesLoading = false, voicesLanguage = language) }
        }
    }

    // ------------------------------------------------------------ music

    fun setMusicImportMood(mood: Emotion) {
        local.update { it.copy(musicImportMood = mood) }
    }

    fun importMusic(uri: Uri) {
        if (musicJob?.isActive == true) return
        val mood = local.value.musicImportMood
        musicJob = viewModelScope.launch {
            local.update { it.copy(musicImportProgress = 0f) }
            try {
                val picked = withContext(Dispatchers.IO) { copier.describe(uri) }
                val dir = File(projectFiles.importedMusicDir(), mood.wireName)
                copier.copy(uri, dir, picked.displayName, picked.sizeBytes) { p ->
                    local.update { it.copy(musicImportProgress = p) }
                }
                _events.send(SettingsEvent.Message("Added \"${picked.displayName}\" for ${mood.wireName} scenes."))
                refreshMusicCounts()
                refreshStorage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not import the track: ${e.message ?: "unknown error"}"))
            } finally {
                local.update { it.copy(musicImportProgress = null) }
            }
        }
    }

    fun clearImportedMusic(mood: Emotion) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(projectFiles.importedMusicDir(), mood.wireName).deleteRecursively() }
            refreshMusicCounts()
            refreshStorage()
        }
    }

    private fun refreshMusicCounts() {
        viewModelScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val root = projectFiles.importedMusicDir()
                Emotion.entries.associateWith { mood ->
                    File(root, mood.wireName).listFiles()?.count { it.isFile && !it.name.startsWith(".") } ?: 0
                }
            }
            local.update { it.copy(importedMusic = counts) }
        }
    }

    // ------------------------------------------------------------ storage

    fun refreshStorage() {
        viewModelScope.launch {
            try {
                val projects = projectRepository.observeProjects().first()
                val (used, free) = withContext(Dispatchers.IO) {
                    val projectBytes = projects.sumOf { runCatching { projectFiles.projectSizeBytes(it.id) }.getOrDefault(0L) }
                    val extra = projectFiles.modelsDir().treeSize() + projectFiles.importedMusicDir().treeSize()
                    (projectBytes + extra) to projectFiles.freeBytes()
                }
                local.update { it.copy(storageUsedBytes = used, freeBytes = free) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(storageUsedBytes = null) }
            }
        }
    }

    /** Deletes temp files of every project that is not currently running. */
    fun clearAllTemp() {
        if (local.value.clearingTemp) return
        viewModelScope.launch {
            local.update { it.copy(clearingTemp = true) }
            try {
                val projects = projectRepository.observeProjects().first()
                projects.filterNot { it.status.isActive }.forEach { projectFiles.clearTemp(it.id, keepFinal = true) }
                refreshStorage()
                val skipped = projects.count { it.status.isActive }
                val note = if (skipped > 0) " ($skipped running video${if (skipped > 1) "s" else ""} skipped)" else ""
                _events.send(SettingsEvent.Message("Temporary files cleared$note."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not clear files: ${e.message ?: "unknown error"}"))
            } finally {
                local.update { it.copy(clearingTemp = false) }
            }
        }
    }

    // ------------------------------------------------------------ Google account

    fun signIn() {
        if (local.value.signingIn) return
        viewModelScope.launch {
            local.update { it.copy(signingIn = true) }
            try {
                handleAuth(youTubeAuth.authorize())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Google sign-in failed: ${e.message ?: "unknown error"}"))
            } finally {
                local.update { it.copy(signingIn = false) }
            }
        }
    }

    fun onConsentResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            _events.trySend(SettingsEvent.Message("Google sign-in was cancelled."))
            return
        }
        viewModelScope.launch {
            local.update { it.copy(signingIn = true) }
            try {
                handleAuth(youTubeAuth.handleConsentResult(data))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Google sign-in failed: ${e.message ?: "unknown error"}"))
            } finally {
                local.update { it.copy(signingIn = false) }
            }
        }
    }

    private suspend fun handleAuth(result: AuthResult) {
        when (result) {
            is AuthResult.Authorized -> {
                // The auth implementation stores the email; make sure "signed in" is recorded even without one.
                settingsRepository.update { it.copy(googleAccountEmail = result.email ?: it.googleAccountEmail ?: SIGNED_IN_NO_EMAIL) }
                _events.send(SettingsEvent.Message(result.email?.let { "Signed in as $it" } ?: "Signed in to Google."))
            }
            is AuthResult.NeedsConsent -> _events.send(SettingsEvent.LaunchConsent(result.pendingIntent))
            is AuthResult.Failed -> _events.send(SettingsEvent.Message(result.message))
        }
    }

    fun signOut(activity: Activity?) {
        viewModelScope.launch {
            try {
                youTubeAuth.signOut(activity)
                settingsRepository.update { it.copy(googleAccountEmail = null) }
                _events.send(SettingsEvent.Message("Signed out of Google."))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(SettingsEvent.Message("Could not sign out: ${e.message ?: "unknown error"}"))
            }
        }
    }

    private companion object {
        const val SIGNED_IN_NO_EMAIL = "Google account"
    }
}
