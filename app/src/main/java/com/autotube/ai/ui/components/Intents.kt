package com.autotube.ai.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Walks the ContextWrapper chain to the hosting Activity (null in previews / non-activity contexts). */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Intents launched from the UI. Every helper shows a toast instead of crashing when nothing can handle it. */
object UiIntents {

    fun openUrl(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Opens the copy saved in the gallery (a content:// Uri from MediaStore). */
    fun openInGallery(context: Context, galleryUri: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(galleryUri), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, intent)
    }

    /** Shares the exported MP4 from app storage through the app's FileProvider. */
    fun shareVideo(context: Context, path: String, title: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "The video file is missing. Re-render it first.", Toast.LENGTH_LONG).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, "This video cannot be shared from here.", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(title, uri)
        start(context, Intent.createChooser(send, "Share video"))
    }

    fun shareText(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, subject))
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!tryStart(context, intent)) openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        start(context, intent)
    }

    fun openBatterySaverSettings(context: Context) {
        if (!tryStart(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) openAppDetails(context)
    }

    fun openStorageSettings(context: Context) {
        if (!tryStart(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))) openAppDetails(context)
    }

    private fun start(context: Context, intent: Intent) {
        if (!tryStart(context, intent)) {
            Toast.makeText(context, "No app on this phone can open this.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
