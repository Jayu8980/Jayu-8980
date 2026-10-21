package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.model.CameraMotion
import com.autotube.ai.core.model.CutType
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Easing
import com.autotube.ai.core.model.MotionType
import com.autotube.ai.core.model.MusicAction
import com.autotube.ai.core.model.MusicCue
import com.autotube.ai.core.model.PunchIn
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.SfxCue
import com.autotube.ai.core.model.SfxType
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.core.model.TextPop
import com.autotube.ai.core.model.TextPopKind
import com.autotube.ai.core.model.TransitionType
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.pipeline.visuals.LocalArtGenerator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Deterministic repair pass run on every plan (LLM, built-in or QC-fixed). It enforces:
 * exact tiling of [0, totalMs); cuts never inside a word and snapped to natural pauses; human,
 * non-uniform pacing per style/emotion; J/L cuts at scene boundaries; 85-95 % hard cuts with fancy
 * transitions only at section changes and never two in a row; eased camera motion on every image,
 * mostly static video; spaced punch-ins on key words; light handheld shake on some shots; no image
 * reuse and only non-overlapping video re-use; alternating shot types; pattern interrupts; text pops
 * at the spoken words without overlaps plus a CTA pop; sound-design and music cues; scene looks.
 */
internal class EdlNormalizer(private val ctx: EditContext) {

    private val pacing = ctx.pacing
    private val tracks = ctx.tracks
    private val total = ctx.totalMs
    private val seed = ctx.seed
    private val trusted = ctx.timingTrusted
    private val trackPosition: Map<Int, Int> = tracks.associate { it.number to it.position }

    private class Boundary(
        val at: Long,
        val cutType: CutType,
        val transition: TransitionType,
        val transitionMs: Long,
        val reason: String,
    )

    private class Segment(
        val start: Long,
        val end: Long,
        val track: SceneTrack,
        val draft: Shot?,
        val boundary: Boundary?,
    )

    /** Start times of shots whose pattern interrupt is an angle change / B-roll cut. */
    private val angleInterrupts = HashSet<Long>()

    fun normalize(draft: EditDecisionList, origin: String): EditDecisionList {
        val draftShots = draft.shots.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val bounds = boundaries(draftShots)
        val segments = tile(draftShots, bounds)
        var shots = assignAssets(segments)
        shots = transitions(shots)
        shots = motion(shots)
        val pops = pops(draft.textPops)
        shots = punchIns(shots, draftShots.flatMap { it.punchIns })
        shots = patternInterrupts(shots, pops)
        shots = finish(shots)
        val moments = keyMoments(draft.music)
        val music = music(draft.music, moments)
        val sfx = if (ctx.settings.soundEffects) sfx(shots, pops, moments) else emptyList()
        return EditDecisionList(
            style = pacing.style,
            totalMs = total,
            shots = shots,
            textPops = pops,
            sfx = sfx,
            music = music,
            sceneLooks = tracks.associate { it.number to it.scene.emotion },
            notes = notes(origin, draft.notes, shots, pops),
        )
    }

    // ------------------------------------------------------------------ scene boundaries (J/L cuts)

    private fun boundaries(draftShots: List<Shot>): List<Boundary> {
        val out = ArrayList<Boundary>(tracks.size)
        out += Boundary(0L, CutType.STRAIGHT, TransitionType.CUT, 0L, "")
        for (k in 1 until tracks.size) {
            val track = tracks[k]
            val prev = tracks[k - 1]
            val prevAt = out.last().at
            val gapStart = prev.timing.audioEndMs
            val gapEnd = max(track.timing.audioStartMs, gapStart)
            val draftFirst = draftShots.filter { it.sceneNumber == track.number }.minByOrNull { it.startMs }
                ?: draftShots.firstOrNull { it.startMs <= gapEnd && it.endMs > gapEnd }

            var transition = draftFirst?.transitionIn ?: TransitionType.CUT
            if (transition != TransitionType.CUT && !track.isSectionStart) transition = TransitionType.CUT
            var cutType = if (transition != TransitionType.CUT) CutType.STRAIGHT else (draftFirst?.cutType ?: CutType.STRAIGHT)

            val lowLimit = prevAt + MIN_SEGMENT_MS
            val highLimit = max(lowLimit, min(track.timing.audioEndMs, total - MIN_SEGMENT_MS))
            val window = when (cutType) {
                CutType.STRAIGHT -> gapStart..gapEnd
                CutType.L_CUT -> (gapStart - LJ_MAX_MS)..(gapStart - LJ_MIN_MS)
                CutType.J_CUT -> (gapEnd + LJ_MIN_MS)..(gapEnd + LJ_MAX_MS)
            }
            var at = -1L
            val draftAt = draftFirst?.startMs
            if (draftAt != null && draftAt in window && draftAt in lowLimit..highLimit &&
                (!trusted || !ctx.isInsideWord(draftAt))
            ) {
                at = draftAt
            } else if (cutType != CutType.STRAIGHT) {
                val off = DetRandom.betweenLong(LJ_MIN_MS, LJ_MAX_MS, seed, track.number, 31)
                val target = if (cutType == CutType.L_CUT) gapStart - off else gapEnd + off
                val lo = max(window.first, lowLimit)
                val hi = min(window.last, highLimit)
                if (hi > lo) {
                    val c = ctx.snapCut(target, lo, hi, 250L)
                    if (!ctx.isInsideWord(c) && c in lo..hi) at = c
                }
                if (at < 0L) cutType = CutType.STRAIGHT
            }
            if (at < 0L) {
                at = if (gapEnd > gapStart) {
                    ctx.snapCut((gapStart + gapEnd) / 2, gapStart, gapEnd, gapEnd - gapStart)
                } else {
                    ctx.legalize(gapEnd, gapEnd - 400L, gapEnd + 400L)
                }
            }
            at = at.coerceIn(lowLimit, highLimit)
            if (at <= prevAt) at = prevAt + 1
            if (at >= total) at = max(prevAt + 1, total - 1)

            val transitionMs = if (transition == TransitionType.CUT) {
                0L
            } else {
                val given = draftFirst?.transitionMs ?: 0L
                if (given in FANCY_MIN_MS..FANCY_MAX_MS) given else EditHeuristics.transitionMs(ctx, track.number)
            }
            out += Boundary(at, cutType, transition, transitionMs, draftFirst?.reason.orEmpty())
        }
        return out
    }

