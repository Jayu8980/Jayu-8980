package com.autotube.ai.ui.navigation

import android.content.Intent
import android.net.Uri
import com.autotube.ai.core.common.IntentKeys
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus

/** String routes for navigation-compose. */
object Routes {
    const val HOME = "home"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val STUDIO = "studio"

    const val ARG_PROJECT_ID = "projectId"
    const val PROGRESS = "progress/{$ARG_PROJECT_ID}"
    const val REVIEW = "review/{$ARG_PROJECT_ID}"
    const val ERROR = "error/{$ARG_PROJECT_ID}"

    const val ARG_STUDIO_ID = "studioId"
    const val STUDIO_EDITOR = "studio/edit/{$ARG_STUDIO_ID}"
    const val ARG_IMPORT_PATH = "path"
    const val ARG_IMPORT_NAME = "name"
    const val STUDIO_IMPORT = "studio/import?$ARG_IMPORT_PATH={$ARG_IMPORT_PATH}&$ARG_IMPORT_NAME={$ARG_IMPORT_NAME}"

    fun studioEditor(id: String) = "studio/edit/$id"
    fun studioImport(path: String, name: String) =
        "studio/import?$ARG_IMPORT_PATH=${Uri.encode(path)}&$ARG_IMPORT_NAME=${Uri.encode(name)}"

    fun progress(projectId: Long) = "progress/$projectId"
    fun review(projectId: Long) = "review/$projectId"
    fun error(projectId: Long) = "error/$projectId"

    /** Screen that fits a project's current status. */
    fun forProject(project: Project): String = when (project.status) {
        ProjectStatus.QUEUED,
        ProjectStatus.RUNNING,
        ProjectStatus.UPLOADING,
        ProjectStatus.AWAITING_TOPIC,
        ProjectStatus.CANCELLED -> progress(project.id)
        ProjectStatus.AWAITING_REVIEW,
        ProjectStatus.UPLOADED -> review(project.id)
        ProjectStatus.FAILED -> error(project.id)
    }
}

/** A navigation request coming from a notification tap. */
data class DeepLink(val projectId: Long, val destination: String) {
    val route: String
        get() = when (destination) {
            IntentKeys.DEST_REVIEW -> Routes.review(projectId)
            IntentKeys.DEST_ERROR -> Routes.error(projectId)
            else -> Routes.progress(projectId)
        }

    companion object {
        /** Reads [IntentKeys] extras (project id as Long; Int/String tolerated) and removes them. */
        fun consume(intent: Intent?): DeepLink? {
            if (intent == null) return null
            val extras = intent.extras ?: return null
            if (!extras.containsKey(IntentKeys.EXTRA_PROJECT_ID)) return null
            @Suppress("DEPRECATION")
            val id = when (val raw = extras.get(IntentKeys.EXTRA_PROJECT_ID)) {
                is Long -> raw
                is Int -> raw.toLong()
                is String -> raw.toLongOrNull()
                else -> null
            }
            val destination = intent.getStringExtra(IntentKeys.EXTRA_DESTINATION) ?: IntentKeys.DEST_PROGRESS
            intent.removeExtra(IntentKeys.EXTRA_PROJECT_ID)
            intent.removeExtra(IntentKeys.EXTRA_DESTINATION)
            if (id == null || id <= 0L) return null
            return DeepLink(id, destination)
        }
    }
}
