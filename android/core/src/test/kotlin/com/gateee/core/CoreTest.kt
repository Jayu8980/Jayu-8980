package com.gateee.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val dataDir = File(System.getProperty("dataDir") ?: "../../data")
    private val bank = GateJson.decodeFromString<QuestionBank>(File(dataDir, "sample_questions.json").readText())
    private val syllabus = GateJson.decodeFromString<Syllabus>(File(dataDir, "syllabus.json").readText())

    private fun resp(q: Question, a: String?, status: QStatus = QStatus.ANSWERED, t: Int = 100, at: Int? = 600) =
        ResponseInput(q, a, status, t, at)

    @Test
    fun sampleBankParses() {
        assertEquals(30, bank.questions.size)
        assertTrue(bank.questions.all { it.answer.isNotBlank() && it.solution.isNotBlank() })
        GateJson.decodeFromString<MockSeries>(File(dataDir, "mock_series.json").readText()).also { assertEquals(40, it.tests.size) }
        GateJson.decodeFromString<FormulaSheets>(File(dataDir, "formula_sheets.json").readText()).also { assertTrue(it.sheets.size >= 10) }
    }

    @Test
    fun gateMarking() {
        val mcq1 = bank.questions.first { it.type == QType.MCQ && it.marks == 1 }
        val mcq2 = bank.questions.first { it.type == QType.MCQ && it.marks == 2 }
        val msq = bank.questions.first { it.type == QType.MSQ }
        val nat = bank.questions.first { it.type == QType.NAT && it.answerRange!!.max > it.answerRange.min }
        val wrong = listOf("A", "B", "C", "D").first { it != mcq1.answer }
        assertEquals(1.0, Scoring.marksFor(mcq1, mcq1.answer, true), 1e-9)
        assertEquals(-1.0 / 3, Scoring.marksFor(mcq1, wrong, true), 1e-9)
        assertEquals(-2.0 / 3, Scoring.marksFor(mcq2, listOf("A", "B", "C", "D").first { it != mcq2.answer }, true), 1e-9)
        assertEquals(0.0, Scoring.marksFor(mcq1, null, false), 1e-9)
        // MSQ: all correct options needed, no negative, no partial
        assertEquals(msq.marks.toDouble(), Scoring.marksFor(msq, msq.answer.reversed(), true), 1e-9)
        assertEquals(0.0, Scoring.marksFor(msq, msq.answer.split(",").first(), true), 1e-9)
        // NAT: inside the range
        assertEquals(nat.marks.toDouble(), Scoring.marksFor(nat, nat.answerRange!!.max.toString(), true), 1e-9)
        assertEquals(0.0, Scoring.marksFor(nat, (nat.answerRange.max * 1.5).toString(), true), 1e-9)
    }

    @Test
    fun answeredAndMarkedIsEvaluatedButMarkedOnlyIsNot() {
        val q = bank.questions.first { it.type == QType.MCQ }
        val ev = Scoring.evaluate(listOf(resp(q, q.answer, QStatus.ANSWERED_MARKED), resp(q.copy(id = "x"), q.answer, QStatus.MARKED)), 3600)
        assertTrue(ev[0].correct)
        assertTrue(!ev[1].attempted)
    }

    @Test
    fun errorClassification() {
        val nat = bank.questions.first { it.id == "SAMPLE-016" } // 1.00 ohm
        assertEquals(ErrorType.TIME_PRESSURE, Scoring.classify(resp(nat, "3", at = 3500), 3600, null))
        assertEquals(ErrorType.SILLY_MISTAKE, Scoring.classify(resp(nat, "1.05", t = 150), 3600, null))
        assertEquals(ErrorType.CONCEPT_GAP, Scoring.classify(resp(nat, "4", t = 150), 3600, 0.2))
    }

    @Test
    fun summaryAndPercentile() {
        val rs = bank.questions.map { resp(it, it.answer) }
        val s = Results.summarize(rs, Scoring.evaluate(rs, 10800), 5000, 85.0)
        assertEquals(s.maxScore.toDouble(), s.score, 1e-6)
        assertEquals(1.0, s.accuracy, 1e-9)
        assertTrue(Results.percentile(25.0, 100, 85.0) in 70.0..90.0)
        assertTrue(Results.percentile(60.0, 100, 85.0) > 99.0)
    }

    @Test
    fun calculator() {
        val c = Calculator(degrees = true)
        assertEquals(0.5, c.evaluate("sin(30)"), 1e-12)
        assertEquals(14.0, c.evaluate("2+3*4"), 1e-12)
        assertEquals(-4.0, c.evaluate("-2^2"), 1e-12)
        assertEquals(2 * Math.PI, c.evaluate("2π"), 1e-12)
        assertEquals(120.0, c.evaluate("5!"), 1e-12)
        assertEquals(240.0, c.evaluate("ANS*2"), 1e-12)
        assertEquals(1.7320508075688772, c.evaluate("√3"), 1e-12)
        c.degrees = false
        assertEquals(1.0, c.evaluate("cos(0)"), 1e-12)
    }

    @Test
    fun natVerificationExpressionsRecompute() {
        val c = Calculator(degrees = false)
        for (q in bank.questions.filter { it.type == QType.NAT }) {
            val v = c.evaluate(q.verification!!.expression, q.verification.variables)
            val r = q.answerRange!!
            val step = maxOf((r.max - r.min) / 2, 1e-9)
            assertTrue("${q.id}: $v not in $r", v >= r.min - step && v <= r.max + step)
        }
    }

    @Test
    fun analysisOfDemoBank() {
        val pyq = GateJson.decodeFromString<PyqBank>(File(dataDir, "pyq_tags_demo.json").readText())
        val a = AnalysisEngine.analyse(pyq, syllabus)
        assertTrue(a.isDemo)
        assertEquals((2010..2026).toList(), a.years)
        assertTrue(a.highPriority.isNotEmpty())
        assertTrue(a.topics.size >= syllabus.allTopics.size)
        val sugg = AnalysisEngine.revisionSuggestions(a, mapOf("EM.IM" to 0.2))
        assertEquals(6, sugg.size)
    }

    @Test
    fun offlineAssembler() {
        val t = OfflineAssembler.assemble(bank.questions, OfflineAssembler.Spec(subjects = setOf("EM", "PS"), count = 5, seed = 1))
        assertEquals(5, t.size)
        assertTrue(t.all { it.subjectId in setOf("EM", "PS") })
        assertEquals((1..5).toList(), t.map { it.qno })
    }

    @Test
    fun topicStrength() {
        assertEquals(1.0, TopicStrength.update(null, 2, 2), 1e-9)
        assertEquals(0.65, TopicStrength.update(1.0, 0, 1), 1e-9)
        assertEquals(listOf("B"), TopicStrength.weakTopics(mapOf("A" to (0.9 to 5), "B" to (0.3 to 3), "C" to (0.1 to 1))))
    }
}
