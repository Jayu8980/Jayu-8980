package com.gateee.core

import kotlin.math.max
import kotlin.math.roundToInt

data class TopicStat(
    val topicId: String,
    val name: String,
    val subjectId: String,
    val frequency: Int,
    val marks: Int,
    val marksByYear: Map<Int, Int>,
    val lastAsked: Int?,
    val trendSlope: Double,
    val trend: String,
    val priority: Int,
    val isGap: Boolean,
    val predictedScore: Double,
)

data class SubjectStat(val subjectId: String, val marksByYear: Map<Int, Int>, val avg5: Double, val avg10: Double, val totalMarks: Int)

data class ConceptStat(val concept: String, val topicId: String, val count: Int, val years: List<Int>)

data class PyqAnalysis(
    val isDemo: Boolean,
    val years: List<Int>,
    val subjects: Map<String, SubjectStat>,
    val topics: Map<String, TopicStat>,
    val typeSplitByYear: Map<Int, Map<QType, Int>>,
    val difficultyByYear: Map<Int, Map<String, Int>>,
    val mostRepeatedConcepts: List<ConceptStat>,
    val highPriority: List<TopicStat>,
    val gapTopics: List<TopicStat>,
    val rising: List<TopicStat>,
    val falling: List<TopicStat>,
)

/** Module 1 maths; mirrors backend/src/analysis.js so the app works offline. */
object AnalysisEngine {
    fun analyse(bank: PyqBank, syllabus: Syllabus, currentYear: Int? = null): PyqAnalysis {
        val records = bank.records
        val years = records.map { it.year }.distinct().sorted()
        val nextYear = currentYear ?: ((years.maxOrNull() ?: 2025) + 1)

        val subjects = records.groupBy { it.subjectId }.mapValues { (sid, rs) ->
            val byYear = rs.groupBy { it.year }.mapValues { e -> e.value.sumOf { it.marks } }
            SubjectStat(sid, byYear, avg(years.takeLast(5).map { byYear[it] ?: 0 }), avg(years.takeLast(10).map { byYear[it] ?: 0 }), rs.sumOf { it.marks })
        }

        val byTopic = records.groupBy { it.topicId }
        val maxBySubject = byTopic.values.groupBy { it.first().subjectId }
            .mapValues { e -> max(1, e.value.maxOf { rs -> rs.sumOf { it.marks } }) }
        val recent = years.takeLast(8)
        val topics = mutableMapOf<String, TopicStat>()
        for ((tid, rs) in byTopic) {
            val sid = rs.first().subjectId
            val marks = rs.sumOf { it.marks }
            val byYear = rs.groupBy { it.year }.mapValues { e -> e.value.sumOf { it.marks } }
            val slope = slope(recent.map { it.toDouble() }, recent.map { (byYear[it] ?: 0).toDouble() })
            val last = rs.maxOf { it.year }
            val importance = marks.toDouble() / maxBySubject.getValue(sid)
            val gapYears = nextYear - last
            val isGap = importance >= 0.35 && gapYears >= 3
            val score = 0.45 * importance + 0.2 * (0.5 + slope / 2).coerceIn(0.0, 1.0) + 0.2 * (if (isGap) 1 else 0) +
                0.15 * (1 - (gapYears - 1) / 8.0).coerceIn(0.0, 1.0)
            topics[tid] = TopicStat(
                tid, syllabus.topicName(tid), sid, rs.size, marks, byYear, last, slope,
                when { slope > 0.15 -> "rising"; slope < -0.15 -> "falling"; else -> "stable" },
                marks * rs.size, isGap, round3(score),
            )
        }
        for (s in syllabus.subjects) for (t in s.topics) topics.getOrPut(t.id) {
            TopicStat(t.id, t.name, s.id, 0, 0, emptyMap(), null, 0.0, "new", 0, false, 0.1)
        }

        val ranked = topics.values.filter { it.subjectId != "GA" }.sortedByDescending { it.priority }
        return PyqAnalysis(
            isDemo = bank.isDemo,
            years = years,
            subjects = subjects,
            topics = topics,
            typeSplitByYear = records.groupBy { it.year }.mapValues { e -> e.value.groupingBy { it.type }.eachCount() },
            difficultyByYear = records.groupBy { it.year }.mapValues { e -> e.value.groupingBy { it.difficulty }.eachCount() },
            mostRepeatedConcepts = records.filter { it.concept.isNotBlank() }.groupBy { it.concept }
                .map { (c, rs) -> ConceptStat(c, rs.first().topicId, rs.size, rs.map { it.year }.sorted()) }
                .sortedByDescending { it.count }.take(25),
            highPriority = ranked.take(20),
            gapTopics = ranked.filter { it.isGap },
            rising = ranked.filter { it.trend == "rising" },
            falling = ranked.filter { it.trend == "falling" },
        )
    }

    /** "Compare performance against PYQ weightage and suggest what to revise next." */
    fun revisionSuggestions(analysis: PyqAnalysis, strengths: Map<String, Double>, limit: Int = 6): List<Pair<TopicStat, String>> =
        analysis.topics.values.filter { it.subjectId != "GA" }
            .map { t ->
                val s = strengths[t.topicId]
                val need = t.predictedScore * (1 - (s ?: 0.4))
                Triple(t, s, need)
            }
            .sortedByDescending { it.third }
            .take(limit)
            .map { (t, s, _) ->
                val why = when {
                    s == null -> "high PYQ weight, not practised yet"
                    s < 0.5 -> "weak (${(s * 100).roundToInt()}% strength) on a ${t.trend} topic"
                    t.isGap -> "gap topic: important but not asked recently"
                    else -> "high predicted weight; keep it sharp"
                }
                t to why
            }

    private fun avg(xs: List<Int>) = if (xs.isEmpty()) 0.0 else (xs.sum().toDouble() / xs.size * 100).roundToInt() / 100.0

    private fun slope(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun round3(v: Double) = (v * 1000).roundToInt() / 1000.0
}
