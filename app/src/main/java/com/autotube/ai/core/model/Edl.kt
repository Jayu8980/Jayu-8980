package com.autotube.ai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reference editing styles the AI editor imitates. */
@Serializable
enum class EditStyle(val label: String) {
    @SerialName("documentary") DOCUMENTARY("Storytelling / documentary"),
    @SerialName("fast_shorts") FAST_SHORTS("Fast-paced Shorts"),
    @SerialName("calm_educational") CALM_EDUCATIONAL("Calm educational"),
}

@Serializable
enum class TransitionType {
    /** Plain hard cut: the default, like real editors. */
    @SerialName("cut") CUT,
    @SerialName("fade") FADE,
    @SerialName("dip_to_black") DIP_TO_BLACK,
    @SerialName("zoom") ZOOM,
    @SerialName("whip") WHIP,
}

/** J-cut: next scene's audio starts before its picture. L-cut: previous audio continues over the new picture. */
@Serializable
enum class CutType {
    @SerialName("straight") STRAIGHT,
    @SerialName("j_cut") J_CUT,
    @SerialName("l_cut") L_CUT,
}

@Serializable
enum class Easing {
    @SerialName("ease_in_out") EASE_IN_OUT,
    @SerialName("ease_out") EASE_OUT,
    @SerialName("ease_in") EASE_IN,
}

@Serializable
enum class MotionType {
    @SerialName("static") STATIC,
    @SerialName("ken_burns_in") KEN_BURNS_IN,
    @SerialName("ken_burns_out") KEN_BURNS_OUT,
    @SerialName("pan_left") PAN_LEFT,
    @SerialName("pan_right") PAN_RIGHT,
    @SerialName("pan_up") PAN_UP,
    @SerialName("pan_down") PAN_DOWN,
    @SerialName("parallax") PARALLAX,
}

/** Camera move for one shot. Zoom 1.0 = frame-filling crop; values are start/end scale factors. */
@Serializable
data class CameraMotion(
    val type: MotionType = MotionType.STATIC,
    val zoomStart: Float = 1.0f,
    val zoomEnd: Float = 1.0f,
    /** Normalized pan offset (-1..1 of the available margin) at start and end. */
    val panStartX: Float = 0f,
    val panStartY: Float = 0f,
    val panEndX: Float = 0f,
    val panEndY: Float = 0f,
    val easing: Easing = Easing.EASE_IN_OUT,
    /** Handheld shake amplitude 0..1 (0 = tripod). Kept very light, ~0.1-0.3. */
    val shake: Float = 0f,
    /** Seed so the shake path is deterministic per shot but different between shots. */
    val shakeSeed: Int = 0,
)

/** Subtle punch-in (105-115 %) on an important word or reveal, eased in and out. */
@Serializable
data class PunchIn(
    /** Absolute timeline time. */
    val atMs: Long,
    val durationMs: Long = 600,
    val scale: Float = 1.1f,
    val word: String = "",
)

/** One continuous shot on the video timeline. Shots tile the timeline without gaps or overlaps. */
@Serializable
data class Shot(
    val index: Int,
    val sceneNumber: Int,
    /** Absolute timeline position (ms). */
    val startMs: Long,
    val endMs: Long,
    /** [VisualAsset.id] to show. */
    val assetId: String,
    /** For video assets: where to start reading inside the source clip. */
    val sourceStartMs: Long = 0,
    val shotType: ShotType = ShotType.MEDIUM,
    val motion: CameraMotion = CameraMotion(),
    /** Transition into this shot from the previous one. */
    val transitionIn: TransitionType = TransitionType.CUT,
    val transitionMs: Long = 0,
    val cutType: CutType = CutType.STRAIGHT,
    val isBroll: Boolean = false,
    val isPatternInterrupt: Boolean = false,
    val punchIns: List<PunchIn> = emptyList(),
    /** The editor's reason for this decision (shown in logs / review). */
    val reason: String = "",
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
enum class TextPopKind {
    @SerialName("number") NUMBER,
    @SerialName("name") NAME,
    @SerialName("fact") FACT,
    @SerialName("title") TITLE,
    @SerialName("cta") CTA,
}

/** Short pop-up text appearing exactly when the word is spoken; always drawn inside safe zones. */
@Serializable
data class TextPop(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val kind: TextPopKind = TextPopKind.FACT,
    val emoji: String? = null,
    val sceneNumber: Int = 0,
    val reason: String = "",
)

@Serializable
enum class SfxType {
    @SerialName("whoosh") WHOOSH,
    @SerialName("riser") RISER,
    @SerialName("impact") IMPACT,
    @SerialName("pop") POP,
    @SerialName("click") CLICK,
    @SerialName("swoosh_soft") SWOOSH_SOFT,
}

@Serializable
data class SfxCue(
    val type: SfxType,
    /** Absolute time of the sync point (for a riser: where it peaks). */
    val atMs: Long,
    /** Linear gain 0..1 relative to the SFX bus. */
    val gain: Float = 0.5f,
    val reason: String = "",
)

@Serializable
enum class MusicAction {
    /** Change mood: crossfade 1-2 s into a track for [MusicCue.emotion]. */
    @SerialName("change") CHANGE,
    /** Brief drop-out before an important line. */
    @SerialName("drop_out") DROP_OUT,
    @SerialName("resume") RESUME,
    /** Raise music in a gap with no voice. */
    @SerialName("swell") SWELL,
}

@Serializable
data class MusicCue(
    val atMs: Long,
    val action: MusicAction,
    val emotion: Emotion = Emotion.CALM,
    val intensity: Int = 5,
    val durationMs: Long = 0,
    val reason: String = "",
)

/** The AI editor's full plan. Produced by the EditorBrain, consumed by the audio engine and the renderer. */
@Serializable
data class EditDecisionList(
    val style: EditStyle,
    val totalMs: Long,
    val shots: List<Shot>,
    val textPops: List<TextPop> = emptyList(),
    val sfx: List<SfxCue> = emptyList(),
    val music: List<MusicCue> = emptyList(),
    /** Per-scene look; the renderer applies one consistent base grade plus this mood tint. */
    val sceneLooks: Map<Int, Emotion> = emptyMap(),
    val notes: String = "",
)
