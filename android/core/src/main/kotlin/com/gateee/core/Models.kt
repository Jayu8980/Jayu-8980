package com.gateee.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One JSON parser for every bank file; unknown keys are ignored so the bank format can grow. */
val GateJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

enum class QType { MCQ, MSQ, NAT }

@Serializable
data class AnswerRange(val min: Double, val max: Double)

@Serializable
data class Verification(val expression: String = "", val variables: Map<String, Double> = emptyMap())

@Serializable
data class SolverVote(val agent: String = "", val model: String = "", val answer: String? = null, val agrees: Boolean = false)

@Serializable
data class VerificationReport(
    val method: String = "",
    val support: String = "",
    val setter: String = "",
    @SerialName("setter_model") val setterModel: String = "",
    @SerialName("candidates_considered") val candidatesConsidered: Int = 0,
    @SerialName("solver_votes") val solverVotes: List<SolverVote> = emptyList(),
    @SerialName("code_check") val codeCheck: String = "",
    val moderator: String = "",
)

/**
 * One drawing primitive of a figure's display list (made by backend/src/figures/layout.js):
 * path (p = x0,y0,x1,y1,...; close = polygon), circle (c = centre, r), text (p = x, vertical
 * centre; t, a = "s"|"m"|"e" anchor, z = size, b = bold). s/f are colour tokens such as "fg", "s0".
 */
@Serializable
data class RenderItem(
    val k: String,
    val p: List<Double> = emptyList(),
    val c: List<Double> = emptyList(),
    val r: Double = 0.0,
    val s: String = "fg",
    val w: Double = 1.5,
    val d: Int = 0,
    val f: String? = null,
    val close: Int = 0,
    val t: String = "",
    val a: String = "m",
    val z: Double = 12.0,
    val b: Int = 0,
)

@Serializable
data class FigureRender(val w: Double, val h: Double, val items: List<RenderItem> = emptyList())

/** Figure attached to a question: circuit, waveform/graph, phasor, Bode, pole-zero, block diagram or chart. */
@Serializable
data class Figure(
    val type: String,
    val title: String = "",
    val caption: String = "",
    val description: String = "",
    val render: FigureRender? = null,
)

@Serializable
data class Question(
    val id: String,
    val qno: Int = 0,
    val section: String = "Technical",
    @SerialName("subject_id") val subjectId: String = "",
    val subject: String = "",
    @SerialName("topic_id") val topicId: String = "",
    val topic: String = "",
    val subtopic: String = "",
    val type: QType = QType.MCQ,
    val marks: Int = 1,
    @SerialName("negative_marks") val negativeMarks: Double = 0.0,
    @SerialName("cognitive_level") val cognitiveLevel: String = "",
    val difficulty: String = "Medium",
    val question: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    @SerialName("answer_range") val answerRange: AnswerRange? = null,
    val solution: String = "",
    val concept: String = "",
    @SerialName("common_trap") val commonTrap: String = "",
    @SerialName("distractor_logic") val distractorLogic: String = "",
    @SerialName("related_pyq_year") val relatedPyqYear: String = "",
    @SerialName("predicted_importance") val predictedImportance: String = "Medium",
    @SerialName("time_estimate_sec") val timeEstimateSec: Int = 90,
    val verification: Verification? = null,
    @SerialName("verification_report") val verificationReport: VerificationReport? = null,
    val figure: Figure? = null,
    val source: String = "",
    val year: Int? = null,
)

@Serializable
data class QuestionBank(val version: Int = 1, val description: String = "", val questions: List<Question> = emptyList())

@Serializable
data class BlueprintSlot(
    val qno: Int,
    val section: String = "",
    @SerialName("subject_id") val subjectId: String = "",
    @SerialName("topic_id") val topicId: String = "",
    val subtopic: String = "",
    val type: QType = QType.MCQ,
    val marks: Int = 1,
    @SerialName("cognitive_level") val cognitiveLevel: String = "",
    val difficulty: String = "",
    val figure: String? = null,
)

