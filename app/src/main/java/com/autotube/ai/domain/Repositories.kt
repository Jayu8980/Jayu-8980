package com.autotube.ai.domain

import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.VideoRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Settings in EncryptedSharedPreferences. API keys are never part of [AppSettings]. */
interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    fun current(): AppSettings = settings.value
    suspend fun update(transform: (AppSettings) -> AppSettings)

    /** Trimmed key, or null when missing/blank. */
    fun apiKey(key: ApiKey): String?
    fun setApiKey(key: ApiKey, value: String?)
    fun hasApiKey(key: ApiKey): Boolean = !apiKey(key).isNullOrBlank()
    /** Emits whenever any key changes (value is the set of keys present). */
    val apiKeysPresent: StateFlow<Set<ApiKey>>
}

/** Room-backed project store. The whole [Project] is persisted (complex fields as JSON). */
interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    fun observeProject(id: Long): Flow<Project?>
    suspend fun getProject(id: Long): Project?
    suspend fun createProject(request: VideoRequest): Long

    /**
     * Atomic read-modify-write (serialized per repository with a Mutex). Sets updatedAt.
     * Returns the saved project, or null if it does not exist.
     */
    suspend fun updateProject(id: Long, transform: (Project) -> Project): Project?
    suspend fun deleteProject(id: Long)
}
