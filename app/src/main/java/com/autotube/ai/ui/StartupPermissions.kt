package com.autotube.ai.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.autotube.ai.ui.components.findActivity

/** Small per-device UI flags (not settings): which permission prompts were already shown. */
internal object UiPrefs {
    private const val FILE = "autotube_ui_prefs"
    private const val KEY_NOTIF_ASKED = "notif_permission_asked"
    private const val KEY_NOTIF_RATIONALE = "notif_rationale_shown"
    private const val KEY_STORAGE_ASKED = "legacy_storage_asked"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun notificationAsked(context: Context) = prefs(context).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotificationAsked(context: Context) = prefs(context).edit { putBoolean(KEY_NOTIF_ASKED, true) }
    fun rationaleShown(context: Context) = prefs(context).getBoolean(KEY_NOTIF_RATIONALE, false)
    fun setRationaleShown(context: Context) = prefs(context).edit { putBoolean(KEY_NOTIF_RATIONALE, true) }
    fun storageAsked(context: Context) = prefs(context).getBoolean(KEY_STORAGE_ASKED, false)
    fun setStorageAsked(context: Context) = prefs(context).edit { putBoolean(KEY_STORAGE_ASKED, true) }
}

/** Runtime permission checks shared by the screens. */
object AppPermissions {
    /** Android 8/9 need WRITE_EXTERNAL_STORAGE to save the finished video to the gallery. */
    val needsLegacyStorage: Boolean get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P

    fun hasLegacyStorage(context: Context): Boolean =
        !needsLegacyStorage || granted(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Permissions for reading the user's own photos/videos as visuals. */
    fun galleryPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasGallery(context: Context): Boolean = galleryPermissions().any { granted(context, it) }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * First-launch permission prompts:
 * - API 33+: POST_NOTIFICATIONS, with a short rationale dialog if the user denied it once.
 * - API 26-28: WRITE_EXTERNAL_STORAGE, needed to save exported videos to the gallery.
 */
@Composable
fun StartupPermissions() {
    val context = LocalContext.current
    var showRationale by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val activity = context.findActivity()
            if (activity != null &&
                !UiPrefs.rationaleShown(context) &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            ) {
                showRationale = true
            }
        }
    }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!AppPermissions.hasNotifications(context) && !UiPrefs.notificationAsked(context)) {
                UiPrefs.setNotificationAsked(context)
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else if (AppPermissions.needsLegacyStorage) {
            if (!AppPermissions.hasLegacyStorage(context) && !UiPrefs.storageAsked(context)) {
                UiPrefs.setStorageAsked(context)
                storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    if (showRationale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                UiPrefs.setRationaleShown(context)
            },
            icon = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
            title = { Text("Stay in the loop") },
            text = {
                Text(
                    "Videos are made in the background and can take several minutes. Notifications tell you " +
                        "when to pick a topic, when your video is ready to review and if something goes wrong.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    UiPrefs.setRationaleShown(context)
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRationale = false
                    UiPrefs.setRationaleShown(context)
                }) { Text("Not now") }
            },
        )
    }
}
