package com.autotube.ai.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.autotube.ai.core.common.AppJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores Studio projects as JSON plus their media copies:
 *
 * filesDir/studio/<id>/project.json
 * filesDir/studio/<id>/media/      imported clips, photos, music, recordings, freeze frames
 * filesDir/studio/<id>/cache/      silence padding for exports
 * filesDir/studio/<id>/export/     the last exported MP4
 *
 * Media is copied in on import so projects keep working after the gallery permission expires.
 */
@Singleton
class StudioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File get() = File(context.filesDir, ROOT_DIR)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _projects = MutableStateFlow<List<StudioProject>>(emptyList())

    /** All projects, most recently edited first. */
    val projects: StateFlow<List<StudioProject>> = _projects.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    fun projectDir(id: String): File = File(root, id)
    fun mediaDir(id: String): File = File(projectDir(id), "media").apply { mkdirs() }
    fun cacheDir(id: String): File = File(projectDir(id), "cache").apply { mkdirs() }
    fun exportDir(id: String): File = File(projectDir(id), "export").apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = root.listFiles()?.mapNotNull { dir -> readProject(File(dir, PROJECT_FILE)) }.orEmpty()
        _projects.value = list.sortedByDescending { it.updatedAt }
    }

    suspend fun load(id: String): StudioProject? = withContext(Dispatchers.IO) { readProject(File(projectDir(id), PROJECT_FILE)) }

    suspend fun create(name: String, ratio: CanvasRatio = CanvasRatio.R9_16): StudioProject {
        val project = StudioProject(name = name, ratio = ratio)
        save(project)
        return project
    }

    /** Writes the project atomically and updates [projects]. */
    suspend fun save(project: StudioProject): StudioProject = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stamped = project.copy(updatedAt = System.currentTimeMillis())
            val dir = projectDir(project.id).apply { mkdirs() }
            val tmp = File(dir, "$PROJECT_FILE.tmp")
            tmp.writeText(AppJson.encodeToString(StudioProject.serializer(), stamped))
            val target = File(dir, PROJECT_FILE)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            _projects.value = (listOf(stamped) + _projects.value.filterNot { it.id == stamped.id }).sortedByDescending { it.updatedAt }
            stamped
        }
    }

    /** Fire-and-forget save that outlives the editor screen. */
    fun saveAsync(project: StudioProject) {
        scope.launch { runCatching { save(project) } }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            projectDir(id).deleteRecursively()
            _projects.value = _projects.value.filterNot { it.id == id }
        }
    }

    suspend fun rename(id: String, name: String) {
        val p = load(id) ?: return
        save(p.copy(name = name.trim().ifBlank { p.name }))
    }

    suspend fun duplicate(id: String): StudioProject? = withContext(Dispatchers.IO) {
        val p = load(id) ?: return@withContext null
        val copy = p.copy(id = newId(), name = "${p.name} copy", createdAt = System.currentTimeMillis(), lastExportPath = null)
        val oldMedia = File(projectDir(id), "media")
        val newMedia = mediaDir(copy.id)
        oldMedia.listFiles()?.forEach { it.copyTo(File(newMedia, it.name), overwrite = true) }
        fun move(path: String) = if (path.startsWith(oldMedia.absolutePath)) File(newMedia, File(path).name).absolutePath else path
        save(
            copy.copy(
                clips = copy.clips.map { it.copy(path = move(it.path)) },
                audio = copy.audio.map { it.copy(path = move(it.path)) },
            ),
        )
    }

    private fun readProject(file: File): StudioProject? = runCatching {
        if (!file.isFile) return null
        AppJson.decodeFromString(StudioProject.serializer(), file.readText())
    }.getOrNull()

    // ------------------------------------------------------------------ import

    /** Copies picked photos/videos into the project and probes them. Unreadable items are skipped. */
    suspend fun importVisuals(projectId: String, uris: List<Uri>, onProgress: (Float) -> Unit = {}): List<StudioClip> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<StudioClip>(uris.size)
            uris.forEachIndexed { i, uri ->
                runCatching { importVisual(projectId, uri) }.getOrNull()?.let { out += it }
                onProgress((i + 1f) / uris.size)
            }
            out
        }

    private fun importVisual(projectId: String, uri: Uri): StudioClip? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val isImage = mime.startsWith("image/") || (mime.isEmpty() && isImageName(displayName(uri)))
        val file = copyIn(projectId, uri, mime, if (isImage) "jpg" else "mp4")
        return if (isImage) probeImage(file) else probeVideo(file)
    }

    fun probeImage(file: File): StudioClip? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            file.delete()
            return null
        }
        return StudioClip(
            path = file.absolutePath,
            isImage = true,
            hasAudio = false,
            sourceDurationMs = StudioEdits.DEFAULT_IMAGE_MS,
            width = opts.outWidth,
            height = opts.outHeight,
        )
    }

    fun probeVideo(file: File): StudioClip? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                file.delete()
                return null
            }
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= StudioEdits.MIN_MS) {
                file.delete()
                return null
            }
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) w = h.also { h = w }
            return StudioClip(
                path = file.absolutePath,
                isImage = false,
                hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                sourceDurationMs = duration,
                width = w,
                height = h,
            )
        } catch (e: Exception) {
            file.delete()
            return null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Copies a picked audio file (or the audio of a video file) in as a layer starting at [startMs]. */
    suspend fun importAudio(projectId: String, uri: Uri, kind: AudioKind, startMs: Long): AudioLayer? = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName(uri)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: kind.label
            val mime = context.contentResolver.getType(uri).orEmpty()
            val file = copyIn(projectId, uri, mime, "m4a")
            audioLayerFor(file, name, kind, startMs)
        }.getOrNull()
    }

    /** Builds a layer for an audio file already inside the project (e.g. a recording). */
    fun audioLayerFor(file: File, name: String, kind: AudioKind, startMs: Long): AudioLayer? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") {
                file.delete()
                return null
            }
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= StudioEdits.MIN_MS) return null
            AudioLayer(path = file.absolutePath, name = name, kind = kind, startMs = startMs, durationMs = duration, sourceDurationMs = duration)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    fun newRecordingFile(projectId: String): File = File(mediaDir(projectId), "voice_${System.currentTimeMillis()}.m4a")

    /** Grabs the frame of [clip] at [sourceMs] as a photo clip ("freeze frame"). */
    suspend fun freezeFrame(projectId: String, clip: StudioClip, sourceMs: Long, durationMs: Long = StudioEdits.DEFAULT_IMAGE_MS): StudioClip? =
        withContext(Dispatchers.IO) {
            if (clip.isImage) return@withContext clip.copy(id = newId(), trimStartMs = 0L, trimEndMs = durationMs, transition = ClipTransition.NONE)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(clip.path)
                val bitmap = r.getFrameAtTime(sourceMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST) ?: return@withContext null
                val file = File(mediaDir(projectId), "freeze_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                bitmap.recycle()
                probeImage(file)?.copy(
                    trimEndMs = durationMs,
                    filter = clip.filter,
                    filterStrength = clip.filterStrength,
                    adjust = clip.adjust,
                    rotation = clip.rotation,
                    flipH = clip.flipH,
                    flipV = clip.flipV,
                    zoom = clip.zoom,
                    fit = clip.fit,
                )
            } catch (e: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }

    private fun copyIn(projectId: String, uri: Uri, mime: String, fallbackExt: String): File {
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: displayName(uri)?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?: fallbackExt
        val file = File(mediaDir(projectId), "${newId()}.${ext.lowercase()}")
        if (uri.scheme == "file") {
            File(requireNotNull(uri.path)).copyTo(file, overwrite = true)
        } else {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
            input.use { src -> FileOutputStream(file).use { dst -> src.copyTo(dst, 1 shl 16) } }
        }
        return file
    }

    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun isImageName(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")

    companion object {
        private const val ROOT_DIR = "studio"
        private const val PROJECT_FILE = "project.json"
    }
}