@Serializable
data class PaperReport(
    @SerialName("total_marks") val totalMarks: Int = 0,
    val figures: Int = 0,
    @SerialName("marks_by_subject") val marksBySubject: Map<String, Int> = emptyMap(),
    @SerialName("type_mix") val typeMix: Map<String, Int> = emptyMap(),
    @SerialName("difficulty_mix") val difficultyMix: Map<String, Int> = emptyMap(),
    @SerialName("expected_topper_score") val expectedTopperScore: Double = 0.0,
    @SerialName("expected_average_score") val expectedAverageScore: Double = 0.0,
    @SerialName("expected_cutoff") val expectedCutoff: Double = 0.0,
    @SerialName("balance_notes") val balanceNotes: String = "",
    @SerialName("pattern_issues") val patternIssues: List<String> = emptyList(),
)

@Serializable
data class VerificationSummary(
    val unanimous: Int = 0,
    val adjudicated: Int = 0,
    @SerialName("corrected_by_adjudicator") val correctedByAdjudicator: Int = 0,
    @SerialName("code_checked") val codeChecked: Int = 0,
    @SerialName("rejected_drafts") val rejectedDrafts: Int = 0,
)

@Serializable
data class Paper(
    val id: String,
    val title: String = "",
    val mode: String = "",
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("duration_min") val durationMin: Int = 180,
    val status: String = "complete",
    val blueprint: List<BlueprintSlot> = emptyList(),
    val questions: List<Question> = emptyList(),
    @SerialName("paper_report") val paperReport: PaperReport? = null,
    @SerialName("verification_summary") val verificationSummary: VerificationSummary? = null,
    val agents: JsonElement? = null,
)

// ---------------- syllabus, PYQ bank, series, formulas ----------------

@Serializable
data class Topic(val id: String, val name: String, val subtopics: List<String> = emptyList())

@Serializable
data class Subject(val id: String, val name: String, val section: String = "Technical", val topics: List<Topic> = emptyList())

@Serializable
data class Syllabus(val exam: String = "", val subjects: List<Subject> = emptyList()) {
    fun subjectName(id: String) = subjects.firstOrNull { it.id == id }?.name ?: id
    fun topicName(id: String) = subjects.asSequence().flatMap { it.topics }.firstOrNull { it.id == id }?.name ?: id
    val allTopics: List<Topic> get() = subjects.flatMap { it.topics }
}

@Serializable
data class PyqRecord(
    val id: String,
    val year: Int,
    @SerialName("subject_id") val subjectId: String,
    @SerialName("topic_id") val topicId: String,
    val subtopic: String = "",
    val type: QType = QType.MCQ,
    val marks: Int = 1,
    val difficulty: String = "Medium",
    val concept: String = "",
    // full text is optional: tags alone drive the analysis, text enables PYQ practice
    val question: String? = null,
    val options: List<String> = emptyList(),
    val answer: String? = null,
    @SerialName("answer_range") val answerRange: AnswerRange? = null,
    val solution: String? = null,
)

@Serializable
data class PyqBank(
    @SerialName("is_demo") val isDemo: Boolean = false,
    val description: String = "",
    val records: List<PyqRecord> = emptyList(),
)

@Serializable
data class GenerateRequest(
    val mode: String,
    val subjects: List<String>? = null,
    val topics: List<String>? = null,
    @SerialName("question_count") val questionCount: Int? = null,
    val difficulty: String? = null,
    @SerialName("type_mix") val typeMix: Map<String, Double>? = null,
    @SerialName("weak_topics") val weakTopics: List<String>? = null,
    @SerialName("avoid_summaries") val avoidSummaries: List<String>? = null,
    val title: String? = null,
    val seed: Int? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    /** share of questions with figures: none | some | many */
    val figures: String? = null,
)

@Serializable
data class SeriesTest(
    val id: String,
    val kind: String,
    val title: String,
    @SerialName("subject_id") val subjectId: String? = null,
    val questions: Int,
    val marks: Int,
    @SerialName("duration_min") val durationMin: Int,
    val request: GenerateRequest,
)

@Serializable
data class MockSeries(val tests: List<SeriesTest> = emptyList())

@Serializable
data class FormulaSection(val heading: String, val formulas: List<String>)

@Serializable
data class FormulaSheet(@SerialName("subject_id") val subjectId: String, val title: String, val sections: List<FormulaSection>)

@Serializable
data class FormulaSheets(val sheets: List<FormulaSheet> = emptyList())
