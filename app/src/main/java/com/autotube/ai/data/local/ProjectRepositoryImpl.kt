package com.autotube.ai.data.local

import android.util.Log
import com.autotube.ai.core.common.AppJson
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [ProjectRepository]. The full [Project] is stored as AppJson in [ProjectEntity.json];
 * a handful of columns are mirrored for sorting and debugging.
 *
 * Writes are serialized with one [Mutex], so concurrent read-modify-write updates from the pipeline,
 * the upload worker and the UI never lose each other's changes. The `transform` passed to
 * [updateProject] runs while that lock is held and must not call back into this repository.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    private val dao: ProjectDao,
    private val files: ProjectFiles,
) : ProjectRepository {

    private val writeLock = Mutex()

    /** Ids of rows already reported as unreadable, so a corrupt row is logged once, not on every emission. */
    private val reportedCorruptRows: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    override fun observeProjects(): Flow<List<Project>> =
        dao.observeAll()
            .map { rows -> rows.mapNotNull { it.toProjectOrNull() } }
            .flowOn(Dispatchers.Default)

    override fun observeProject(id: Long): Flow<Project?> =
        dao.observeById(id)
            .map { it?.toProjectOrNull() }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    override suspend fun getProject(id: Long): Project? {
        val entity = dao.getById(id) ?: return null
        return withContext(Dispatchers.Default) { entity.toProjectOrNull() }
    }

    override suspend fun createProject(request: VideoRequest): Long = writeLock.withLock {
        val now = System.currentTimeMillis()
        val draft = Project(
            id = 0,
            request = request,
            status = ProjectStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.insert(withContext(Dispatchers.Default) { draft.toEntity() })
        // Rewrite the blob so the JSON carries the generated id as well.
        val saved = draft.copy(id = id)
        dao.update(withContext(Dispatchers.Default) { saved.toEntity() })
        id
    }

    override suspend fun updateProject(id: Long, transform: (Project) -> Project): Project? =
        writeLock.withLock {
            val entity = dao.getById(id) ?: return@withLock null
            withContext(Dispatchers.Default) {
                val current = entity.toProjectOrNull() ?: return@withContext null
                val updated = transform(current).copy(
                    id = id,
                    updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt),
                )
                val newEntity = updated.toEntity()
                val rows = dao.update(newEntity)
                if (rows > 0) updated else null
            }
        }

    override suspend fun deleteProject(id: Long) {
        writeLock.withLock { dao.deleteById(id) }
        reportedCorruptRows.remove(id)
        files.deleteProject(id)
    }

    private fun Project.toEntity(): ProjectEntity = ProjectEntity(
        id = id,
        status = status.name,
        niche = request.niche,
        format = request.format.name,
        title = displayTitle.take(MAX_TITLE_COLUMN),
        thumbnailPath = thumbnailPath,
        outputPath = outputVideoPath,
        createdAt = createdAt,
        updatedAt = updatedAt,
        json = AppJson.encodeToString(Project.serializer(), this),
    )

    /** Decodes the blob; returns null (and logs once) for rows whose JSON can no longer be read. */
    private fun ProjectEntity.toProjectOrNull(): Project? = try {
        AppJson.decodeFromString(Project.serializer(), json).copy(id = id)
    } catch (e: Exception) {
        if (reportedCorruptRows.add(id)) {
            Log.w(TAG, "Skipping project $id: stored data could not be read (${e.javaClass.simpleName}: ${e.message?.take(300)})")
        }
        null
    }

    private companion object {
        const val TAG = "AutoTube"
        const val MAX_TITLE_COLUMN = 300
    }
}