    // ------------------------------------------------------------------ tiling and pacing

    private fun tile(draftShots: List<Shot>, bounds: List<Boundary>): List<Segment> {
        val segments = ArrayList<Segment>()
        for (k in tracks.indices) {
            val track = tracks[k]
            val a = bounds[k].at
            val b = if (k == tracks.lastIndex) total else bounds[k + 1].at
            if (b <= a) continue
            val minShot = pacing.minShotMs(track.scene)

            val raw = draftShots.asSequence().map { it.startMs }.filter { it > a && it < b }.distinct().sorted().toList()
            val cuts = ArrayList<Long>()
            var last = a
            for (c in raw) {
                var t = c
                if (trusted && ctx.isInsideWord(t)) {
                    val lo = last + minShot
                    val hi = b - minShot
                    if (hi < lo) continue
                    t = ctx.snapCut(t, lo, hi, 500L)
                }
                if (t - last >= minShot && b - t >= minShot) {
                    cuts += t
                    last = t
                }
            }

            val points = ArrayList<Long>()
            points += a
            points += cuts
            points += b
            val refined = ArrayList<Long>()
            refined += a
            for (i in 0 until points.size - 1) {
                refined += splitLong(track, points[i], points[i + 1], i)
                refined += points[i + 1]
            }
            deRegularize(refined, track, minShot)

            for (i in 0 until refined.size - 1) {
                val s = refined[i]
                val e = refined[i + 1]
                if (e <= s) continue
                val mid = (s + e) / 2
                val d = draftShots.firstOrNull { it.startMs <= mid && it.endMs > mid }
                segments += Segment(s, e, track, d, if (i == 0 && k > 0) bounds[k] else null)
            }
        }
        return segments
    }

    /** Interior cuts for a segment longer than the style allows. */
    private fun splitLong(track: SceneTrack, s: Long, e: Long, salt: Int): List<Long> {
        val len = e - s
        val minShot = pacing.minShotMs(track.scene)
        if (len <= pacing.maxShotMs * 1.15 || len < 2 * minShot) return emptyList()
        val target = pacing.targetShotMs(track.scene).coerceAtLeast(1L)
        val n = max(2L, (len.toDouble() / target).roundToLong()).toInt()
        val out = ArrayList<Long>()
        var cursor = s
        for (j in 1 until n) {
            val ideal = s + len * j / n
            val jitter = ((DetRandom.unit(seed, track.number, salt, j, (s / 10L).toInt()) - 0.5) * 0.36 * len / n).toLong()
            val lo = cursor + minShot
            val hi = e - minShot
            if (hi < lo) break
            val c = ctx.snapCut(ideal + jitter, lo, hi, 600L)
            if (c <= cursor || c >= e) break
            out += c
            cursor = c
        }
        return out
    }

    /** Breaks up runs of three near-identical shot lengths (a mechanical rhythm). */
    private fun deRegularize(points: MutableList<Long>, track: SceneTrack, minShot: Long) {
        if (!trusted || points.size < 4) return
        for (i in 2 until points.size - 1) {
            val d1 = points[i - 1] - points[i - 2]
            val d2 = points[i] - points[i - 1]
            val d3 = points[i + 1] - points[i]
            if (abs(d1 - d2) < UNIFORM_TOLERANCE_MS && abs(d2 - d3) < UNIFORM_TOLERANCE_MS) {
                val shift = DetRandom.betweenLong(250L, 550L, seed, track.number, i, 81)
                val sign = if (DetRandom.chance(0.5, seed, track.number, i, 82)) 1 else -1
                val lo = points[i - 1] + minShot
                val hi = points[i + 1] - minShot
                if (hi > lo) {
                    val c = ctx.snapCut(points[i] + sign * shift, lo, hi, 200L)
                    if (!ctx.isInsideWord(c)) points[i] = c
                }
            }
        }
    }

    // ------------------------------------------------------------------ assets

    private val imageUses = HashMap<String, Int>()
    private val videoRanges = HashMap<String, MutableList<LongArray>>()

    private fun assignAssets(segments: List<Segment>): List<Shot> {
        val queue = ArrayDeque(segments)
        val out = ArrayList<Shot>()
        var prev: VisualAsset? = null
        var lastPosition = -1
        var posInScene = 0
        while (queue.isNotEmpty()) {
            var seg = queue.removeFirst()
            posInScene = if (seg.track.position == lastPosition) posInScene + 1 else 0
            lastPosition = seg.track.position
            val desired = desiredType(prev?.shotType, posInScene)
            var len = seg.end - seg.start

            var choice: Pair<VisualAsset, Long>? = null
            val d = seg.draft
            if (d != null && d.assetId.isNotBlank()) {
                val a = ctx.assetById[d.assetId]
                if (a != null && a.id != prev?.id) {
                    val preferred = if (a.isVideo) d.sourceStartMs.coerceAtLeast(0L) + max(0L, seg.start - d.startMs) else 0L
                    choice = fit(a, len, preferred)
                }
            }
            if (choice == null) choice = pick(seg, len, prev, desired)
            if (choice == null) {
                // Not enough footage: shorten this shot to what the roomiest clip can cover.
                val roomy = roomiestVideo(prev?.id)
                if (roomy != null && roomy.second >= MIN_SPLIT_MS && roomy.second < len) {
                    val hi = seg.start + roomy.second
                    val cut = ctx.snapCut(hi - 100L, seg.start + MIN_SPLIT_MS, hi, 400L)
                    if (cut > seg.start && cut < seg.end && cut - seg.start <= roomy.second) {
                        queue.addFirst(Segment(cut, seg.end, seg.track, seg.draft, null))
                        seg = Segment(seg.start, cut, seg.track, seg.draft, seg.boundary)
                        len = seg.end - seg.start
                        choice = fit(roomy.first, len, null)
                    }
                }
            }
            val picked: Pair<VisualAsset, Long> = choice ?: run {
                val fallback = lastResort(prev, len)
                if (fallback.isVideo && fallback.durationMs in 1 until len && fallback.durationMs >= MIN_SPLIT_MS) {
                    val hi = seg.start + fallback.durationMs
                    val cut = ctx.snapCut(hi - 100L, seg.start + MIN_SPLIT_MS, hi, 400L)
                    if (cut > seg.start && cut < seg.end) {
                        queue.addFirst(Segment(cut, seg.end, seg.track, seg.draft, null))
                        seg = Segment(seg.start, cut, seg.track, seg.draft, seg.boundary)
                        len = seg.end - seg.start
                    }
                }
                val start = if (fallback.isVideo) max(0L, fallback.durationMs - len) else 0L
                fallback to start
            }

            val (asset, sourceStart) = picked
            val boundary = seg.boundary
            markUse(asset, sourceStart, len)
            val draftMatches = d != null && d.assetId == asset.id
            val typography = LocalArtGenerator.isTypography(asset)
            out += Shot(
                index = out.size,
                sceneNumber = seg.track.number,
                startMs = seg.start,
                endMs = seg.end,
                assetId = asset.id,
                sourceStartMs = if (asset.isVideo) sourceStart else 0L,
                shotType = asset.shotType,
                motion = if (draftMatches && d != null) d.motion else CameraMotion(),
                transitionIn = boundary?.transition ?: TransitionType.CUT,
                transitionMs = boundary?.transitionMs ?: 0L,
                cutType = boundary?.cutType ?: CutType.STRAIGHT,
                isBroll = typography || asset.sceneNumber != seg.track.number || (d?.isBroll == true),
                punchIns = emptyList(),
                reason = when {
                    boundary != null && boundary.reason.isNotBlank() -> boundary.reason
                    draftMatches && d != null -> d.reason
                    else -> ""
                },
            )
            prev = asset
        }
        return out
    }

