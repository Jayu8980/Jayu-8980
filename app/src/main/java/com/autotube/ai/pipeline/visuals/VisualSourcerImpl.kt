package com.autotube.ai.pipeline.visuals

import com.autotube.ai.core.common.ApiException
import com.autotube.ai.core.common.NetworkPolicyException
import com.autotube.ai.core.common.withRetry
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.ImageGenProviderType
import com.autotube.ai.core.model.Orientation
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.TextToVideoProviderType
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VisualSource
import com.autotube.ai.core.model.VoiceTimeline
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.Downloader
import com.autotube.ai.domain.ImageGenClient
import com.autotube.ai.domain.LlmClient
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.StockCandidate
import com.autotube.ai.domain.StockMediaClient
import com.autotube.ai.domain.TextToVideoClient
import com.autotube.ai.domain.VisualSourcer
import com.autotube.ai.pipeline.editor.PacingProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Finds the visuals for every scene (spec 7B). Per shot slot it walks the chain
 * stock video -> AI video -> stock photo -> AI image -> gallery filler -> on-device art, checks each
 * candidate with a vision model when available (max 3 tries per slot, best kept), never repeats an
 * asset id inside a project, and alternates wide / medium / close-up framing within a scene.
 * Per-slot failures fall down the chain; the on-device art at the end always works offline.
 */
