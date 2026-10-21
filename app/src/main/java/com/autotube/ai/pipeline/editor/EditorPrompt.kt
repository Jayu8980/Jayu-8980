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
import com.autotube.ai.core.model.Shot
import com.autotube.ai.core.model.ShotType
import com.autotube.ai.core.model.TextPop
import com.autotube.ai.core.model.TextPopKind
import com.autotube.ai.core.model.TransitionType
import kotlinx.serialization.Serializable

// Lenient mirrors of core/model/Edl.kt (same field names, everything optional) so a slightly
// imperfect LLM answer still decodes; EdlNormalizer repairs the rest.

@Serializable
internal data class LlmEdlDto(
    val style: EditStyle? = null,
    val shots: List<LlmShotDto> = emptyList(),
    val textPops: List<LlmPopDto> = emptyList(),
    val music: List<LlmMusicDto> = emptyList(),
    val notes: String = "",
)

@Serializable
internal data class LlmShotDto(
    val sceneNumber: Int = 0,
    val startMs: Long = -1,
    val endMs: Long = -1,
    val assetId: String = "",
    val sourceStartMs: Long = 0,
    val shotType: ShotType = ShotType.MEDIUM,
    val motion: LlmMotionDto? = null,
    val transitionIn: TransitionType = TransitionType.CUT,
    val transitionMs: Long = 0,
    val cutType: CutType = CutType.STRAIGHT,
    val isBroll: Boolean = false,
    val punchIns: List<LlmPunchDto> = emptyList(),
    val reason: String = "",
)

@Serializable
internal data class LlmMotionDto(
    val type: MotionType = MotionType.STATIC,
    val zoomStart: Float = 1f,
    val zoomEnd: Float = 1f,
    val panStartX: Float = 0f,
    val panStartY: Float = 0f,
    val panEndX: Float = 0f,
    val panEndY: Float = 0f,
    val easing: Easing = Easing.EASE_IN_OUT,
    val shake: Float = 0f,
)

@Serializable
internal data class LlmPunchDto(
    val atMs: Long = -1,
    val durationMs: Long = 600,
    val scale: Float = 1.1f,
    val word: String = "",
)

@Serializable
internal data class LlmPopDto(
    val text: String = "",
    val startMs: Long = -1,
    val endMs: Long = -1,
    val kind: TextPopKind = TextPopKind.FACT,
    val emoji: String? = null,
    val sceneNumber: Int = 0,
    val reason: String = "",
)

@Serializable
internal data class LlmMusicDto(
    val atMs: Long = -1,
    val action: MusicAction? = null,
    val durationMs: Long = 0,
    val intensity: Int = 5,
    val reason: String = "",
)

/** Builds the "professional YouTube editor" prompt and converts the answer into a draft EDL. */
internal object EditorPrompt {

    val SYSTEM: String = """
        You are a senior professional YouTube video editor. You cut voice-over videos so they feel
        hand-edited by a human: varied shot lengths, cuts on breaths and word boundaries, J/L-cuts
        between scenes, restrained transitions, motivated punch-ins and well-timed text pops.
        You return ONLY one JSON object, no prose, no markdown fences.
    """.trimIndent()

    fun styleRules(style: EditStyle, isShorts: Boolean): String = when (style) {
        EditStyle.FAST_SHORTS ->
            "FAST SHORTS: shots mostly 2-3.5 s (1.5 s allowed in high-intensity moments, max 4 s); " +
                "pattern interrupt every 5-10 s; punchy text pops; whip/zoom only at section changes."
        EditStyle.DOCUMENTARY ->
            "STORYTELLING / DOCUMENTARY: shots 3.5-6 s, longer on emotional lines, faster in tense parts; " +
                "wide establishing shot when a scene opens, then medium, then close-up details; " +
                "dip-to-black or fade only at chapter changes; pattern interrupt every " +
                (if (isShorts) "5-10 s" else "20-40 s") + "."
        EditStyle.CALM_EDUCATIONAL ->
            "CALM EDUCATIONAL: shots 4.5-6 s, gentle Ken Burns on images, mostly static video, " +
                "text pops for definitions and numbers, soft fades only at section changes; pattern interrupt every " +
                (if (isShorts) "5-10 s" else "20-40 s") + "."
    }