    private fun desiredType(prevType: ShotType?, posInScene: Int): ShotType = when {
        prevType == null || (posInScene == 0 && pacing.style != EditStyle.FAST_SHORTS) -> ShotType.WIDE
        prevType == ShotType.WIDE -> ShotType.MEDIUM
        prevType == ShotType.MEDIUM -> ShotType.CLOSE_UP
        else -> ShotType.WIDE
    }

    private fun fit(a: VisualAsset, len: Long, preferredStart: Long?): Pair<VisualAsset, Long>? {
        if (!a.isVideo) return if ((imageUses[a.id] ?: 0) > 0) null else a to 0L
        if (a.durationMs <= 0L || a.durationMs < len) return null
        val start = freeStart(a, len, preferredStart) ?: return null
        return a to start
    }

    private fun freeStart(a: VisualAsset, len: Long, preferred: Long?): Long? {
        val used = videoRanges[a.id].orEmpty()
        fun fits(s: Long): Boolean = s >= 0L && s + len <= a.durationMs &&
            used.none { s < it[1] + REUSE_GAP_MS && s + len > it[0] - REUSE_GAP_MS }
        if (preferred != null && fits(preferred)) return preferred
        val maxLead = min(1_500L, a.durationMs - len)
        val lead = if (maxLead > 0L) DetRandom.betweenLong(0L, maxLead, seed, DetRandom.key(a.id), used.size) else 0L
        val options = ArrayList<Long>()
        options += lead
        used.sortedBy { it[0] }.forEach { options += it[1] + REUSE_GAP_MS }
        options += 0L
        options += a.durationMs - len
        return options.firstOrNull { fits(it) }
    }

    private fun pick(seg: Segment, len: Long, prev: VisualAsset?, desired: ShotType): Pair<VisualAsset, Long>? {
        val wantBroll = seg.draft?.isBroll == true
        val calm = pacing.energy(seg.track.scene) < -0.3
        var best: Pair<VisualAsset, Long>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for ((idx, a) in ctx.visuals.withIndex()) {
            if (a.id == prev?.id) continue
            val f = fit(a, len, null) ?: continue
            val dist = abs((trackPosition[a.sceneNumber] ?: 99) - seg.track.position)
            var score = when (dist) {
                0 -> 4.0
                1 -> 1.5
                else -> -0.1 * min(dist, 20)
            }
            if (prev != null && a.shotType != prev.shotType) score += 2.0
            if (a.shotType == desired) score += 0.5
            if (LocalArtGenerator.isTypography(a)) score += if (wantBroll) 3.0 else -2.5
            score += if (a.matchScore >= 0) a.matchScore * 0.2 else 1.0
            if (a.isVideo) score += if (calm) 0.2 else 0.6
            score -= idx * 0.0005
            if (score > bestScore) {
                bestScore = score
                best = f
            }
        }
        return best
    }

    /** The video (other than [excludeId]) with the largest unused stretch, and that stretch's length. */
    private fun roomiestVideo(excludeId: String?): Pair<VisualAsset, Long>? {
        var best: Pair<VisualAsset, Long>? = null
        for (a in ctx.visuals) {
            if (!a.isVideo || a.id == excludeId || a.durationMs <= 0L) continue
            val used = videoRanges[a.id].orEmpty().sortedBy { it[0] }
            var cursor = 0L
            var largest = 0L
            for (r in used) {
                largest = max(largest, r[0] - REUSE_GAP_MS - cursor)
                cursor = max(cursor, r[1] + REUSE_GAP_MS)
            }
            largest = max(largest, a.durationMs - cursor)
            if (best == null || largest > best.second) best = a to largest
        }
        return best
    }

    /** Only reached when every image is used and no clip has room left. */
    private fun lastResort(prev: VisualAsset?, len: Long): VisualAsset {
        val pool = ctx.visuals.filter { it.id != prev?.id }.ifEmpty { ctx.visuals }
        val sceneOfPrev = prev?.sceneNumber
        return pool.minWithOrNull(
            compareBy<VisualAsset> { useCount(it) }
                .thenBy { if (it.isVideo && it.durationMs >= len) 0 else 1 }
                .thenBy { if (it.sceneNumber == sceneOfPrev) 0 else 1 },
        ) ?: ctx.visuals.first()
    }

    private fun useCount(a: VisualAsset): Int =
        if (a.isVideo) videoRanges[a.id]?.size ?: 0 else imageUses[a.id] ?: 0

