package com.gateee.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** GATE palette states. ANSWERED_MARKED is evaluated, as in the real exam. */
enum class QStatus { NOT_VISITED, NOT_ANSWERED, ANSWERED, MARKED, ANSWERED_MARKED }

enum class ErrorType { SILLY_MISTAKE, CONCEPT_GAP, TIME_PRESSURE }

data class ResponseInput(
    val question: Question,
    val answer: String?,
    val status: QStatus,
    val timeSpentSec: Int,
    /** seconds since test start when the final answer was saved */
    val answeredAtSec: Int?,
)

data class Evaluated(
    val questionId: String,
    val attempted: Boolean,
    val correct: Boolean,
    val marksAwarded: Double,
    val errorType: ErrorType?,
)

object Answers {
    fun normalizeLetters(raw: String?): List<String> =
        raw.orEmpty().uppercase().split(',', ';', ' ').map { it.trim() }.filter { it in listOf("A", "B", "C", "D") }.distinct().sorted()

    fun parseNumber(raw: String?): Double? = raw?.trim()?.replace(",", "")?.toDoubleOrNull()

    fun isAttempted(r: ResponseInput) =
        (r.status == QStatus.ANSWERED || r.status == QStatus.ANSWERED_MARKED) && !r.answer.isNullOrBlank()

    fun isCorrect(q: Question, answer: String?): Boolean = when (q.type) {
        QType.MCQ -> normalizeLetters(answer).singleOrNull() == normalizeLetters(q.answer).singleOrNull() && normalizeLetters(answer).size == 1
        QType.MSQ -> normalizeLetters(answer).isNotEmpty() && normalizeLetters(answer) == normalizeLetters(q.answer)
        QType.NAT -> {
            val v = parseNumber(answer)
            val range = q.answerRange ?: parseNumber(q.answer)?.let { AnswerRange(it, it) }
            v != null && range != null && v >= range.min - 1e-9 && v <= range.max + 1e-9
        }
    }
}

object Scoring {
    /** GATE marking: MCQ wrong -> -1/3 or -2/3; MSQ and NAT have no negative marks and no partial credit. */
    fun marksFor(q: Question, answer: String?, attempted: Boolean): Double {
        if (!attempted) return 0.0
        if (Answers.isCorrect(q, answer)) return q.marks.toDouble()
        return if (q.type == QType.MCQ) -(if (q.marks == 1) 1.0 / 3 else 2.0 / 3) else 0.0
    }

    /**
     * Classify a wrong answer:
     *  - TIME_PRESSURE: answered in the last 10% of the test, or in under a quarter of the expected time on a 2-mark question
     *  - SILLY_MISTAKE: the topic was strong before the test, or the answer was numerically close / one option off
     *  - CONCEPT_GAP: everything else
     */
    fun classify(r: ResponseInput, durationSec: Int, priorStrength: Double?): ErrorType {
        val q = r.question
        val late = r.answeredAtSec != null && r.answeredAtSec >= durationSec * 0.9
        val rushed = q.marks == 2 && r.timeSpentSec < q.timeEstimateSec * 0.25
        if (late || rushed) return ErrorType.TIME_PRESSURE
        val near = when (q.type) {
            QType.NAT -> {
                val v = Answers.parseNumber(r.answer)
                val ref = Answers.parseNumber(q.answer)
                v != null && ref != null && ref != 0.0 && abs(v - ref) / abs(ref) <= 0.1
            }
            QType.MSQ -> {
                val a = Answers.normalizeLetters(r.answer).toSet()
                val k = Answers.normalizeLetters(q.answer).toSet()
                (a - k).size + (k - a).size == 1
            }
            QType.MCQ -> false
        }
        if (near || (priorStrength ?: 0.0) >= 0.7) return ErrorType.SILLY_MISTAKE
        return ErrorType.CONCEPT_GAP
    }

    fun evaluate(responses: List<ResponseInput>, durationSec: Int, strengths: Map<String, Double> = emptyMap()): List<Evaluated> =
        responses.map { r ->
            val attempted = Answers.isAttempted(r)
            val correct = attempted && Answers.isCorrect(r.question, r.answer)
            Evaluated(
                questionId = r.question.id,
                attempted = attempted,
                correct = correct,
                marksAwarded = marksFor(r.question, r.answer, attempted),
                errorType = if (attempted && !correct) classify(r, durationSec, strengths[r.question.topicId]) else null,
            )
        }
}

