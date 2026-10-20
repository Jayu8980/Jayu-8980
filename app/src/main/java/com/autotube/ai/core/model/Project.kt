package com.autotube.ai.core.model

import kotlinx.serialization.Serializable

@Serializable
data class StepState(
    val status: StepStatus = StepStatus.PENDING,
    val progress: Float = 0f,
    val message: String = "",
    val attempts: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)

/** Readable error shown on the error screen. [technical] goes to the log / "details" section. */
@Serializable
data class PipelineError(
    val step: PipelineStep?,
    val title: String,
    val message: String,
    val technical: String = "",
    val retryable: Boolean = true,
    val at: Long = System.currentTimeMillis(),
)

/** Everything produced along the way, so steps can be resumed, scenes regenerated and edits re-rendered. */
@Serializable
data class ProjectArtifacts(
    val sceneAudios: List<SceneAudio> = emptyList(),
    val voiceTimeline: VoiceTimeline? = null,
    val captions: CaptionTrack? = null,
    val visuals: List<VisualAsset> = emptyList(),
    val edl: EditDecisionList? = null,
    val audioMix: AudioMixResult? = null,
    val render: RenderResult? = null,
    val renderPasses: Int = 0,
    /** Speed applied to TTS by the length manager (0.9..1.1). */
    val voiceSpeed: Float = 1f,
    val lengthAttempts: Int = 0,
)

@Serializable
data class Project(
    val id: Long = 0,
    val request: VideoRequest,
    val status: ProjectStatus = ProjectStatus.QUEUED,
    val currentStep: PipelineStep? = null,
    val steps: Map<PipelineStep, StepState> = emptyMap(),
    /** 0..1 over the whole pipeline. */
    val overallProgress: Float = 0f,
    val statusMessage: String = "",
    val topicCandidates: List<TopicCandidate> = emptyList(),
    val chosenTopic: TopicCandidate? = null,
    val script: Script? = null,
    val artifacts: ProjectArtifacts = ProjectArtifacts(),
    val seo: SeoPackage? = null,
    val qcReports: List<QcReport> = emptyList(),
    /** Final MP4 inside app storage. */
    val outputVideoPath: String? = null,
    /** content:// Uri of the copy saved to the gallery. */
    val galleryUri: String? = null,
    val thumbnailPath: String? = null,
    /** Latest image to show on the Progress screen. */
    val previewImagePath: String? = null,
    val uploadOptions: UploadOptions? = null,
    val uploadProgress: Float = 0f,
    val youtubeVideoId: String? = null,
    val error: PipelineError? = null,
    val warnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val displayTitle: String
        get() = seo?.title ?: script?.title ?: chosenTopic?.title ?: request.niche

    fun step(step: PipelineStep): StepState = steps[step] ?: StepState()
}
