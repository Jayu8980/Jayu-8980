package com.autotube.ai.pipeline.editor

import com.autotube.ai.core.model.CameraMotion
import com.autotube.ai.core.model.CutType
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditFix
import com.autotube.ai.core.model.EditFixAction
import com.autotube.ai.core.model.MusicCue
import com.autotube.ai.core.model.PunchIn
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.TextPop
import com.autotube.ai.core.model.TextPopKind
import com.autotube.ai.core.model.TransitionType
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.pipeline.visuals.LocalArtGenerator
import kotlin.math.abs
import kotlin.math.max

/**
 * Applies QC [EditFix]es deterministically to an existing EDL. The result is a draft; the caller
 * always re-runs [EdlNormalizer] on it so every editing rule still holds afterwards.
 */
internal class EditFixer(private val ctx: EditContext) {

    private var shots: MutableList<Shot> = mutableListOf()
    private var pops: MutableList<TextPop> = mutableListOf()
    private var music: MutableList<MusicCue> = mutableListOf()

    fun apply(edl: EditDecisionList, fixes: List<EditFix>): EditDecisionList {
        shots = edl.shots.sortedBy { it.startMs }.toMutableList()
        pops = edl.textPops.toMutableList()
        music = edl.music.toMutableList()
        for (fix in fixes) {
            when (fix.action) {
                EditFixAction.SHORTEN_SHOTS -> scenes(fix).forEach { resplit(it, SHORTEN_FACTOR, fix) }
                EditFixAction.LENGTHEN_SHOTS -> scenes(fix).forEach { resplit(it, LENGTHEN_FACTOR, fix) }
                EditFixAction.ADD_PUNCH_IN -> addPunchIns(fix)
                EditFixAction.ADD_BROLL -> scenes(fix).forEach { addBroll(it, fix) }
                EditFixAction.ADD_TEXT_POP -> scenes(fix).forEach { addTextPop(it, fix) }
                EditFixAction.SIMPLIFY_TRANSITIONS -> simplifyTransitions(fix)
                EditFixAction.ADD_PATTERN_INTERRUPT -> scenes(fix).forEach { addPatternInterrupt(it, fix) }
                EditFixAction.FIX_CAPTION_OVERFLOW -> Unit // handled by the caption renderer
                EditFixAction.REPLACE_VISUAL -> replaceVisual(fix)
                EditFixAction.LOWER_MUSIC -> lowerMusic()
            }
        }
        return edl.copy(shots = shots.mapIndexed { i, s -> s.copy(index = i) }, textPops = pops, music = music)
    }

    /** Scene numbers a fix targets: explicit scene, the scene of the shot, or every scene. */
    private fun scenes(fix: EditFix): List<Int> {
        fix.sceneNumber?.let { n -> if (ctx.track(n) != null) return listOf(n) }
        fix.shotIndex?.let { i -> shots.getOrNull(i)?.let { return listOf(it.sceneNumber) } }
        return ctx.tracks.map { it.number }
    }

    private fun sceneShotIndices(scene: Int): List<Int> = shots.indices.filter { shots[it].sceneNumber == scene }

    private fun usedAssetIds(): Set<String> = shots.map { it.assetId }.toHashSet()

    private fun reason(fix: EditFix, what: String): String =
        "QC fix: $what" + if (fix.reason.isNotBlank()) " (${fix.reason.take(120)})" else ""

    // ------------------------------------------------------------------ pacing

    private fun resplit(scene: Int, factor: Double, fix: EditFix) {
        val idx = sceneShotIndices(scene)
        if (idx.isEmpty()) return
        val track = ctx.track(scene) ?: return
        val first = shots[idx.first()]
        val start = first.startMs
        val end = shots[idx.last()].endMs
        val old = idx.map { shots[it] }
        val salt = if (factor < 1.0) 17 else 29
        val cuts = HeuristicEditPlanner.sceneCuts(ctx, track, start, end, factor, salt)
        val points = listOf(start) + cuts + listOf(end)
        val label = if (factor < 1.0) "shorter shots for a faster pace" else "longer shots to let it breathe"
        val replacement = (0 until points.size - 1).map { i ->
            val s = points[i]
            val e = points[i + 1]
            val mid = (s + e) / 2
            val src = old.firstOrNull { it.startMs <= mid && it.endMs > mid } ?: old.last()
            Shot(
                index = 0,
                sceneNumber = scene,
                startMs = s,
                endMs = e,
                assetId = src.assetId,
                sourceStartMs = if (src.startMs <= s) src.sourceStartMs + (s - src.startMs) else src.sourceStartMs,
                shotType = src.shotType,
                motion = CameraMotion(),
                transitionIn = if (i == 0) first.transitionIn else TransitionType.CUT,
                transitionMs = if (i == 0) first.transitionMs else 0L,
                cutType = if (i == 0) first.cutType else CutType.STRAIGHT,
                isBroll = src.isBroll,
                punchIns = src.punchIns.filter { it.atMs in s until e },
                reason = if (i == 0) first.reason else reason(fix, label),
            )
        }
        val from = idx.first()
        repeat(idx.size) { shots.removeAt(from) }
        shots.addAll(from, replacement)
    }

