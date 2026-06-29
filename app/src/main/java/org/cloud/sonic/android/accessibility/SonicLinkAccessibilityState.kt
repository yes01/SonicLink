package org.cloud.sonic.android.accessibility

object SonicLinkAccessibilityState {
    @Volatile
    var service: SonicLinkAccessibilityService? = null

    val isConnected: Boolean
        get() = service != null
}
