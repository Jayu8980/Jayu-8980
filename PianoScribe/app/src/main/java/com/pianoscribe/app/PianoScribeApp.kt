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
    }
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