    private fun markUse(a: VisualAsset, start: Long, len: Long) {
        if (a.isVideo) {
            videoRanges.getOrPut(a.id) { ArrayList() } += longArrayOf(start, start + len)
        } else {
            imageUses[a.id] = (imageUses[a.id] ?: 0) + 1
        }
    }

    // ------------------------------------------------------------------ transitions

    private fun transitions(shots: List<Shot>): List<Shot> {
        if (shots.isEmpty()) return shots
        val list = shots.toMutableList()
        list[0] = list[0].copy(transitionIn = TransitionType.CUT, transitionMs = 0L, cutType = CutType.STRAIGHT)
        for (i in 1 until list.size) {
            val s = list[i]
            if (!s.transitionIn.isFancy()) continue
            val maxMs = min(FANCY_MAX_MS, min(s.durationMs, list[i - 1].durationMs) / 3)
            list[i] = if (list[i - 1].transitionIn.isFancy() || maxMs < FANCY_MIN_MS) {
                s.copy(transitionIn = TransitionType.CUT, transitionMs = 0L)
            } else {
                s.copy(transitionMs = s.transitionMs.coerceIn(FANCY_MIN_MS, maxMs), cutType = CutType.STRAIGHT)
            }
        }
        val n = list.size
        val maxFancy = if (n >= 6) max(1, ((n - 1) * MAX_FANCY_SHARE).toInt()) else 0
        val fancy = list.indices.filter { list[it].transitionIn.isFancy() }
        if (fancy.size > maxFancy) {
            val keep = fancy.sortedWith(
                compareByDescending<Int> { ctx.track(list[it].sceneNumber)?.sectionChangeStrength ?: 0 }.thenBy { it },
            ).take(maxFancy).toSet()
            for (i in fancy) {
                if (i !in keep) list[i] = list[i].copy(transitionIn = TransitionType.CUT, transitionMs = 0L)
            }
        }
        for (i in list.indices) {
            if (!list[i].transitionIn.isFancy() && list[i].transitionMs != 0L) list[i] = list[i].copy(transitionMs = 0L)
        }
        return list
    }

    private fun TransitionType.isFancy(): Boolean = this != TransitionType.CUT

    // ------------------------------------------------------------------ camera motion and shake

    private fun motion(shots: List<Shot>): List<Shot> {
        var prevImageType: MotionType? = null
        val result = shots.map { shot ->
            val a = ctx.assetById[shot.assetId]
            val scene = ctx.track(shot.sceneNumber)?.scene
            val energy = scene?.let { pacing.energy(it) } ?: 0.0
            val k = (shot.startMs / 10L).toInt()
            val draft = shot.motion
            val base = if (a == null || a.isVideo) {
                videoMotion(draft, energy, k)
            } else {
                imageMotion(a, draft, energy, k, prevImageType).also { prevImageType = it.type }
            }
            val shake = when {
                !ctx.settings.handheldShake -> 0f
                draft.shake > 0f -> draft.shake.coerceIn(SHAKE_MIN, SHAKE_MAX)
                DetRandom.chance(0.25 + 0.2 * max(0.0, energy), seed, k, 44) ->
                    (0.08 + 0.12 * (energy + 1.0) / 2.0 + DetRandom.between(0.0, 0.06, seed, k, 46)).toFloat()
                        .coerceIn(SHAKE_MIN, SHAKE_MAX)
                else -> 0f
            }
            val shakeSeed = if (shake > 0f) (DetRandom.unit(seed, k, 45) * 1_000_000).toInt() + 1 else 0
            shot.copy(motion = base.copy(shake = shake, shakeSeed = shakeSeed))
        }
        if (result.size >= 3 && result.all { it.motion.shake > 0f }) {
            return result.mapIndexed { i, s -> if (i % 3 == 0) s.copy(motion = s.motion.copy(shake = 0f, shakeSeed = 0)) else s }
        }
        return result
    }

    private fun imageMotion(a: VisualAsset, draft: CameraMotion, energy: Double, k: Int, prevType: MotionType?): CameraMotion {
        val keepDraft = draft.type != MotionType.STATIC && draft.type != prevType && hasMovement(draft)
        if (keepDraft) {
            return draft.copy(
                zoomStart = draft.zoomStart.coerceIn(1.0f, MAX_ZOOM),
                zoomEnd = draft.zoomEnd.coerceIn(1.0f, MAX_ZOOM),
                panStartX = draft.panStartX.coerceIn(-1f, 1f),
                panStartY = draft.panStartY.coerceIn(-1f, 1f),
                panEndX = draft.panEndX.coerceIn(-1f, 1f),
                panEndY = draft.panEndY.coerceIn(-1f, 1f),
                easing = if (draft.easing == Easing.EASE_IN) Easing.EASE_IN_OUT else draft.easing,
            )
        }
        val type = if (draft.type != MotionType.STATIC && draft.type != prevType) draft.type else chooseImageMotion(a, k, prevType)
        return generateImageMotion(type, energy, k)
    }

    private fun hasMovement(m: CameraMotion): Boolean =
        abs(m.zoomEnd - m.zoomStart) >= 0.02f || abs(m.panEndX - m.panStartX) >= 0.1f || abs(m.panEndY - m.panStartY) >= 0.1f

    private fun chooseImageMotion(a: VisualAsset, k: Int, prev: MotionType?): MotionType {
        val frameAspect = ctx.request.format.width.toDouble() / ctx.request.format.height
        val aspect = if (a.width > 0 && a.height > 0) a.width.toDouble() / a.height else frameAspect
        val options = when {
            LocalArtGenerator.isTypography(a) -> listOf(MotionType.KEN_BURNS_IN, MotionType.KEN_BURNS_OUT)
            aspect < frameAspect * 0.85 -> listOf(MotionType.PAN_UP, MotionType.PAN_DOWN, MotionType.KEN_BURNS_IN, MotionType.KEN_BURNS_OUT)
            aspect > frameAspect * 1.2 -> listOf(MotionType.PAN_LEFT, MotionType.PAN_RIGHT, MotionType.KEN_BURNS_IN, MotionType.PARALLAX)
            else -> listOf(
                MotionType.KEN_BURNS_IN, MotionType.KEN_BURNS_OUT, MotionType.PAN_LEFT,
                MotionType.PAN_RIGHT, MotionType.PARALLAX, MotionType.KEN_BURNS_IN,
            )
        }.filter { it != prev }.ifEmpty { listOf(MotionType.KEN_BURNS_IN) }
        val i = (DetRandom.unit(seed, k, 43) * options.size).toInt().coerceIn(0, options.size - 1)
        return options[i]
    }

