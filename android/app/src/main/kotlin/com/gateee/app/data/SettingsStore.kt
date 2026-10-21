package com.gateee.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gateee.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val clientToken: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val remindersOn: Boolean = true,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    val dailyTarget: Int = 15,
)

class SettingsStore(private val context: Context) {
    private object K {
        val url = stringPreferencesKey("backend_url")
        val token = stringPreferencesKey("client_token")
        val theme = stringPreferencesKey("theme")
        val remind = booleanPreferencesKey("reminders_on")
        val hour = intPreferencesKey("reminder_hour")
        val minute = intPreferencesKey("reminder_minute")
        val target = intPreferencesKey("daily_target")
    }

    val flow: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            backendUrl = p[K.url] ?: BuildConfig.DEFAULT_BACKEND_URL,
            clientToken = p[K.token] ?: "",
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            remindersOn = p[K.remind] ?: true,
            reminderHour = p[K.hour] ?: 19,
            reminderMinute = p[K.minute] ?: 0,
            dailyTarget = p[K.target] ?: 15,
        )
    }

    suspend fun current() = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        val s = transform(current())
        context.dataStore.edit { p ->
            p[K.url] = s.backendUrl.trim().trimEnd('/')
            p[K.token] = s.clientToken.trim()
            p[K.theme] = s.theme.name
            p[K.remind] = s.remindersOn
            p[K.hour] = s.reminderHour
            p[K.minute] = s.reminderMinute
            p[K.target] = s.dailyTarget
        }
    }
}
