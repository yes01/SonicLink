package org.cloud.sonic.android.agent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.cloud.sonic.android.ScreenCaptureState
import org.cloud.sonic.android.accessibility.SonicLinkAccessibilityService
import java.net.Inet4Address
import java.net.NetworkInterface

object SonicLinkDeviceInfo {
    fun collect(context: Context, config: SonicLinkConfig): SonicLinkDeviceStatus {
        val store = SonicLinkConfigStore(context)
        return SonicLinkDeviceStatus(
            deviceId = store.getOrCreateDeviceId(),
            deviceName = config.deviceName,
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            apkVersion = appVersion(context),
            display = displayInfo(context),
            accessibilityEnabled = isAccessibilityEnabled(context),
            screenCaptureGranted = ScreenCaptureState.hasPermission,
            screenStreaming = SonicLinkStatus.screenStreaming,
            batteryPercent = batteryPercent(context),
            wifiIp = wifiIp(context)
        )
    }

    fun displayInfo(context: Context): SonicLinkDisplayInfo {
        val metrics = context.resources.displayMetrics
        val windowManager = context.getSystemService<WindowManager>()
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: 0
        }
        return SonicLinkDisplayInfo(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            rotation = rotation,
            densityDpi = metrics.densityDpi
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expectedService = "${context.packageName}/${SonicLinkAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (enabledServices.isNullOrBlank()) {
            return false
        }
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        return splitter.any { it.equals(expectedService, ignoreCase = true) }
    }

    private fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    private fun batteryPercent(context: Context): Int? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) {
            return null
        }
        return (level * 100f / scale).toInt()
    }

    private fun wifiIp(context: Context): String? {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        val isWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager?.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
        if (!isWifi) {
            return networkInterfaceIp()
        }
        val wifiManager = context.applicationContext.getSystemService<WifiManager>()
        @Suppress("DEPRECATION")
        val wifiAddress = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (wifiAddress != 0) {
            return "%d.%d.%d.%d".format(
                wifiAddress and 0xff,
                wifiAddress shr 8 and 0xff,
                wifiAddress shr 16 and 0xff,
                wifiAddress shr 24 and 0xff
            )
        }
        return networkInterfaceIp()
    }

    private fun networkInterfaceIp(): String? {
        return NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