    private fun generateImageMotion(type: MotionType, energy: Double, k: Int): CameraMotion {
        val amount = (0.07 + 0.05 * (energy + 1.0) / 2.0 + DetRandom.between(0.0, 0.05, seed, k, 41)).coerceIn(0.05, 0.16)
        val easing = if (DetRandom.chance(0.3, seed, k, 42)) Easing.EASE_OUT else Easing.EASE_IN_OUT
        fun r(lo: Double, hi: Double, salt: Int): Float = DetRandom.between(lo, hi, seed, k, salt).toFloat()
        val pan = (0.45 + amount * 2.0).toFloat().coerceIn(0.3f, 0.8f)
        return when (type) {
            MotionType.KEN_BURNS_IN, MotionType.STATIC -> {
                val zs = r(1.0, 1.02, 47)
                CameraMotion(
                    type = MotionType.KEN_BURNS_IN,
                    zoomStart = zs,
                    zoomEnd = (zs + amount.toFloat()).coerceAtMost(MAX_ZOOM),
                    panEndX = r(-0.25, 0.25, 48),
                    panEndY = r(-0.2, 0.2, 49),
                    easing = easing,
                )
            }
            MotionType.KEN_BURNS_OUT -> {
                val ze = r(1.0, 1.02, 47)
                CameraMotion(
                    type = MotionType.KEN_BURNS_OUT,
                    zoomStart = (ze + amount.toFloat()).coerceAtMost(MAX_ZOOM),
                    zoomEnd = ze,
                    panStartX = r(-0.25, 0.25, 48),
                    panStartY = r(-0.2, 0.2, 49),
                    easing = easing,
                )
            }
            MotionType.PAN_LEFT, MotionType.PAN_RIGHT -> {
                val z = r(1.08, 1.14, 50)
                val sign = if (type == MotionType.PAN_LEFT) 1f else -1f
                CameraMotion(
                    type = type,
                    zoomStart = z,
                    zoomEnd = (z + r(0.0, 0.03, 51)).coerceAtMost(MAX_ZOOM),
                    panStartX = sign * pan,
                    panEndX = -sign * pan,
                    easing = easing,
                )
            }
            MotionType.PAN_UP, MotionType.PAN_DOWN -> {
                val z = r(1.08, 1.14, 50)
                val sign = if (type == MotionType.PAN_UP) 1f else -1f
                CameraMotion(
                    type = type,
                    zoomStart = z,
                    zoomEnd = (z + r(0.0, 0.03, 51)).coerceAtMost(MAX_ZOOM),
                    panStartY = sign * pan,
                    panEndY = -sign * pan,
                    easing = easing,
                )
            }
            MotionType.PARALLAX -> {
                val zs = r(1.03, 1.05, 52)
                val dir = if (DetRandom.chance(0.5, seed, k, 53)) 1f else -1f
                CameraMotion(
                    type = MotionType.PARALLAX,
                    zoomStart = zs,
                    zoomEnd = (zs + amount.toFloat()).coerceAtMost(MAX_ZOOM),
                    panStartX = -0.2f * dir,
                    panEndX = 0.2f * dir,
                    easing = Easing.EASE_IN_OUT,
                )
            }
        }
    }

    private fun videoMotion(draft: CameraMotion, energy: Double, k: Int): CameraMotion {
        if ((draft.type == MotionType.KEN_BURNS_IN || draft.type == MotionType.KEN_BURNS_OUT) && hasMovement(draft)) {
            return CameraMotion(
                type = draft.type,
                zoomStart = draft.zoomStart.coerceIn(1.0f, MAX_VIDEO_ZOOM),
                zoomEnd = draft.zoomEnd.coerceIn(1.0f, MAX_VIDEO_ZOOM),
                easing = if (draft.easing == Easing.EASE_IN) Easing.EASE_IN_OUT else draft.easing,
            )
        }
        val p = if (energy < 0.0) 0.35 else 0.2
        return if (DetRandom.chance(p, seed, k, 54)) {
            CameraMotion(
                type = MotionType.KEN_BURNS_IN,
                zoomStart = 1.0f,
                zoomEnd = (1.02 + DetRandom.between(0.0, 0.03, seed, k, 55)).toFloat(),
                easing = Easing.EASE_IN_OUT,
            )
        } else {
            CameraMotion(type = MotionType.STATIC, easing = Easing.EASE_IN_OUT)
        }
    }

    // ------------------------------------------------------------------ text pops

    private fun pops(draftPops: List<TextPop>): List<TextPop> {
        val candidates = ArrayList<PopCandidate>()
        for (p in draftPops) {
            alignPop(p)?.let { candidates += PopCandidate(it, if (it.kind == TextPopKind.CTA) 100.0 else 10.0) }
        }
        val heuristics = EditHeuristics.popCandidates(ctx)
        if (candidates.none { it.pop.kind != TextPopKind.CTA }) candidates += heuristics.filter { it.pop.kind != TextPopKind.CTA }
        if (candidates.none { it.pop.kind == TextPopKind.CTA }) candidates += heuristics.filter { it.pop.kind == TextPopKind.CTA }

        val minGap = if (ctx.isShorts) 2_200L else 4_500L
        val accepted = ArrayList<TextPop>()
        for (c in candidates.sortedWith(compareByDescending<PopCandidate> { it.priority }.thenBy { it.pop.startMs })) {
            val p = c.pop
            if (p.endMs - p.startMs < MIN_POP_MS - 300) continue
            val ok = accepted.all { q ->
                val apart = p.endMs + POP_MARGIN_MS <= q.startMs || q.endMs + POP_MARGIN_MS <= p.startMs
                apart && (p.kind == TextPopKind.CTA || q.kind == TextPopKind.CTA || abs(p.startMs - q.startMs) >= minGap)
            }
            if (ok) accepted += p
        }
        accepted.sortBy { it.startMs }
        val allowedEmoji = max(1, accepted.size / 4)
        var emojiUsed = 0
        return accepted.map { p ->
            when {
                p.emoji.isNullOrBlank() -> p.copy(emoji = null)
                p.kind == TextPopKind.CTA -> p
                emojiUsed < allowedEmoji -> {
                    emojiUsed++
                    p
                }
                else -> p.copy(emoji = null)
            }
        }
    }

