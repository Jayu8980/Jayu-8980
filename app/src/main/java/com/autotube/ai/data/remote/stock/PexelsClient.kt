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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pexels stock videos and photos.
 *
 * For videos the best MP4 rendition is chosen: the smallest file whose short side is at least
 * 1080 px, else the largest available; renditions above 4K are skipped. Ids are
 * `pexels_video:<id>` / `pexels_photo:<id>`.
 */
@Singleton
class PexelsClient @Inject constructor(
    private val http: OkHttpClient,
    private val settingsRepository: SettingsRepository,
) : StockMediaClient {

    override val name: String = "Pexels"

    override suspend fun isAvailable(): Boolean = settingsRepository.isRemoteUsable(ApiKey.PEXELS)

    override suspend fun searchVideos(query: String, orientation: Orientation, perPage: Int): List<StockCandidate> {
        val q = cleanQuery(query) ?: return emptyList()
        val url = VIDEO_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", q)
            .addQueryParameter("orientation", orientation.pexelsValue())
            .addQueryParameter("per_page", perPage.coerceIn(1, MAX_PER_PAGE).toString())
            .addQueryParameter("size", "medium")
            .build()
        val response = fetch(url) { raw -> AppJson.decodeApi(SERVICE, PexelsVideoSearch.serializer(), raw) }
        return response.videos.mapNotNull { video ->
            val file = chooseVideoFile(video.videoFiles) ?: return@mapNotNull null
            StockCandidate(
                id = "pexels_video:${video.id}",
                source = VisualSource.PEXELS_VIDEO,
                downloadUrl = file.link,
                previewUrl = video.image,
                width = file.width ?: video.width,
                height = file.height ?: video.height,
                durationSec = video.duration,
                tags = slugWords(video.url),
                attribution = "Video by ${video.user?.name?.takeIf { it.isNotBlank() } ?: "a Pexels creator"} on Pexels",
            )
        }
    }

    override suspend fun searchPhotos(query: String, orientation: Orientation, perPage: Int): List<StockCandidate> {
        val q = cleanQuery(query) ?: return emptyList()
        val url = PHOTO_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", q)
            .addQueryParameter("orientation", orientation.pexelsValue())
            .addQueryParameter("per_page", perPage.coerceIn(1, MAX_PER_PAGE).toString())
            .build()
        val response = fetch(url) { raw -> AppJson.decodeApi(SERVICE, PexelsPhotoSearch.serializer(), raw) }
        return response.photos.mapNotNull { photo ->
            val src = photo.src ?: return@mapNotNull null
            val download = src.large2x?.takeIf { it.isNotBlank() }
                ?: src.original?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val (w, h) = large2xSize(photo.width, photo.height)
            StockCandidate(
                id = "pexels_photo:${photo.id}",
                source = VisualSource.PEXELS_PHOTO,
                downloadUrl = download,
                previewUrl = src.medium ?: src.small,
                width = w,
                height = h,
                durationSec = 0,
                tags = photo.alt?.takeIf { it.isNotBlank() } ?: slugWords(photo.url),
                attribution = "Photo by ${photo.photographer?.takeIf { it.isNotBlank() } ?: "a Pexels creator"} on Pexels",
            )
        }
    }

    private suspend fun <T> fetch(url: HttpUrl, decode: (String) -> T): T {
        val apiKey = settingsRepository.requireApiKey(ApiKey.PEXELS)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", apiKey)
            .get()
            .build()
        return withRetry(
            attempts = 3,
            onRetry = { attempt, error -> Log.w(TAG, "Pexels search failed (attempt $attempt): ${error.message}") },
        ) {
            decode(http.callForString(SERVICE, request))
        }
    }

    private fun chooseVideoFile(files: List<PexelsVideoFile>): PexelsVideoFile? {
        val usable = files.filter { file ->
            file.link.isNotBlank() &&
                (file.fileType == null || file.fileType.equals("video/mp4", ignoreCase = true))
        }
        val sized = usable.filter { (it.width ?: 0) > 0 && (it.height ?: 0) > 0 }
            .filter { file ->
                val w = file.width ?: 0
                val h = file.height ?: 0
                min(w, h) <= MAX_SHORT_SIDE && max(w, h) <= MAX_LONG_SIDE
            }
        if (sized.isEmpty()) return usable.firstOrNull { it.quality.equals("hd", ignoreCase = true) } ?: usable.firstOrNull()
        val fullHd = sized.filter { min(it.width ?: 0, it.height ?: 0) >= 1080 }
        return if (fullHd.isNotEmpty()) {
            fullHd.minWith(compareBy<PexelsVideoFile>({ it.pixels() }, { it.size ?: Long.MAX_VALUE }))
        } else {
            sized.maxWith(compareBy<PexelsVideoFile>({ it.pixels() }, { it.size ?: 0L }))
        }
    }

    private fun PexelsVideoFile.pixels(): Long = (width ?: 0).toLong() * (height ?: 0).toLong()

    /** large2x is resized to fit 1880x1300 (940x650 at 2x); report the resulting size. */
    private fun large2xSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val scale = min(1.0, min(LARGE2X_MAX_W / width, LARGE2X_MAX_H / height))
        return (width * scale).roundToInt() to (height * scale).roundToInt()
    }

    /** "https://www.pexels.com/video/aerial-view-of-a-city-3129671/" -> "aerial view of a city". */
    private fun slugWords(pageUrl: String?): String {
        val segment = pageUrl?.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() } ?: return ""
        return segment.replace(Regex("-?\\d+$"), "").replace('-', ' ').trim()
    }

    private fun cleanQuery(query: String): String? =
        query.replace(Regex("\\s+"), " ").trim().take(MAX_QUERY_CHARS).takeIf { it.isNotEmpty() }

    private fun Orientation.pexelsValue(): String = when (this) {
        Orientation.LANDSCAPE -> "landscape"
        Orientation.PORTRAIT -> "portrait"
    }

    private companion object {
        const val TAG = "PexelsClient"
        const val SERVICE = "Pexels"
        const val VIDEO_SEARCH_URL = "https://api.pexels.com/videos/search"
        const val PHOTO_SEARCH_URL = "https://api.pexels.com/v1/search"
        const val MAX_PER_PAGE = 80
        const val MAX_QUERY_CHARS = 100
        const val MAX_SHORT_SIDE = 2160
        const val MAX_LONG_SIDE = 4096
        const val LARGE2X_MAX_W = 1880.0
        const val LARGE2X_MAX_H = 1300.0
    }
}

@Serializable
private data class PexelsVideoSearch(
    val videos: List<PexelsVideo> = emptyList(),
)

@Serializable
private data class PexelsVideo(
    val id: Long,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
    val url: String? = null,
    val image: String? = null,
    val user: PexelsUser? = null,
    @SerialName("video_files") val videoFiles: List<PexelsVideoFile> = emptyList(),
)

@Serializable
private data class PexelsUser(
    val name: String? = null,
)

@Serializable
private data class PexelsVideoFile(
    val quality: String? = null,
    @SerialName("file_type") val fileType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val link: String = "",
    val size: Long? = null,
)

@Serializable
private data class PexelsPhotoSearch(
    val photos: List<PexelsPhoto> = emptyList(),
)

@Serializable
private data class PexelsPhoto(
    val id: Long,
    val width: Int = 0,
    val height: Int = 0,
    val url: String? = null,
    val photographer: String? = null,
    val alt: String? = null,
    val src: PexelsPhotoSrc? = null,
)

@Serializable
private data class PexelsPhotoSrc(
    val original: String? = null,
    val large2x: String? = null,
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)
