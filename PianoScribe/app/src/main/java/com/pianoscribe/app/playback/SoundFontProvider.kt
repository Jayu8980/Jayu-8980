package com.pianoscribe.app.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pianoscribe.app.data.SettingsStore
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
 * Loads the piano preset: a user-chosen .sf2 (via the document picker) or the bundled
 * PianoScribe Grand. Returns null only if both fail, in which case the synth uses its additive model.
 */
class SoundFontProvider(private val context: Context, private val settings: SettingsStore) {
    private val lock = Mutex()
    private var cachedKey: String? = null
    private var cached: Sf2Preset? = null

    var lastError: String? = null
        private set

    suspend fun preset(): Sf2Preset? = lock.withLock {
        val key = settings.value.customSoundFont ?: BUNDLED
        if (key == cachedKey) return@withLock cached
        val p = withContext(Dispatchers.IO) {
            if (key != BUNDLED) {
                runCatching { loadUri(Uri.parse(key)) }.onFailure {
                    lastError = "Couldn't load the custom SoundFont (${it.message}); using the built-in piano."
                    Log.w("PianoScribe", "custom sf2", it)
                }.getOrNull() ?: loadBundled()
            } else loadBundled()
        }
        cachedKey = key
        cached = p
        p
    }

    fun invalidate() { cachedKey = null; cached = null }

    private fun loadBundled(): Sf2Preset? = runCatching {
        val bytes = context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        Sf2Reader.load(ArrayByteSource(bytes))
    }.onFailure { Log.e("PianoScribe", "bundled sf2", it) }.getOrNull()

    private fun loadUri(uri: Uri): Sf2Preset {
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
                return Sf2Reader.load(src, 0, 0)
            }
        }
    }


    companion object {
        const val BUNDLED = "bundled"
        const val BUNDLED_ASSET = "soundfonts/pianoscribe_piano.sf2"
    }
}
