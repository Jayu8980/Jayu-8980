package com.pianoscribe.app.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pianoscribe.app.data.SettingsStore
import com.pianoscribe.core.perform.Instrument
import com.pianoscribe.core.synth.ArrayByteSource
import com.pianoscribe.core.synth.ByteSource
import com.pianoscribe.core.synth.Sf2Preset
import com.pianoscribe.core.synth.Sf2Reader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Loads instrument presets: from a user-chosen .sf2 (via the document picker) or, for the piano,
 * the bundled PianoScribe Grand. A null preset means the synth uses its built-in model.
 */
class SoundFontProvider(private val context: Context, private val settings: SettingsStore) {
    private val lock = Mutex()
    private val cache = HashMap<String, java.util.Optional<Sf2Preset>>()

    var lastError: String? = null
        private set

    /**
     * Sample-based preset for [instrument], or null when the built-in synth model should be used.
     * Piano: the custom SoundFont's piano, else the bundled one. Other instruments: the custom
     * SoundFont's General MIDI program for that instrument, if it has one (the bundled font is piano only).
     */
    suspend fun preset(instrument: Instrument = Instrument.PIANO): Sf2Preset? = lock.withLock {
        val custom = settings.value.customSoundFont
        val key = (custom ?: BUNDLED) + "#" + instrument.name
        cache[key]?.let { return@withLock it.orElse(null) }
        val p = withContext(Dispatchers.IO) {
            when {
                instrument == Instrument.PIANO && custom != null ->
                    runCatching { loadUri(Uri.parse(custom), 0) }.onFailure {
                        lastError = "Couldn't load the custom SoundFont (${it.message}); using the built-in piano."
                        Log.w("PianoScribe", "custom sf2", it)
                    }.getOrNull() ?: loadBundled()
                instrument == Instrument.PIANO -> loadBundled()
                custom != null -> runCatching { loadUri(Uri.parse(custom), instrument.gmProgram) }.getOrNull()
                else -> null
            }
        }
        cache[key] = java.util.Optional.ofNullable(p)
        p
    }

    fun invalidate() { cache.clear() }

    private fun loadBundled(): Sf2Preset? = runCatching {
        val bytes = context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        Sf2Reader.load(ArrayByteSource(bytes))
    }.onFailure { Log.e("PianoScribe", "bundled sf2", it) }.getOrNull()

    /**
     * Loads bank 0 [program] from a user SoundFont. For anything but the piano the program must
     * really exist: Sf2Reader falls back to the first preset, which would play a piano as a flute.
     */
    private fun loadUri(uri: Uri, program: Int): Sf2Preset? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("cannot open")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                val ch = fis.channel
                val src = object : ByteSource {
                    override val size: Long = ch.size()
                    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int) {
                        val bb = java.nio.ByteBuffer.wrap(dst, off, len)
                        var p = pos
                        while (bb.hasRemaining()) {
                            val n = ch.read(bb, p)
                            if (n < 0) throw java.io.EOFException()
                            p += n
                        }
                    }
                }
                if (program != 0 && Sf2Reader.listPresets(src).none { it.bank == 0 && it.program == program }) return null
                return Sf2Reader.load(src, 0, program)
            }
        }
    }


    companion object {
        const val BUNDLED = "bundled"
        const val BUNDLED_ASSET = "soundfonts/pianoscribe_piano.sf2"
    }
}
