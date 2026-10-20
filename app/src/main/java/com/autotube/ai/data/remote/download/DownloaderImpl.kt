package com.autotube.ai.data.remote.download

import android.util.Log
import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.LowStorageException
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.data.remote.http.await
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams a URL to a file: writes `<name>.part`, verifies the length against Content-Length and
 * renames it into place, so a half-written file never looks finished. Respects the Wi-Fi-only
 * setting before every attempt, retries transient failures (3 attempts) and deletes partial files
 * on failure. Progress (0..1) is reported when the server sends a Content-Length.
 */
@Singleton
class DownloaderImpl @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val deviceStatus: DeviceStatus,
) : Downloader {

    override suspend fun download(
        url: String,
        outFile: File,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): File {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: throw PipelineException(
            userTitle = "Bad download link",
            userMessage = "A media link could not be opened, so that file was skipped.",
            retryable = false,
        )
        return withRetry(
            attempts = ATTEMPTS,
            onRetry = { attempt, error -> Log.w(TAG, "Download from ${httpUrl.host} failed (attempt $attempt): ${error.message}") },
        ) {
            deviceStatus.requireNetworkForTransfer(settingsRepository.current().wifiOnly)
            downloadOnce(httpUrl, outFile, headers, onProgress)
        }
    }

    private suspend fun downloadOnce(
        url: HttpUrl,
        outFile: File,
        headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = outFile.absoluteFile
        val dir = target.parentFile ?: throw IOException("No folder for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create folder ${dir.name}")
        val part = File(dir, target.name + PART_SUFFIX)
        part.delete()

        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()

        try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw ApiException("Download (${url.host})", response.code, errorBody.take(500))
                }
                val body = response.body ?: throw IOException("Empty response from ${url.host}")
                val total = body.contentLength()
                requireSpace(dir, total)

                var copied = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (total > 0 && copied != total) throw IOException("Download incomplete: $copied of $total bytes")
                if (copied == 0L) throw IOException("Downloaded file is empty")
            }

            if (target.exists() && !target.delete()) throw IOException("Cannot replace ${target.name}")
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            onProgress(1f)
            target
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private fun requireSpace(dir: File, contentLength: Long) {
        if (contentLength <= 0) return
        val free = dir.usableSpace
        val needed = contentLength + SAFETY_MARGIN_BYTES
        if (free in 1 until needed) {
            throw LowStorageException(freeMb = free / MB, neededMb = needed / MB + 1)
        }
    }

    private companion object {
        const val TAG = "Downloader"
        const val ATTEMPTS = 3
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 64 * 1024
        const val MB = 1024L * 1024L
        const val SAFETY_MARGIN_BYTES = 50L * MB
    }
}
