package org.cloud.sonic.android.agent

data class SonicLinkDeviceStatus(
    val deviceId: String,
    val deviceName: String,
    val brand: String,
    val model: String,
    val androidVersion: String,
    val apiLevel: Int,
    val apkVersion: String,
    val display: SonicLinkDisplayInfo,
    val accessibilityEnabled: Boolean,
    val screenCaptureGranted: Boolean,
    val screenStreaming: Boolean,
    val batteryPercent: Int?,
    val wifiIp: String?
)
