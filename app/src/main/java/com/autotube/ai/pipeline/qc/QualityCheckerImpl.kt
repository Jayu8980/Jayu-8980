package com.autotube.ai.pipeline.qc

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.AudioMixResult
import com.autotube.ai.core.model.CaptionStyle
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditFix
import com.autotube.ai.core.model.EditFixAction
import com.autotube.ai.core.model.QcIssue
import com.autotube.ai.core.model.QcIssueType
import com.autotube.ai.core.model.QcReport
import com.autotube.ai.core.model.QcSeverity
import com.autotube.ai.core.model.RenderResult
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.TransitionType
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.domain.AudioIO
import com.autotube.ai.domain.LlmProvider
import com.autotube.ai.domain.LlmRequest
import com.autotube.ai.domain.QualityChecker
import com.autotube.ai.domain.StepLogger
import com.autotube.ai.domain.completeJson
import com.autotube.ai.pipeline.render.effects.TextFit
import com.autotube.ai.pipeline.render.effects.TextFitKind
import com.autotube.ai.pipeline.render.effects.TransitionEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Automatic review of an export (spec 7I): black frames, clipping/loudness, caption sync, repeated
 * clips, text overflow, weak visual matches, pacing and length. Then an LLM acting as a senior YouTube
 * editor rates "human feel" and pacing and proposes fixes (heuristics when no LLM is usable).
 */
