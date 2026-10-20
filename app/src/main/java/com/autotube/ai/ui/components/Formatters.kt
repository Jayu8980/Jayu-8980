package com.autotube.ai.ui.components

import androidx.compose.ui.graphics.Color
import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.Project
import com.autotube.ai.core.model.ProjectStatus
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.ui.theme.AutoTubeColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Small, locale-safe formatting helpers used by the screens. */
object Format {
    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
    private val dateTimeLongFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm", Locale.getDefault())
    private val dayFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /** 45 -> "45 s", 480 -> "8 min", 90 -> "1:30 min". */
    fun durationSec(sec: Int): String = when {
        sec < 60 -> "$sec s"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> String.format(Locale.US, "%d:%02d min", sec / 60, sec % 60)
    }

    /** Milliseconds as m:ss. */
    fun clock(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    fun date(millis: Long): String =
        dateFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun dateTimeLong(millis: Long): String =
        dateTimeLongFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun day(millis: Long): String =
        dayFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun time(millis: Long): String =
        timeFormatter.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%"
}

/** Language name written in its own script, e.g. "हिन्दी". */
val AppLanguage.nativeLabel: String
    get() = when (this) {
        AppLanguage.ENGLISH -> "English"
        AppLanguage.HINDI -> "हिन्दी"
    }

/** "Shorts · 60 s · English" */
fun Project.summaryLine(): String {
    val durationSec = artifacts.render?.durationMs?.let { (it / 1000L).toInt() } ?: request.targetDurationSec
    return "${request.format.label} · ${Format.durationSec(durationSec)} · ${request.language.nativeLabel}"
}

val ProjectStatus.label: String
    get() = when (this) {
        ProjectStatus.QUEUED -> "Queued"
        ProjectStatus.RUNNING -> "Creating"
        ProjectStatus.AWAITING_TOPIC -> "Pick a topic"
        ProjectStatus.AWAITING_REVIEW -> "Ready to review"
        ProjectStatus.UPLOADING -> "Uploading"
        ProjectStatus.UPLOADED -> "Uploaded"
        ProjectStatus.FAILED -> "Failed"
        ProjectStatus.CANCELLED -> "Cancelled"
    }

val ProjectStatus.color: Color
    get() = when (this) {
        ProjectStatus.QUEUED -> AutoTubeColors.Grey
        ProjectStatus.RUNNING -> AutoTubeColors.Blue
        ProjectStatus.AWAITING_TOPIC -> AutoTubeColors.Amber
        ProjectStatus.AWAITING_REVIEW -> AutoTubeColors.Teal
        ProjectStatus.UPLOADING -> AutoTubeColors.Purple
        ProjectStatus.UPLOADED -> AutoTubeColors.Green
        ProjectStatus.FAILED -> AutoTubeColors.Red
        ProjectStatus.CANCELLED -> AutoTubeColors.Grey
    }

val Emotion.label: String
    get() = when (this) {
        Emotion.SUSPENSE -> "Suspense"
        Emotion.HAPPY -> "Happy"
        Emotion.SAD -> "Sad"
        Emotion.EXCITING -> "Exciting"
        Emotion.CALM -> "Calm"
        Emotion.DRAMATIC -> "Dramatic"
        Emotion.INSPIRING -> "Inspiring"
        Emotion.FUNNY -> "Funny"
    }

val Emotion.color: Color
    get() = when (this) {
        Emotion.SUSPENSE -> AutoTubeColors.Purple
        Emotion.HAPPY -> AutoTubeColors.Amber
        Emotion.SAD -> AutoTubeColors.Blue
        Emotion.EXCITING -> AutoTubeColors.Orange
        Emotion.CALM -> AutoTubeColors.Teal
        Emotion.DRAMATIC -> AutoTubeColors.Red
        Emotion.INSPIRING -> AutoTubeColors.Green
        Emotion.FUNNY -> AutoTubeColors.Amber
    }

val SceneRole.label: String
    get() = when (this) {
        SceneRole.HOOK -> "Hook"
        SceneRole.INTRO -> "Intro"
        SceneRole.MAIN -> "Main"
        SceneRole.CTA -> "Call to action"
    }
