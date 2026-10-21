package com.autotube.ai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Long-form (16:9, 1920x1080) or Shorts (9:16, 1080x1920). */
@Serializable
enum class VideoFormat(val label: String, val width: Int, val height: Int) {
    @SerialName("long_form") LONG_FORM("Long-form", 1920, 1080),
    @SerialName("shorts") SHORTS("Shorts", 1080, 1920);

    val isVertical: Boolean get() = this == SHORTS
    val aspect: AspectRatio get() = if (this == SHORTS) AspectRatio.PORTRAIT_9_16 else AspectRatio.LANDSCAPE_16_9
}

@Serializable
enum class AspectRatio(val w: Int, val h: Int, val label: String) {
    @SerialName("16:9") LANDSCAPE_16_9(16, 9, "16:9"),
    @SerialName("9:16") PORTRAIT_9_16(9, 16, "9:16");
}

@Serializable
enum class AppLanguage(val code: String, val label: String, val bcp47: String) {
    @SerialName("en") ENGLISH("en", "English", "en-US"),
    @SerialName("hi") HINDI("hi", "Hindi", "hi-IN");
}

/** Scene emotion. Serialized lowercase so it matches the LLM JSON contract. */
@Serializable
enum class Emotion {
    @SerialName("suspense") SUSPENSE,
    @SerialName("happy") HAPPY,
    @SerialName("sad") SAD,
    @SerialName("exciting") EXCITING,
    @SerialName("calm") CALM,
    @SerialName("dramatic") DRAMATIC,
    @SerialName("inspiring") INSPIRING,
    @SerialName("funny") FUNNY;

    val wireName: String get() = name.lowercase()

    /** Warm grade for happy/inspiring/funny, cool for suspense/dramatic/sad, neutral otherwise. */
    val isWarm: Boolean get() = this == HAPPY || this == INSPIRING || this == FUNNY
    val isCool: Boolean get() = this == SUSPENSE || this == DRAMATIC || this == SAD
    /** Emotions where an editor cuts faster. */
    val isHighEnergy: Boolean get() = this == EXCITING || this == DRAMATIC || this == FUNNY || this == SUSPENSE

    companion object {
        fun parse(value: String?): Emotion =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() } ?: CALM
    }
}

@Serializable
enum class ScenePriority {
    @SerialName("essential") ESSENTIAL,
    @SerialName("optional") OPTIONAL,
}

/** Structural role of a scene: hook (first 5-10 s) -> intro -> main sections -> CTA. */
@Serializable
enum class SceneRole {
    @SerialName("hook") HOOK,
    @SerialName("intro") INTRO,
    @SerialName("main") MAIN,
    @SerialName("cta") CTA,
}

/**
 * Pipeline steps in execution order. [uiGroup] maps each step onto the 7 user-facing stages
 * Research -> Script -> Length check -> Voice -> Music -> Editing -> Upload.
 */
@Serializable
enum class PipelineStep(val title: String, val uiGroup: UiStage) {
    RESEARCH("Topic research", UiStage.RESEARCH),
    SCRIPT("Script writing", UiStage.SCRIPT),
    LENGTH_CHECK("Length check", UiStage.LENGTH_CHECK),
    VOICE("Voiceover", UiStage.VOICE),
    CAPTIONS("Captions", UiStage.VOICE),
    VISUALS("Finding visuals", UiStage.EDITING),
    EDIT_PLAN("AI editor plan", UiStage.EDITING),
    MUSIC("Music & sound design", UiStage.MUSIC),
    RENDER("Rendering video", UiStage.EDITING),
    QUALITY_CHECK("Quality check", UiStage.EDITING),
    SEO("SEO & thumbnail", UiStage.EDITING),
    UPLOAD("Upload", UiStage.UPLOAD),
}

@Serializable
enum class UiStage(val title: String) {
    RESEARCH("Research"),
    SCRIPT("Script"),
    LENGTH_CHECK("Length check"),
    VOICE("Voice"),
    MUSIC("Music"),
    EDITING("Editing"),
    UPLOAD("Upload"),
}

@Serializable
enum class StepStatus { PENDING, RUNNING, DONE, SKIPPED, WAITING, FAILED }

@Serializable
enum class ProjectStatus {
    QUEUED,
    RUNNING,
    /** Review mode: waiting for the user to pick one of the top 5 topics. */
    AWAITING_TOPIC,
    /** Video exported; waiting for the user to approve the upload (review mode) or to upload manually. */
    AWAITING_REVIEW,
    UPLOADING,
    UPLOADED,
    FAILED,
    CANCELLED;

    val isActive: Boolean get() = this == QUEUED || this == RUNNING || this == UPLOADING
}

/** Where a visual came from. */
@Serializable
enum class VisualSource(val isVideo: Boolean, val label: String) {
    PEXELS_VIDEO(true, "Pexels"),
    PIXABAY_VIDEO(true, "Pixabay"),
    PEXELS_PHOTO(false, "Pexels photo"),
    PIXABAY_PHOTO(false, "Pixabay photo"),
    AI_VIDEO(true, "AI video"),
    AI_IMAGE(false, "AI image"),
    GALLERY_VIDEO(true, "Your gallery"),
    GALLERY_PHOTO(false, "Your gallery"),
    /** Procedurally drawn on the device (fully offline). */
    LOCAL_GENERATED(false, "On-device art"),
}

@Serializable
enum class ShotType {
    @SerialName("wide") WIDE,
    @SerialName("medium") MEDIUM,
    @SerialName("close_up") CLOSE_UP,
}

@Serializable
enum class Orientation { LANDSCAPE, PORTRAIT }