    fun user(ctx: EditContext): String = buildString {
        val format = ctx.request.format
        appendLine("VIDEO: format=${if (ctx.isShorts) "shorts" else "long_form"} (${format.width}x${format.height}), language=${ctx.script.language.code}, totalMs=${ctx.totalMs}, style=${styleWire(ctx.style)}")
        appendLine("TITLE: ${ctx.script.title}")
        appendLine("STYLE REFERENCE: ${styleRules(ctx.style, ctx.isShorts)}")
        appendLine()
        appendLine("SCENES (times are absolute ms on the final timeline):")
        for (track in ctx.tracks) {
            val s = track.scene
            append("#${s.sceneNumber} role=${s.role.name.lowercase()} emotion=${s.emotion.wireName} intensity=${s.intensity}")
            append(" audio=${track.timing.audioStartMs}-${track.timing.audioEndMs} gapAfter=${track.timing.gapAfterMs}")
            if (s.sectionTitle.isNotBlank()) append(" section=\"${s.sectionTitle}\"")
            if (s.keyWords.isNotEmpty()) append(" key_words=[${s.keyWords.joinToString(", ")}]")
            if (s.onScreenText.isNotBlank()) append(" on_screen_text=\"${s.onScreenText}\"")
            appendLine()
            if (track.words.isNotEmpty()) {
                appendLine("  words: " + track.words.joinToString(" ") { "${it.text}@${it.startMs}" })
            }
            val pauses = ctx.pauses.filter { it.first >= track.timing.audioStartMs && it.second <= track.timing.audioEndMs + 50 }
            if (pauses.isNotEmpty()) appendLine("  pauses: " + pauses.joinToString(", ") { "${it.first}-${it.second}" })
            val assets = track.assets
            if (assets.isEmpty()) {
                appendLine("  assets: (none - reuse unused assets of neighbouring scenes)")
            } else {
                appendLine("  assets: " + assets.joinToString("; ") { a ->
                    buildString {
                        append(a.id)
                        append(if (a.isVideo) " video ${a.durationMs}ms" else " image")
                        append(" ${a.shotType.name.lowercase()}")
                        if (a.matchScore >= 0) append(" score ${a.matchScore}")
                        if (a.query.startsWith("typography")) append(" text-card")
                    }
                })
            }
        }
        appendLine()
        appendLine("RULES:")
        appendLine("- shots tile [0, totalMs) exactly: sorted, no gaps, no overlaps; every assetId must be one listed above.")
        appendLine("- Never cut inside a word: cut points sit in pauses/breaths or between words (use the word times).")
        appendLine("- Vary shot length naturally (never a fixed rhythm); faster in exciting/dramatic/high-intensity parts, longer in calm/emotional parts.")
        appendLine("- Scene boundaries: prefer J-cuts (picture changes 200-600 ms after the new scene's audio starts) or L-cuts (picture changes 200-600 ms before the previous audio ends); set cutType.")
        appendLine("- transitionIn is \"cut\" for 85-95% of shots; fade/dip_to_black/zoom/whip only where the scene role or section changes, never two in a row, transitionMs 250-600.")
        appendLine("- Images get eased motion (ken_burns_in/out, pan_*, parallax) with zoom 1.0-1.18; videos mostly static or a very gentle zoom. Easing ease_in_out or ease_out.")
        appendLine("- Punch-ins 1.05-1.15 on key words and reveals: atMs = word start - 80, durationMs 500-900, at most one per 4 s.")
        appendLine("- Never reuse an image; a video may be reused only for a different, non-overlapping sourceStartMs segment (sourceStartMs + shot duration <= clip duration). Alternate wide/medium/close-up.")
        appendLine("- Pattern interrupt every " + (if (ctx.isShorts) "5-10 s" else "20-40 s") + " (angle change, B-roll, punch-in or text pop).")
        appendLine("- textPops for numbers, names and key facts exactly when spoken, 900-2000 ms, kind number|name|fact|title|cta, emoji rarely; a cta pop in the CTA scene; pops never overlap.")
        appendLine("- music: add {\"action\":\"drop_out\"} 400-700 ms before the single most important line(s) (hook payoff / big reveal). Other sound design is added automatically.")
        appendLine("- Every shot needs a short \"reason\" explaining the editorial decision.")
        appendLine()
        appendLine("Return JSON with exactly these field names (omit fields that keep their default):")
        appendLine(EXAMPLE)
    }

