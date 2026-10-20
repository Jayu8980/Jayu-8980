package com.autotube.ai.data.files

import android.util.Log
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.StepLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Appends lines such as `2026-10-20 12:00:01.123 I [VOICE] Scene 3 done` (plus indented stack traces)
 * to `projects/<id>/logs/project.log`, and mirrors them to Logcat under the tag "AutoTube".
 *
 * Every message is passed through [SecretRedactor] first, so API keys and OAuth tokens never reach
 * disk or Logcat. The file is rotated to `project.log.1` once it grows beyond 2 MB.
 * Logging never throws: IO problems are reported to Logcat only.
 */
@Singleton
class ProjectLoggerImpl @Inject constructor(
    private val files: ProjectFiles,
) : ProjectLogger {

    private val locks = ConcurrentHashMap<String, Any>()
    private val timeFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun forStep(projectId: Long, step: PipelineStep?): StepLogger =
        BoundLogger(projectId, step?.name ?: GENERAL_TAG)

    override suspend fun read(projectId: Long, maxChars: Int): String = withContext(Dispatchers.IO) {
        if (maxChars <= 0) return@withContext ""
        val file = files.logFile(projectId)
        try {
            synchronized(lockFor(file)) {
                val current = tail(file, maxChars)
                if (current.length >= maxChars) {
                    current
                } else {
                    tail(rotatedFile(file), maxChars - current.length) + current
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read project log: ${e.message}")
            ""
        }
    }

    private inner class BoundLogger(
        private val projectId: Long,
        private val tag: String,
    ) : StepLogger {
        override fun i(message: String) = write(projectId, LEVEL_INFO, tag, message, null)
        override fun w(message: String, error: Throwable?) = write(projectId, LEVEL_WARN, tag, message, error)
        override fun e(message: String, error: Throwable?) = write(projectId, LEVEL_ERROR, tag, message, error)
    }

    private fun write(projectId: Long, level: Char, tag: String, message: String, error: Throwable?) {
        val safeMessage = SecretRedactor.redact(message)
        val safeStack = error?.let { SecretRedactor.redact(it.stackTraceToString()) }

        val logcatText = buildString {
            append("[P").append(projectId).append("][").append(tag).append("] ").append(safeMessage)
            if (safeStack != null) append('\n').append(safeStack)
        }
        when (level) {
            LEVEL_INFO -> Log.i(TAG, logcatText)
            LEVEL_WARN -> Log.w(TAG, logcatText)
            else -> Log.e(TAG, logcatText)
        }

        val line = buildString {
            append(LocalDateTime.now().format(timeFormat))
            append(' ').append(level).append(" [").append(tag).append("] ")
            append(safeMessage.trimEnd().replace("\n", "\n    "))
            append('\n')
            if (safeStack != null) {
                append(safeStack.trimEnd().prependIndent("    "))
                append('\n')
            }
        }

        try {
            val file = files.logFile(projectId)
            synchronized(lockFor(file)) {
                rotateIfNeeded(file)
                FileOutputStream(file, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not write project log: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not write project log: ${e.message}")
        }
    }

    private fun lockFor(file: File): Any = locks.getOrPut(file.absolutePath) { Any() }

    private fun rotatedFile(file: File): File = File(file.parentFile, file.name + ROTATED_SUFFIX)

    /** Must be called with the file's lock held. */
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) return
        val rotated = rotatedFile(file)
        if (rotated.exists()) rotated.delete()
        if (!file.renameTo(rotated)) {
            // Could not rename (unexpected on app storage): start over rather than grow forever.
            FileOutputStream(file, false).use { }
        }
    }

    /** Last [maxChars] characters of [file], starting at a line boundary when truncated. */
    private fun tail(file: File, maxChars: Int): String {
        if (maxChars <= 0 || !file.isFile) return ""
        val length = file.length()
        if (length <= 0L) return ""
        // UTF-8 uses at most 4 bytes per char, so this many bytes always covers maxChars characters.
        val byteCount = min(length, maxChars.toLong() * 4L)
        val bytes = ByteArray(byteCount.toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(length - byteCount)
            raf.readFully(bytes)
        }
        var text = String(bytes, Charsets.UTF_8)
        val truncated = byteCount < length || text.length > maxChars
        if (text.length > maxChars) text = text.takeLast(maxChars)
        if (truncated) {
            val newline = text.indexOf('\n')
            if (newline >= 0 && newline < text.length - 1) text = text.substring(newline + 1)
        }
        return text
    }

    private companion object {
        const val TAG = "AutoTube"
        const val GENERAL_TAG = "APP"
        const val ROTATED_SUFFIX = ".1"
        const val MAX_LOG_BYTES = 2L * 1024L * 1024L
        const val LEVEL_INFO = 'I'
        const val LEVEL_WARN = 'W'
        const val LEVEL_ERROR = 'E'
    }
}
