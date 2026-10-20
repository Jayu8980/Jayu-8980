package com.autotube.ai.core.model

import kotlinx.serialization.Serializable

/** Secrets kept in EncryptedSharedPreferences. Never serialized into [AppSettings] or logs. */
enum class ApiKey(val label: String, val hint: String) {
    ANTHROPIC("Claude (Anthropic) API key", "sk-ant-..."),
    OPENAI("OpenAI API key", "sk-..."),
    ELEVENLABS("ElevenLabs API key", "xi-..."),
    PEXELS("Pexels API key", "Pexels key"),
    PIXABAY("Pixabay API key", "Pixabay key"),
    YOUTUBE_DATA("YouTube Data API v3 key", "AIza..."),
    REPLICATE("Replicate API token (AI video / images)", "r8_..."),
    CUSTOM_VIDEO("Custom text-to-video API key", "Bearer token"),
}

@Serializable
enum class LlmProviderType(val label: String) {
    CLAUDE("Claude (Anthropic)"),
    OPENAI("OpenAI"),
    /** MediaPipe LLM Inference with a Gemma .task model file on the phone. */
    ON_DEVICE("On-device LLM (Gemma, offline)"),
    /** Deterministic templates; no model at all. */
    OFFLINE_TEMPLATES("Offline templates (no AI)"),
}

@Serializable
enum class TtsProviderType(val label: String) {
    ELEVENLABS("ElevenLabs"),
    OPENAI("OpenAI TTS"),
    /** android.speech.tts.TextToSpeech, works offline. */
    ON_DEVICE("On-device voice (offline)"),
}

@Serializable
enum class SttProviderType(val label: String) {
    WHISPER("OpenAI Whisper (word timestamps)"),
    /** Word timings from the TTS engine itself / text alignment, fully offline. */
    ON_DEVICE("On-device timing (offline)"),
}

@Serializable
enum class TextToVideoProviderType(val label: String) {
    NONE("Off"),
    REPLICATE("Replicate"),
    CUSTOM("Custom endpoint"),
}

@Serializable
enum class ImageGenProviderType(val label: String) {
    NONE("Off (use on-device art)"),
    OPENAI("OpenAI Images"),
    REPLICATE("Replicate"),
}

@Serializable
enum class MusicStyle(val label: String) {
    AUTO("Auto (match the mood)"),
    CINEMATIC("Cinematic"),
    LOFI("Lo-fi"),
    ELECTRONIC("Electronic"),
    ACOUSTIC("Acoustic"),
    AMBIENT("Ambient"),
}

@Serializable
enum class UploadMode(val label: String) {
    PUBLISH_NOW("Publish now"),
    SCHEDULE("Schedule"),
    PRIVATE_REVIEW("Private (review on YouTube)"),
}

@Serializable
enum class PrivacyStatus(val wire: String) { PRIVATE("private"), UNLISTED("unlisted"), PUBLIC("public") }

@Serializable
enum class CaptionStyle(val label: String) {
    BOLD_POP("Bold pop (word by word)"),
    KARAOKE("Karaoke highlight"),
    MINIMAL("Minimal"),
    OFF("Off"),
}

/**
 * Non-secret user settings, persisted as JSON. Every field has a default so older JSON keeps decoding.
 * "Local / offline" works out of the box: with no API keys every step falls back to an on-device path.
 */
@Serializable
data class AppSettings(
    // Defaults for the Home screen
    val defaultFormat: VideoFormat = VideoFormat.SHORTS,
    val defaultLanguage: AppLanguage = AppLanguage.ENGLISH,
    val defaultLongFormSec: Int = 480,
    val defaultShortsSec: Int = 60,
    val reviewMode: Boolean = true,
    /** Run everything on the phone even if API keys are present. */
    val forceOffline: Boolean = false,

    // Script / research
    val llmProvider: LlmProviderType = LlmProviderType.CLAUDE,
    val claudeModel: String = "claude-opus-5-5",
    val openAiModel: String = "gpt-4o",
    /** Absolute path of an imported MediaPipe .task model for [LlmProviderType.ON_DEVICE]. */
    val onDeviceModelPath: String? = null,
    val regionCode: String = "US",

    // Voice
    val ttsProvider: TtsProviderType = TtsProviderType.OPENAI,
    val openAiTtsModel: String = "gpt-4o-mini-tts",
    val openAiVoice: String = "onyx",
    val elevenLabsVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    val elevenLabsModel: String = "eleven_multilingual_v2",
    /** Voice name from android.speech.tts.Voice#getName, or null for the engine default. */
    val onDeviceVoiceName: String? = null,
    val sttProvider: SttProviderType = SttProviderType.WHISPER,

    // Music and sound
    val musicStyle: MusicStyle = MusicStyle.AUTO,
    val musicVolume: Float = 0.8f,
    val soundEffects: Boolean = true,
    val ambientRoomTone: Boolean = true,
    val targetLufs: Float = -14f,

    // Visuals
    val useStockFootage: Boolean = true,
    val textToVideoProvider: TextToVideoProviderType = TextToVideoProviderType.NONE,
    val replicateVideoModel: String = "minimax/video-01",
    val customVideoEndpoint: String = "",
    val imageGenProvider: ImageGenProviderType = ImageGenProviderType.NONE,
    val openAiImageModel: String = "gpt-image-1",
    val replicateImageModel: String = "black-forest-labs/flux-schnell",
    val useGalleryMedia: Boolean = false,
    val visionCheck: Boolean = true,
    val visionMinScore: Int = 6,

    // Look
    val colorGrading: Boolean = true,
    val filmGrain: Boolean = true,
    val vignette: Boolean = true,
    val handheldShake: Boolean = true,
    val captionStyle: CaptionStyle = CaptionStyle.BOLD_POP,
    val emojiInCaptions: Boolean = true,

    // Quality
    val qualityCheck: Boolean = true,
    val maxQcPasses: Int = 2,
    val minHumanFeelScore: Int = 7,

    // Device limits
    val wifiOnly: Boolean = false,
    val minFreeStorageMb: Int = 1500,
    val deleteTempAfterExport: Boolean = true,

    // Upload
    val defaultUploadMode: UploadMode = UploadMode.PRIVATE_REVIEW,
    val autoUploadWhenReviewOff: Boolean = false,
    val googleAccountEmail: String? = null,
    val madeForKids: Boolean = false,
    val youtubeCategoryId: String = "22",
)
