package org.cloud.sonic.android.agent

enum class SonicLinkConnectionState {
    STOPPED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    ERROR
}

object SonicLinkStatus {
    @Volatile
    var serviceRunning: Boolean = false

    @Volatile
    var connectionState: SonicLinkConnectionState = SonicLinkConnectionState.STOPPED

    @Volatile
    var lastError: String? = null

    @Volatile
    var lastConnectedAt: Long = 0L

    @Volatile
    var lastHeartbeatAt: Long = 0L

    @Volatile
    var screenStreaming: Boolean = false

    @Volatile
    var lastStreamEvent: String? = null

    @Volatile
    var screenCaptureRevokedAt: Long = 0L
}