    private fun alignPop(p: TextPop): TextPop? {
        val text = WordText.trimPunctuation(p.text.replace(WordText.WHITESPACE, " ")).take(40)
        if (text.isBlank()) return null
        var start = p.startMs
        var end = p.endMs
        if (trusted && (p.kind == TextPopKind.NUMBER || p.kind == TextPopKind.NAME || p.kind == TextPopKind.FACT)) {
            val w = ctx.findSpoken(text, p.startMs, 2_500L)
            if (w != null) {
                val delta = w.startMs - start
                start = w.startMs
                end += delta
            } else if (p.kind == TextPopKind.NUMBER || p.kind == TextPopKind.NAME) {
                return null
            }
        }
        val duration = if (end > start) (end - start).coerceIn(MIN_POP_MS, MAX_POP_MS) else 1_400L
        start = start.coerceIn(0L, max(0L, total - MIN_POP_MS))
        end = min(total, start + duration)
        if (end - start < 600L) return null
        val sceneNumber = if (p.sceneNumber in trackPosition) p.sceneNumber else ctx.trackAt(start).number
        return p.copy(text = text, startMs = start, endMs = end, sceneNumber = sceneNumber)
    }

    // ------------------------------------------------------------------ punch-ins

    private fun punchIns(shots: List<Shot>, draftPunches: List<PunchIn>): List<Shot> {
        if (shots.isEmpty()) return shots
        val candidates = ArrayList<PunchCandidate>()
        for (p in draftPunches) alignPunch(p)?.let { candidates += PunchCandidate(it, 10.0) }
        if (draftPunches.isEmpty()) candidates += EditHeuristics.punchInCandidates(ctx)
        val styleSpacing = if (ctx.isShorts) pacing.punchInSpacingMs else LONG_FORM_PUNCH_SPACING_MS
        val accepted = ArrayList<PunchIn>()
        val perShot = HashMap<Int, MutableList<PunchIn>>()
        for (c in candidates.sortedWith(compareByDescending<PunchCandidate> { it.priority }.thenBy { it.punch.atMs })) {
            val p = c.punch
            val idx = shotIndexAt(shots, p.atMs)
            val s = shots[idx]
            val minAt = s.startMs + max(250L, s.transitionMs + 100L)
            if (p.atMs < minAt || p.atMs + p.durationMs > s.endMs - 40L) continue
            val gap = if (c.priority >= 10.0) pacing.punchInSpacingMs else styleSpacing
            if (accepted.any { abs(it.atMs - p.atMs) < gap }) continue
            accepted += p
            perShot.getOrPut(idx) { ArrayList() } += p
        }
        return shots.mapIndexed { i, s -> s.copy(punchIns = perShot[i].orEmpty().sortedBy { it.atMs }) }
    }

    private fun alignPunch(p: PunchIn): PunchIn? {
        var at = p.atMs
        val word = WordText.trimPunctuation(p.word)
        if (trusted && word.isNotBlank()) {
            val w = ctx.findSpoken(word, p.atMs + EditHeuristics.PUNCH_LEAD_MS, 1_500L) ?: return null
            at = w.startMs - EditHeuristics.PUNCH_LEAD_MS
        }
        if (at < 0L || at >= total) return null
        return p.copy(
            atMs = at,
            durationMs = p.durationMs.coerceIn(500L, 900L),
            scale = p.scale.coerceIn(1.05f, 1.15f),
            word = word,
        )
    }

    // ------------------------------------------------------------------ pattern interrupts

    private fun patternInterrupts(shots: List<Shot>, pops: List<TextPop>): List<Shot> {
        if (shots.isEmpty()) return shots
        val list = shots.map { it.copy(isPatternInterrupt = false) }.toMutableList()
        angleInterrupts.clear()
        val minGap = pacing.patternInterruptMinMs
        val maxGap = pacing.patternInterruptMaxMs
        var last = 0L
        var iter = 0
        while (iter < 1_000) {
            val winLo = last + minGap
            val winHi = min(last + maxGap, total - 500L)
            if (winLo >= winHi) break
            val target = DetRandom.betweenLong(winLo, winHi, seed, 51, iter)
            var bestT = -1L
            var bestIdx = -1
            var bestKind = ""
            fun offer(t: Long, idx: Int, kind: String) {
                if (t < winLo || t > winHi || idx < 0) return
                if (bestT < 0L || abs(t - target) < abs(bestT - target)) {
                    bestT = t
                    bestIdx = idx
                    bestKind = kind
                }
            }
            for (i in 1 until list.size) {
                val s = list[i]
                if (s.startMs > winHi) break
                if (s.startMs < winLo) continue
                when {
                    s.isBroll && !list[i - 1].isBroll -> offer(s.startMs, i, KIND_BROLL)
                    s.shotType != list[i - 1].shotType || s.transitionIn != TransitionType.CUT -> offer(s.startMs, i, KIND_ANGLE)
                }
            }
            for ((i, s) in list.withIndex()) for (p in s.punchIns) offer(p.atMs, i, KIND_PUNCH)
            for (p in pops) offer(p.startMs, shotIndexAt(list, p.startMs), KIND_POP)

            if (bestT < 0L) {
                val word = ctx.wordsBetween(winLo, winHi).filter { w ->
                    val at = w.startMs - EditHeuristics.PUNCH_LEAD_MS
                    val s = list[shotIndexAt(list, at)]
                    at >= s.startMs + 250L && at + 700L <= s.endMs - 40L &&
                        list.all { sh -> sh.punchIns.none { abs(it.atMs - at) < 2_500L } }
                }.minByOrNull { abs(it.startMs - target) - if (ctx.isKeyWord(it)) 1_500L else 0L }
                if (word != null) {
                    val at = word.startMs - EditHeuristics.PUNCH_LEAD_MS
                    val idx = shotIndexAt(list, at)
                    val punch = PunchIn(atMs = at, durationMs = 700L, scale = 1.08f, word = WordText.trimPunctuation(word.text))
                    list[idx] = list[idx].copy(punchIns = (list[idx].punchIns + punch).sortedBy { it.atMs })
                    bestT = at
                    bestIdx = idx
                    bestKind = KIND_PUNCH
                }
            }
            if (bestT < 0L || bestIdx < 0) {
                last = winHi
                iter++
                continue
            }
            val s = list[bestIdx]
            if (bestKind == KIND_ANGLE || bestKind == KIND_BROLL) angleInterrupts += s.startMs
            val label = when (bestKind) {
                KIND_ANGLE -> "pattern interrupt: angle change"
                KIND_BROLL -> "pattern interrupt: B-roll"
                KIND_PUNCH -> "pattern interrupt: punch-in"
                else -> "pattern interrupt: text pop"
            }
            list[bestIdx] = s.copy(isPatternInterrupt = true, reason = appendReason(s.reason, label))
            last = bestT
            iter++
        }
        return list
    }

