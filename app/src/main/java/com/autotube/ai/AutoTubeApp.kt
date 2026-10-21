package com.autotube.ai

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.autotube.ai.core.common.NotificationChannels
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point: Hilt root, WorkManager configuration (Hilt worker factory, on-demand
 * initialisation) and notification channels.
 */
@HiltAndroidApp
class AutoTubeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.INFO else android.util.Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val progress = NotificationChannel(
            NotificationChannels.PIPELINE,
            "Video progress",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while a video is being made or uploaded"
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            NotificationChannels.ALERTS,
            "Video alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when a video is ready, needs a topic, failed or was uploaded"
        }
        manager.createNotificationChannels(listOf(progress, alerts))
    }
}
