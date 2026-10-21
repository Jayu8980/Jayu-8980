package com.autotube.ai.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import com.autotube.ai.core.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** An installed on-device TTS voice. */
data class VoiceOption(val name: String, val label: String)

/** Lists android.speech.tts voices for a language (binds a short-lived TextToSpeech instance). */
class OnDeviceVoiceCatalog @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun voicesFor(language: AppLanguage): List<VoiceOption> = withContext(Dispatchers.Main) {
        withTimeoutOrNull(10_000) { queryVoices(language) } ?: emptyList()
    }

    private suspend fun queryVoices(language: AppLanguage): List<VoiceOption> = suspendCancellableCoroutine { cont ->
        val holder = arrayOfNulls<TextToSpeech>(1)
        val listener = TextToSpeech.OnInitListener { status ->
            val engine = holder[0]
            val result = if (status == TextToSpeech.SUCCESS && engine != null) readVoices(engine, language) else emptyList()
            runCatching { engine?.shutdown() }
            if (cont.isActive) cont.resume(result)
        }
        val created = TextToSpeech(context, listener)
        holder[0] = created
        // Init can fail synchronously inside the constructor (no engine installed).
        if (!cont.isActive) runCatching { created.shutdown() }
        cont.invokeOnCancellation { runCatching { created.shutdown() } }
    }

    private fun readVoices(engine: TextToSpeech, language: AppLanguage): List<VoiceOption> {
        val voices: Set<Voice> = try {
            engine.voices ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
        return voices
            .filter { it.locale?.language == language.code }
            .filter { voice -> voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
            .map { voice ->
                val quality = when {
                    voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high quality"
                    voice.quality >= Voice.QUALITY_HIGH -> "High quality"
                    voice.quality >= Voice.QUALITY_NORMAL -> "Normal quality"
                    else -> "Low quality"
                }
                val network = if (voice.isNetworkConnectionRequired) " · needs internet" else ""
                VoiceOption(voice.name, "${voice.locale.displayName} · $quality$network · ${voice.name}")
            }
    }
}

/** Copies a picked document (SAF Uri) into app storage, reporting progress. */
class UriFileCopier @Inject constructor(@ApplicationContext private val context: Context) {

    data class Picked(val displayName: String, val sizeBytes: Long)

    fun describe(uri: Uri): Picked {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // Some providers do not support queries; fall back to the Uri's last segment.
        }
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "imported"
        return Picked(sanitize(name ?: fallback), size)
    }

    /**
     * Copies [uri] into [dir]/[fileName] through a temporary file, so a cancelled copy never
     * leaves a half-written file behind. Runs on Dispatchers.IO.
     */
    suspend fun copy(uri: Uri, dir: File, fileName: String, expectedBytes: Long, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create ${dir.name}")
            val target = File(dir, fileName)
            val temp = File(dir, ".$fileName.part")
            try {
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened")
                input.use { inp ->
                    temp.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        var lastReported = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = inp.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (expectedBytes > 0) {
                                val percent = (copied * 100 / expectedBytes).toInt().coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                onProgress(1f)
                target
            } catch (e: Throwable) {
                temp.delete()
                throw e
            }
        }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.').ifBlank { "imported" }.take(120)
    }
}

/** Total size of a file or folder tree in bytes. */
internal fun File.treeSize(): Long = if (!exists()) 0L else walkTopDown().filter { it.isFile }.sumOf { it.length() }
