package com.autotube.ai.domain

import com.autotube.ai.core.model.AppSettings
import com.autotube.ai.core.model.AudioMixResult
import com.autotube.ai.core.model.CaptionTrack
import com.autotube.ai.core.model.EditDecisionList
import com.autotube.ai.core.model.EditFix
import com.autotube.ai.core.model.QcReport
import com.autotube.ai.core.model.RenderResult
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.SceneAudio
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.SeoPackage
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.core.model.VisualAsset
import com.autotube.ai.core.model.VoiceTimeline
import java.io.File

/** Progress callback, 0..1 within the current step. */
typealias Progress = (Float) -> Unit

/** Step 1. YouTube Data API trends -> LLM ideas; offline: niche-based ideas. Best first, max 5. */
interface TopicResearcher {
    suspend fun research(request: VideoRequest, log: StepLogger, onProgress: Progress = {}): List<TopicCandidate>
}

/** Step 2. JSON scenes: hook (5-10 s) -> intro -> main sections -> CTA. */
interface ScriptWriter {
    suspend fun write(request: VideoRequest, topic: TopicCandidate, log: StepLogger): Script
    /** Rewrites one scene, keeping its role, approximate length and continuity. */
    suspend fun rewriteScene(request: VideoRequest, script: Script, sceneNumber: Int, instruction: String, log: StepLogger): Scene
    /** Shortens to about [targetMs] (drops optional scenes first, never the hook or CTA). */
    suspend fun shorten(request: VideoRequest, script: Script, targetMs: Long, log: StepLogger): Script
    /** Expands to about [targetMs]. */
    suspend fun expand(request: VideoRequest, script: Script, targetMs: Long, log: StepLogger): Script
}

data class LengthFitResult(
    val script: Script,
    val audios: List<SceneAudio>,
    val speed: Float,
    val attempts: Int,
    val finalDurationMs: Long,
    val withinLimits: Boolean,
    val notes: List<String>,
)

/** Step 3. Hard limits: Shorts <= 60 s; long-form within +-5 % of target. Max 3 attempts. */
interface LengthManager {
    /** Words-per-minute based estimate (language aware), including natural gaps. */
    fun estimateDurationMs(script: Script, speed: Float = 1f): Long
    /** Allowed final duration window for the request. */
    fun allowedRangeMs(request: VideoRequest): LongRange
    /** Before voiceover: shorten/expand via the [ScriptWriter] until the estimate fits (max 3 attempts). */
    suspend fun fitBeforeVoice(request: VideoRequest, script: Script, log: StepLogger): Script
    /** After voiceover: measure real duration; tune TTS speed 0.9-1.1, trim pauses, or regenerate scenes. */
    suspend fun fitAfterVoice(projectId: Long, request: VideoRequest, script: Script, audios: List<SceneAudio>, log: StepLogger, onProgress: Progress = {}): LengthFitResult
}

/** Step 4. One WAV per scene with exact durations; emotion-matched delivery; long silences trimmed. */
interface VoiceSynthesizer {
    suspend fun synthesizeScene(projectId: Long, request: VideoRequest, scene: Scene, speed: Float, log: StepLogger): SceneAudio
    suspend fun synthesizeAll(projectId: Long, request: VideoRequest, script: Script, speed: Float, log: StepLogger, onProgress: Progress = {}): List<SceneAudio>
    /** Re-time an existing scene WAV with a pitch-preserving speed change (Sonic). */
    suspend fun changeSpeed(projectId: Long, audio: SceneAudio, newSpeed: Float, log: StepLogger): SceneAudio
}

/** Step 5. Word-level timestamps (Whisper or on-device) and the animated caption track. */
interface CaptionAligner {
    /** Returns [audios] with accurate [SceneAudio.words] filled in. */
    suspend fun align(projectId: Long, request: VideoRequest, script: Script, audios: List<SceneAudio>, log: StepLogger, onProgress: Progress = {}): List<SceneAudio>
    /** Places words on the final timeline, chunks them (1-4 words), flags key words and sparse emojis. */
    fun buildTrack(request: VideoRequest, script: Script, audios: List<SceneAudio>, timeline: VoiceTimeline, settings: AppSettings): CaptionTrack
}

/** Places scene audio back to back with natural, slightly varied gaps. Pure function. */
interface TimelineBuilder {
    fun build(request: VideoRequest, script: Script, audios: List<SceneAudio>): VoiceTimeline
}