    // ------------------------------------------------------------------ punch-ins

    private fun addPunchIns(fix: EditFix) {
        val targetShot = fix.shotIndex?.let { shots.getOrNull(it) }
        val sceneList = if (targetShot != null) listOf(targetShot.sceneNumber) else scenes(fix)
        for (scene in sceneList) {
            val existing = shots.flatMap { it.punchIns }
            var added = 0
            val candidates = EditHeuristics.punchInCandidates(ctx, scene).sortedByDescending { it.priority }
            for (c in candidates) {
                if (added >= MAX_ADDED_PER_SCENE) break
                val p = c.punch
                val i = shotIndexAt(p.atMs)
                if (i < 0) continue
                val shot = shots[i]
                if (targetShot != null && shot.startMs != targetShot.startMs) continue
                if (p.atMs < shot.startMs + 250L || p.atMs + p.durationMs > shot.endMs - 40L) continue
                val all = existing + shots.flatMap { it.punchIns }
                if (all.any { abs(it.atMs - p.atMs) < MIN_PUNCH_SPACING_MS }) continue
                shots[i] = shot.copy(punchIns = (shot.punchIns + p).sortedBy { it.atMs }, reason = appendReason(shot.reason, reason(fix, "punch-in on \"${p.word}\"")))
                added++
            }
        }
    }

    // ------------------------------------------------------------------ B-roll and visuals

    private fun addBroll(scene: Int, fix: EditFix) {
        val idx = sceneShotIndices(scene)
        if (idx.isEmpty()) return
        val target = fix.shotIndex?.takeIf { it in idx } ?: idx.maxByOrNull { shots[it].durationMs } ?: return
        val shot = shots[target]
        val used = usedAssetIds()
        val sceneAssets = ctx.visuals.filter { it.sceneNumber == scene && it.id !in used && it.id != shot.assetId }
        val broll = sceneAssets.firstOrNull { LocalArtGenerator.isTypography(it) }
            ?: sceneAssets.firstOrNull { it.shotType != shot.shotType }
            ?: sceneAssets.firstOrNull()
            ?: ctx.visuals.firstOrNull { it.id !in used && LocalArtGenerator.isTypography(it) }
            ?: ctx.visuals.firstOrNull { it.id !in used }
            ?: return
        splitOrSwap(target, broll, isBroll = true, interrupt = false, label = reason(fix, "B-roll cutaway"))
    }

    private fun addPatternInterrupt(scene: Int, fix: EditFix) {
        val idx = sceneShotIndices(scene)
        if (idx.isEmpty()) return
        val target = fix.shotIndex?.takeIf { it in idx } ?: idx.maxByOrNull { shots[it].durationMs } ?: return
        val shot = shots[target]
        val used = usedAssetIds()
        val alt = ctx.visuals.filter { it.sceneNumber == scene && it.id !in used && it.shotType != shot.shotType }.firstOrNull()
            ?: ctx.visuals.firstOrNull { it.id !in used && it.shotType != shot.shotType }
        val track = ctx.track(scene)
        val minShot = track?.let { ctx.pacing.minShotMs(it.scene) } ?: 1_500L
        if (alt != null && shot.durationMs >= 2 * minShot) {
            splitOrSwap(target, alt, isBroll = alt.sceneNumber != scene, interrupt = true, label = reason(fix, "angle change to break the rhythm"))
            return
        }
        // No spare visual: punch in on a word in the middle of the shot instead.
        val mid = (shot.startMs + shot.endMs) / 2
        val word = ctx.wordsBetween(shot.startMs + 300L, shot.endMs - 800L).minByOrNull { abs(it.startMs - mid) } ?: return
        val at = word.startMs - EditHeuristics.PUNCH_LEAD_MS
        if (shots.flatMap { it.punchIns }.any { abs(it.atMs - at) < 2_500L }) return
        val punch = PunchIn(atMs = at, durationMs = 700L, scale = 1.1f, word = WordText.trimPunctuation(word.text))
        shots[target] = shot.copy(
            punchIns = (shot.punchIns + punch).sortedBy { it.atMs },
            isPatternInterrupt = true,
            reason = appendReason(shot.reason, reason(fix, "punch-in to break the rhythm")),
        )
    }