    private fun appendReason(reason: String, extra: String): String =
        if (reason.isBlank()) extra.replaceFirstChar { it.uppercase() }
        else if (reason.contains(extra, ignoreCase = true)) reason
        else "$reason; $extra"

    private fun shotIndexAt(shots: List<Shot>, t: Long): Int {
        var lo = 0
        var hi = shots.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (shots[mid].startMs <= t) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return idx
    }

    // ------------------------------------------------------------------ final pass

    private fun finish(shots: List<Shot>): List<Shot> {
        val out = ArrayList<Shot>(shots.size)
        var cursor = 0L
        for ((i, s) in shots.withIndex()) {
            val end = if (i == shots.lastIndex) total else max(s.endMs, cursor + 1)
            val shot = s.copy(index = i, startMs = cursor, endMs = end)
            out += if (shot.reason.isBlank()) shot.copy(reason = autoReason(shot)) else shot
            cursor = end
        }
        return out
    }

    private fun autoReason(shot: Shot): String {
        val a = ctx.assetById[shot.assetId]
        val what = when (shot.shotType) {
            ShotType.WIDE -> "Wide shot"
            ShotType.MEDIUM -> "Medium shot"
            ShotType.CLOSE_UP -> "Close-up"
        }
        val about = a?.query?.takeIf { it.isNotBlank() && !it.startsWith("art:") && !it.startsWith("gallery") }
            ?.let { " of \"${it.removePrefix(LocalArtGenerator.TYPOGRAPHY_PREFIX).take(40)}\"" } ?: ""
        val mood = ctx.track(shot.sceneNumber)?.scene?.emotion?.wireName ?: "this"
        val cut = when (shot.cutType) {
            CutType.J_CUT -> "J-cut after the new line starts"
            CutType.L_CUT -> "L-cut while the last line finishes"
            CutType.STRAIGHT -> "cut on ${HeuristicEditPlanner.describeCut(ctx, shot.startMs)}"
        }
        return "$what$about for the $mood beat; $cut"
    }

    // ------------------------------------------------------------------ music

    private fun keyMoments(draftMusic: List<MusicCue>): List<KeyMoment> {
        val count = if (ctx.isShorts) 1 else min(3, 1 + (total / 150_000L).toInt())
        val hints = draftMusic.filter { it.action == MusicAction.DROP_OUT }.map { it.atMs }
        if (hints.isNotEmpty()) {
            val fromHints = hints.mapNotNull { h -> ctx.lines.firstOrNull { it.startMs >= h - 100L && it.startMs <= h + 1_500L } }
                .distinctBy { it.startMs }
                .take(count)
                .map { line ->
                    KeyMoment(
                        atMs = line.startMs,
                        lineEndMs = line.endMs,
                        sceneNumber = line.sceneNumber,
                        intensity = ctx.track(line.sceneNumber)?.scene?.intensity?.coerceIn(1, 10) ?: 5,
                        firstWord = line.words.first().text,
                        score = 10.0,
                    )
                }
            if (fromHints.isNotEmpty()) return fromHints.sortedBy { it.atMs }
        }
        return ctx.keyMoments
    }

    private fun baseIntensity(track: SceneTrack): Int =
        (track.scene.intensity - if (track.scene.role == SceneRole.CTA) 1 else 0).coerceIn(1, 10)

    private fun music(draftMusic: List<MusicCue>, moments: List<KeyMoment>): List<MusicCue> {
        val delta = if (draftMusic.isEmpty()) {
            0
        } else {
            val diffs = draftMusic.map { cue ->
                val extra = if (cue.action == MusicAction.RESUME || cue.action == MusicAction.SWELL) 1 else 0
                cue.intensity - baseIntensity(ctx.trackAt(cue.atMs)) - extra
            }.sorted()
            diffs[diffs.size / 2].coerceIn(-6, 2)
        }
        fun level(track: SceneTrack, extra: Int = 0): Int = (baseIntensity(track) + delta + extra).coerceIn(1, 10)

        val cues = ArrayList<MusicCue>()
        val first = tracks.first()
        cues += MusicCue(0L, MusicAction.CHANGE, first.scene.emotion, level(first), 1_500L, "Opening mood: ${first.scene.emotion.wireName}")
        for (k in 1 until tracks.size) {
            val t = tracks[k]
            if (t.scene.emotion != tracks[k - 1].scene.emotion) {
                cues += MusicCue(
                    t.timing.audioStartMs, MusicAction.CHANGE, t.scene.emotion, level(t), 1_500L,
                    "Mood shifts to ${t.scene.emotion.wireName} as scene ${t.number} starts",
                )
            }
        }
        val dropWindows = ArrayList<LongArray>()
        for (m in moments) {
            val track = ctx.track(m.sceneNumber) ?: continue
            val off = DetRandom.betweenLong(400L, 700L, seed, 61, (m.atMs / 10L).toInt())
            val dropAt = max(0L, m.atMs - off)
            cues += MusicCue(
                dropAt, MusicAction.DROP_OUT, track.scene.emotion, level(track), m.atMs - dropAt,
                "Music drops out before the key line \"${m.firstWord}...\"",
            )
            cues += MusicCue(m.atMs, MusicAction.RESUME, track.scene.emotion, level(track, 1), 0L, "Music returns on the reveal")
            dropWindows += longArrayOf(dropAt - 1_000L, m.atMs + 1_000L)
        }
        fun clearOfDrops(s: Long, e: Long) = dropWindows.none { s < it[1] && e > it[0] }
        for (k in 0 until tracks.size - 1) {
            val gs = tracks[k].timing.audioEndMs
            val ge = tracks[k + 1].timing.audioStartMs
            if (ge - gs >= SWELL_GAP_MS && clearOfDrops(gs, ge)) {
                cues += MusicCue(gs, MusicAction.SWELL, tracks[k].scene.emotion, level(tracks[k], 1), ge - gs, "Music swells in the pause between scenes")
            }
        }
        for (p in ctx.pauses) {
            if (p.second - p.first >= SWELL_GAP_MS && clearOfDrops(p.first, p.second)) {
                val t = ctx.trackAt(p.first)
                cues += MusicCue(p.first, MusicAction.SWELL, t.scene.emotion, level(t, 1), p.second - p.first, "Music fills a long pause")
            }
        }
        val lastEnd = tracks.last().timing.audioEndMs
        if (total - lastEnd >= SWELL_GAP_MS) {
            val t = tracks.last()
            cues += MusicCue(lastEnd, MusicAction.SWELL, t.scene.emotion, level(t, 1), total - lastEnd, "Outro swell after the last line")
        }
        return cues.sortedBy { it.atMs }.distinctBy { "${it.atMs}|${it.action}" }
    }

