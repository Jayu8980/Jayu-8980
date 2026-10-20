package com.autotube.ai.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] backed by EncryptedSharedPreferences (file "autotube_secure", AES256-GCM master
 * key). Non-secret [AppSettings] are stored as one JSON string; every [ApiKey] lives under its own
 * `key_<NAME>` entry and is never logged or serialized into [AppSettings].
 *
 * Some devices ship a broken keystore or restore an undecryptable keyset; in that case the encrypted
 * file is deleted and recreated once, and if that also fails a private (unencrypted, MODE_PRIVATE)
 * preferences file is used so the app keeps working.
 */
@Singleton
class SecureSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val prefs: SharedPreferences = openPreferences()
    private val writeLock = Mutex()

    /** In-memory copy of the keys, so [apiKey] never decrypts on the caller's thread. */
    private val keyCache = ConcurrentHashMap<ApiKey, String>()

    private val _settings = MutableStateFlow(loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _apiKeysPresent: MutableStateFlow<Set<ApiKey>>
    override val apiKeysPresent: StateFlow<Set<ApiKey>>

    init {
        ApiKey.entries.forEach { key ->
            val stored = readKeySafely(key)
            if (stored != null) keyCache[key] = stored
        }
        _apiKeysPresent = MutableStateFlow(keyCache.keys.toSet())
        apiKeysPresent = _apiKeysPresent.asStateFlow()
    }

    override fun current(): AppSettings = _settings.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        writeLock.withLock {
            val previous = _settings.value
            val updated = transform(previous)
            if (updated != previous) {
                _settings.value = updated
                persist(updated)
            }
        }
    }

    private suspend fun persist(value: AppSettings) {
        val json = AppJson.encodeToString(AppSettings.serializer(), value)
        val written = withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_SETTINGS, json).commit()
        }
        if (!written) Log.w(TAG, "Settings could not be written to storage")
    }

    override fun apiKey(key: ApiKey): String? = keyCache[key]

    override fun hasApiKey(key: ApiKey): Boolean = keyCache.containsKey(key)

    override fun setApiKey(key: ApiKey, value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(keyCache) {
            if (trimmed == null) {
                keyCache.remove(key)
                prefs.edit().remove(prefKey(key)).apply()
            } else {
                keyCache[key] = trimmed
                prefs.edit().putString(prefKey(key), trimmed).apply()
            }
            _apiKeysPresent.value = keyCache.keys.toSet()
        }
    }

    private fun loadSettings(): AppSettings {
        val raw = try {
            prefs.getString(KEY_SETTINGS, null)
        } catch (e: Exception) {
            Log.w(TAG, "Stored settings could not be decrypted; using defaults (${e.javaClass.simpleName})")
            null
        }
        if (raw.isNullOrBlank()) return AppSettings()
        return try {
            AppJson.decodeFromString(AppSettings.serializer(), raw)
        } catch (e: Exception) {
            Log.w(TAG, "Stored settings could not be read; using defaults (${e.javaClass.simpleName})")
            AppSettings()
        }
    }

    private fun readKeySafely(key: ApiKey): String? = try {
        prefs.getString(prefKey(key), null)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        // Never include the value (or the exception message, which may echo it) in the log.
        Log.w(TAG, "Stored ${key.name} key could not be decrypted (${e.javaClass.simpleName}); it must be entered again")
        null
    }

    private fun openPreferences(): SharedPreferences {
        try {
            return createEncrypted()
        } catch (first: Exception) {
            Log.w(TAG, "Encrypted settings unavailable (${first.javaClass.simpleName}); recreating the secure store")
        }
        try {
            context.deleteSharedPreferences(SECURE_FILE)
            return createEncrypted()
        } catch (second: Exception) {
            Log.w(
                TAG,
                "Encrypted settings still unavailable (${second.javaClass.simpleName}); " +
                    "falling back to private, unencrypted app storage. API keys are protected only by app sandboxing.",
            )
        }
        return context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
    }

    private fun createEncrypted(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        // Touch the store once so a keyset that cannot be decrypted fails here, not later.
        encrypted.getString(KEY_SETTINGS, null)
        return encrypted
    }

    private fun prefKey(key: ApiKey): String = KEY_PREFIX + key.name

    private companion object {
        const val TAG = "AutoTube"
        const val SECURE_FILE = "autotube_secure"
        const val FALLBACK_FILE = "autotube_settings_fallback"
        const val KEY_SETTINGS = "app_settings_json"
        const val KEY_PREFIX = "key_"
    }
}
