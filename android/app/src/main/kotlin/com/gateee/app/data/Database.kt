package com.gateee.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Every question the app knows: bundled sample bank, PYQs with text, series and AI papers. */
@Entity(tableName = "questions", indices = [Index("subjectId"), Index("topicId"), Index("source")])
data class QuestionEntity(
    @PrimaryKey val id: String,
    val subjectId: String,
    val topicId: String,
    val subtopic: String,
    val type: String,
    val marks: Int,
    val difficulty: String,
    val source: String, // sample | pyq | series | ai_generated
    val year: Int?,
    val json: String, // full Question JSON (core.Question)
    val reported: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tests")
data class TestEntity(
    @PrimaryKey val id: String,
    val title: String,
    val kind: String, // topic | subject | full | ai | daily | custom | weak_area | offline
    val seriesId: String?,
    val questionIdsJson: String,
    val durationMin: Int,
    val totalMarks: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val paperReportJson: String? = null,
    val verificationJson: String? = null,
    val blueprintJson: String? = null,
)

@Entity(tableName = "attempts", indices = [Index("testId")])
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val testId: String,
    val startedAt: Long,
    val submittedAt: Long? = null,
    val score: Double = 0.0,
    val maxScore: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val unattempted: Int = 0,
    val timeTakenSec: Int = 0,
    val percentile: Double = 0.0,
    val estimatedRank: Int = 0,
    val autoSubmitted: Boolean = false,
    val currentIndex: Int = 0,
)

@Entity(tableName = "responses", primaryKeys = ["attemptId", "questionId"])
data class ResponseEntity(
    val attemptId: Long,
    val questionId: String,
    val answer: String? = null,
    val status: String = "NOT_VISITED",
    val timeSpentSec: Int = 0,
    val answeredAtSec: Int? = null,
    val isCorrect: Boolean? = null,
    val marksAwarded: Double = 0.0,
    val errorType: String? = null,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(@PrimaryKey val questionId: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey val questionId: String, val text: String, val updatedAt: Long = System.currentTimeMillis())

@Entity(tableName = "topic_strength")
data class TopicStrengthEntity(
    @PrimaryKey val topicId: String,
    val subjectId: String,
    val strength: Double,
    val attempts: Int,
    val correct: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "study_days")
data class StudyDayEntity(@PrimaryKey val date: String, val questions: Int)

data class WrongAnswerRow(val questionId: String, val answer: String?, val errorType: String?, val attemptId: Long)

@Dao
interface QuestionDao {
    @Upsert suspend fun upsert(items: List<QuestionEntity>)
    @Query("SELECT * FROM questions WHERE id IN (:ids)") suspend fun byIds(ids: List<String>): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE id = :id") suspend fun byId(id: String): QuestionEntity?
    @Query("SELECT * FROM questions WHERE reported = 0") suspend fun allActive(): List<QuestionEntity>
    @Query("SELECT * FROM questions WHERE source = :source AND reported = 0") suspend fun bySource(source: String): List<QuestionEntity>
    @Query("UPDATE questions SET reported = 1 WHERE id = :id") suspend fun markReported(id: String)
    @Query("SELECT COUNT(*) FROM questions") suspend fun count(): Int
    @Query("SELECT json FROM questions WHERE source = 'ai_generated' ORDER BY createdAt DESC LIMIT :limit") suspend fun recentGeneratedJson(limit: Int): List<String>
}

@Dao
interface TestDao {
    @Upsert suspend fun upsert(t: TestEntity)
    @Query("SELECT * FROM tests WHERE id = :id") suspend fun byId(id: String): TestEntity?
    @Query("SELECT * FROM tests ORDER BY createdAt DESC") fun all(): Flow<List<TestEntity>>
    @Query("SELECT * FROM tests WHERE seriesId IS NOT NULL") fun seriesTests(): Flow<List<TestEntity>>
}

@Dao
interface AttemptDao {
    @Insert suspend fun insert(a: AttemptEntity): Long
    @Upsert suspend fun upsert(a: AttemptEntity)
    @Query("SELECT * FROM attempts WHERE id = :id") suspend fun byId(id: Long): AttemptEntity?
    @Query("SELECT * FROM attempts WHERE testId = :testId AND submittedAt IS NULL ORDER BY startedAt DESC LIMIT 1") suspend fun openAttempt(testId: String): AttemptEntity?
    @Query("SELECT * FROM attempts WHERE submittedAt IS NOT NULL ORDER BY submittedAt ASC") fun submitted(): Flow<List<AttemptEntity>>
    @Query("SELECT * FROM attempts WHERE testId = :testId AND submittedAt IS NOT NULL ORDER BY submittedAt DESC") fun forTest(testId: String): Flow<List<AttemptEntity>>

    @Upsert suspend fun upsertResponses(items: List<ResponseEntity>)
    @Query("SELECT * FROM responses WHERE attemptId = :attemptId") suspend fun responses(attemptId: Long): List<ResponseEntity>
    @Query(
        """SELECT r.questionId, r.answer, r.errorType, r.attemptId FROM responses r
           JOIN attempts a ON a.id = r.attemptId
           WHERE r.isCorrect = 0 AND a.submittedAt IS NOT NULL ORDER BY a.submittedAt DESC""",
    )
    fun wrongAnswers(): Flow<List<WrongAnswerRow>>
    @Query("SELECT DISTINCT questionId FROM responses r JOIN attempts a ON a.id = r.attemptId WHERE a.submittedAt IS NOT NULL")
    suspend fun attemptedQuestionIds(): List<String>
}

@Dao
interface StudyDao {
    @Upsert suspend fun upsertBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE questionId = :id") suspend fun deleteBookmark(id: String)
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") fun bookmarks(): Flow<List<BookmarkEntity>>
    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE questionId = :id)") fun isBookmarked(id: String): Flow<Boolean>

    @Upsert suspend fun upsertNote(n: NoteEntity)
    @Query("DELETE FROM notes WHERE questionId = :id") suspend fun deleteNote(id: String)
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC") fun notes(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE questionId = :id") fun note(id: String): Flow<NoteEntity?>

    @Upsert suspend fun upsertStrengths(items: List<TopicStrengthEntity>)
    @Query("SELECT * FROM topic_strength") suspend fun strengths(): List<TopicStrengthEntity>
    @Query("SELECT * FROM topic_strength ORDER BY strength ASC") fun strengthsFlow(): Flow<List<TopicStrengthEntity>>

    @Query("SELECT * FROM study_days WHERE date = :date") suspend fun day(date: String): StudyDayEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDay(d: StudyDayEntity)
    @Query("SELECT * FROM study_days ORDER BY date DESC") fun days(): Flow<List<StudyDayEntity>>
}

@Database(
    entities = [
        QuestionEntity::class, TestEntity::class, AttemptEntity::class, ResponseEntity::class,
        BookmarkEntity::class, NoteEntity::class, TopicStrengthEntity::class, StudyDayEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun questions(): QuestionDao
    abstract fun tests(): TestDao
    abstract fun attempts(): AttemptDao
    abstract fun study(): StudyDao

    companion object {
        fun build(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "gate_ee.db").build()
    }
}
