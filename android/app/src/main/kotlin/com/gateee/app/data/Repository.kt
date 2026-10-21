package com.gateee.app.data

import android.content.Context
import com.gateee.core.AnalysisEngine
import com.gateee.core.Answers
import com.gateee.core.FormulaSheets
import com.gateee.core.GateJson
import com.gateee.core.MockSeries
import com.gateee.core.Paper
import com.gateee.core.PyqAnalysis
import com.gateee.core.PyqBank
import com.gateee.core.PyqRecord
import com.gateee.core.QStatus
import com.gateee.core.Question
import com.gateee.core.QuestionBank
import com.gateee.core.ResponseInput
import com.gateee.core.ResultSummary
import com.gateee.core.Results
import com.gateee.core.Scoring
import com.gateee.core.Syllabus
import com.gateee.core.TopicStrength
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** In-memory state of one question during a test. */
data class QState(val answer: String? = null, val status: QStatus = QStatus.NOT_VISITED, val timeSpentSec: Int = 0, val answeredAtSec: Int? = null)

data class LoadedTest(val test: TestEntity, val questions: List<Question>)

data class AttemptResult(
    val attempt: AttemptEntity,
    val test: TestEntity,
    val questions: List<Question>,
    val responses: Map<String, ResponseEntity>,
    val summary: ResultSummary,
    val suggestions: List<Pair<com.gateee.core.TopicStat, String>>,
)

class Repository(private val context: Context, val db: AppDatabase, val backend: BackendClient) {
    private val seedLock = Mutex()

    // ---------- bundled JSON (assets come from the repo's /data folder) ----------
    private fun asset(name: String): String? = runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    val syllabus: Syllabus by lazy { GateJson.decodeFromString(asset("syllabus.json")!!) }
    val series: MockSeries by lazy { GateJson.decodeFromString(asset("mock_series.json") ?: "{}") }
    val formulas: FormulaSheets by lazy { GateJson.decodeFromString(asset("formula_sheets.json") ?: "{}") }

    /** Real tagged PYQs (data/pyq_bank.json) when present, otherwise the synthetic demo tags. */
    val pyqBank: PyqBank by lazy {
        (asset("pyq_bank.json") ?: asset("pyq_tags_demo.json"))?.let { GateJson.decodeFromString<PyqBank>(it) } ?: PyqBank(isDemo = true)
    }
    val analysis: PyqAnalysis by lazy { AnalysisEngine.analyse(pyqBank, syllabus) }

