package com.autotube.ai.data.remote.stock

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.ApiKey
import com.autotube.ai.core.model.Orientation
import com.autotube.ai.core.model.VisualSource
import com.autotube.ai.data.remote.http.callForString
import com.autotube.ai.data.remote.http.decodeApi
import com.autotube.ai.data.remote.http.isRemoteUsable
import com.autotube.ai.data.remote.http.requireApiKey
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StockCandidate
import com.autotube.ai.domain.StockMediaClient
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixabay stock videos and photos (safe search on). The key travels as a query parameter, which
 * the debug HTTP logger masks. The video API has no orientation filter, so results matching the
 * requested orientation are ranked first. Ids are `pixabay_video:<id>` / `pixabay_photo:<id>`.
 */
@Singleton
class PixabayClient @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : StockMediaClient {

    override val name: String = "Pixabay"

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.PIXABAY)

    override suspend fun searchVideos(query: String, orientation: Orientation, perPage: Int): List<StockCandidate> {
        val q = cleanQuery(query) ?: return emptyList()
        val wanted = perPage.coerceAtLeast(1)
        val url = VIDEO_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("per_page", (wanted * 2).coerceIn(MIN_PER_PAGE, MAX_PER_PAGE).toString())
            .addQueryParameter("safesearch", "true")
            .build()
        val response = fetch(url) { raw -> AppJson.decodeApi(SERVICE, PixabayVideoSearch.serializer(), raw) }
        val candidates = response.hits.mapNotNull { hit ->
            val renditions = hit.videos ?: return@mapNotNull null
            val file = listOfNotNull(renditions.large, renditions.medium)
                .firstOrNull { it.url.isNotBlank() }
                ?: return@mapNotNull null
            StockCandidate(
                id = "pixabay_video:${hit.id}",
                source = VisualSource.PIXABAY_VIDEO,
                downloadUrl = file.url,
                previewUrl = file.thumbnail?.takeIf { it.isNotBlank() }
                    ?: renditions.medium?.thumbnail?.takeIf { it.isNotBlank() }
                    ?: renditions.small?.thumbnail?.takeIf { it.isNotBlank() },
                width = file.width,
                height = file.height,
                durationSec = hit.duration,
                tags = hit.tags,
                attribution = "Video by ${hit.user.ifBlank { "a Pixabay creator" }} on Pixabay",
            )
        }
        return candidates
            .sortedByDescending { matchesOrientation(it.width, it.height, orientation) }
            .take(wanted)
    }

    override suspend fun searchPhotos(query: String, orientation: Orientation, perPage: Int): List<StockCandidate> {
        val q = cleanQuery(query) ?: return emptyList()
        val url = PHOTO_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("image_type", "photo")
            .addQueryParameter(
                "orientation",
                when (orientation) {
                    Orientation.LANDSCAPE -> "horizontal"
                    Orientation.PORTRAIT -> "vertical"
                },
            )
            .addQueryParameter("per_page", perPage.coerceIn(MIN_PER_PAGE, MAX_PER_PAGE).toString())
            .addQueryParameter("safesearch", "true")
            .build()
        val response = fetch(url) { raw -> AppJson.decodeApi(SERVICE, PixabayPhotoSearch.serializer(), raw) }
        return response.hits.mapNotNull { hit ->
            if (hit.largeImageURL.isBlank()) return@mapNotNull null
            val (w, h) = largeImageSize(hit.imageWidth, hit.imageHeight)
            StockCandidate(
                id = "pixabay_photo:${hit.id}",
                source = VisualSource.PIXABAY_PHOTO,
                downloadUrl = hit.largeImageURL,
                previewUrl = hit.webformatURL ?: hit.previewURL,
                width = w,
                height = h,
                durationSec = 0,
                tags = hit.tags,
                attribution = "Photo by ${hit.user.ifBlank { "a Pixabay creator" }} on Pixabay",
            )
        }.take(perPage.coerceAtLeast(1))
    }

    private suspend fun <T> fetch(baseUrl: HttpUrl, decode: (String) -> T): T {
        val apiKey = settingsRepository.requireApiKey(ApiKey.PIXABAY)
        val request = Request.Builder()
            .url(baseUrl.newBuilder().addQueryParameter("key", apiKey).build())
            .get()
            .build()
        return withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "Pixabay search failed (attempt $attempt): ${error.message}") },
        ) {
            decode(http.callForString(SERVICE, request))
        }
    }

    private fun matchesOrientation(width: Int, height: Int, orientation: Orientation): Boolean = when (orientation) {
        Orientation.LANDSCAPE -> width >= height
        Orientation.PORTRAIT -> height > width
    }

    /** largeImageURL is scaled so its longest side is at most 1280 px. */
    private fun largeImageSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= LARGE_IMAGE_MAX) return width to height
        val scale = LARGE_IMAGE_MAX.toDouble() / longest
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    private fun cleanQuery(query: String): String? =
        query.replace(Regex("\\s+"), " ").trim().take(MAX_QUERY_CHARS).trim().takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "PixabayClient"
        const val SERVICE = "Pixabay"
        const val VIDEO_SEARCH_URL = "https://pixabay.com/api/videos/"
        const val PHOTO_SEARCH_URL = "https://pixabay.com/api/"
        const val MIN_PER_PAGE = 3
        const val MAX_PER_PAGE = 200
        const val MAX_QUERY_CHARS = 100
        const val LARGE_IMAGE_MAX = 1280
    }
}

@Serializable
private data class PixabayVideoSearch(
    val hits: List<PixabayVideoHit> = emptyList(),
)

@Serializable
private data class PixabayVideoHit(
    val id: Long,
    val tags: String = "",
    val duration: Int = 0,
    val user: String = "",
    val videos: PixabayVideoRenditions? = null,
)

@Serializable
private data class PixabayVideoRenditions(
    val large: PixabayVideoFile? = null,
    val medium: PixabayVideoFile? = null,
    val small: PixabayVideoFile? = null,
)

@Serializable
private data class PixabayVideoFile(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val size: Long = 0,
    val thumbnail: String? = null,
)

@Serializable
private data class PixabayPhotoSearch(
    val hits: List<PixabayPhotoHit> = emptyList(),
)

@Serializable
private data class PixabayPhotoHit(
    val id: Long,
    val tags: String = "",
    val user: String = "",
    val largeImageURL: String = "",
    val webformatURL: String? = null,
    val previewURL: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)
