package com.autotube.ai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per video project. The complete [com.autotube.ai.core.model.Project] lives in [json]
 * (encoded with the shared AppJson); the other columns mirror a few fields so lists can be sorted
 * and inspected without decoding the blob.
 */
@Entity(
    tableName = ProjectEntity.TABLE,
    indices = [Index(value = ["updatedAt"])],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "niche")
    val niche: String,
    @ColumnInfo(name = "format")
    val format: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "thumbnailPath")
    val thumbnailPath: String?,
    @ColumnInfo(name = "outputPath")
    val outputPath: String?,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
    @ColumnInfo(name = "json")
    val json: String,
) {
    companion object {
        const val TABLE = "projects"
    }
}
