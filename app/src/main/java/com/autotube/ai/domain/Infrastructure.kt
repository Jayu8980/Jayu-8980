package com.autotube.ai.domain

import android.net.Uri
import com.autotube.ai.core.model.PipelineStep
import java.io.File

/** App-private storage layout: filesDir/projects/<id>/{audio,visuals,render,temp,logs}. */
interface ProjectFiles {
    fun projectDir(projectId: Long): File
    fun audioDir(projectId: Long): File
    fun visualsDir(projectId: Long): File
    fun renderDir(projectId: Long): File
    /** Scratch space wiped by [clearTemp]. */
    fun tempDir(projectId: Long): File
    fun logFile(projectId: Long): File
    /** Folder for user-imported music, per mood: filesDir/music/<emotion>/ */
    fun importedMusicDir(): File
    /** Folder for imported on-device LLM models. */
    fun modelsDir(): File

    fun freeBytes(): Long
    fun projectSizeBytes(projectId: Long): Long
    /** Deletes temp files (and, when [keepFinal] is false, intermediate audio/visuals too). */
    suspend fun clearTemp(projectId: Long, keepFinal: Boolean = true)
    suspend fun deleteProject(projectId: Long)
}

/** Per-step logger bound to one project. Writes to the project's log file and Logcat. */
interface StepLogger {
    fun i(message: String)
    fun w(message: String, error: Throwable? = null)
    fun e(message: String, error: Throwable? = null)
}

interface ProjectLogger {
    fun forStep(projectId: Long, step: PipelineStep?): StepLogger
    suspend fun read(projectId: Long, maxChars: Int = 200_000): String
}

/** Phone-limit checks used before heavy work. */
interface DeviceStatus {
    fun freeStorageMb(): Long
    fun isLowStorage(requiredMb: Long): Boolean = freeStorageMb() < requiredMb
    fun isPowerSaveMode(): Boolean
    fun isOnline(): Boolean
    /** True on Wi-Fi/Ethernet (unmetered) networks. */
    fun isUnmetered(): Boolean
    fun batteryPercent(): Int
    /**
     * Throws [com.autotube.ai.core.common.NetworkPolicyException] when wifiOnly is set and the device
     * is on a metered network, or when offline.
     */
    fun requireNetworkForTransfer(wifiOnly: Boolean)
}

/** Saves exported files into the shared gallery through MediaStore. */
interface MediaStoreSaver {
    suspend fun saveVideo(file: File, displayName: String): Uri
    suspend fun saveImage(file: File, displayName: String): Uri
}

/** Streams a URL to a file; respects the Wi-Fi-only setting; retries transient failures. */
interface Downloader {
    suspend fun download(url: String, outFile: File, headers: Map<String, String> = emptyMap(), onProgress: (Float) -> Unit = {}): File
}

/** Schedules background work (WorkManager). Implemented in the work package. */
interface WorkScheduler {
    /** Start or resume the full pipeline for a project. */
    fun startPipeline(projectId: Long)
    /** Continue after the user picked a topic (review mode). */
    fun resumeAfterTopic(projectId: Long)
    /** Re-create one scene (new narration/voice/visuals) and re-render. */
    fun regenerateScene(projectId: Long, sceneNumber: Int)
    /** Re-render with the current EDL (e.g. after the user edited text). */
    fun rerender(projectId: Long)
    fun upload(projectId: Long)
    fun cancel(projectId: Long)
}
