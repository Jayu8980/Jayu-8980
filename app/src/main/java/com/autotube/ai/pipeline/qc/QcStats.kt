package com.autotube.ai.pipeline.qc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Numbers the QC collects; also sent to the LLM reviewer as JSON. */
@Serializable
internal data class QcStats(
    val format: String,
    val style: String,
    @SerialName("requested_sec") val requestedSec: Int,
    @SerialName("rendered_ms") val renderedMs: Long,
    val shots: Int,
    @SerialName("mean_shot_sec") val meanShotSec: Double,
    @SerialName("stdev_shot_sec") val stdevShotSec: Double,
    @SerialName("min_shot_sec") val minShotSec: Double,
    @SerialName("max_shot_sec") val maxShotSec: Double,
    @SerialName("longest_gap_without_interrupt_sec") val longestGapSec: Double,
    @SerialName("interrupt_rule_sec") val interruptRuleSec: Double,
    @SerialName("cuts_inside_words") val cutsInsideWords: Int,
    @SerialName("non_cut_transitions") val nonCutTransitions: Int,
    @SerialName("text_pops") val textPops: Int,
    @SerialName("punch_ins") val punchIns: Int,
    @SerialName("broll_shots") val brollShots: Int,
    @SerialName("integrated_lufs") val integratedLufs: Float,
    @SerialName("true_peak_dbfs") val truePeakDbfs: Float,
    @SerialName("black_frame_runs") val blackFrameRuns: Int,
    @SerialName("caption_style") val captionStyle: String,
)

/** Compact per-shot line for the reviewer. */
@Serializable
internal data class QcShotSummary(
    @SerialName("shot_index") val shotIndex: Int,
    @SerialName("scene_number") val sceneNumber: Int,
    @SerialName("start_sec") val startSec: Double,
    @SerialName("dur_sec") val durSec: Double,
    val visual: String,
    val motion: String,
    val transition: String,
    val broll: Boolean = false,
    @SerialName("pattern_interrupt") val patternInterrupt: Boolean = false,
    @SerialName("punch_ins") val punchIns: Int = 0,
    val mood: String? = null,
)

@Serializable
internal data class QcReviewRequest(
    val stats: QcStats,
    val shots: List<QcShotSummary>,
    val issues: List<String>,
    @SerialName("text_pops") val textPops: List<String>,
    @SerialName("scene_narration") val sceneNarration: List<String>,
)

@Serializable
internal data class QcReviewFix(
    val action: String = "",
    @SerialName("scene_number") val sceneNumber: Int? = null,
    @SerialName("shot_index") val shotIndex: Int? = null,
    val reason: String = "",
)

@Serializable
internal data class QcReviewReply(
    @SerialName("human_feel") val humanFeel: Int = 0,
    val pacing: Int = 0,
    val notes: String = "",
    val fixes: List<QcReviewFix> = emptyList(),
)