    /** Splits the shot at a natural cut point and puts [asset] in the second half, or swaps it when too short. */
    private fun splitOrSwap(index: Int, asset: VisualAsset, isBroll: Boolean, interrupt: Boolean, label: String) {
        val shot = shots[index]
        val track = ctx.track(shot.sceneNumber)
        val minShot = track?.let { ctx.pacing.minShotMs(it.scene) } ?: 1_500L
        if (shot.durationMs >= 2 * minShot) {
            val cut = ctx.snapCut((shot.startMs + shot.endMs) / 2, shot.startMs + minShot, shot.endMs - minShot, 600L)
            if (cut > shot.startMs && cut < shot.endMs) {
                val firstHalf = shot.copy(endMs = cut, punchIns = shot.punchIns.filter { it.atMs < cut })
                val secondHalf = Shot(
                    index = 0,
                    sceneNumber = shot.sceneNumber,
                    startMs = cut,
                    endMs = shot.endMs,
                    assetId = asset.id,
                    sourceStartMs = 0L,
                    shotType = asset.shotType,
                    isBroll = isBroll,
                    isPatternInterrupt = interrupt,
                    punchIns = shot.punchIns.filter { it.atMs >= cut },
                    reason = label,
                )
                shots[index] = firstHalf
                shots.add(index + 1, secondHalf)
                return
            }
        }
        shots[index] = shot.copy(
            assetId = asset.id,
            sourceStartMs = 0L,
            shotType = asset.shotType,
            motion = CameraMotion(),
            isBroll = isBroll,
            isPatternInterrupt = interrupt || shot.isPatternInterrupt,
            reason = label,
        )
    }

    private fun replaceVisual(fix: EditFix) {
        val targets: List<Int> = fix.shotIndex?.takeIf { it in shots.indices }?.let { listOf(it) } ?: run {
            val scene = fix.sceneNumber ?: return
            val idx = sceneShotIndices(scene)
            val weak = idx.filter { i ->
                val score = ctx.assetById[shots[i].assetId]?.matchScore ?: -1
                score in 0 until ctx.settings.visionMinScore
            }
            weak.ifEmpty { idx }
        }
        for (i in targets) {
            val shot = shots[i]
            val used = usedAssetIds()
            // Newest first: the orchestrator appends freshly sourced replacements at the end.
            val replacement = ctx.visuals.asReversed().firstOrNull { it.sceneNumber == shot.sceneNumber && it.id !in used && it.id != shot.assetId }
                ?: ctx.visuals.asReversed().firstOrNull { it.id !in used && it.id != shot.assetId && !LocalArtGenerator.isTypography(it) }
                ?: continue
            shots[i] = shot.copy(
                assetId = replacement.id,
                sourceStartMs = 0L,
                shotType = replacement.shotType,
                motion = CameraMotion(),
                reason = reason(fix, "replaced a visual that did not match the narration"),
            )
        }
    }

    // ------------------------------------------------------------------ text, transitions, music

    private fun addTextPop(scene: Int, fix: EditFix) {
        val candidates = EditHeuristics.popCandidates(ctx, scene)
            .filter { it.pop.kind != TextPopKind.CTA || pops.none { p -> p.kind == TextPopKind.CTA } }
            .sortedByDescending { it.priority }
        val pick = candidates.firstOrNull { c ->
            pops.none { p -> c.pop.startMs < p.endMs + 120L && p.startMs < c.pop.endMs + 120L }
        }
        if (pick != null) {
            pops += pick.pop.copy(reason = reason(fix, pick.pop.reason))
            return
        }
        val track = ctx.track(scene) ?: return
        val text = track.scene.onScreenText.ifBlank { track.scene.keyWords.firstOrNull().orEmpty() }
        if (text.isBlank()) return
        val start = track.timing.audioStartMs + 500L
        val end = start + 1_500L
        if (pops.any { start < it.endMs + 120L && it.startMs < end + 120L }) return
        pops += TextPop(text = text, startMs = start, endMs = end, kind = TextPopKind.FACT, sceneNumber = scene, reason = reason(fix, "key fact on screen"))
    }

    private fun simplifyTransitions(fix: EditFix) {
        val sceneFilter = fix.sceneNumber
        for (i in shots.indices) {
            val s = shots[i]
            if (sceneFilter != null && s.sceneNumber != sceneFilter) continue
            if (s.transitionIn != TransitionType.CUT) {
                shots[i] = s.copy(transitionIn = TransitionType.CUT, transitionMs = 0L, reason = appendReason(s.reason, reason(fix, "plain cut instead of an effect")))
            }
        }
    }

    private fun lowerMusic() {
        music = music.map { it.copy(intensity = max(1, it.intensity - MUSIC_STEP)) }.toMutableList()
        if (music.isEmpty()) {
            // Seed one cue so the normalizer picks up the lowered level.
            val first = ctx.tracks.first()
            music += MusicCue(0L, com.autotube.ai.core.model.MusicAction.CHANGE, first.scene.emotion, max(1, first.scene.intensity - MUSIC_STEP), 1_500L, "QC: music lowered")
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun shotIndexAt(t: Long): Int = shots.indexOfFirst { t >= it.startMs && t < it.endMs }

    private fun appendReason(reason: String, extra: String): String = if (reason.isBlank()) extra else "$reason; $extra"

    private companion object {
        const val SHORTEN_FACTOR = 0.7
        const val LENGTHEN_FACTOR = 1.35
        const val MAX_ADDED_PER_SCENE = 2
        const val MIN_PUNCH_SPACING_MS = 4_000L
        const val MUSIC_STEP = 2
    }
}
