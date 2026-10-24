package com.pianoscribe.app

import android.app.Application
import com.pianoscribe.app.audio.Draft
import com.pianoscribe.app.data.AppDatabase
import com.pianoscribe.app.data.ProjectRepository
import com.pianoscribe.app.data.SettingsStore
import com.pianoscribe.app.midi.MidiInput
import com.pianoscribe.app.playback.SoundFontProvider
import kotlinx.coroutines.flow.MutableStateFlow

class PianoScribeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CrashLog.install(this)
    }
}

/**
 * Keeps the last crash on the phone (the app has no internet access) so the home screen can show
 * it on the next start and the user can share it.
 */
object CrashLog {
    private fun file(context: android.content.Context) = java.io.File(context.filesDir, "last_crash.txt")

    fun install(context: android.content.Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = java.io.StringWriter()
                error.printStackTrace(java.io.PrintWriter(sw))
                val info = "PianoScribe ${BuildConfig.VERSION_NAME} on Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, thread ${thread.name}\n\n"
                file(context).writeText(info + sw.toString().take(20_000))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: android.content.Context): String? = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(context: android.content.Context) { file(context).delete() }
}

/** Manual dependency container: one instance of each service for the whole app. */
class AppContainer(val app: Application) {
    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val repository: ProjectRepository by lazy { ProjectRepository(app, database.projectDao()) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val soundFonts: SoundFontProvider by lazy { SoundFontProvider(app, settings) }
    val midiInput: MidiInput by lazy { MidiInput(app) }

    /** Decoded audio waiting to be trimmed and converted (passed between screens). */
    val draft = MutableStateFlow<Draft?>(null)
    /** Audio shared into the app from another app, waiting for the home screen to pick it up. */
    val pendingSharedUri = MutableStateFlow<android.net.Uri?>(null)
}