@Singleton
class VisualSourcerImpl @Inject constructor(
    private val stockClients: Set<@JvmSuppressWildcards StockMediaClient>,
    private val textToVideoClients: Map<TextToVideoProviderType, @JvmSuppressWildcards TextToVideoClient>,
    private val imageGenClients: Map<ImageGenProviderType, @JvmSuppressWildcards ImageGenClient>,
    private val downloader: Downloader,
    private val projectFiles: ProjectFiles,
    private val settingsRepository: SettingsRepository,
    private val deviceStatus: DeviceStatus,
    private val llmProvider: LlmProvider,
    private val promptBuilder: CinematicPromptBuilder,
    private val visionScorer: VisionScorer,
    private val gallery: GalleryMediaSource,
    private val localArt: LocalArtGenerator,
) : VisualSourcer {

    override suspend fun source(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        existing: List<VisualAsset>,
        onlyScenes: Set<Int>?,
        log: StepLogger,
        onProgress: (Float) -> Unit,
    ): List<VisualAsset> {
        val sceneNumbers = script.scenes.map { it.sceneNumber }.toSet()
        val workScenes = script.scenes.filter { onlyScenes == null || it.sceneNumber in onlyScenes }
        val workNumbers = workScenes.map { it.sceneNumber }.toSet()

        // Keep everything outside the work set; for a full run keep (resume) assets whose files exist.
        val kept = existing.filter { a ->
            when {
                a.sceneNumber !in sceneNumbers -> false
                a.sceneNumber !in workNumbers -> true
                onlyScenes == null -> fileExists(a.localPath)
                else -> false
            }
        }
        val session = openSession(projectId, request, log, usedIds = (existing.map { it.id } + kept.map { it.id }).toMutableSet())
        val pacing = PacingProfile.forScript(request, script)

        class Plan(val scene: Scene, val slots: List<Int>, val slotMs: Long, val typography: Boolean)

        val plans = workScenes.map { scene ->
            val timing = timeline.timingOf(scene.sceneNumber)
            val span = timing?.let { it.durationMs + it.gapAfterMs } ?: (scene.wordCount * 420L).coerceAtLeast(2_000L)
            val shots = pacing.shotsFor(scene, span)
            val have = kept.count { it.sceneNumber == scene.sceneNumber && !LocalArtGenerator.isTypography(it) }
            val slots = (have until shots).toList()
            val hasTypography = kept.any { it.sceneNumber == scene.sceneNumber && LocalArtGenerator.isTypography(it) }
            val wantsTypography = !hasTypography && (scene.onScreenText.isNotBlank() || scene.keyWords.isNotEmpty())
            Plan(scene, slots, (span / shots).coerceAtLeast(1_500L), wantsTypography)
        }
        val totalUnits = plans.sumOf { it.slots.size + if (it.typography) 1 else 0 }.coerceAtLeast(1)
        var done = 0
        onProgress(0f)
        log.i(
            "Sourcing ${plans.sumOf { it.slots.size }} shots for ${plans.size} scenes " +
                "(stock: ${session.stock.size}, AI video: ${session.textToVideo != null}, AI image: ${session.imageGen != null}, " +
                "vision check: ${session.vision != null}, network: ${session.network})",
        )

        val produced = ArrayList<VisualAsset>()
        for (plan in plans) {
            for (slot in plan.slots) {
                val desired = RHYTHM[slot % RHYTHM.size]
                val asset = fillSlot(session, plan.scene, slot, desired, plan.slotMs)
                session.usedIds += asset.id
                produced += asset
                log.i(
                    "Scene ${plan.scene.sceneNumber} shot ${slot + 1}: ${asset.source.label} ${asset.shotType.name.lowercase()}" +
                        (if (asset.matchScore >= 0) " (match ${asset.matchScore}/10)" else "") + " [${asset.id}]",
                )
                done++
                onProgress(done.toFloat() / totalUnits)
            }
            if (plan.typography) {
                try {
                    val slot = freeLocalSlot(session, plan.scene.sceneNumber, TYPOGRAPHY_SLOT_BASE)
                    val asset = localArt.generate(plan.scene, slot, request.format, session.dir, typography = true)
                    session.usedIds += asset.id
                    produced += asset
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w("Could not draw the text card for scene ${plan.scene.sceneNumber}", e)
                } catch (e: OutOfMemoryError) {
                    log.w("Not enough memory to draw the text card for scene ${plan.scene.sceneNumber}")
                }
                done++
                onProgress(done.toFloat() / totalUnits)
            }
        }
        onProgress(1f)
        val order = script.scenes.mapIndexed { i, s -> s.sceneNumber to i }.toMap()
        return (kept + produced).sortedBy { order[it.sceneNumber] ?: Int.MAX_VALUE }
    }

    override suspend fun replace(
        projectId: Long,
        request: VideoRequest,
        scene: Scene,
        excludeIds: Set<String>,
        log: StepLogger,
    ): VisualAsset? {
        val session = openSession(projectId, request, log, usedIds = excludeIds.toMutableSet())
        val slot = REPLACE_SLOT_BASE + excludeIds.size
        val desired = RHYTHM[excludeIds.size % RHYTHM.size]
        return try {
            fillSlot(session, scene, slot, desired, REPLACE_SLOT_MS).takeIf { it.id !in excludeIds }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Could not find a replacement visual for scene ${scene.sceneNumber}", e)
            null
        }
    }

    // ------------------------------------------------------------------ session

    private class Session(
        val projectId: Long,
        val request: VideoRequest,
        val settings: AppSettings,
        val dir: File,
        val log: StepLogger,
        val usedIds: MutableSet<String>,
        var network: Boolean,
        val stock: List<StockMediaClient>,
        var textToVideo: TextToVideoClient?,
        var imageGen: ImageGenClient?,
        var vision: LlmClient?,
    ) {
        val rejected = HashSet<String>()
        val searchCache = HashMap<String, List<Pair<StockCandidate, String>>>()
        var galleryItems: List<GalleryItem>? = null
        var aiVideoBudget = MAX_AI_VIDEOS
        var aiImageBudget = MAX_AI_IMAGES
        var aiVideoFailures = 0
        var aiImageFailures = 0
        var visionFailures = 0
        val orientation: Orientation get() = if (request.format.isVertical) Orientation.PORTRAIT else Orientation.LANDSCAPE
        fun isTaken(id: String) = id in usedIds || id in rejected
    }

    private suspend fun openSession(projectId: Long, request: VideoRequest, log: StepLogger, usedIds: MutableSet<String>): Session {
        val settings = settingsRepository.current()
        val dir = projectFiles.visualsDir(projectId)
        withContext(Dispatchers.IO) { dir.mkdirs() }
        var network = !settings.forceOffline && safeBool { deviceStatus.isOnline() }
        if (network && settings.wifiOnly && !safeBool { deviceStatus.isUnmetered() }) {
            log.w("Wi-Fi only is on and the phone is on mobile data: using on-device visuals.")
            network = false
        }
        val stock = if (network && settings.useStockFootage) {
            stockClients.filter { client -> safeBool { client.isAvailable() } }.sortedBy { it.name }
        } else {
            emptyList()
        }
        val t2v = if (network && settings.textToVideoProvider != TextToVideoProviderType.NONE) {
            textToVideoClients[settings.textToVideoProvider]?.takeIf { safeBool { it.isAvailable() } }
        } else {
            null
        }
        val img = if (network && settings.imageGenProvider != ImageGenProviderType.NONE) {
            imageGenClients[settings.imageGenProvider]?.takeIf { safeBool { it.isAvailable() } }
        } else {
            null
        }
        val vision = if (settings.visionCheck) {
            try {
                llmProvider.vision()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w("Vision model unavailable: ${e.message?.take(120)}")
                null
            }
        } else {
            null
        }
        return Session(projectId, request, settings, dir, log, usedIds, network, stock, t2v, img, vision)
    }

    // ------------------------------------------------------------------ slot chain

    private class SlotJob(val scene: Scene, val slot: Int, val desired: ShotType, val slotMs: Long) {
        var tries = 0
        var best: VisualAsset? = null
        val exhausted: Boolean get() = tries >= MAX_TRIES_PER_SLOT
    }

    /** Walks the source chain for one slot. Always returns an asset (on-device art at worst). */
    private suspend fun fillSlot(s: Session, scene: Scene, slot: Int, desired: ShotType, slotMs: Long): VisualAsset {
        val job = SlotJob(scene, slot, desired, slotMs)

        if (s.network && s.stock.isNotEmpty()) {
            tryStock(s, job, videos = true)?.let { return it }
            if (job.exhausted) job.best?.let { return it }
        }
        if (s.network && s.textToVideo != null && s.aiVideoBudget > 0) {
            tryAiVideo(s, job)?.let { return it }
            if (job.exhausted) job.best?.let { return it }
        }
        if (s.network && s.stock.isNotEmpty()) {
            tryStock(s, job, videos = false)?.let { return it }
            if (job.exhausted) job.best?.let { return it }
        }
        if (s.network && s.imageGen != null && s.aiImageBudget > 0) {
            tryAiImage(s, job)?.let { return it }
            if (job.exhausted) job.best?.let { return it }
        }
        job.best?.let { return it }
        if (s.settings.useGalleryMedia) {
            tryGallery(s, job)?.let { return it }
            job.best?.let { return it }
        }
        val localSlot = freeLocalSlot(s, scene.sceneNumber, slot)
        return localArt.generate(scene, localSlot, s.request.format, s.dir, typography = false, shotType = desired)
    }

    /**
     * Scores [asset] with the vision model. Returns it when good enough (or when no vision check
     * runs); otherwise remembers the best attempt and returns null.
     */
    private suspend fun consider(s: Session, job: SlotJob, asset: VisualAsset): VisualAsset? {
        job.tries++
        val client = s.vision
        val preview = asset.previewPath
        if (client == null || preview == null) return asset
        val verdict = visionScorer.score(client, File(preview), job.scene, s.log)
        if (verdict == null) {
            s.visionFailures++
            if (s.visionFailures >= MAX_VISION_FAILURES) {
                s.log.w("Vision check keeps failing; continuing without it.")
                s.vision = null
            }
            return asset
        }
        s.visionFailures = 0
        val scored = asset.copy(matchScore = verdict.score, shotType = verdict.shotType ?: asset.shotType)
        if (scored.matchScore >= s.settings.visionMinScore) return scored
        s.log.i("Scene ${job.scene.sceneNumber}: rejected ${asset.id} (match ${verdict.score}/10: ${verdict.reason})")
        val best = job.best
        if (best == null || scored.matchScore > best.matchScore) {
            if (best != null) discard(s, best)
            job.best = scored
        } else {
            discard(s, scored)
        }
        return null
    }

    private suspend fun discard(s: Session, asset: VisualAsset) {
        s.rejected += asset.id
        withContext(Dispatchers.IO) {
            File(asset.localPath).delete()
            asset.previewPath?.let { File(it).delete() }
        }
    }

    private suspend fun tryStock(s: Session, job: SlotJob, videos: Boolean): VisualAsset? {
        val candidates = rankStock(s, job, stockCandidates(s, job.scene, videos), videos)
        for ((candidate, query) in candidates) {
            if (job.exhausted || !s.network) return null
            if (s.isTaken(candidate.id)) continue
            val asset = materializeStock(s, job, candidate, query) ?: continue
            consider(s, job, asset)?.let { return it }
        }
        return null
    }

    private suspend fun stockCandidates(s: Session, scene: Scene, videos: Boolean): List<Pair<StockCandidate, String>> {
        val out = LinkedHashMap<String, Pair<StockCandidate, String>>()
        for (query in searchQueries(scene)) {
            for (client in s.stock) {
                if (!s.network) break
                val key = "${client.name}|$videos|${s.orientation}|$query"
                val results = s.searchCache.getOrPut(key) {
                    try {
                        withRetry(attempts = 2) {
                            if (videos) client.searchVideos(query, s.orientation, STOCK_PER_PAGE)
                            else client.searchPhotos(query, s.orientation, STOCK_PER_PAGE)
                        }.map { it to query }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: NetworkPolicyException) {
                        s.network = false
                        s.log.w(e.userMessage)
                        emptyList()
                    } catch (e: Exception) {
                        s.log.w("${client.name} search \"$query\" failed: ${e.message?.take(160)}")
                        emptyList()
                    }
                }
                for (pair in results) out.putIfAbsent(pair.first.id, pair)
            }
        }
        return out.values.toList()
    }

    /** Combined keywords first (most specific), then single keywords, then the visual description. */
    private fun searchQueries(scene: Scene): List<String> {
        val keywords = scene.searchKeywords.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val queries = LinkedHashSet<String>()
        if (keywords.size >= 2) queries += "${keywords[0]} ${keywords[1]}"
        keywords.take(3).forEach { queries += it }
        if (queries.isEmpty()) {
            val fallback = promptBuilder.subject(scene).split(Regex("[\\s,]+")).filter { it.length > 2 }.take(4).joinToString(" ")
            if (fallback.isNotBlank()) queries += fallback
        }
        return queries.take(MAX_QUERIES)
    }

    private fun rankStock(
        s: Session,
        job: SlotJob,
        candidates: List<Pair<StockCandidate, String>>,
        videos: Boolean,
    ): List<Pair<StockCandidate, String>> {
        val wantPortrait = s.orientation == Orientation.PORTRAIT
        return candidates
            .filter { !s.isTaken(it.first.id) }
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Pair<StockCandidate, String>>> { iv ->
                    if (!videos) 1 else if (iv.value.first.durationSec * 1000L >= job.slotMs) 1 else 0
                }.thenByDescending { iv ->
                    val c = iv.value.first
                    if (ShotClassifier.classify(c.tags + " " + iv.value.second) == job.desired) 1 else 0
                }.thenByDescending { iv ->
                    val c = iv.value.first
                    if (c.width <= 0 || c.height <= 0) 0 else if ((c.height > c.width) == wantPortrait) 1 else 0
                }.thenBy { it.index },
            )
            .map { it.value }
            .take(MAX_TRIES_PER_SLOT + 2)
    }

    private suspend fun materializeStock(s: Session, job: SlotJob, candidate: StockCandidate, query: String): VisualAsset? {
        val ext = if (candidate.source.isVideo) "mp4" else "jpg"
        val file = File(s.dir, "${safeName(candidate.id)}.$ext")
        val downloaded = download(s, candidate.downloadUrl, file) ?: return null
        val asset = probeAndPreview(
            s = s,
            file = downloaded,
            id = candidate.id,
            scene = job.scene,
            source = candidate.source,
            heuristicType = ShotClassifier.classify(candidate.tags + " " + query),
            query = query,
            attribution = candidate.attribution,
        )
        if (asset == null) {
            s.rejected += candidate.id
            withContext(Dispatchers.IO) { downloaded.delete() }
        }
        return asset
    }

    private suspend fun tryAiVideo(s: Session, job: SlotJob): VisualAsset? {
        val client = s.textToVideo ?: return null
        val id = uniqueId(s, "ai_video:s${job.scene.sceneNumber}_${job.slot}")
        val file = File(s.dir, "${safeName(id)}.mp4")
        val prompt = promptBuilder.build(job.scene, job.slot, job.desired, s.request.format, forVideo = true)
        s.aiVideoBudget--
        val result = (try {
            val seconds = ceil(job.slotMs / 1000.0).toInt().coerceIn(3, 10)
            client.generate(prompt, s.request.format.aspect, seconds, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            s.aiVideoFailures++
            s.log.w("AI video failed for scene ${job.scene.sceneNumber}: ${e.message?.take(160)}")
            if (isFatalProviderError(e) || s.aiVideoFailures >= MAX_PROVIDER_FAILURES) {
                s.log.w("Turning off AI video for the rest of this video.")
                s.textToVideo = null
            }
            null
        }) ?: return null
        s.aiVideoFailures = 0
        val asset = probeAndPreview(s, result, id, job.scene, VisualSource.AI_VIDEO, job.desired, prompt.take(200), "AI generated video")
            ?: return null
        return consider(s, job, asset)
    }

    private suspend fun tryAiImage(s: Session, job: SlotJob): VisualAsset? {
        val client = s.imageGen ?: return null
        val id = uniqueId(s, "ai_image:s${job.scene.sceneNumber}_${job.slot}")
        val file = File(s.dir, "${safeName(id)}.jpg")
        val prompt = promptBuilder.build(job.scene, job.slot, job.desired, s.request.format, forVideo = false)
        s.aiImageBudget--
        val result = (try {
            client.generate(prompt, s.request.format.aspect, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            s.aiImageFailures++
            s.log.w("AI image failed for scene ${job.scene.sceneNumber}: ${e.message?.take(160)}")
            if (isFatalProviderError(e) || s.aiImageFailures >= MAX_PROVIDER_FAILURES) {
                s.log.w("Turning off AI images for the rest of this video.")
                s.imageGen = null
            }
            null
        }) ?: return null
        s.aiImageFailures = 0
        val asset = probeAndPreview(s, result, id, job.scene, VisualSource.AI_IMAGE, job.desired, prompt.take(200), "AI generated image")
            ?: return null
        return consider(s, job, asset)
    }

    private suspend fun tryGallery(s: Session, job: SlotJob): VisualAsset? {
        val items = s.galleryItems ?: gallery.list(s.log).also { s.galleryItems = it }
        if (items.isEmpty()) return null
        val start = Math.floorMod(job.scene.sceneNumber * 31 + job.slot * 7, items.size)
        for (k in items.indices) {
            if (job.exhausted) return null
            val item = items[(start + k) % items.size]
            if (s.isTaken(item.assetId)) continue
            val ext = if (item.isVideo) "mp4" else "jpg"
            val file = File(s.dir, "${safeName(item.assetId)}.$ext")
            val copied = gallery.copyTo(item, file)
            if (copied == null) {
                s.rejected += item.assetId
                continue
            }
            val asset = probeAndPreview(
                s, copied, item.assetId, job.scene,
                if (item.isVideo) VisualSource.GALLERY_VIDEO else VisualSource.GALLERY_PHOTO,
                job.desired, "gallery", "",
            )
            if (asset == null) {
                s.rejected += item.assetId
                continue
            }
            return consider(s, job, asset)
        }
        return null
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun download(s: Session, url: String, file: File): File? {
        if (withContext(Dispatchers.IO) { file.isFile && file.length() > 0L }) return file
        return try {
            downloader.download(url, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkPolicyException) {
            s.network = false
            s.log.w(e.userMessage)
            null
        } catch (e: Exception) {
            s.log.w("Download failed (${e.message?.take(120)}); trying the next visual.")
            null
        }
    }

    private suspend fun probeAndPreview(
        s: Session,
        file: File,
        id: String,
        scene: Scene,
        source: VisualSource,
        heuristicType: ShotType,
        query: String,
        attribution: String,
    ): VisualAsset? = withContext(Dispatchers.IO) {
        val preview = File(s.dir, "${safeName(id)}_preview.jpg")
        if (source.isVideo) {
            val info = MediaProbe.video(file) ?: return@withContext null
            val hasPreview = MediaProbe.writeVideoPreview(file, info.durationMs, preview)
            VisualAsset(
                id = id,
                sceneNumber = scene.sceneNumber,
                source = source,
                localPath = file.absolutePath,
                isVideo = true,
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                shotType = heuristicType,
                query = query,
                attribution = attribution,
                previewPath = if (hasPreview) preview.absolutePath else null,
            )
        } else {
            val info = MediaProbe.image(file) ?: return@withContext null
            val hasPreview = MediaProbe.writeImagePreview(file, preview)
            VisualAsset(
                id = id,
                sceneNumber = scene.sceneNumber,
                source = source,
                localPath = file.absolutePath,
                isVideo = false,
                durationMs = 0,
                width = info.width,
                height = info.height,
                shotType = heuristicType,
                query = query,
                attribution = attribution,
                previewPath = if (hasPreview) preview.absolutePath else null,
            )
        }
    }

    private fun uniqueId(s: Session, base: String): String {
        if (!s.isTaken(base)) return base
        var n = 2
        while (s.isTaken("${base}_$n")) n++
        return "${base}_$n"
    }

    private fun freeLocalSlot(s: Session, sceneNumber: Int, from: Int): Int {
        var slot = from
        while (s.isTaken(LocalArtGenerator.idFor(sceneNumber, slot))) slot++
        return slot
    }

    private fun isFatalProviderError(e: Exception): Boolean =
        e is ApiException && (e.code == 401 || e.code == 402 || e.code == 403)

    private suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        f.isFile && f.length() > 0L
    }

    private suspend fun safeBool(block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120)

    private companion object {
        val RHYTHM = listOf(ShotType.WIDE, ShotType.MEDIUM, ShotType.CLOSE_UP)
        const val MAX_TRIES_PER_SLOT = 3
        const val MAX_QUERIES = 4
        const val STOCK_PER_PAGE = 12
        const val MAX_AI_VIDEOS = 12
        const val MAX_AI_IMAGES = 40
        const val MAX_PROVIDER_FAILURES = 2
        const val MAX_VISION_FAILURES = 3
        const val TYPOGRAPHY_SLOT_BASE = 50
        const val REPLACE_SLOT_BASE = 20
        const val REPLACE_SLOT_MS = 4_000L
    }
}
