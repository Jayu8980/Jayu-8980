package com.autotube.ai.data.media

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.domain.MediaStoreSaver
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Copies exported files into the shared gallery.
 *
 * - Android 10+: MediaStore insert into `Movies/AutoTube AI` or `Pictures/AutoTube AI` with
 *   IS_PENDING while copying (no storage permission needed).
 * - Android 8-9: copy into the public Movies/Pictures folder and register it with the media scanner;
 *   requires the WRITE_EXTERNAL_STORAGE runtime permission, which the UI must request first.
 *
 * Display names are sanitized and made unique with a timestamp suffix.
 */
@Singleton
class MediaStoreSaverImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStoreSaver {

    override suspend fun saveVideo(file: File, displayName: String): Uri =
        save(file, displayName, MediaKind.VIDEO)

    override suspend fun saveImage(file: File, displayName: String): Uri =
        save(file, displayName, MediaKind.IMAGE)

    private suspend fun save(file: File, displayName: String, kind: MediaKind): Uri {
        if (!file.isFile || file.length() == 0L) {
            throw PipelineException(
                userTitle = "Nothing to save",
                userMessage = "The exported file is missing, so it could not be added to your gallery. Render the video again.",
                retryable = false,
            )
        }
        val extension = extensionFor(file, kind)
        val mimeType = mimeTypeFor(extension, kind)
        val name = uniqueBaseName(displayName, kind) + "." + extension
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                withContext(Dispatchers.IO) { saveScoped(file, name, mimeType, kind) }
            } else {
                saveLegacy(file, name, mimeType, kind)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PipelineException) {
            throw e
        } catch (e: Exception) {
            throw PipelineException(
                userTitle = "Could not save to gallery",
                userMessage = "The ${kind.noun} could not be copied to your gallery (${e.javaClass.simpleName}). " +
                    "Check that the phone has free space and try again.",
                retryable = true,
                cause = e,
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(file: File, name: String, mimeType: String, kind: MediaKind): Uri {
        val resolver: ContentResolver = context.contentResolver
        val collection = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create $name")
        try {
            val output = resolver.openOutputStream(uri, "w")
                ?: throw IOException("MediaStore returned no output stream for $name")
            output.use { out ->
                file.inputStream().use { input -> input.copyTo(out, COPY_BUFFER) }
            }
            val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, publish, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun saveLegacy(file: File, name: String, mimeType: String, kind: MediaKind): Uri {
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            throw PipelineException(
                userTitle = "Storage permission needed",
                userMessage = "Allow AutoTube AI to save files so the ${kind.noun} can be added to your gallery, then try again.",
                retryable = false,
            )
        }
        val target = withContext(Dispatchers.IO) {
            val publicDir = Environment.getExternalStoragePublicDirectory(kind.legacyDirectory)
            val folder = File(publicDir, ALBUM)
            if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) {
                throw IOException("Could not create ${folder.absolutePath}")
            }
            val destination = uniqueFile(folder, name)
            try {
                file.inputStream().use { input ->
                    destination.outputStream().use { out -> input.copyTo(out, COPY_BUFFER) }
                }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
            destination
        }
        val scanned = withTimeoutOrNull(SCAN_TIMEOUT_MS) { scan(target, mimeType) }
        return scanned ?: Uri.fromFile(target)
    }

    private suspend fun scan(file: File, mimeType: String): Uri? = suspendCancellableCoroutine { cont ->
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
        ) { _, uri ->
            if (cont.isActive) cont.resume(uri)
        }
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var counter = 2
        while (candidate.exists()) {
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            candidate = File(folder, "$base ($counter)$suffix")
            counter++
        }
        return candidate
    }

    /** Sanitized name (no extension) + timestamp, e.g. "Why cats purr 20261020_120001". */
    private fun uniqueBaseName(displayName: String, kind: MediaKind): String {
        val withoutExtension = displayName.trim().let { raw ->
            val dot = raw.lastIndexOf('.')
            val ext = if (dot > 0) raw.substring(dot + 1).lowercase(Locale.US) else ""
            if (ext in VIDEO_EXTENSIONS || ext in IMAGE_EXTENSIONS) raw.substring(0, dot) else raw
        }
        val cleaned = withoutExtension
            .replace(ILLEGAL_CHARS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .trim('.')
            .let { safeTake(it, MAX_BASE_NAME) }
            .trim()
            .ifEmpty { kind.defaultName }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$cleaned $stamp"
    }

    /** Takes up to [max] chars without splitting a surrogate pair (emoji). */
    private fun safeTake(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    private fun extensionFor(file: File, kind: MediaKind): String {
        val ext = file.extension.lowercase(Locale.US)
        return when (kind) {
            MediaKind.VIDEO -> if (ext in VIDEO_EXTENSIONS) ext else "mp4"
            MediaKind.IMAGE -> if (ext in IMAGE_EXTENSIONS) (if (ext == "jpeg") "jpg" else ext) else "jpg"
        }
    }

    private fun mimeTypeFor(extension: String, kind: MediaKind): String = when (kind) {
        MediaKind.VIDEO -> when (extension) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            else -> "video/mp4"
        }
        MediaKind.IMAGE -> when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private enum class MediaKind(
        val relativePath: String,
        val legacyDirectory: String,
        val noun: String,
        val defaultName: String,
    ) {
        VIDEO("${Environment.DIRECTORY_MOVIES}/$ALBUM", Environment.DIRECTORY_MOVIES, "video", "AutoTube video"),
        IMAGE("${Environment.DIRECTORY_PICTURES}/$ALBUM", Environment.DIRECTORY_PICTURES, "image", "AutoTube image"),
    }

    private companion object {
        const val ALBUM = "AutoTube AI"
        const val COPY_BUFFER = 256 * 1024
        const val MAX_BASE_NAME = 80
        const val SCAN_TIMEOUT_MS = 15_000L
        val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
        val WHITESPACE = Regex("""\s+""")
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "3gp")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
