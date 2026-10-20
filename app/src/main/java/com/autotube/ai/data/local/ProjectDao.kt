package com.autotube.ai.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room access to the `projects` table. */
@Dao
interface ProjectDao {

    /** All projects, most recently updated first. Re-emits on every change to the table. */
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    /** One project, or null when it does not exist (or was deleted). */
    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ProjectEntity?

    /** Inserts a new row and returns the generated id. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ProjectEntity): Long

    /** Returns the number of rows updated (0 when the row no longer exists). */
    @Update
    suspend fun update(entity: ProjectEntity): Int

    @Delete
    suspend fun delete(entity: ProjectEntity): Int

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}