@Singleton
class QualityCheckerImpl @Inject constructor(
    private val llmProvider: LlmProvider,
    private val audioIO: AudioIO,
) : QualityChecker {

    override suspend fun check(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        edl: EditDecisionList,
        visuals: List<VisualAsset>,
        captions: CaptionTrack,
        audioMix: AudioMixResult,
        render: RenderResult,
        pass: Int,
        settings: AppSettings,
        log: StepLogger,
        onProgress: (Float) -> Unit,
    ): QcReport {
        val issues = ArrayList<QcIssue>()
        val shots = edl.shots.sortedBy { it.startMs }
        val visualsById = visuals.associateBy { it.id }

        // (a) Black frames: the heaviest check.
        val blackRuns = withContext(Dispatchers.IO) { checkBlackFrames(render, shots, issues, log) { onProgress(0.55f * it) } }
        currentCoroutineContext().ensureActive()

        // (b) Audio.
        checkAudio(audioMix, settings, issues)
        onProgress(0.6f)

        // (c) Caption sync.
        checkCaptionSync(captions, shots, edl.totalMs, issues)
        checkVoiceEnergy(audioMix, captions, issues, log)
        onProgress(0.7f)

        // (d) Repeats, (e) overflow, (f) visual mismatch.
        checkRepeats(shots, visualsById, issues)
        withContext(Dispatchers.Default) { checkOverflow(request, edl, captions, settings, issues) }
        checkVisualMatch(shots, visualsById, settings, issues)

        // (g) Pacing, (h) length.
        val pacing = pacingStats(request, edl, shots, captions)
        addPacingIssues(request, pacing, issues)
        checkLength(request, edl, render, issues)
        onProgress(0.8f)

        val stats = QcStats(
            format = request.format.label,
            style = edl.style.label,
            requestedSec = request.targetDurationSec,
            renderedMs = render.durationMs,
            shots = shots.size,
            meanShotSec = pacing.meanSec.round2(),
            stdevShotSec = pacing.stdevSec.round2(),
            minShotSec = pacing.minSec.round2(),
            maxShotSec = pacing.maxSec.round2(),
            longestGapSec = pacing.longestGapSec.round2(),
            interruptRuleSec = pacing.ruleSec,
            cutsInsideWords = pacing.cutsInsideWords,
            nonCutTransitions = pacing.nonCutTransitions,
            textPops = edl.textPops.size,
            punchIns = shots.sumOf { it.punchIns.size },
            brollShots = shots.count { it.isBroll },
            integratedLufs = audioMix.integratedLufs,
            truePeakDbfs = audioMix.truePeakDbfs,
            blackFrameRuns = blackRuns,
            captionStyle = captions.style.name,
        )

        val review = runCatchingNonCancel { llmReview(request, script, edl, shots, visualsById, stats, issues, log) }
            .onFailure { log.w("LLM review unavailable, using heuristics: ${it.message}") }
            .getOrNull()
        onProgress(0.95f)

        val heuristic = heuristicScores(pacing, issues, request)
        val humanFeel = review?.humanFeel?.takeIf { it in 1..10 } ?: heuristic.first
        val pacingScore = review?.pacing?.takeIf { it in 1..10 } ?: heuristic.second
        val hasError = issues.any { it.severity == QcSeverity.ERROR }
        val needsFixes = hasError || humanFeel < settings.minHumanFeelScore || pacingScore < settings.minHumanFeelScore
        val fixes = if (!needsFixes) emptyList() else {
            val fromLlm = review?.let { validFixes(it.fixes, shots, script) }.orEmpty()
            dedupe(fromLlm + heuristicFixes(issues, pacing, shots, humanFeel, pacingScore, settings))
        }
        val notes = buildString {
            if (review != null && review.notes.isNotBlank()) append(review.notes.trim()) else append(heuristicNotes(pacing, issues))
        }
        log.i("QC pass $pass: humanFeel=$humanFeel pacing=$pacingScore issues=${issues.size} fixes=${fixes.size}" +
            (if (review != null) " (LLM review)" else " (heuristic review)"))
        onProgress(1f)
        return QcReport(
            pass = pass,
            issues = issues,
            humanFeelScore = humanFeel,
            pacingScore = pacingScore,
            notes = notes,
            fixes = fixes,
        )
    }

    // ------------------------------------------------------------------ (a) black frames

    private suspend fun checkBlackFrames(
        render: RenderResult,
        shots: List<Shot>,
        issues: MutableList<QcIssue>,
        log: StepLogger,
        onProgress: (Float) -> Unit,
    ): Int {
        val file = File(render.outputPath)
        if (!file.isFile) {
            issues += QcIssue(QcIssueType.BLACK_FRAMES, QcSeverity.ERROR, "The exported video file is missing.")
            return 0
        }
        val duration = render.durationMs
        if (duration <= 0) return 0
        val step = maxOf(1_000L, duration / MAX_FRAME_SAMPLES)
        val intentional = intentionalDarkWindows(shots, duration)
        val retriever = MediaMetadataRetriever()
        var runs = 0
        try {
            retriever.setDataSource(file.absolutePath)
            var runStart = -1L
            var runSamples = 0
            var t = 300L
            fun closeRun() {
                if (runSamples > 0 && runStart >= 0) {
                    runs++
                    val lengthSec = runSamples * step / 1000.0
                    val severity = if (runSamples >= 2) QcSeverity.ERROR else QcSeverity.WARNING
                    issues += QcIssue(
                        QcIssueType.BLACK_FRAMES, severity,
                        "Black picture around ${fmtTime(runStart)} (about ${"%.1f".format(Locale.US, lengthSec)} s).",
                        sceneNumber = shotAt(shots, runStart)?.sceneNumber,
                        atMs = runStart,
                    )
                }
                runStart = -1L
                runSamples = 0
            }
            while (t < duration) {
                currentCoroutineContext().ensureActive()
                val black = isBlackFrame(retriever, t)
                if (black && intentional.none { t in it }) {
                    if (runStart < 0) runStart = t
                    runSamples++
                } else closeRun()
                onProgress(t.toFloat() / duration)
                t += step
            }
            closeRun()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Black-frame scan failed", e)
        } finally {
            runCatching { retriever.release() }
        }
        return runs
    }

    private fun isBlackFrame(retriever: MediaMetadataRetriever, atMs: Long): Boolean {
        val us = atMs * 1000
        val frame: Bitmap = (if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_W, THUMB_W)
        } else {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }) ?: return false
        val small = if (frame.width > THUMB_W * 2) {
            val h = (frame.height * THUMB_W.toFloat() / frame.width).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, THUMB_W, h, true).also { frame.recycle() }
        } else frame
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        small.recycle()
        var sum = 0.0
        var sumSq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            sum += y
            sumSq += y * y
        }
        val n = pixels.size.coerceAtLeast(1)
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        return mean < BLACK_LUMA && sqrt(variance) < BLACK_STDEV
    }

    /** Dip-to-black transitions and the end fade are intentionally dark. */
    private fun intentionalDarkWindows(shots: List<Shot>, duration: Long): List<LongRange> {
        val windows = shots.filter { it.transitionIn == TransitionType.DIP_TO_BLACK && it.transitionMs > 0 }
            .map { (it.startMs - it.transitionMs - 100)..(it.startMs + it.transitionMs + 100) }
            .toMutableList()
        windows += (duration - TransitionEffect.END_FADE_MS - 300)..Long.MAX_VALUE
        return windows
    }

    // ------------------------------------------------------------------ (b) audio

    private fun checkAudio(mix: AudioMixResult, settings: AppSettings, issues: MutableList<QcIssue>) {
        if (mix.truePeakDbfs > -0.5f) {
            issues += QcIssue(
                QcIssueType.AUDIO_CLIPPING, QcSeverity.ERROR,
                "Audio peaks at ${"%.1f".format(Locale.US, mix.truePeakDbfs)} dBFS (should stay below -1 dBFS); it may distort.",
            )
        } else if (mix.clippedSamples > 0) {
            issues += QcIssue(
                QcIssueType.AUDIO_CLIPPING, if (mix.clippedSamples > 4_800) QcSeverity.WARNING else QcSeverity.INFO,
                "${mix.clippedSamples} samples hit full scale before limiting; the limiter caught them.",
            )
        }
        val target = settings.targetLufs
        val diff = mix.integratedLufs - target
        if (abs(diff) > 1.5f) {
            issues += QcIssue(
                QcIssueType.AUDIO_CLIPPING, if (abs(diff) > 4f) QcSeverity.ERROR else QcSeverity.WARNING,
                "Loudness is ${"%.1f".format(Locale.US, mix.integratedLufs)} LUFS; YouTube's target is about ${"%.0f".format(Locale.US, target)} LUFS.",
            )
        }
    }

    // ------------------------------------------------------------------ (c) caption sync

    private fun checkCaptionSync(captions: CaptionTrack, shots: List<Shot>, totalMs: Long, issues: MutableList<QcIssue>) {
        val words = captions.words
        if (words.isEmpty()) return
        val sceneWindows = shots.groupBy { it.sceneNumber }
            .mapValues { (_, s) -> (s.minOf { it.startMs } - SCENE_TOLERANCE_MS)..(s.maxOf { it.endMs } + SCENE_TOLERANCE_MS) }
        var outside = 0
        var firstOutside: Long? = null
        var outsideScene: Int? = null
        var nonMonotonic = 0
        var firstNonMono: Long? = null
        var prevStart = Long.MIN_VALUE
        for (w in words) {
            val window = sceneWindows[w.sceneNumber]
            if (window != null && (w.startMs !in window || w.endMs !in window)) {
                outside++
                if (firstOutside == null) { firstOutside = w.startMs; outsideScene = w.sceneNumber }
            }
            if (w.startMs + 5 < prevStart || w.endMs < w.startMs) {
                nonMonotonic++
                if (firstNonMono == null) firstNonMono = w.startMs
            }
            prevStart = w.startMs
        }
        if (outside > 0) {
            issues += QcIssue(
                QcIssueType.CAPTION_SYNC,
                if (outside > words.size / 20 + 2) QcSeverity.ERROR else QcSeverity.WARNING,
                "$outside caption word(s) fall outside their scene's audio.",
                sceneNumber = outsideScene, atMs = firstOutside,
            )
        }
        if (nonMonotonic > 0) {
            issues += QcIssue(
                QcIssueType.CAPTION_SYNC, QcSeverity.ERROR,
                "$nonMonotonic caption word(s) are out of order.", atMs = firstNonMono,
            )
        }
        val late = words.count { it.startMs > totalMs + 200 }
        if (late > 0) {
            issues += QcIssue(QcIssueType.CAPTION_SYNC, QcSeverity.ERROR, "$late caption word(s) appear after the video ends.")
        }
    }

    private suspend fun checkVoiceEnergy(mix: AudioMixResult, captions: CaptionTrack, issues: MutableList<QcIssue>, log: StepLogger) {
        val path = mix.voiceTrackPath ?: return
        val file = File(path)
        if (!file.isFile || captions.chunks.isEmpty()) return
        val pcm = try {
            audioIO.decode(file, ENERGY_RATE, 1)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w("Could not decode the voice track for the caption sync check", e)
            return
        }
        val result = withContext(Dispatchers.Default) {
            val frame = ENERGY_RATE / 100 // 10 ms
            val frames = pcm.samples.size / frame
            if (frames == 0) return@withContext null
            val db = FloatArray(frames)
            for (i in 0 until frames) {
                var s = 0.0
                val base = i * frame
                for (j in 0 until frame) {
                    val v = pcm.samples[base + j]
                    s += v * v
                }
                val rms = sqrt(s / frame)
                db[i] = if (rms <= 1e-9) -120f else (20 * log10(rms)).toFloat()
            }
            val sorted = db.sorted()
            val floor = sorted[(sorted.size * 0.1).toInt().coerceIn(0, sorted.lastIndex)]
            val loud = sorted[(sorted.size * 0.9).toInt().coerceIn(0, sorted.lastIndex)]
            val threshold = maxOf(floor + 6f, loud - 30f, -55f)
            var silent = 0
            var first: Long? = null
            for (chunk in captions.chunks) {
                val startFrame = (chunk.startMs / 10).toInt()
                val endFrame = (startFrame + SILENCE_LIMIT_MS / 10).coerceAtMost(frames)
                if (startFrame >= frames) continue
                val voiced = (startFrame until endFrame).any { db[it] > threshold }
                if (!voiced) {
                    silent++
                    if (first == null) first = chunk.startMs
                }
            }
            silent to first
        } ?: return
        val (silent, first) = result
        if (silent > 0) {
            val severe = silent > captions.chunks.size / 10 + 1
            issues += QcIssue(
                QcIssueType.CAPTION_SYNC, if (severe) QcSeverity.ERROR else QcSeverity.WARNING,
                "$silent caption group(s) appear while the voice is silent for over ${SILENCE_LIMIT_MS} ms.",
                atMs = first,
            )
        }
    }

    // ------------------------------------------------------------------ (d)(e)(f)

    private fun checkRepeats(shots: List<Shot>, visualsById: Map<String, VisualAsset>, issues: MutableList<QcIssue>) {
        val byAsset = shots.groupBy { it.assetId }
        for ((assetId, uses) in byAsset) {
            if (uses.size < 2) continue
            val asset = visualsById[assetId]
            val isVideo = asset?.isVideo ?: true
            if (isVideo) {
                for (i in uses.indices) for (j in i + 1 until uses.size) {
                    val a = uses[i]
                    val b = uses[j]
                    val aEnd = a.sourceStartMs + a.durationMs
                    val bEnd = b.sourceStartMs + b.durationMs
                    if (a.sourceStartMs < bEnd && b.sourceStartMs < aEnd) {
                        issues += QcIssue(
                            QcIssueType.REPEATED_CLIP, QcSeverity.WARNING,
                            "Shot ${b.index} repeats footage already used in shot ${a.index}.",
                            sceneNumber = b.sceneNumber, atMs = b.startMs,
                        )
                    }
                }
            } else {
                val later = uses.drop(1)
                issues += QcIssue(
                    QcIssueType.REPEATED_CLIP, QcSeverity.WARNING,
                    "The same image is used ${uses.size} times (shots ${uses.joinToString { it.index.toString() }}).",
                    sceneNumber = later.first().sceneNumber, atMs = later.first().startMs,
                )
            }
        }
    }

    private fun checkOverflow(request: VideoRequest, edl: EditDecisionList, captions: CaptionTrack, settings: AppSettings, issues: MutableList<QcIssue>) {
        if (captions.style != CaptionStyle.OFF && settings.captionStyle != CaptionStyle.OFF) {
            for (chunk in captions.chunks) {
                val text = chunk.words.joinToString(" ") { w ->
                    if (settings.emojiInCaptions && !w.emoji.isNullOrBlank()) "${w.text} ${w.emoji}" else w.text
                }
                if (text.isNotBlank() && TextFit.measureOverflow(text, request.format, TextFitKind.CAPTION)) {
                    issues += QcIssue(
                        QcIssueType.TEXT_OVERFLOW, QcSeverity.WARNING,
                        "Caption \"${text.take(40)}\" is too long to fit on screen.",
                        sceneNumber = chunk.words.firstOrNull()?.sceneNumber, atMs = chunk.startMs,
                    )
                }
            }
        }
        for (pop in edl.textPops) {
            val text = if (pop.emoji.isNullOrBlank()) pop.text else "${pop.text} ${pop.emoji}"
            if (TextFit.measureOverflow(text, request.format, TextFitKind.TEXT_POP)) {
                issues += QcIssue(
                    QcIssueType.TEXT_OVERFLOW, QcSeverity.WARNING,
                    "Pop-up text \"${pop.text.take(40)}\" is too long to fit on screen.",
                    sceneNumber = pop.sceneNumber.takeIf { it > 0 }, atMs = pop.startMs,
                )
            }
        }
    }

    private fun checkVisualMatch(shots: List<Shot>, visualsById: Map<String, VisualAsset>, settings: AppSettings, issues: MutableList<QcIssue>) {
        val reported = HashSet<String>()
        for (shot in shots) {
            val asset = visualsById[shot.assetId] ?: continue
            if (asset.matchScore in 0 until settings.visionMinScore && reported.add(asset.id)) {
                issues += QcIssue(
                    QcIssueType.VISUAL_MISMATCH,
                    if (asset.matchScore <= 3) QcSeverity.WARNING else QcSeverity.INFO,
                    "Visual for shot ${shot.index} only matches the narration ${asset.matchScore}/10.",
                    sceneNumber = shot.sceneNumber, atMs = shot.startMs,
                )
            }
        }
    }

    // ------------------------------------------------------------------ (g)(h)

    private data class Pacing(
        val meanSec: Double,
        val stdevSec: Double,
        val minSec: Double,
        val maxSec: Double,
        val longestGapSec: Double,
        val longestGapAtMs: Long,
        val ruleSec: Double,
        val cutsInsideWords: Int,
        val cutCount: Int,
        val nonCutTransitions: Int,
        val idealMinSec: Double,
        val idealMaxSec: Double,
        /** Scenes whose average shot is far longer than ideal. */
        val slowScenes: List<Int>,
        val fastScenes: List<Int>,
        val firstWordCutShot: Int?,
    )

    private fun pacingStats(request: VideoRequest, edl: EditDecisionList, shots: List<Shot>, captions: CaptionTrack): Pacing {
        val lens = shots.map { it.durationMs / 1000.0 }
        val mean = if (lens.isEmpty()) 0.0 else lens.average()
        val stdev = if (lens.size < 2) 0.0 else sqrt(lens.sumOf { (it - mean) * (it - mean) } / (lens.size - 1))

        val events = ArrayList<Long>()
        events += 0L
        shots.filter { it.isPatternInterrupt || it.isBroll || it.transitionIn != TransitionType.CUT }.forEach { events += it.startMs }
        shots.forEach { s -> s.punchIns.forEach { events += it.atMs } }
        edl.textPops.forEach { events += it.startMs }
        events += edl.totalMs
        events.sort()
        var longest = 0L
        var longestAt = 0L
        for (i in 1 until events.size) {
            val gap = events[i] - events[i - 1]
            if (gap > longest) { longest = gap; longestAt = events[i - 1] }
        }
        val ruleSec = if (request.isShorts) 10.0 else 40.0

        val words = captions.words
        var inside = 0
        var firstCutShot: Int? = null
        val cuts = shots.drop(1)
        for (s in cuts) {
            val cut = s.startMs
            if (words.any { cut > it.startMs + WORD_EDGE_MS && cut < it.endMs - WORD_EDGE_MS }) {
                inside++
                if (firstCutShot == null) firstCutShot = s.index
            }
        }
        val (idealMin, idealMax) = when {
            request.isShorts -> 1.5 to 4.0
            else -> 3.0 to 7.0
        }
        val byScene = shots.groupBy { it.sceneNumber }
        val slow = byScene.filter { (_, s) -> s.size >= 1 && s.map { it.durationMs / 1000.0 }.average() > idealMax * 1.5 }.keys.sorted()
        val fast = byScene.filter { (_, s) -> s.size >= 2 && s.map { it.durationMs / 1000.0 }.average() < idealMin * 0.6 }.keys.sorted()
        return Pacing(
            meanSec = mean,
            stdevSec = stdev,
            minSec = lens.minOrNull() ?: 0.0,
            maxSec = lens.maxOrNull() ?: 0.0,
            longestGapSec = longest / 1000.0,
            longestGapAtMs = longestAt,
            ruleSec = ruleSec,
            cutsInsideWords = inside,
            cutCount = cuts.size,
            nonCutTransitions = shots.count { it.transitionIn != TransitionType.CUT },
            idealMinSec = idealMin,
            idealMaxSec = idealMax,
            slowScenes = slow,
            fastScenes = fast,
            firstWordCutShot = firstCutShot,
        )
    }

    private fun addPacingIssues(request: VideoRequest, p: Pacing, issues: MutableList<QcIssue>) {
        if (p.cutCount >= 3 && p.stdevSec < 0.35) {
            issues += QcIssue(
                QcIssueType.PACING, QcSeverity.WARNING,
                "Shot lengths are too regular (variation ${"%.2f".format(Locale.US, p.stdevSec)} s); the rhythm feels robotic.",
            )
        }
        if (p.longestGapSec > p.ruleSec) {
            issues += QcIssue(
                QcIssueType.PACING, if (p.longestGapSec > p.ruleSec * 1.5) QcSeverity.WARNING else QcSeverity.INFO,
                "${"%.0f".format(Locale.US, p.longestGapSec)} s without a pattern interrupt (aim for at most ${p.ruleSec.toInt()} s on ${request.format.label}).",
                atMs = p.longestGapAtMs,
            )
        }
        if (p.cutsInsideWords > 3 || (p.cutCount > 0 && p.cutsInsideWords > p.cutCount / 5)) {
            issues += QcIssue(
                QcIssueType.PACING, QcSeverity.WARNING,
                "${p.cutsInsideWords} cut(s) land in the middle of a spoken word.",
            )
        }
        if (p.meanSec > p.idealMaxSec * 1.3) {
            issues += QcIssue(QcIssueType.PACING, QcSeverity.WARNING, "Shots average ${"%.1f".format(Locale.US, p.meanSec)} s; this feels slow for ${request.format.label}.")
        } else if (p.meanSec in 0.01..(p.idealMinSec * 0.7)) {
            issues += QcIssue(QcIssueType.PACING, QcSeverity.INFO, "Shots average ${"%.1f".format(Locale.US, p.meanSec)} s; this may feel frantic.")
        }
    }

    private fun checkLength(request: VideoRequest, edl: EditDecisionList, render: RenderResult, issues: MutableList<QcIssue>) {
        val dur = render.durationMs
        if (request.isShorts) {
            if (dur > SHORTS_MAX_MS) {
                issues += QcIssue(QcIssueType.LENGTH, QcSeverity.ERROR, "The Short is ${fmtTime(dur)} long; Shorts must be 60 s or less.")
            }
        } else {
            val target = request.targetDurationMs
            if (target > 0) {
                val dev = abs(dur - target).toDouble() / target
                if (dev > 0.10) {
                    issues += QcIssue(QcIssueType.LENGTH, QcSeverity.WARNING, "The video is ${fmtTime(dur)} long; you asked for ${fmtTime(target)}.")
                }
            }
        }
        if (abs(dur - edl.totalMs) > 1_000) {
            issues += QcIssue(QcIssueType.LENGTH, QcSeverity.WARNING, "Exported length ${fmtTime(dur)} differs from the edit plan (${fmtTime(edl.totalMs)}).")
        }
    }

    // ------------------------------------------------------------------ LLM review

    private suspend fun llmReview(
        request: VideoRequest,
        script: Script,
        edl: EditDecisionList,
        shots: List<Shot>,
        visualsById: Map<String, VisualAsset>,
        stats: QcStats,
        issues: List<QcIssue>,
        log: StepLogger,
    ): QcReviewReply? {
        val llm = llmProvider.text() ?: return null
        val payload = QcReviewRequest(
            stats = stats,
            shots = shots.take(MAX_SHOTS_IN_PROMPT).map { s ->
                val asset = visualsById[s.assetId]
                QcShotSummary(
                    shotIndex = s.index,
                    sceneNumber = s.sceneNumber,
                    startSec = (s.startMs / 1000.0).round2(),
                    durSec = (s.durationMs / 1000.0).round2(),
                    visual = buildString {
                        append(asset?.source?.label ?: "unknown")
                        append(if (asset?.isVideo == true) " video" else " image")
                        if (asset != null && asset.matchScore >= 0) append(", match ${asset.matchScore}/10")
                        if (!asset?.query.isNullOrBlank()) append(", \"${asset?.query?.take(40)}\"")
                    },
                    motion = s.motion.type.name.lowercase(),
                    transition = s.transitionIn.name.lowercase(),
                    broll = s.isBroll,
                    patternInterrupt = s.isPatternInterrupt,
                    punchIns = s.punchIns.size,
                    mood = edl.sceneLooks[s.sceneNumber]?.wireName,
                )
            },
            issues = issues.map { "${it.severity.name} ${it.type.name}: ${it.message}" },
            textPops = edl.textPops.map { "${fmtTime(it.startMs)} ${it.kind.name.lowercase()}: ${it.text}" },
            sceneNarration = script.scenes.map { "${it.sceneNumber} (${it.role.name.lowercase()}, ${it.emotion.wireName}): ${it.narrationText.take(160)}" },
        )
        val actions = EditFixAction.entries.joinToString(", ") { actionWire(it) }
        val system = """
            You are a senior YouTube video editor reviewing a rough cut made by an automatic editor.
            Judge it like a viewer: does it feel edited by a human (varied rhythm, cuts on meaning,
            motivated punch-ins and B-roll, clean captions), and is the pacing right for the format?
            Reply with ONLY a JSON object:
            {"human_feel": 1-10, "pacing": 1-10, "notes": "two or three short sentences",
             "fixes": [{"action": one of [$actions], "scene_number": int or null, "shot_index": int or null, "reason": "short"}]}
            Give at most 6 fixes, only for the weakest sections. Use scene numbers and shot indexes from the data.
        """.trimIndent()
        val user = "Format: ${request.format.label}, language: ${request.language.label}, topic: ${script.title}\n" +
            "Edit data:\n" + AppJson.encodeToString(QcReviewRequest.serializer(), payload)
        val reply = llm.completeJson<QcReviewReply>(
            LlmRequest(system = system, user = user, maxTokens = 2_000, json = true, jsonSchema = null, effort = "low"),
        )
        log.i("${llm.displayName} review: human feel ${reply.humanFeel}, pacing ${reply.pacing}")
        return reply
    }

    private fun validFixes(raw: List<QcReviewFix>, shots: List<Shot>, script: Script): List<EditFix> {
        val sceneNumbers = script.scenes.map { it.sceneNumber }.toSet()
        val shotIndexes = shots.map { it.index }.toSet()
        return raw.mapNotNull { f ->
            val action = EditFixAction.entries.firstOrNull { actionWire(it) == f.action.trim().lowercase() || it.name == f.action.trim().uppercase() }
                ?: return@mapNotNull null
            val shotIndex = f.shotIndex?.takeIf { it in shotIndexes }
            val scene = f.sceneNumber?.takeIf { it in sceneNumbers }
                ?: shotIndex?.let { idx -> shots.firstOrNull { it.index == idx }?.sceneNumber }
            EditFix(action, scene, shotIndex, f.reason.take(200))
        }.take(8)
    }

    // ------------------------------------------------------------------ heuristics

    private fun heuristicScores(p: Pacing, issues: List<QcIssue>, request: VideoRequest): Pair<Int, Int> {
        var feel = 9.0
        var pace = 9.0
        for (i in issues) {
            val w = when (i.severity) {
                QcSeverity.ERROR -> 2.0
                QcSeverity.WARNING -> 0.6
                QcSeverity.INFO -> 0.15
            }
            when (i.type) {
                QcIssueType.PACING -> pace -= w
                QcIssueType.LENGTH -> { pace -= w / 2; feel -= w / 2 }
                QcIssueType.BLACK_FRAMES, QcIssueType.AUDIO_CLIPPING, QcIssueType.CAPTION_SYNC,
                QcIssueType.REPEATED_CLIP, QcIssueType.TEXT_OVERFLOW, QcIssueType.VISUAL_MISMATCH -> feel -= w
            }
        }
        if (p.meanSec > 0) {
            if (p.meanSec > p.idealMaxSec) pace -= ((p.meanSec - p.idealMaxSec) / p.idealMaxSec * 3).coerceAtMost(3.0)
            if (p.meanSec < p.idealMinSec) pace -= ((p.idealMinSec - p.meanSec) / p.idealMinSec * 2).coerceAtMost(2.0)
        }
        if (p.stdevSec < 0.35 && p.cutCount >= 3) feel -= 1.0
        if (request.isShorts && p.nonCutTransitions > p.cutCount / 3 + 1) feel -= 0.5
        return feel.roundToInt().coerceIn(1, 10) to pace.roundToInt().coerceIn(1, 10)
    }

    private fun heuristicFixes(
        issues: List<QcIssue>,
        p: Pacing,
        shots: List<Shot>,
        humanFeel: Int,
        pacingScore: Int,
        settings: AppSettings,
    ): List<EditFix> {
        val fixes = ArrayList<EditFix>()
        for (i in issues) {
            if (i.severity == QcSeverity.INFO) continue
            val shotIdx = i.atMs?.let { t -> shotAt(shots, t)?.index }
            when (i.type) {
                QcIssueType.VISUAL_MISMATCH, QcIssueType.REPEATED_CLIP, QcIssueType.BLACK_FRAMES ->
                    fixes += EditFix(EditFixAction.REPLACE_VISUAL, i.sceneNumber, shotIdx, i.message)
                QcIssueType.TEXT_OVERFLOW -> fixes += EditFix(EditFixAction.FIX_CAPTION_OVERFLOW, i.sceneNumber, null, i.message)
                QcIssueType.AUDIO_CLIPPING -> fixes += EditFix(EditFixAction.LOWER_MUSIC, null, null, i.message)
                QcIssueType.PACING, QcIssueType.CAPTION_SYNC, QcIssueType.LENGTH -> Unit
            }
        }
        if (p.longestGapSec > p.ruleSec) {
            val shot = shotAt(shots, p.longestGapAtMs + (p.longestGapSec * 500).toLong())
            fixes += EditFix(EditFixAction.ADD_PATTERN_INTERRUPT, shot?.sceneNumber, shot?.index, "Long stretch without a pattern interrupt")
        }
        if (pacingScore < settings.minHumanFeelScore) {
            p.slowScenes.take(3).forEach { fixes += EditFix(EditFixAction.SHORTEN_SHOTS, it, null, "Shots in this scene feel slow") }
            p.fastScenes.take(2).forEach { fixes += EditFix(EditFixAction.LENGTHEN_SHOTS, it, null, "Shots in this scene feel frantic") }
        }
        if (humanFeel < settings.minHumanFeelScore) {
            if (p.stdevSec < 0.35 && p.cutCount >= 3) {
                val scene = shots.getOrNull(shots.size / 2)?.sceneNumber
                fixes += EditFix(EditFixAction.ADD_BROLL, scene, null, "Break up the regular rhythm with B-roll")
                fixes += EditFix(EditFixAction.ADD_PUNCH_IN, scene, null, "Add a motivated punch-in on a key word")
            }
            if (p.nonCutTransitions > p.cutCount / 3 + 1) {
                fixes += EditFix(EditFixAction.SIMPLIFY_TRANSITIONS, null, null, "Too many effect transitions; real editors mostly cut")
            }
        }
        return fixes
    }

    private fun heuristicNotes(p: Pacing, issues: List<QcIssue>): String {
        val errors = issues.count { it.severity == QcSeverity.ERROR }
        val warnings = issues.count { it.severity == QcSeverity.WARNING }
        return "Automatic review: ${"%.1f".format(Locale.US, p.meanSec)} s average shot " +
            "(variation ${"%.2f".format(Locale.US, p.stdevSec)} s), longest stretch without an interrupt " +
            "${"%.0f".format(Locale.US, p.longestGapSec)} s; $errors error(s), $warnings warning(s)."
    }

    private fun dedupe(fixes: List<EditFix>): List<EditFix> =
        fixes.distinctBy { Triple(it.action, it.sceneNumber, it.shotIndex) }.take(10)

    // ------------------------------------------------------------------ utils

    private fun shotAt(shots: List<Shot>, t: Long): Shot? = shots.firstOrNull { t >= it.startMs && t < it.endMs } ?: shots.lastOrNull()

    private fun fmtTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(Locale.US, s / 60, s % 60)
    }

    private fun Double.round2(): Double = (this * 100).roundToInt() / 100.0

    private fun actionWire(a: EditFixAction): String = when (a) {
        EditFixAction.REPLACE_VISUAL -> "replace_visual"
        EditFixAction.SHORTEN_SHOTS -> "shorten_shots"
        EditFixAction.LENGTHEN_SHOTS -> "lengthen_shots"
        EditFixAction.ADD_PUNCH_IN -> "add_punch_in"
        EditFixAction.ADD_BROLL -> "add_broll"
        EditFixAction.ADD_TEXT_POP -> "add_text_pop"
        EditFixAction.SIMPLIFY_TRANSITIONS -> "simplify_transitions"
        EditFixAction.ADD_PATTERN_INTERRUPT -> "add_pattern_interrupt"
        EditFixAction.FIX_CAPTION_OVERFLOW -> "fix_caption_overflow"
        EditFixAction.LOWER_MUSIC -> "lower_music"
    }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private companion object {
        const val MAX_FRAME_SAMPLES = 600L
        const val THUMB_W = 64
        const val BLACK_LUMA = 12.0
        const val BLACK_STDEV = 6.0
        const val SCENE_TOLERANCE_MS = 400L
        const val ENERGY_RATE = 16_000
        const val SILENCE_LIMIT_MS = 300L
        const val WORD_EDGE_MS = 40L
        const val SHORTS_MAX_MS = 60_000L
        const val MAX_SHOTS_IN_PROMPT = 120
    }
}
