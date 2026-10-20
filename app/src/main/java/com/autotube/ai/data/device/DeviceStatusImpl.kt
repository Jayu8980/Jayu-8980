package com.autotube.ai.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import com.autotube.ai.core.common.NetworkPolicyException
import com.autotube.ai.domain.DeviceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads storage, power-saver, network and battery state from the system services. All calls are
 * cheap, non-blocking binder queries and safe from any thread.
 */
@Singleton
class DeviceStatusImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceStatus {

    private val connectivity: ConnectivityManager? =
        context.getSystemService(ConnectivityManager::class.java)
    private val power: PowerManager? = context.getSystemService(PowerManager::class.java)
    private val battery: BatteryManager? = context.getSystemService(BatteryManager::class.java)

    override fun freeStorageMb(): Long {
        val bytes = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "StatFs failed for app storage", e)
            context.filesDir.usableSpace
        }
        return bytes / BYTES_PER_MB
    }

    override fun isPowerSaveMode(): Boolean = power?.isPowerSaveMode ?: false

    override fun isOnline(): Boolean {
        val caps = activeCapabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun isUnmetered(): Boolean {
        val caps = activeCapabilities() ?: return false
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
        ) {
            return true
        }
        // Wi-Fi / Ethernet count as "Wi-Fi" for the Wi-Fi-only option even if the router reports metered.
        val onCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val onLocalLink = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return onLocalLink && !onCellular
    }

    override fun batteryPercent(): Int {
        val fromProperty = try {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
        } catch (e: RuntimeException) {
            Int.MIN_VALUE
        }
        if (fromProperty in 0..100) return fromProperty

        // Fallback: the sticky ACTION_BATTERY_CHANGED broadcast (no receiver is registered).
        val sticky: Intent? = try {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (e: RuntimeException) {
            null
        }
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) return (level * 100 / scale).coerceIn(0, 100)
        // No battery information (e.g. devices without a battery): treat as fully powered.
        return UNKNOWN_BATTERY_PERCENT
    }

    override fun requireNetworkForTransfer(wifiOnly: Boolean) {
        if (!isOnline()) {
            throw NetworkPolicyException("No internet connection.")
        }
        if (wifiOnly && !isUnmetered()) {
            throw NetworkPolicyException(
                "Wi-Fi only is on in Settings and you are on mobile data. Connect to Wi-Fi or turn the option off.",
            )
        }
    }

    private fun activeCapabilities(): NetworkCapabilities? {
        val cm = connectivity ?: return null
        return try {
            val network = cm.activeNetwork ?: return null
            cm.getNetworkCapabilities(network)
        } catch (e: SecurityException) {
            Log.w(TAG, "Network state not readable", e)
            null
        }
    }

    private companion object {
        const val TAG = "AutoTube"
        const val BYTES_PER_MB = 1024L * 1024L
        const val UNKNOWN_BATTERY_PERCENT = 100
    }
}
