package com.autotube.ai.core.common

/** Extras used by notifications to open a specific screen in MainActivity. */
object IntentKeys {
    const val EXTRA_PROJECT_ID = "com.autotube.ai.extra.PROJECT_ID"
    const val EXTRA_DESTINATION = "com.autotube.ai.extra.DESTINATION"

    const val DEST_PROGRESS = "progress"
    const val DEST_REVIEW = "review"
    const val DEST_ERROR = "error"
}

/** Notification channel ids, created in AutoTubeApp.onCreate. */
object NotificationChannels {
    const val PIPELINE = "pipeline_progress"
    const val ALERTS = "pipeline_alerts"
}
