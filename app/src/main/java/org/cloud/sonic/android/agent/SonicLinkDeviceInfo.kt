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
import android.view.DisplayCutout
import android.view.WindowManager
import android.view.WindowMetrics
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
            densityDpi = metrics.densityDpi,
            insets = displayInsets(context, windowManager)
        )
    }

    private fun displayInsets(context: Context, windowManager: WindowManager?): SonicLinkDisplayInsets {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics? = windowManager?.currentWindowMetrics
            val windowInsets = windowMetrics?.windowInsets
            val statusInsets = windowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())
            val navigationInsets = windowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())
            val cutout = windowInsets?.displayCutout
            SonicLinkDisplayInsets(
                statusBarTop = statusInsets?.top ?: 0,
                navigationBarBottom = navigationInsets?.bottom ?: 0,
                cutoutTop = cutout?.safeInsetTop ?: 0,
                cutoutBottom = cutout?.safeInsetBottom ?: 0,
                cutoutLeft = cutout?.safeInsetLeft ?: 0,
                cutoutRight = cutout?.safeInsetRight ?: 0
            )
        } else {
            SonicLinkDisplayInsets(
                statusBarTop = resourceSize(context, "status_bar_height"),
                navigationBarBottom = resourceSize(context, "navigation_bar_height"),
                cutoutTop = 0,
                cutoutBottom = 0,
                cutoutLeft = 0,
                cutoutRight = 0
            )
        }
    }

    private fun resourceSize(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
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