    suspend fun seedIfNeeded() = seedLock.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("seed", Context.MODE_PRIVATE)
            if (prefs.getInt("version", 0) >= SEED_VERSION && db.questions().count() > 0) return@withContext
            val sample = GateJson.decodeFromString<QuestionBank>(asset("sample_questions.json")!!)
            db.questions().upsert(sample.questions.map { it.toEntity("sample") })
            db.questions().upsert(pyqBank.records.filter { !it.question.isNullOrBlank() }.map { it.toQuestion().toEntity("pyq") })
            // pre-generated series papers (backend/scripts/build-series.js -> data/generated/)
            context.assets.list("generated").orEmpty().filter { it.endsWith(".json") }.forEach { file ->
                val paper = GateJson.decodeFromString<Paper>(asset("generated/$file")!!)
                val def = series.tests.firstOrNull { it.id == (paper.seriesId ?: paper.id) }
                importPaper(paper, kind = def?.kind ?: "full", seriesId = def?.id, source = "series")
            }
            prefs.edit().putInt("version", SEED_VERSION).apply()
        }
    }

    // ---------- tests ----------
    suspend fun importPaper(paper: Paper, kind: String, seriesId: String? = null, source: String = "ai_generated"): String {
        val qs = paper.questions.sortedBy { it.qno }
        db.questions().upsert(qs.map { it.toEntity(source) })
        val id = seriesId ?: paper.id
        db.tests().upsert(
            TestEntity(
                id = id,
                title = paper.title.ifBlank { id },
                kind = kind,
                seriesId = seriesId,
                questionIdsJson = GateJson.encodeToString(qs.map { it.id }),
                durationMin = paper.durationMin,
                totalMarks = qs.sumOf { it.marks },
                paperReportJson = paper.paperReport?.let { GateJson.encodeToString(it) },
                verificationJson = paper.verificationSummary?.let { GateJson.encodeToString(it) },
                blueprintJson = GateJson.encodeToString(paper.blueprint),
            ),
        )
        return id
    }

    suspend fun createOfflineTest(title: String, kind: String, questions: List<Question>, durationMin: Int, seriesId: String? = null): String {
        val id = seriesId ?: "offline-${System.currentTimeMillis().toString(36)}"
        db.tests().upsert(
            TestEntity(
                id = id, title = title, kind = kind, seriesId = seriesId,
                questionIdsJson = GateJson.encodeToString(questions.map { it.id }),
                durationMin = durationMin, totalMarks = questions.sumOf { it.marks },
            ),
        )
        return id
    }

    suspend fun loadTest(testId: String): LoadedTest? {
        val t = db.tests().byId(testId) ?: return null
        val ids = GateJson.decodeFromString<List<String>>(t.questionIdsJson)
        val byId = db.questions().byIds(ids).associateBy { it.id }
        return LoadedTest(t, ids.mapNotNull { byId[it]?.toQuestion() }.mapIndexed { i, q -> q.copy(qno = i + 1) })
    }

    fun allTests(): Flow<List<TestEntity>> = db.tests().all()
    fun seriesTests(): Flow<List<TestEntity>> = db.tests().seriesTests()

    suspend fun bankQuestions(): List<Question> = db.questions().allActive().map { it.toQuestion() }
    suspend fun questionsBySource(source: String) = db.questions().bySource(source).map { it.toQuestion() }
    suspend fun question(id: String): Question? = db.questions().byId(id)?.toQuestion()

    // ---------- attempts ----------
    suspend fun startOrResume(testId: String, questions: List<Question>): Pair<AttemptEntity, Map<String, QState>> {
        db.attempts().openAttempt(testId)?.let { open ->
            val rs = db.attempts().responses(open.id).associateBy { it.questionId }
            return open to questions.associate { q ->
                q.id to (rs[q.id]?.let { QState(it.answer, QStatus.valueOf(it.status), it.timeSpentSec, it.answeredAtSec) } ?: QState())
            }
        }
        val id = db.attempts().insert(AttemptEntity(testId = testId, startedAt = System.currentTimeMillis()))
        return db.attempts().byId(id)!! to questions.associate { it.id to QState() }
    }

    suspend fun saveProgress(attempt: AttemptEntity, states: Map<String, QState>, currentIndex: Int) {
        db.attempts().upsert(attempt.copy(currentIndex = currentIndex))
        db.attempts().upsertResponses(states.map { (qid, s) -> ResponseEntity(attempt.id, qid, s.answer, s.status.name, s.timeSpentSec, s.answeredAtSec) })
    }

    /** Score with GATE negative marking, classify errors, update topic strengths and the streak. */
    suspend fun submit(attempt: AttemptEntity, test: TestEntity, questions: List<Question>, states: Map<String, QState>, timeTakenSec: Int, auto: Boolean): Long {
        val strengthsBefore = db.study().strengths().associateBy { it.topicId }
        val inputs = questions.map { q -> states.getValue(q.id).let { ResponseInput(q, it.answer, it.status, it.timeSpentSec, it.answeredAtSec) } }
        val evaluated = Scoring.evaluate(inputs, test.durationMin * 60, strengthsBefore.mapValues { it.value.strength })
        val topper = test.paperReportJson?.let { GateJson.decodeFromString<com.gateee.core.PaperReport>(it).expectedTopperScore }
        val summary = Results.summarize(inputs, evaluated, timeTakenSec, topper)
        db.attempts().upsertResponses(
            inputs.zip(evaluated).map { (r, e) ->
                ResponseEntity(attempt.id, r.question.id, r.answer, r.status.name, r.timeSpentSec, r.answeredAtSec, if (e.attempted) e.correct else null, e.marksAwarded, e.errorType?.name)
            },
        )
        db.attempts().upsert(
            attempt.copy(
                submittedAt = System.currentTimeMillis(), score = summary.score, maxScore = summary.maxScore,
                correct = summary.correct, wrong = summary.wrong, unattempted = summary.unattempted,
                timeTakenSec = timeTakenSec, percentile = summary.percentile, estimatedRank = summary.estimatedRank, autoSubmitted = auto,
            ),
        )
        // personalisation: topic-strength map feeds the next AI generation
        val updates = inputs.zip(evaluated).filter { it.second.attempted }.groupBy { it.first.question.topicId }.map { (topic, rows) ->
            val prev = strengthsBefore[topic]
            val c = rows.count { it.second.correct }
            TopicStrengthEntity(topic, rows.first().first.question.subjectId, TopicStrength.update(prev?.strength, c, rows.size), (prev?.attempts ?: 0) + rows.size, (prev?.correct ?: 0) + c)
        }
        db.study().upsertStrengths(updates)
        recordStudy(evaluated.count { it.attempted })
        return attempt.id
    }

    suspend fun result(attemptId: Long): AttemptResult? {
        val attempt = db.attempts().byId(attemptId) ?: return null
        val loaded = loadTest(attempt.testId) ?: return null
        val rs = db.attempts().responses(attemptId).associateBy { it.questionId }
        val inputs = loaded.questions.map { q ->
            val r = rs[q.id]
            ResponseInput(q, r?.answer, r?.status?.let { QStatus.valueOf(it) } ?: QStatus.NOT_VISITED, r?.timeSpentSec ?: 0, r?.answeredAtSec)
        }
        val evaluated = inputs.map { i ->
            val r = rs[i.question.id]
            com.gateee.core.Evaluated(i.question.id, Answers.isAttempted(i), r?.isCorrect == true, r?.marksAwarded ?: 0.0, r?.errorType?.let { com.gateee.core.ErrorType.valueOf(it) })
        }
        val topper = loaded.test.paperReportJson?.let { GateJson.decodeFromString<com.gateee.core.PaperReport>(it).expectedTopperScore }
        val summary = Results.summarize(inputs, evaluated, attempt.timeTakenSec, topper)
        val strengths = db.study().strengths().associate { it.topicId to it.strength }
        return AttemptResult(attempt, loaded.test, loaded.questions, rs, summary, AnalysisEngine.revisionSuggestions(analysis, strengths))
    }

    fun submittedAttempts() = db.attempts().submitted()
    fun wrongAnswers() = db.attempts().wrongAnswers()
    fun strengths() = db.study().strengthsFlow()

    suspend fun weakTopics(): List<String> =
        TopicStrength.weakTopics(db.study().strengths().associate { it.topicId to (it.strength to it.attempts) })

    /** Summaries of earlier AI questions, sent so the setter agents don't repeat them. */
    suspend fun avoidSummaries(limit: Int = 150): List<String> =
        db.questions().recentGeneratedJson(limit).mapNotNull { runCatching { GateJson.decodeFromString<Question>(it) }.getOrNull() }
            .map { "${it.topicId}: ${it.question.take(140)}" }

    suspend fun attemptedQuestionIds() = db.attempts().attemptedQuestionIds().toSet()

    // ---------- bookmarks, notes, reports ----------
    fun isBookmarked(id: String) = db.study().isBookmarked(id)
    suspend fun setBookmark(id: String, on: Boolean) = if (on) db.study().upsertBookmark(BookmarkEntity(id)) else db.study().deleteBookmark(id)
    fun bookmarks() = db.study().bookmarks()
    fun notes() = db.study().notes()
    fun note(id: String) = db.study().note(id)
    suspend fun saveNote(id: String, text: String) = if (text.isBlank()) db.study().deleteNote(id) else db.study().upsertNote(NoteEntity(id, text))

    /** "Report wrong question": removed from the local bank immediately, and reported to the server if reachable. */
    suspend fun reportQuestion(id: String, reason: String): Boolean {
        db.questions().markReported(id)
        return runCatching { backend.reportQuestion(id, reason) }.isSuccess
    }

    // ---------- streak ----------
    suspend fun recordStudy(questions: Int) {
        val today = LocalDate.now().toString()
        val d = db.study().day(today)
        db.study().putDay(StudyDayEntity(today, (d?.questions ?: 0) + questions))
    }

    fun streak(): Flow<Pair<Int, Int>> = db.study().days().map { days ->
        val set = days.filter { it.questions > 0 }.map { it.date }.toSet()
        var day = LocalDate.now()
        if (day.toString() !in set) day = day.minusDays(1) // today not done yet: streak still alive from yesterday
        var n = 0
        while (day.toString() in set) { n++; day = day.minusDays(1) }
        n to (days.firstOrNull { it.date == LocalDate.now().toString() }?.questions ?: 0)
    }

    companion object {
        const val SEED_VERSION = 2
    }
}

fun Question.toEntity(source: String) = QuestionEntity(
    id = id, subjectId = subjectId, topicId = topicId, subtopic = subtopic, type = type.name, marks = marks,
    difficulty = difficulty, source = source, year = year, json = GateJson.encodeToString(copy(source = source)),
)

fun QuestionEntity.toQuestion(): Question = GateJson.decodeFromString(json)

fun PyqRecord.toQuestion() = Question(
    id = id, subjectId = subjectId, topicId = topicId, subtopic = subtopic, type = type, marks = marks,
    negativeMarks = if (type == com.gateee.core.QType.MCQ) (if (marks == 1) 0.33 else 0.67) else 0.0,
    difficulty = difficulty, question = question.orEmpty(), options = options, answer = answer.orEmpty(),
    answerRange = answerRange, solution = solution.orEmpty(), concept = concept, year = year, source = "pyq",
    section = if (subjectId == "GA") "GA" else "Technical",
)