/** Step 7B. Stock -> AI video -> AI image -> on-device art; vision check; no repeats; shot variety. */
interface VisualSourcer {
    /**
     * Returns enough distinct assets for every scene (several per scene for multi-shot scenes).
     * [onlyScenes] limits work to those scenes and keeps [existing] for the rest.
     */
    suspend fun source(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        existing: List<VisualAsset>,
        onlyScenes: Set<Int>? = null,
        log: StepLogger,
        onProgress: Progress = {},
    ): List<VisualAsset>

    /** Finds a different asset for [scene]; never returns an id in [excludeIds]. */
    suspend fun replace(projectId: Long, request: VideoRequest, scene: Scene, excludeIds: Set<String>, log: StepLogger): VisualAsset?
}

/** Step 7A/C/D. LLM acts as a pro editor and returns an EDL; heuristics validate/repair or replace it. */
interface EditorBrain {
    suspend fun plan(
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        captions: CaptionTrack,
        visuals: List<VisualAsset>,
        log: StepLogger,
    ): EditDecisionList

    /** Applies QC fixes deterministically (may use [visuals] for B-roll / replacements). */
    fun applyFixes(edl: EditDecisionList, fixes: List<EditFix>, script: Script, visuals: List<VisualAsset>, timeline: VoiceTimeline): EditDecisionList
}

/** Step 6/7G. Voice + situation-based music + SFX + ambience -> one loudness-normalized WAV (-14 LUFS). */
interface AudioEngine {
    suspend fun mix(
        projectId: Long,
        request: VideoRequest,
        script: Script,
        timeline: VoiceTimeline,
        audios: List<SceneAudio>,
        edl: EditDecisionList,
        settings: AppSettings,
        log: StepLogger,
        onProgress: Progress = {},
    ): AudioMixResult
}

/** Step 7J. Media3 Transformer render, 1080p H.264/AAC, hardware encoder. */
interface VideoRenderer {
    suspend fun render(
        projectId: Long,
        request: VideoRequest,
        edl: EditDecisionList,
        visuals: List<VisualAsset>,
        captions: CaptionTrack,
        audioMix: AudioMixResult,
        settings: AppSettings,
        outFile: File,
        log: StepLogger,
        onProgress: Progress = {},
    ): RenderResult
}

/** Step 7I. Automatic review of the export + LLM "human feel"/pacing rating with fixes. */
interface QualityChecker {
    suspend fun check(
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
        onProgress: Progress = {},
    ): QcReport
}

/** Step 8. Title, description with chapter timestamps, 15 tags, hashtags. */
interface SeoGenerator {
    suspend fun generate(request: VideoRequest, script: Script, timeline: VoiceTimeline, log: StepLogger): SeoPackage
}

/** Step 8. Canvas thumbnail (1280x720 or 1080x1920) with bold text over a scene image. */
interface ThumbnailMaker {
    suspend fun make(projectId: Long, request: VideoRequest, script: Script, seo: SeoPackage, visuals: List<VisualAsset>, log: StepLogger): File
}

/** 16-bit PCM audio in memory as interleaved floats in -1..1. */
class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
) {
    val frames: Int get() = if (channels == 0) 0 else samples.size / channels
    val durationMs: Long get() = if (sampleRate == 0) 0 else frames * 1000L / sampleRate
}

/** Decoding/encoding helpers shared by the voice, music and QC code (MediaExtractor + MediaCodec). */
interface AudioIO {
    /** Decodes any supported file (mp3/m4a/wav/ogg) and resamples to [sampleRate]/[channels]. */
    suspend fun decode(file: File, sampleRate: Int = 48_000, channels: Int = 1): PcmAudio
    /** Decodes an asset under app/src/main/assets. */
    suspend fun decodeAsset(assetPath: String, sampleRate: Int = 48_000, channels: Int = 2): PcmAudio
    /** Raw little-endian 16-bit PCM bytes -> PcmAudio (no resample). */
    fun fromPcm16(bytes: ByteArray, sampleRate: Int, channels: Int): PcmAudio
    suspend fun writeWav(audio: PcmAudio, outFile: File): File
    suspend fun durationMs(file: File): Long
    /** Pitch-preserving time stretch through Media3's SonicAudioProcessor. */
    fun timeStretch(audio: PcmAudio, speed: Float): PcmAudio
}
