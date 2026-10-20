package com.autotube.ai.data.remote.youtube

import android.util.Log
import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.PipelineException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeDataClient
import com.autotube.ai.domain.YtVideoStat
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube Data API v3 (API key): `search.list` (most viewed recent videos) followed by
 * `videos.list` for statistics and durations. Results keep the search ranking. Shorts mode asks for
 * `videoDuration=short` and keeps videos of at most 3 minutes.
 */
@Singleton
class YouTubeDataClientImpl @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : YouTubeDataClient {

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.YOUTUBE_DATA)

    override suspend fun searchPopular(
        query: String,
        language: AppLanguage,
        regionCode: String,
        publishedAfterMillis: Long,
        shortsOnly: Boolean,
        maxResults: Int,
    ): List<YtVideoStat> {
        val apiKey = settingsRepository.requireApiKey(ApiKey.YOUTUBE_DATA)
        val q = query.replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) return emptyList()

        val searchUrl = "$API_BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("type", "video")
            .addQueryParameter("order", "viewCount")
            .addQueryParameter("q", q)
            .addQueryParameter("maxResults", maxResults.coerceIn(1, 50).toString())
            .addQueryParameter("publishedAfter", rfc3339(publishedAfterMillis))
            .addQueryParameter("relevanceLanguage", language.code)
            .apply {
                val region = regionCode.trim().uppercase()
                if (REGION_REGEX.matches(region)) addQueryParameter("regionCode", region)
                if (shortsOnly) addQueryParameter("videoDuration", "short")
            }
            .addQueryParameter("key", apiKey)
            .build()
        val search = get(searchUrl) { raw -> AppJson.decodeApi(SERVICE, YtSearchResponse.serializer(), raw) }
        val ids = search.items.mapNotNull { it.id?.videoId?.takeIf { id -> id.isNotBlank() } }.distinct()
        if (ids.isEmpty()) return emptyList()

        val videosUrl = "$API_BASE/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet,statistics,contentDetails")
            .addQueryParameter("id", ids.joinToString(","))
            .addQueryParameter("maxResults", ids.size.coerceAtMost(50).toString())
            .addQueryParameter("key", apiKey)
            .build()
        val videos = get(videosUrl) { raw -> AppJson.decodeApi(SERVICE, YtVideosResponse.serializer(), raw) }
        val byId = videos.items.associateBy { it.id }

        return ids.mapNotNull { id ->
            val item = byId[id] ?: return@mapNotNull null
            val snippet = item.snippet
            val stats = item.statistics
            YtVideoStat(
                videoId = id,
                title = snippet?.title.orEmpty(),
                channelTitle = snippet?.channelTitle.orEmpty(),
                publishedAtMillis = parseTimestamp(snippet?.publishedAt),
                viewCount = stats?.viewCount?.toLongOrNull() ?: 0L,
                likeCount = stats?.likeCount?.toLongOrNull() ?: 0L,
                commentCount = stats?.commentCount?.toLongOrNull() ?: 0L,
                durationSec = parseIsoDurationSec(item.contentDetails?.duration),
                tags = snippet?.tags.orEmpty(),
            )
        }.filter { !shortsOnly || it.durationSec in 1..MAX_SHORTS_SEC }
    }

    private suspend fun <T> get(url: HttpUrl, decode: (String) -> T): T {
        val request = Request.Builder().url(url).get().build()
        return withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "YouTube Data API failed (attempt $attempt): ${error.message}") },
        ) {
            val raw = try {
                http.callForString(SERVICE, request)
            } catch (e: ApiException) {
                throw friendlyError(e)
            }
            decode(raw)
        }
    }

    private fun friendlyError(e: ApiException): PipelineException = when {
        e.code == 403 && (e.body.contains("quotaExceeded") || e.body.contains("dailyLimitExceeded")) ->
            PipelineException(
                userTitle = "YouTube quota used up",
                userMessage = "The YouTube Data API daily quota is used up. Research uses offline ideas until it resets.",
                retryable = false,
                cause = e,
            )
        e.code == 400 && e.body.contains("keyInvalid") ->
            PipelineException(
                userTitle = "YouTube API key invalid",
                userMessage = "The YouTube Data API key was rejected. Check it in Settings.",
                retryable = false,
                cause = e,
            )
        else -> e
    }

    private fun rfc3339(millis: Long): String =
        Instant.ofEpochMilli(millis.coerceAtLeast(0L)).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun parseTimestamp(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .getOrDefault(0L)
    }

    private companion object {
        const val TAG = "YouTubeDataClient"
        const val SERVICE = "YouTube Data API"
        const val API_BASE = "https://www.googleapis.com/youtube/v3"
        const val MAX_SHORTS_SEC = 180
        val REGION_REGEX = Regex("^[A-Z]{2}$")
    }
}

private val ISO_DURATION = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.\\d+)?S)?)?$")

/** ISO-8601 duration ("PT1H2M3S", "P1DT5M", "PT45S") to whole seconds; 0 when missing or invalid. */
internal fun parseIsoDurationSec(value: String?): Int {
    if (value.isNullOrBlank()) return 0
    val match = ISO_DURATION.matchEntire(value.trim().uppercase()) ?: return 0
    fun part(index: Int): Long = match.groupValues[index].toLongOrNull() ?: 0L
    val total = part(1) * 7 * 86_400 + part(2) * 86_400 + part(3) * 3_600 + part(4) * 60 + part(5)
    return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

@Serializable
private data class YtSearchResponse(
    val items: List<YtSearchItem> = emptyList(),
)

@Serializable
private data class YtSearchItem(
    val id: YtSearchId? = null,
)

@Serializable
private data class YtSearchId(
    val videoId: String? = null,
)

@Serializable
private data class YtVideosResponse(
    val items: List<YtVideoItem> = emptyList(),
)

@Serializable
private data class YtVideoItem(
    val id: String = "",
    val snippet: YtSnippet? = null,
    val statistics: YtStatistics? = null,
    val contentDetails: YtContentDetails? = null,
)

@Serializable
private data class YtSnippet(
    val title: String? = null,
    val channelTitle: String? = null,
    val publishedAt: String? = null,
    val tags: List<String>? = null,
)

/** The API sends counts as strings and omits hidden ones. */
@Serializable
private data class YtStatistics(
    val viewCount: String? = null,
    val likeCount: String? = null,
    val commentCount: String? = null,
)

@Serializable
private data class YtContentDetails(
    val duration: String? = null,
)
