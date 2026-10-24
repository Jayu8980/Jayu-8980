package com.pianoscribe.app.data

import android.content.Context
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.transcribe.AudioType
import com.pianoscribe.core.transcribe.TranscriptionSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Notation { WESTERN, SARGAM }

data class AppSettings(
    val onsetThreshold: Float = 0.5f,
    val frameThreshold: Float = 0.3f,
    val minNoteMs: Float = 127.7f,
    val minPitch: Int = Note.MIN_PIANO,
    val maxPitch: Int = Note.MAX_PIANO,
    val pianoOnly: Boolean = false,
    /** 8 = eighth notes, 16 = sixteenth notes. */
    val quantizeDivision: Int = 16,
    val splitPoint: Int = Note.MIDDLE_C,
    val notation: Notation = Notation.WESTERN,
    /** Pitch class used as Sa (0 = C). */
    val saPitchClass: Int = 0,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** content:// URI of a user-chosen .sf2, or null for the bundled piano. */
    val customSoundFont: String? = null,
    val beatsPerBar: Int = 4,
    /** Solo piano, full song (drums removed + clean-up), or detect automatically. */
    val audioType: AudioType = AudioType.AUTO,
    /** Instrument the transcription is played on. */
    val instrument: Instrument = Instrument.PIANO,
    /** 0 = play exactly as written, 1 = most expressive (timing, dynamics, phrasing, vibrato). */
    val humanFeel: Float = 0.6f,
    /** Remove mains hum and rumble before transcribing, when the analysis finds them. */
    val autoClean: Boolean = true,
) {
    fun transcription() = TranscriptionSettings(
        onsetThreshold = onsetThreshold,
        frameThreshold = frameThreshold,
        minNoteLengthMs = minNoteMs.toDouble(),
        minPitch = minPitch,
        maxPitch = maxPitch,
    )
}

/** SharedPreferences-backed settings exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    fun reset() = update {
        AppSettings(theme = it.theme, customSoundFont = it.customSoundFont, audioType = it.audioType, instrument = it.instrument, humanFeel = it.humanFeel)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onsetThreshold = prefs.getFloat("onset", d.onsetThreshold),
            frameThreshold = prefs.getFloat("frame", d.frameThreshold),
            minNoteMs = prefs.getFloat("minNoteMs", d.minNoteMs),
            minPitch = prefs.getInt("minPitch", d.minPitch),
            maxPitch = prefs.getInt("maxPitch", d.maxPitch),
            pianoOnly = prefs.getBoolean("pianoOnly", d.pianoOnly),
            quantizeDivision = prefs.getInt("quantize", d.quantizeDivision),
            splitPoint = prefs.getInt("split", d.splitPoint),
            notation = runCatching { Notation.valueOf(prefs.getString("notation", d.notation.name)!!) }.getOrDefault(d.notation),
            saPitchClass = prefs.getInt("sa", d.saPitchClass),
            theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            customSoundFont = prefs.getString("soundFont", null),
            beatsPerBar = prefs.getInt("beatsPerBar", d.beatsPerBar),
            audioType = runCatching { AudioType.valueOf(prefs.getString("audioType", d.audioType.name)!!) }.getOrDefault(d.audioType),
            instrument = runCatching { Instrument.valueOf(prefs.getString("instrument", d.instrument.name)!!) }.getOrDefault(d.instrument),
            humanFeel = prefs.getFloat("humanFeel", d.humanFeel),
            autoClean = prefs.getBoolean("autoClean", d.autoClean),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putFloat("onset", s.onsetThreshold)
            .putFloat("frame", s.frameThreshold)
            .putFloat("minNoteMs", s.minNoteMs)
            .putInt("minPitch", s.minPitch)
            .putInt("maxPitch", s.maxPitch)
            .putBoolean("pianoOnly", s.pianoOnly)
            .putInt("quantize", s.quantizeDivision)
            .putInt("split", s.splitPoint)
            .putString("notation", s.notation.name)
            .putInt("sa", s.saPitchClass)
            .putString("theme", s.theme.name)
            .putBoolean("dynamicColor", s.dynamicColor)
            .putString("soundFont", s.customSoundFont)
            .putInt("beatsPerBar", s.beatsPerBar)
            .putString("audioType", s.audioType.name)
            .putString("instrument", s.instrument.name)
            .putFloat("humanFeel", s.humanFeel)
            .putBoolean("autoClean", s.autoClean)
            .apply()
    }
}