data class GroupStat(val key: String, val attempted: Int, val correct: Int, val total: Int, val marks: Double, val maxMarks: Int) {
    val accuracy: Double get() = if (attempted == 0) 0.0 else correct.toDouble() / attempted
}

data class ResultSummary(
    val score: Double,
    val maxScore: Int,
    val correct: Int,
    val wrong: Int,
    val unattempted: Int,
    val accuracy: Double,
    val negativeMarks: Double,
    val timeTakenSec: Int,
    val avgTimePerQuestionSec: Int,
    val bySubject: List<GroupStat>,
    val byTopic: List<GroupStat>,
    val errors: Map<ErrorType, Int>,
    val percentile: Double,
    val estimatedRank: Int,
)

object Results {
    // About 60-75k candidates sit GATE EE each year; used only for the rank estimate.
    const val CANDIDATES = 70000

    fun summarize(responses: List<ResponseInput>, evaluated: List<Evaluated>, timeTakenSec: Int, topperScore: Double?): ResultSummary {
        val byId = evaluated.associateBy { it.questionId }
        val score = evaluated.sumOf { it.marksAwarded }
        val maxScore = responses.sumOf { it.question.marks }
        val attempted = evaluated.count { it.attempted }
        val correct = evaluated.count { it.correct }
        fun group(key: (Question) -> String) = responses.groupBy { key(it.question) }.map { (k, rs) ->
            val ev = rs.map { byId.getValue(it.question.id) }
            GroupStat(k, ev.count { it.attempted }, ev.count { it.correct }, rs.size, ev.sumOf { it.marksAwarded }, rs.sumOf { it.question.marks })
        }.sortedBy { it.accuracy }
        val p = percentile(score, maxScore, topperScore)
        return ResultSummary(
            score = (score * 100).roundToInt() / 100.0,
            maxScore = maxScore,
            correct = correct,
            wrong = attempted - correct,
            unattempted = responses.size - attempted,
            accuracy = if (attempted == 0) 0.0 else correct.toDouble() / attempted,
            negativeMarks = -evaluated.filter { it.marksAwarded < 0 }.sumOf { it.marksAwarded },
            timeTakenSec = timeTakenSec,
            avgTimePerQuestionSec = if (attempted == 0) 0 else responses.filter { Answers.isAttempted(it) }.sumOf { it.timeSpentSec } / attempted,
            bySubject = group { it.subjectId },
            byTopic = group { it.topicId },
            errors = evaluated.mapNotNull { it.errorType }.groupingBy { it }.eachCount(),
            percentile = p,
            estimatedRank = max(1, ((100 - p) / 100 * CANDIDATES).roundToInt()),
        )
    }

    /**
     * Rough percentile model: GATE EE raw scores are strongly right-skewed (median ~ 15/100,
     * toppers 80-90). A logistic curve on the score as a share of the paper's expected topper
     * score reproduces that shape. It is an estimate, shown as such in the app.
     */
    fun percentile(score: Double, maxScore: Int, topperScore: Double?): Double {
        if (maxScore <= 0) return 0.0
        val topper = (topperScore?.takeIf { it > 0 } ?: (maxScore * 0.85))
        val x = score / topper
        val p = 100 / (1 + exp(-12 * (x - 0.18)))
        return (p.coerceIn(0.0, 99.99) * 100).roundToInt() / 100.0
    }
}

/** Topic-strength map: exponential moving average of correctness, fed back into the next AI mock. */
object TopicStrength {
    const val ALPHA = 0.35

    fun update(previous: Double?, correct: Int, attempted: Int): Double {
        if (attempted == 0) return previous ?: 0.5
        val now = correct.toDouble() / attempted
        return if (previous == null) now else previous * (1 - ALPHA) + now * ALPHA
    }

    /** Weakest topics first; only topics seen at least [minAttempts] times count. */
    fun weakTopics(strengths: Map<String, Pair<Double, Int>>, limit: Int = 8, minAttempts: Int = 2): List<String> =
        strengths.filter { it.value.second >= minAttempts && it.value.first < 0.6 }
            .entries.sortedBy { it.value.first }.take(limit).map { it.key }
}
