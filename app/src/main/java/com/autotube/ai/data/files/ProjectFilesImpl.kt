package com.autotube.ai.data.files

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.autotube.ai.domain.ProjectFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-private storage layout:
 * ```
 * filesDir/projects/<id>/audio
 *                       /visuals
 *                       /render
 *                       /temp
 *                       /logs/project.log
 * filesDir/music/<emotion>/   (user-imported music)
 * filesDir/models/            (imported on-device LLM models)
 * cacheDir/<id>/              (per-project cache, wiped by clearTemp)
 * ```
 * Every directory accessor creates the directory on access.
 */
@Singleton
class ProjectFilesImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProjectFiles {

    private val projectsRoot: File get() = File(context.filesDir, PROJECTS_DIR)

    override fun projectDir(projectId: Long): File = ensureDir(projectPath(projectId))

    override fun audioDir(projectId: Long): File = ensureDir(File(projectPath(projectId), AUDIO_DIR))

    override fun visualsDir(projectId: Long): File = ensureDir(File(projectPath(projectId), VISUALS_DIR))

    override fun renderDir(projectId: Long): File = ensureDir(File(projectPath(projectId), RENDER_DIR))

    override fun tempDir(projectId: Long): File = ensureDir(File(projectPath(projectId), TEMP_DIR))

    override fun logFile(projectId: Long): File {
        val logs = ensureDir(File(projectPath(projectId), LOGS_DIR))
        return File(logs, LOG_FILE_NAME)
    }

    override fun importedMusicDir(): File = ensureDir(File(context.filesDir, MUSIC_DIR))

    override fun modelsDir(): File = ensureDir(File(context.filesDir, MODELS_DIR))

    override fun freeBytes(): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "StatFs failed for app storage", e)
        context.filesDir.usableSpace
    }

    override fun projectSizeBytes(projectId: Long): Long {
        val dir = projectPath(projectId)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    override suspend fun clearTemp(projectId: Long, keepFinal: Boolean) {
        withContext(Dispatchers.IO) {
            val root = projectPath(projectId)
            File(root, TEMP_DIR).deleteRecursively()
            File(context.cacheDir, projectId.toString()).deleteRecursively()
            if (!keepFinal) {
                File(root, AUDIO_DIR).deleteRecursively()
                File(root, VISUALS_DIR).deleteRecursively()
                File(root, RENDER_DIR).listFiles()?.forEach { child ->
                    if (!isFinalVideo(child)) child.deleteRecursively()
                }
            }
        }
    }

    override suspend fun deleteProject(projectId: Long) {
        withContext(Dispatchers.IO) {
            val dir = projectPath(projectId)
            if (dir.exists() && !dir.deleteRecursively()) {
                Log.w(TAG, "Could not fully delete ${dir.absolutePath}")
            }
            File(context.cacheDir, projectId.toString()).deleteRecursively()
        }
    }

    /** Path of a project directory without creating it. */
    private fun projectPath(projectId: Long): File = File(projectsRoot, projectId.toString())

    private fun isFinalVideo(file: File): Boolean =
        file.isFile && file.name.startsWith("final", ignoreCase = true) &&
            file.name.endsWith(".mp4", ignoreCase = true)

    private fun ensureDir(dir: File): File {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            Log.w(TAG, "Could not create directory ${dir.absolutePath}")
        }
        return dir
    }

    internal companion object {
        const val TAG = "AutoTube"
        const val PROJECTS_DIR = "projects"
        const val AUDIO_DIR = "audio"
        const val VISUALS_DIR = "visuals"
        const val RENDER_DIR = "render"
        const val TEMP_DIR = "temp"
        const val LOGS_DIR = "logs"
        const val LOG_FILE_NAME = "project.log"
        const val MUSIC_DIR = "music"
        const val MODELS_DIR = "models"
    }
}
