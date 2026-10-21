package com.autotube.ai.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.autotube.ai.MainActivity
import com.autotube.ai.R
import com.autotube.ai.core.common.IntentKeys
import com.autotube.ai.core.common.NotificationChannels
import com.autotube.ai.core.model.PipelineError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds and posts every notification of the app: the ongoing progress notifications used by the
 * foreground workers and the one-shot alerts (topic pick, review ready, failure, uploaded).
 * Posting is skipped silently when the user has not granted notifications.
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager: NotificationManagerCompat = NotificationManagerCompat.from(context)

    // ------------------------------------------------------------------ foreground (ongoing)

    /** Ongoing notification for a running pipeline. [progress] null = indeterminate. */
    fun pipelineProgress(
        projectId: Long,
        title: String,
        text: String,
        progress: Float?,
        cancelIntent: PendingIntent?,
    ): Notification = ongoing(
        channel = NotificationChannels.PIPELINE,
        projectId = projectId,
        title = title,
        text = text,
        progress = progress,
        cancelIntent = cancelIntent,
    )

    /** Ongoing notification for a running upload. */
    fun uploadProgress(
        projectId: Long,
        title: String,
        progress: Float?,
        cancelIntent: PendingIntent?,
    ): Notification = ongoing(
        channel = NotificationChannels.PIPELINE,
        projectId = projectId,
        title = title,
        text = if (progress == null) "Preparing the upload…" else "Uploading to YouTube… ${(progress * 100).toInt()}%",
        progress = progress,
        cancelIntent = cancelIntent,
    )

    /** Foreground info for the pipeline: mediaProcessing|dataSync on 35+, dataSync on 29-34. */
    fun pipelineForegroundInfo(projectId: Long, notification: Notification): ForegroundInfo {
        val id = progressNotificationId(projectId)
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ForegroundInfo(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(
                id,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> ForegroundInfo(id, notification)
        }
    }

    /** Foreground info for an upload (dataSync on 29+). */
    fun uploadForegroundInfo(projectId: Long, notification: Notification): ForegroundInfo {
        val id = uploadNotificationId(projectId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    // ------------------------------------------------------------------ alerts

    fun notifyPickTopic(projectId: Long, title: String) {
        post(
            alertNotificationId(projectId),
            alert(
                projectId = projectId,
                title = "Pick a topic",
                text = "Topic ideas for \"$title\" are ready. Choose one to continue.",
                destination = IntentKeys.DEST_PROGRESS,
            ),
        )
    }

    fun notifyReviewReady(projectId: Long, title: String) {
        post(
            alertNotificationId(projectId),
            alert(
                projectId = projectId,
                title = "Your video is ready",
                text = "\"$title\" is exported and saved to your gallery. Tap to review it.",
                destination = IntentKeys.DEST_REVIEW,
            ),
        )
    }

    fun notifyFailed(projectId: Long, title: String, error: PipelineError) {
        post(
            alertNotificationId(projectId),
            alert(
                projectId = projectId,
                title = error.title,
                text = "\"$title\": ${error.message}",
                destination = IntentKeys.DEST_ERROR,
            ),
        )
    }

    fun notifyUploaded(projectId: Long, title: String, videoId: String) {
        val watch = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/$videoId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val watchIntent = PendingIntent.getActivity(
            context,
            requestCode(projectId, REQ_WATCH),
            watch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = alertBuilder(
            projectId = projectId,
            title = "Uploaded to YouTube",
            text = "\"$title\" is on your channel.",
            destination = IntentKeys.DEST_REVIEW,
        )
            .addAction(R.drawable.ic_notification, "Open on YouTube", watchIntent)
            .build()
        post(alertNotificationId(projectId), notification)
    }

    /** Re-posts an ongoing notification (used to update the text/progress of a foreground worker). */
    fun update(notificationId: Int, notification: Notification) {
        post(notificationId, notification)
    }

    fun cancelAlert(projectId: Long) {
        manager.cancel(alertNotificationId(projectId))
    }

    /** True when notifications can be shown (permission on API 33+ and not blocked by the user). */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    // ------------------------------------------------------------------ internals

    private fun ongoing(
        channel: String,
        projectId: Long,
        title: String,
        text: String,
        progress: Float?,
        cancelIntent: PendingIntent?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(projectId, IntentKeys.DEST_PROGRESS))
        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(PROGRESS_MAX, (progress.coerceIn(0f, 1f) * PROGRESS_MAX).toInt(), false)
        }
        if (cancelIntent != null) {
            builder.addAction(R.drawable.ic_notification, "Cancel", cancelIntent)
        }
        return builder.build()
    }

    private fun alert(projectId: Long, title: String, text: String, destination: String): Notification =
        alertBuilder(projectId, title, text, destination).build()

    private fun alertBuilder(projectId: Long, title: String, text: String, destination: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openApp(projectId, destination))

    private fun openApp(projectId: Long, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(IntentKeys.EXTRA_PROJECT_ID, projectId)
            .putExtra(IntentKeys.EXTRA_DESTINATION, destination)
        val code = when (destination) {
            IntentKeys.DEST_REVIEW -> REQ_REVIEW
            IntentKeys.DEST_ERROR -> REQ_ERROR
            else -> REQ_PROGRESS
        }
        return PendingIntent.getActivity(
            context,
            requestCode(projectId, code),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (!canPost()) return
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call; nothing else to do.
        }
    }

    companion object {
        private const val PROGRESS_MAX = 1000
        private const val REQ_PROGRESS = 0
        private const val REQ_REVIEW = 1
        private const val REQ_ERROR = 2
        private const val REQ_WATCH = 3

        private fun slot(projectId: Long): Int = (projectId % 100_000L).toInt().let { if (it < 0) -it else it }

        /** Id of the ongoing pipeline notification for a project. */
        fun progressNotificationId(projectId: Long): Int = 1_000_000 + slot(projectId) * 4

        /** Id of the ongoing upload notification for a project. */
        fun uploadNotificationId(projectId: Long): Int = 1_000_000 + slot(projectId) * 4 + 1

        /** Id of the alert notification for a project (one alert at a time per project). */
        fun alertNotificationId(projectId: Long): Int = 1_000_000 + slot(projectId) * 4 + 2

        private fun requestCode(projectId: Long, kind: Int): Int = slot(projectId) * 8 + kind
    }
}
