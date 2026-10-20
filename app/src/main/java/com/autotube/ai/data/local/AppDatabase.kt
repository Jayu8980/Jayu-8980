package com.autotube.ai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The app's Room database ("autotube.db"). Version 1; the schema JSON is exported to
 * `app/schemas` (configured through the `room.schemaLocation` KSP argument).
 */
@Database(
    entities = [ProjectEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    companion object {
        const val NAME = "autotube.db"
    }
}
