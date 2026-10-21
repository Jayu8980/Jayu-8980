package com.autotube.ai.di

import android.content.Context
import androidx.room.Room
import com.autotube.ai.data.device.DeviceStatusImpl
import com.autotube.ai.data.files.ProjectFilesImpl
import com.autotube.ai.data.files.ProjectLoggerImpl
import com.autotube.ai.data.google.YouTubeAuthImpl
import com.autotube.ai.data.google.YouTubeUploaderImpl
import com.autotube.ai.data.local.AppDatabase
import com.autotube.ai.data.local.ProjectDao
import com.autotube.ai.data.local.ProjectRepositoryImpl
import com.autotube.ai.data.media.MediaStoreSaverImpl
import com.autotube.ai.data.settings.SecureSettingsRepository
import com.autotube.ai.domain.DeviceStatus
import com.autotube.ai.domain.MediaStoreSaver
import com.autotube.ai.domain.ProjectFiles
import com.autotube.ai.domain.ProjectLogger
import com.autotube.ai.domain.ProjectRepository
import com.autotube.ai.domain.SettingsRepository
import com.autotube.ai.domain.YouTubeAuth
import com.autotube.ai.domain.YouTubeUploader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Local data, device, gallery and Google/YouTube bindings. The implementations are @Singleton, so
 * the bindings themselves are unscoped.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SecureSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindProjectFiles(impl: ProjectFilesImpl): ProjectFiles

    @Binds
    abstract fun bindProjectLogger(impl: ProjectLoggerImpl): ProjectLogger

    @Binds
    abstract fun bindDeviceStatus(impl: DeviceStatusImpl): DeviceStatus

    @Binds
    abstract fun bindMediaStoreSaver(impl: MediaStoreSaverImpl): MediaStoreSaver

    @Binds
    abstract fun bindYouTubeAuth(impl: YouTubeAuthImpl): YouTubeAuth

    @Binds
    abstract fun bindYouTubeUploader(impl: YouTubeUploaderImpl): YouTubeUploader

    companion object {
        @Provides
        @Singleton
        fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()

        @Provides
        fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()
    }
}
