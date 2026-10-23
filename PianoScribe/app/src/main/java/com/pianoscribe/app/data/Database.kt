package com.pianoscribe.app.data

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
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Mono 22050 Hz float32 little-endian PCM, relative to filesDir. */
    val audioPath: String,
    val durationSec: Double,
    val bpm: Double,
    val beatsPerBar: Int,
    val beatUnit: Int,
    val gridOffset: Double,
    val splitPoint: Int,
    /** 0 = not quantised, else 8 or 16. */
    val quantizeDivision: Int,
    val keyName: String?,
    val noteCount: Int,
    val source: String,
)

@Entity(
    tableName = "notes",
    primaryKeys = ["projectId", "noteId"],
    indices = [Index("projectId")],
)
data class NoteEntity(
    val projectId: Long,
    val noteId: Long,
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val velocity: Int,
    val confidence: Float,
    /** 0 = right hand, 1 = left hand. */
    val hand: Int,
    val cents: Float,
    val verified: Boolean,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProject(id: Long)

    @Query("SELECT * FROM notes WHERE projectId = :id ORDER BY start, pitch")
    suspend fun notes(id: Long): List<NoteEntity>

    @Query("DELETE FROM notes WHERE projectId = :id")
    suspend fun deleteNotes(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotes(notes: List<NoteEntity>)

    @Transaction
    suspend fun replaceNotes(id: Long, notes: List<NoteEntity>) {
        deleteNotes(id)
        if (notes.isNotEmpty()) insertNotes(notes)
    }

    @Transaction
    suspend fun deleteAll(id: Long) {
        deleteNotes(id)
        deleteProject(id)
    }
}

@Database(entities = [ProjectEntity::class, NoteEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "pianoscribe.db").build()
    }
}