    /** Converts the decoded answer into a draft; null when it is unusable. */
    fun toDraft(dto: LlmEdlDto, ctx: EditContext): EditDecisionList? {
        val shots = dto.shots
            .filter { it.startMs >= 0 && it.endMs > it.startMs && it.startMs < ctx.totalMs }
            .sortedBy { it.startMs }
        if (shots.isEmpty()) return null
        val covered = shots.sumOf { it.endMs.coerceAtMost(ctx.totalMs) - it.startMs }
        if (covered < ctx.totalMs * MIN_COVERAGE) return null
        val known = shots.count { it.assetId in ctx.assetById }
        if (known * 2 < shots.size) return null
        return EditDecisionList(
            style = ctx.style,
            totalMs = ctx.totalMs,
            shots = shots.mapIndexed { i, s ->
                Shot(
                    index = i,
                    sceneNumber = s.sceneNumber,
                    startMs = s.startMs,
                    endMs = s.endMs,
                    assetId = s.assetId,
                    sourceStartMs = s.sourceStartMs.coerceAtLeast(0L),
                    shotType = s.shotType,
                    motion = s.motion?.let { m ->
                        CameraMotion(
                            type = m.type,
                            zoomStart = m.zoomStart,
                            zoomEnd = m.zoomEnd,
                            panStartX = m.panStartX,
                            panStartY = m.panStartY,
                            panEndX = m.panEndX,
                            panEndY = m.panEndY,
                            easing = m.easing,
                            shake = m.shake.coerceIn(0f, 1f),
                        )
                    } ?: CameraMotion(),
                    transitionIn = s.transitionIn,
                    transitionMs = s.transitionMs,
                    cutType = s.cutType,
                    isBroll = s.isBroll,
                    punchIns = s.punchIns.filter { it.atMs >= 0 }.map { p ->
                        PunchIn(atMs = p.atMs, durationMs = p.durationMs, scale = p.scale, word = p.word)
                    },
                    reason = s.reason.take(240),
                )
            },
            textPops = dto.textPops.filter { it.text.isNotBlank() && it.startMs >= 0 }.map { p ->
                TextPop(
                    text = p.text,
                    startMs = p.startMs,
                    endMs = if (p.endMs > p.startMs) p.endMs else p.startMs + 1_400L,
                    kind = p.kind,
                    emoji = p.emoji?.takeIf { it.isNotBlank() },
                    sceneNumber = p.sceneNumber,
                    reason = p.reason.take(240),
                )
            },
            music = dto.music.mapNotNull { m ->
                val action = m.action ?: return@mapNotNull null
                if (m.atMs < 0) return@mapNotNull null
                MusicCue(atMs = m.atMs, action = action, intensity = m.intensity.coerceIn(1, 10), durationMs = m.durationMs.coerceAtLeast(0L), reason = m.reason.take(200))
            },
            notes = dto.notes.take(600),
        )
    }

    private fun styleWire(style: EditStyle): String = when (style) {
        EditStyle.DOCUMENTARY -> "documentary"
        EditStyle.FAST_SHORTS -> "fast_shorts"
        EditStyle.CALM_EDUCATIONAL -> "calm_educational"
    }

    private const val MIN_COVERAGE = 0.6

    private val EXAMPLE = """
        {"style":"fast_shorts","shots":[
         {"sceneNumber":1,"startMs":0,"endMs":2300,"assetId":"pexels_video:123","sourceStartMs":800,"shotType":"wide","motion":{"type":"static"},"transitionIn":"cut","cutType":"straight","punchIns":[{"atMs":1520,"durationMs":700,"scale":1.1,"word":"secret"}],"reason":"Wide establishing shot under the hook question"},
         {"sceneNumber":1,"startMs":2300,"endMs":4650,"assetId":"local:s1_1","shotType":"close_up","motion":{"type":"ken_burns_in","zoomStart":1.0,"zoomEnd":1.12,"easing":"ease_in_out","shake":0.15},"reason":"Close-up detail as the number lands, cut on the breath"},
         {"sceneNumber":2,"startMs":4650,"endMs":7400,"assetId":"pixabay_video:456","sourceStartMs":0,"shotType":"medium","cutType":"j_cut","isBroll":true,"reason":"J-cut: scene 2 voice starts before its picture"}],
         "textPops":[{"text":"90%","startMs":3100,"endMs":4500,"kind":"number","sceneNumber":1,"reason":"Number popped exactly when spoken"}],
         "music":[{"atMs":20500,"action":"drop_out","durationMs":550,"reason":"Silence before the reveal"}],
         "notes":"One-line summary of the edit"}
    """.trimIndent()
}
