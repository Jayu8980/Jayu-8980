package com.pianoscribe.app.data

import android.content.Context
import com.pianoscribe.core.model.Hand
import com.pianoscribe.core.model.Note
import com.pianoscribe.core.model.TimeGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** A loaded project: metadata plus notes. */
data class Project(
    val entity: ProjectEntity,
    val notes: List<Note>,
) {
    val grid: TimeGrid get() = TimeGrid(entity.bpm, entity.beatsPerBar, entity.beatUnit, entity.gridOffset)
}

class ProjectRepository(private val context: Context, private val dao: ProjectDao) {
    val projects: Flow<List<ProjectEntity>> = dao.observeProjects()

    fun audioFile(p: ProjectEntity): File = File(context.filesDir, p.audioPath)

    fun projectDir(id: Long): File = File(context.filesDir, "projects/$id").apply { mkdirs() }

    suspend fun load(id: Long): Project? = withContext(Dispatchers.IO) {
        val p = dao.project(id) ?: return@withContext null
        Project(p, dao.notes(id).map { it.toNote() })
    }

    /** Creates a project row and returns its id (audio is attached by the caller). */
    suspend fun create(entity: ProjectEntity): Long = withContext(Dispatchers.IO) { dao.insert(entity) }

    suspend fun save(entity: ProjectEntity, notes: List<Note>) = withContext(Dispatchers.IO) {
        dao.update(entity.copy(updatedAt = System.currentTimeMillis(), noteCount = notes.size))
        dao.replaceNotes(entity.id, notes.map { it.toEntity(entity.id) })
    }

    suspend fun updateMeta(entity: ProjectEntity) = withContext(Dispatchers.IO) {
        dao.update(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteAll(id)
        File(context.filesDir, "projects/$id").deleteRecursively()
    }

    private fun NoteEntity.toNote() = Note(noteId, pitch, start, duration, velocity, confidence, if (hand == 0) Hand.RIGHT else Hand.LEFT, cents, verified)
    private fun Note.toEntity(projectId: Long) = NoteEntity(projectId, id, pitch, start, duration, velocity, confidence, if (hand == Hand.RIGHT) 0 else 1, cents, verified)
}
