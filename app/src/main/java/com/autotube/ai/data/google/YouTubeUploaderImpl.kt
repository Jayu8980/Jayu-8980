package com.autotube.ai.data.google

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.model.PipelineStep
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.UploadMode
import com.autotube.ai.core.model.UploadOptions
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeUploader
import com.google.android.gms.auth.GoogleAuthUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

/**
 * Uploads a finished video with the YouTube Data API v3 resumable protocol:
 *
 * 1. `POST /upload/youtube/v3/videos?uploadType=resumable` with the metadata JSON -> session URI.
 * 2. `PUT` 8 MiB chunks with `Content-Range`; `308` + `Range` tells how much YouTube has.
 * 3. On network errors / 5xx the session status is queried (`bytes *\/total`) and the upload resumes,
 *    with exponential backoff, up to 8 consecutive failures. A 401 fetches a fresh token once.
 *
 * The session URI is remembered on disk (no-backup storage) so a retried worker resumes the same
 * upload instead of creating a duplicate video. Afterwards the thumbnail is set; failures there
 * (e.g. 403 for channels that are not verified) are logged and never fail the upload.
 */
@Singleton
class YouTubeUploaderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: GoogleHttp,
    private val device: DeviceStatus,
    private val settings: SettingsRepository,
) : YouTubeUploader {

    private val sessions = SessionStore(File(context.noBackupFilesDir, SESSION_DIR))

    override suspend fun upload(
        videoFile: File,
        thumbnail: File?,
        seo: SeoPackage,
        options: UploadOptions,
        languageCode: String,
        accessToken: suspend () -> String,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        ensureVideoExists(videoFile)
        device.requireNetworkForTransfer(settings.current().wifiOnly)

        val total = videoFile.length()
        val metadata = buildMetadata(seo, options, languageCode)
        val metadataJson = AppJson.encodeToString(VideoInsertBody.serializer(), metadata)
        val progress = ProgressReporter(total, onProgress)
        val tokens = TokenHolder(accessToken)
        tokens.load()

        progress.report(0f)
        Log.i(TAG, "YouTube upload started: ${total / 1024} KB, privacy=${metadata.status.privacyStatus}")
        val videoId = uploadVideo(videoFile, total, metadataJson, tokens, progress)
        Log.i(TAG, "YouTube upload finished: video $videoId")
        progress.report(VIDEO_SHARE)

        if (thumbnail != null) setThumbnail(videoId, thumbnail, tokens)
        progress.report(1f)
        videoId
    }

    // ------------------------------------------------------------------ video

    private suspend fun uploadVideo(
        file: File,
        total: Long,
        metadataJson: String,
        tokens: TokenHolder,
        progress: ProgressReporter,
    ): String {
        val sessionKey = sessions.keyFor(file)
        val metadataHash = sha256(metadataJson)

        var sessionUri: String? = sessions.load(sessionKey, file, metadataHash)
        var needsStatusQuery = sessionUri != null
        if (sessionUri != null) Log.i(TAG, "Resuming a previous YouTube upload session")
        var offset = 0L
        var confirmed = 0L
        var failures = 0
        var restartedSession = false
        var lastError: Throwable? = null

        while (true) {
            currentCoroutineContext().ensureActive()
            val session = sessionUri ?: startSession(metadataJson, total, tokens).also { created ->
                sessions.save(sessionKey, file, metadataHash, created)
                sessionUri = created
                offset = 0L
                confirmed = 0L
                needsStatusQuery = false
            }

            val outcome: Outcome = try {
                if (needsStatusQuery || offset >= total) {
                    queryStatus(session, total, tokens)
                } else {
                    sendChunk(file, session, offset, total, tokens, progress)
                }
            } catch (e: IOException) {
                Outcome.Transient("network error (${e.javaClass.simpleName})", e)
            }

            when (outcome) {
                is Outcome.Completed -> {
                    tokens.markSuccess()
                    sessions.clear(sessionKey)
                    return outcome.videoId
                }
                is Outcome.Resume -> {
                    tokens.markSuccess()
                    offset = outcome.offset.coerceIn(0L, total)
                    if (offset > confirmed) {
                        confirmed = offset
                        failures = 0
                    }
                    needsStatusQuery = false
                    progress.onPosition(offset)
                    if (offset >= total) {
                        // Every byte is acknowledged but the video is not finalized yet: wait, then ask again.
                        failures++
                        if (failures >= MAX_ATTEMPTS) throw interrupted(lastError)
                        backoff(failures)
                        needsStatusQuery = true
                    }
                }
                is Outcome.Unauthorized -> {
                    tokens.refresh()
                    needsStatusQuery = true
                }
                is Outcome.Expired -> {
                    sessions.clear(sessionKey)
                    if (restartedSession) {
                        throw PipelineException(
                            userTitle = "YouTube upload failed",
                            userMessage = "YouTube closed the upload session twice. Please try the upload again.",
                            retryable = true,
                            step = PipelineStep.UPLOAD,
                        )
                    }
                    Log.w(TAG, "YouTube upload session expired; starting a new session")
                    restartedSession = true
                    sessionUri = null
                }
                is Outcome.Transient -> {
                    failures++
                    lastError = outcome.error ?: lastError
                    if (failures >= MAX_ATTEMPTS) throw interrupted(lastError)
                    Log.w(TAG, "YouTube upload: ${outcome.reason}; retry $failures of ${MAX_ATTEMPTS - 1}")
                    backoff(failures)
                    ensureVideoExists(file)
                    checkNetworkPolicy()
                    needsStatusQuery = true
                }
            }
        }
    }

    private suspend fun startSession(metadataJson: String, total: Long, tokens: TokenHolder): String {
        var failures = 0
        var lastError: Throwable? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = INIT_URL.toHttpUrl().newBuilder()
                .addQueryParameter("uploadType", "resumable")
                .addQueryParameter("part", "snippet,status")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${tokens.token}")
                .header("X-Upload-Content-Type", VIDEO_MIME)
                .header("X-Upload-Content-Length", total.toString())
                .post(metadataJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val result: HttpResult? = try {
                execute(request)
            } catch (e: IOException) {
                lastError = e
                null
            }

            if (result != null && result.code in 200..299) {
                tokens.markSuccess()
                val location = result.location
                if (location.isNullOrBlank()) {
                    throw PipelineException(
                        userTitle = "YouTube upload failed",
                        userMessage = "YouTube did not start the upload session. Please try again.",
                        retryable = true,
                        step = PipelineStep.UPLOAD,
                    )
                }
                return location
            }
            if (result != null && result.code == 401) {
                tokens.refresh()
                continue
            }
            if (result != null && !isTransient(result.code, result.body)) {
                throw mapError(result.code, result.body)
            }
            if (result != null) lastError = ApiException("YouTube", result.code, result.body.take(MAX_ERROR_BODY))

            failures++
            if (failures >= MAX_ATTEMPTS) throw interrupted(lastError)
            Log.w(TAG, "YouTube upload session could not be started (${result?.code ?: "network"}); retry $failures")
            backoff(failures)
            checkNetworkPolicy()
        }
    }

    private suspend fun sendChunk(
        file: File,
        session: String,
        offset: Long,
        total: Long,
        tokens: TokenHolder,
        progress: ProgressReporter,
    ): Outcome {
        val end = min(offset + CHUNK_SIZE, total) - 1
        val length = end - offset + 1
        val body = FileChunkBody(file, offset, length, VIDEO_MEDIA_TYPE) { written ->
            progress.onPosition(offset + written)
        }
        val request = Request.Builder()
            .url(session)
            .header("Authorization", "Bearer ${tokens.token}")
            .header("Content-Range", "bytes $offset-$end/$total")
            .put(body)
            .build()
        return interpret(execute(request))
    }

    private suspend fun queryStatus(session: String, total: Long, tokens: TokenHolder): Outcome {
        val request = Request.Builder()
            .url(session)
            .header("Authorization", "Bearer ${tokens.token}")
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(null))
            .build()
        return interpret(execute(request))
    }

    private fun interpret(result: HttpResult): Outcome = when {
        result.code == 200 || result.code == 201 -> Outcome.Completed(parseVideoId(result.body))
        result.code == 308 -> Outcome.Resume(nextOffset(result.range))
        result.code == 401 -> Outcome.Unauthorized
        result.code == 404 || result.code == 410 -> Outcome.Expired
        isTransient(result.code, result.body) -> Outcome.Transient(
            "HTTP ${result.code}",
            ApiException("YouTube", result.code, result.body.take(MAX_ERROR_BODY)),
        )
        else -> throw mapError(result.code, result.body)
    }

    private fun parseVideoId(body: String): String {
        val id = try {
            AppJson.decodeFromString(VideoResource.serializer(), body).id
        } catch (e: Exception) {
            null
        }
        if (id.isNullOrBlank()) {
            throw PipelineException(
                userTitle = "YouTube upload unclear",
                userMessage = "YouTube accepted the file but did not return the video id. Check YouTube Studio before uploading again.",
                retryable = false,
                step = PipelineStep.UPLOAD,
            )
        }
        return id
    }

    /** "bytes=0-524287" -> 524288; no header means YouTube has nothing yet. */
    private fun nextOffset(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0L
        val match = RANGE_REGEX.find(rangeHeader) ?: return 0L
        val last = match.groupValues[2].toLongOrNull() ?: return 0L
        return last + 1
    }

    // ------------------------------------------------------------------ thumbnail

    private suspend fun setThumbnail(videoId: String, thumbnail: File, tokens: TokenHolder) {
        if (!thumbnail.isFile || thumbnail.length() == 0L) {
            Log.w(TAG, "Thumbnail file missing; YouTube will pick a frame")
            return
        }
        val prepared: PreparedThumbnail? = try {
            withContext(Dispatchers.Default) { prepareThumbnail(thumbnail) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail could not be prepared (${e.javaClass.simpleName}); YouTube will pick a frame")
            null
        }
        if (prepared == null) {
            Log.w(TAG, "Thumbnail image could not be read; YouTube will pick a frame")
            return
        }

        try {
            val url = THUMBNAIL_URL.toHttpUrl().newBuilder()
                .addQueryParameter("videoId", videoId)
                .addQueryParameter("uploadType", "media")
                .build()
            var attempt = 0
            var refreshed = false
            while (attempt < THUMBNAIL_ATTEMPTS) {
                attempt++
                currentCoroutineContext().ensureActive()
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${tokens.token}")
                    .post(prepared.file.asRequestBody(prepared.mimeType.toMediaType()))
                    .build()
                val result: HttpResult? = try {
                    execute(request)
                } catch (e: IOException) {
                    Log.w(TAG, "Thumbnail upload network error (${e.javaClass.simpleName})")
                    null
                }
                when {
                    result != null && result.code in 200..299 -> {
                        Log.i(TAG, "Thumbnail set for video $videoId")
                        return
                    }
                    result != null && result.code == 401 && !refreshed -> {
                        refreshed = true
                        tokens.refresh()
                        attempt--
                    }
                    result != null && result.code == 403 -> {
                        Log.w(
                            TAG,
                            "YouTube refused the custom thumbnail (403). The channel probably needs phone " +
                                "verification for custom thumbnails; the video is uploaded without it.",
                        )
                        return
                    }
                    result != null && !isTransient(result.code, result.body) -> {
                        Log.w(TAG, "Thumbnail rejected by YouTube (HTTP ${result.code}); the video is uploaded without it")
                        return
                    }
                    else -> if (attempt < THUMBNAIL_ATTEMPTS) backoff(attempt)
                }
            }
            Log.w(TAG, "Thumbnail could not be uploaded after $THUMBNAIL_ATTEMPTS attempts")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail upload failed (${e.javaClass.simpleName}); the video is uploaded without it")
        } finally {
            if (prepared.temporary) prepared.file.delete()
        }
    }

    /** YouTube accepts JPEG/PNG up to 2 MB; anything else is re-encoded as JPEG. */
    private fun prepareThumbnail(source: File): PreparedThumbnail? {
        val ext = source.extension.lowercase()
        val directMime = when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> null
        }
        if (directMime != null && source.length() <= MAX_THUMBNAIL_BYTES) {
            return PreparedThumbnail(source, directMime, temporary = false)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMBNAIL_MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val out = File(context.cacheDir, "yt_thumbnail_upload.jpg")
        try {
            var quality = 92
            while (true) {
                out.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream) }
                if (out.length() <= MAX_THUMBNAIL_BYTES || quality <= 50) break
                quality -= 10
            }
        } finally {
            bitmap.recycle()
        }
        if (out.length() == 0L || out.length() > MAX_THUMBNAIL_BYTES) {
            out.delete()
            return null
        }
        return PreparedThumbnail(out, "image/jpeg", temporary = true)
    }

    // ------------------------------------------------------------------ metadata

    private fun buildMetadata(seo: SeoPackage, options: UploadOptions, languageCode: String): VideoInsertBody {
        val language = languageCode.trim().takeIf { it.isNotEmpty() }
        val categoryId = options.categoryId.trim().takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
            ?: DEFAULT_CATEGORY
        val publishAt = if (options.mode == UploadMode.SCHEDULE) {
            rfc3339(validatedPublishAt(options.publishAtMillis))
        } else {
            null
        }
        return VideoInsertBody(
            snippet = SnippetBody(
                title = cleanTitle(seo.title),
                description = cleanDescription(seo),
                tags = cleanTags(seo.tags),
                categoryId = categoryId,
                defaultLanguage = language,
                defaultAudioLanguage = language,
            ),
            status = StatusBody(
                privacyStatus = options.privacy.wire,
                publishAt = publishAt,
                selfDeclaredMadeForKids = options.madeForKids,
            ),
        )
    }

    private fun validatedPublishAt(millis: Long?): Long {
        val earliest = System.currentTimeMillis() + MIN_SCHEDULE_LEAD_MS
        if (millis == null || millis < earliest) {
            throw PipelineException(
                userTitle = "Schedule time has passed",
                userMessage = "The scheduled publish time is missing or already in the past. Pick a new time and upload again.",
                retryable = false,
                step = PipelineStep.UPLOAD,
            )
        }
        return millis
    }

    private fun rfc3339(millis: Long): String =
        Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun cleanTitle(raw: String): String {
        val cleaned = stripAngles(raw).replace(WHITESPACE, " ").trim()
        if (cleaned.length <= MAX_TITLE_CHARS) return cleaned.ifEmpty { FALLBACK_TITLE }
        var cut = safeTake(cleaned, MAX_TITLE_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        if (lastSpace >= MAX_TITLE_CHARS * 2 / 3) cut = cut.substring(0, lastSpace)
        return cut.trim().ifEmpty { FALLBACK_TITLE }
    }

    private fun cleanDescription(seo: SeoPackage): String {
        var text = stripAngles(seo.description).trim()
        val hashtags = seo.hashtags
            .map { stripAngles(it).trim().replace(WHITESPACE, "") }
            .filter { it.length > 1 }
            .map { if (it.startsWith("#")) it else "#$it" }
        if (hashtags.isNotEmpty() && !text.contains('#')) {
            text = (text + "\n\n" + hashtags.take(MAX_APPENDED_HASHTAGS).joinToString(" ")).trim()
        }
        return utf8Truncate(text, MAX_DESCRIPTION_BYTES)
    }

    private fun cleanTags(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        var used = 0
        for (candidate in raw) {
            val tag = stripAngles(candidate)
                .replace(TAG_FORBIDDEN, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .removePrefix("#")
                .trim()
            if (tag.isEmpty() || tag.length > MAX_SINGLE_TAG_CHARS) continue
            if (!seen.add(tag.lowercase())) continue
            // YouTube counts a tag with spaces as if quoted, plus a comma between tags.
            val cost = tag.length + (if (tag.contains(' ')) 2 else 0) + (if (result.isEmpty()) 0 else 1)
            if (used + cost > MAX_TAGS_CHARS) continue
            result += tag
            used += cost
        }
        return result
    }

    private fun stripAngles(text: String): String =
        text.replace("->", "→").replace("<-", "←").replace("<", "").replace(">", "")

    /** Takes up to [max] UTF-16 chars without splitting a surrogate pair. */
    private fun safeTake(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max
        if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }

    /** Truncates to at most [maxBytes] UTF-8 bytes on a code point boundary, preferring a line break. */
    private fun utf8Truncate(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val builder = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val size = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            builder.appendCodePoint(codePoint)
            bytes += size
            index += Character.charCount(codePoint)
        }
        var result = builder.toString()
        val lastBreak = result.lastIndexOf('\n')
        if (lastBreak >= result.length * 4 / 5) result = result.substring(0, lastBreak)
        return result.trimEnd()
    }

    // ------------------------------------------------------------------ errors and helpers

    private fun isTransient(code: Int, body: String): Boolean {
        if (code == 408 || code == 429 || code in 500..599) return true
        if (code == 403) {
            val reason = parseError(body)?.errors?.firstOrNull()?.reason.orEmpty()
            return reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" || reason == "backendError"
        }
        return false
    }

    private fun mapError(code: Int, body: String): PipelineException {
        val error = parseError(body)
        val reason = error?.errors?.firstOrNull()?.reason.orEmpty()
        val apiMessage = error?.message.orEmpty()
        Log.w(TAG, "YouTube API error $code ${reason.ifEmpty { error?.status.orEmpty() }}: ${apiMessage.take(300)}")
        val step = PipelineStep.UPLOAD
        return when {
            reason == "quotaExceeded" || reason == "dailyLimitExceeded" -> PipelineException(
                userTitle = "YouTube quota used up",
                userMessage = "The daily YouTube API quota of this app's Google Cloud project is used up. " +
                    "It resets at midnight Pacific Time; your video is saved, so retry the upload later.",
                retryable = true,
                step = step,
            )
            reason == "uploadLimitExceeded" -> PipelineException(
                userTitle = "YouTube upload limit reached",
                userMessage = "This channel reached YouTube's daily upload limit. Try again in 24 hours.",
                retryable = true,
                step = step,
            )
            reason == "youtubeSignupRequired" -> PipelineException(
                userTitle = "No YouTube channel",
                userMessage = "This Google account has no YouTube channel yet. Create one on youtube.com, then retry the upload.",
                retryable = false,
                step = step,
            )
            reason == "accessNotConfigured" ||
                apiMessage.contains("has not been used in project", ignoreCase = true) ||
                apiMessage.contains("it is disabled", ignoreCase = true) -> PipelineException(
                userTitle = "YouTube API not enabled",
                userMessage = "Enable the YouTube Data API v3 in the Google Cloud project that holds this app's OAuth client (see README), then retry.",
                retryable = false,
                step = step,
            )
            reason == "insufficientPermissions" || error?.status == "PERMISSION_DENIED" &&
                apiMessage.contains("scope", ignoreCase = true) -> PipelineException(
                userTitle = "Upload permission missing",
                userMessage = "AutoTube AI is not allowed to upload to this channel. Sign out of YouTube in Settings, sign in again and allow YouTube access.",
                retryable = false,
                step = step,
            )
            code == 400 -> PipelineException(
                userTitle = "YouTube rejected the video details",
                userMessage = "YouTube did not accept the title, description, tags or schedule" +
                    (if (reason.isNotEmpty()) " ($reason)" else "") + ". Edit them and upload again.",
                retryable = false,
                step = step,
            )
            code == 403 -> PipelineException(
                userTitle = "YouTube refused the upload",
                userMessage = "YouTube refused the upload" + (if (reason.isNotEmpty()) " ($reason)" else "") +
                    ". Check the channel in YouTube Studio, then try again.",
                retryable = false,
                step = step,
            )
            else -> ApiException("YouTube", code, body.take(MAX_ERROR_BODY))
        }
    }

    private fun parseError(body: String): GoogleErrorBody? = try {
        if (body.isBlank()) null else AppJson.decodeFromString(GoogleErrorEnvelope.serializer(), body).error
    } catch (e: Exception) {
        null
    }

    private fun interrupted(cause: Throwable?): PipelineException = PipelineException(
        userTitle = "Upload interrupted",
        userMessage = "The connection to YouTube kept dropping. When you retry, the upload continues from where it stopped.",
        retryable = true,
        step = PipelineStep.UPLOAD,
        cause = cause,
    )

    private fun signInExpired(): PipelineException = PipelineException(
        userTitle = "YouTube sign-in expired",
        userMessage = "Google did not accept the sign-in. Connect YouTube again in Settings, then retry the upload.",
        retryable = false,
        step = PipelineStep.UPLOAD,
    )

    private fun ensureVideoExists(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw PipelineException(
                userTitle = "Video file missing",
                userMessage = "The exported video could not be found. Render it again, then upload.",
                retryable = false,
                step = PipelineStep.UPLOAD,
            )
        }
    }

    /** Before a retry: pause (throw) when Wi-Fi-only is on and the phone moved to mobile data. */
    private fun checkNetworkPolicy() {
        // Offline: keep backing off (the connection often returns); throws NetworkPolicyException otherwise.
        if (device.isOnline()) device.requireNetworkForTransfer(settings.current().wifiOnly)
    }

    private suspend fun backoff(failures: Int) {
        val exponent = (failures - 1).coerceIn(0, 6)
        val base = min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl exponent)
        delay(base + Random.nextLong(0L, JITTER_MS))
    }

    private suspend fun execute(request: Request): HttpResult {
        val response = http.client.newCall(request).await()
        return response.use { r ->
            HttpResult(
                code = r.code,
                body = r.body?.string().orEmpty(),
                location = r.header("Location"),
                range = r.header("Range"),
            )
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ helper types

    /** Holds the current OAuth token; refreshes it after a 401, but never twice without progress. */
    private inner class TokenHolder(private val provider: suspend () -> String) {
        var token: String = ""
            private set
        private var refreshedWithoutSuccess = false

        suspend fun load() {
            token = fetch()
        }

        fun markSuccess() {
            refreshedWithoutSuccess = false
        }

        suspend fun refresh() {
            if (refreshedWithoutSuccess) throw signInExpired()
            refreshedWithoutSuccess = true
            val stale = token
            Log.i(TAG, "YouTube token rejected (401); fetching a fresh one")
            withContext(Dispatchers.IO) {
                try {
                    // Drop the stale token from Play services' cache so the next authorize() mints a new one.
                    GoogleAuthUtil.clearToken(context, stale)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear the cached Google token (${e.javaClass.simpleName})")
                }
            }
            token = fetch()
        }

        private suspend fun fetch(): String {
            val value = try {
                provider().trim()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PipelineException) {
                throw e
            } catch (e: Exception) {
                throw PipelineException(
                    userTitle = "YouTube sign-in needed",
                    userMessage = "Could not get permission from Google to upload. Connect YouTube in Settings, then retry.",
                    retryable = true,
                    step = PipelineStep.UPLOAD,
                    cause = e,
                )
            }
            if (value.isEmpty()) throw signInExpired()
            return value
        }
    }

    /** Throttled, monotonic progress for the video part (0..[VIDEO_SHARE]). */
    private class ProgressReporter(private val total: Long, private val onProgress: (Float) -> Unit) {
        @Volatile
        private var last = -1f

        fun onPosition(bytes: Long) {
            if (total <= 0L) return
            val fraction = (bytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) * VIDEO_SHARE
            if (fraction - last >= MIN_PROGRESS_STEP || (fraction >= VIDEO_SHARE && last < VIDEO_SHARE)) {
                report(fraction)
            }
        }

        fun report(value: Float) {
            if (value < last) return
            last = value
            try {
                onProgress(value)
            } catch (e: Exception) {
                // A failing UI callback must never break the upload (it may run on an OkHttp thread).
                Log.w(TAG, "Upload progress callback failed (${e.javaClass.simpleName})")
            }
        }
    }

    /** Streams [length] bytes of [file] starting at [offset]; can be written again on retry. */
    private class FileChunkBody(
        private val file: File,
        private val offset: Long,
        private val length: Long,
        private val mediaType: MediaType,
        private val onWritten: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(IO_BUFFER)
                var written = 0L
                while (written < length) {
                    val toRead = min(buffer.size.toLong(), length - written).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read < 0) throw IOException("Video file ended early")
                    sink.write(buffer, 0, read)
                    written += read
                    onWritten(written)
                }
            }
        }
    }

    /** Remembers resumable session URIs on disk, keyed by the video path. */
    private class SessionStore(private val dir: File) {

        fun keyFor(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(file.absolutePath.toByteArray(Charsets.UTF_8))
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }

        fun load(key: String, file: File, metadataHash: String): String? {
            val record = File(dir, "$key.json")
            if (!record.isFile) return null
            val session = try {
                AppJson.decodeFromString(SessionRecord.serializer(), record.readText())
            } catch (e: Exception) {
                record.delete()
                return null
            }
            val fresh = System.currentTimeMillis() - session.createdAt < SESSION_MAX_AGE_MS
            val sameFile = session.fileLength == file.length() && session.fileModified == file.lastModified()
            if (fresh && sameFile && session.metadataHash == metadataHash && session.sessionUri.startsWith("https://")) {
                return session.sessionUri
            }
            record.delete()
            return null
        }

        fun save(key: String, file: File, metadataHash: String, sessionUri: String) {
            try {
                if (!dir.isDirectory) dir.mkdirs()
                val record = SessionRecord(
                    sessionUri = sessionUri,
                    fileLength = file.length(),
                    fileModified = file.lastModified(),
                    metadataHash = metadataHash,
                    createdAt = System.currentTimeMillis(),
                )
                File(dir, "$key.json").writeText(AppJson.encodeToString(SessionRecord.serializer(), record))
            } catch (e: IOException) {
                Log.w(TAG, "Upload session could not be remembered (${e.javaClass.simpleName})")
            }
        }

        fun clear(key: String) {
            File(dir, "$key.json").delete()
        }
    }

    private sealed interface Outcome {
        data class Completed(val videoId: String) : Outcome
        data class Resume(val offset: Long) : Outcome
        data object Unauthorized : Outcome
        data object Expired : Outcome
        data class Transient(val reason: String, val error: Throwable?) : Outcome
    }

    private class HttpResult(
        val code: Int,
        val body: String,
        val location: String?,
        val range: String?,
    )

    private class PreparedThumbnail(val file: File, val mimeType: String, val temporary: Boolean)

    @Serializable
    private data class VideoInsertBody(val snippet: SnippetBody, val status: StatusBody)

    @Serializable
    private data class SnippetBody(
        val title: String,
        val description: String,
        val tags: List<String>,
        val categoryId: String,
        val defaultLanguage: String? = null,
        val defaultAudioLanguage: String? = null,
    )

    @Serializable
    private data class StatusBody(
        val privacyStatus: String,
        val publishAt: String? = null,
        val selfDeclaredMadeForKids: Boolean,
    )

    @Serializable
    private data class VideoResource(val id: String? = null)

    @Serializable
    private data class GoogleErrorEnvelope(val error: GoogleErrorBody? = null)

    @Serializable
    private data class GoogleErrorBody(
        val code: Int = 0,
        val message: String = "",
        val status: String = "",
        val errors: List<GoogleErrorItem> = emptyList(),
    )

    @Serializable
    private data class GoogleErrorItem(val reason: String = "", val message: String = "", val domain: String = "")

    @Serializable
    private data class SessionRecord(
        val sessionUri: String,
        val fileLength: Long,
        val fileModified: Long,
        val metadataHash: String,
        val createdAt: Long,
    )

    private companion object {
        const val TAG = "AutoTube"
        const val INIT_URL = "https://www.googleapis.com/upload/youtube/v3/videos"
        const val THUMBNAIL_URL = "https://www.googleapis.com/upload/youtube/v3/thumbnails/set"
        const val VIDEO_MIME = "video/mp4"
        val VIDEO_MEDIA_TYPE: MediaType = VIDEO_MIME.toMediaType()
        val JSON_MEDIA_TYPE: MediaType = "application/json; charset=UTF-8".toMediaType()

        /** 8 MiB = 32 x 256 KiB, as the resumable protocol requires multiples of 256 KiB. */
        const val CHUNK_SIZE = 8L * 1024L * 1024L
        const val IO_BUFFER = 64 * 1024
        const val MAX_ATTEMPTS = 8
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 64_000L
        const val JITTER_MS = 1_000L
        const val THUMBNAIL_ATTEMPTS = 3
        const val MAX_THUMBNAIL_BYTES = 2L * 1024L * 1024L
        const val THUMBNAIL_MAX_SIDE = 1920
        const val MAX_ERROR_BODY = 2_000

        const val VIDEO_SHARE = 0.97f
        const val MIN_PROGRESS_STEP = 0.005f

        const val SESSION_DIR = "youtube_upload_sessions"
        /** Google keeps resumable sessions for about a week; stay safely below that. */
        const val SESSION_MAX_AGE_MS = 5L * 24L * 60L * 60L * 1000L

        const val MAX_TITLE_CHARS = 100
        const val MAX_DESCRIPTION_BYTES = 5_000
        const val MAX_TAGS_CHARS = 500
        const val MAX_SINGLE_TAG_CHARS = 100
        const val MAX_APPENDED_HASHTAGS = 5
        const val MIN_SCHEDULE_LEAD_MS = 2L * 60L * 1000L
        const val DEFAULT_CATEGORY = "22"
        const val FALLBACK_TITLE = "New video"

        val WHITESPACE = Regex("""\s+""")
        val TAG_FORBIDDEN = Regex("""[,"]""")
        val RANGE_REGEX = Regex("""(\d+)\s*-\s*(\d+)""")
    }
}