    // ------------------------------------------------------------------ sound design

    private fun sfx(shots: List<Shot>, pops: List<TextPop>, moments: List<KeyMoment>): List<SfxCue> {
        class Cand(val cue: SfxCue, val priority: Int)
        val cands = ArrayList<Cand>()
        for ((i, s) in shots.withIndex()) {
            if (i == 0) continue
            if (s.transitionIn != TransitionType.CUT) {
                val strong = s.transitionIn == TransitionType.WHIP || s.transitionIn == TransitionType.ZOOM
                cands += Cand(
                    SfxCue(
                        type = if (strong) SfxType.WHOOSH else SfxType.SWOOSH_SOFT,
                        atMs = s.startMs,
                        gain = if (strong) 0.42f else 0.28f,
                        reason = "Whoosh on the ${s.transitionIn.name.lowercase().replace('_', ' ')} transition",
                    ),
                    4,
                )
            } else if (s.isPatternInterrupt && s.startMs in angleInterrupts) {
                cands += Cand(
                    SfxCue(
                        SfxType.SWOOSH_SOFT, s.startMs,
                        DetRandom.between(0.16, 0.22, seed, (s.startMs / 10L).toInt(), 62).toFloat(),
                        "Subtle swoosh on the angle change",
                    ),
                    1,
                )
            }
        }
        for (p in pops) {
            cands += Cand(
                SfxCue(SfxType.POP, p.startMs, if (p.kind == TextPopKind.CTA) 0.32f else 0.28f, "Pop as \"${p.text.take(20)}\" appears"),
                3,
            )
        }
        val maxRisers = if (ctx.isShorts) 1 else 3
        var risers = 0
        for (m in moments) {
            if (m.intensity >= 7) cands += Cand(SfxCue(SfxType.IMPACT, m.atMs, 0.5f, "Impact on the reveal"), 5)
            if (m.intensity >= 6 && m.atMs >= 2_500L && risers < maxRisers) {
                cands += Cand(SfxCue(SfxType.RISER, m.atMs, 0.35f, "Riser peaking on the big moment"), 6)
                risers++
            }
        }
        val accepted = ArrayList<SfxCue>()
        for (c in cands.sortedByDescending { it.priority }) {
            val cue = c.cue
            if (cue.type == SfxType.RISER || accepted.none { it.type != SfxType.RISER && abs(it.atMs - cue.atMs) < SFX_MIN_SPACING_MS }) {
                accepted += cue
            }
        }
        return accepted.sortedBy { it.atMs }
    }

    private fun notes(origin: String, draftNotes: String, shots: List<Shot>, pops: List<TextPop>): String {
        val avg = if (shots.isEmpty()) 0.0 else shots.sumOf { it.durationMs } / shots.size / 100 / 10.0
        val cuts = if (shots.size <= 1) 100 else shots.drop(1).count { it.transitionIn == TransitionType.CUT } * 100 / (shots.size - 1)
        val punches = shots.sumOf { it.punchIns.size }
        val interrupts = shots.count { it.isPatternInterrupt }
        val base = "$origin. Style: ${pacing.style.label}. ${shots.size} shots (avg ${avg}s), $cuts% hard cuts, " +
            "$punches punch-ins, $interrupts pattern interrupts, ${pops.size} text pops."
        val extra = draftNotes.trim().takeIf { it.isNotEmpty() && it != origin }?.take(400)
        return if (extra != null) "$base Editor notes: $extra" else base
    }

    private companion object {
        const val MIN_SEGMENT_MS = 400L
        const val LJ_MIN_MS = 200L
        const val LJ_MAX_MS = 600L
        const val FANCY_MIN_MS = 250L
        const val FANCY_MAX_MS = 600L
        const val MAX_FANCY_SHARE = 0.12
        const val UNIFORM_TOLERANCE_MS = 120L
        const val REUSE_GAP_MS = 400L
        const val MIN_SPLIT_MS = 1_200L
        const val MAX_ZOOM = 1.18f
        const val MAX_VIDEO_ZOOM = 1.06f
        const val SHAKE_MIN = 0.08f
        const val SHAKE_MAX = 0.3f
        const val MIN_POP_MS = 900L
        const val MAX_POP_MS = 2_000L
        const val POP_MARGIN_MS = 120L
        const val LONG_FORM_PUNCH_SPACING_MS = 6_500L
        const val SWELL_GAP_MS = 800L
        const val SFX_MIN_SPACING_MS = 220L
        const val KIND_ANGLE = "angle"
        const val KIND_BROLL = "broll"
        const val KIND_PUNCH = "punch"
        const val KIND_POP = "pop"
    }
}
