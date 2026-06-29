package org.cloud.sonic.android.agent

data class SonicLinkConfig(
    val serverHost: String,
    val httpUrl: String,
    val webSocketUrl: String,
    val token: String,
    val deviceName: String,
    val autoConnect: Boolean
) {
    val isReady: Boolean
        get() = webSocketUrl.isNotBlank()

    companion object {
        fun empty(defaultDeviceName: String): SonicLinkConfig {
            return SonicLinkConfig(
                serverHost = "",
                httpUrl = "",
                webSocketUrl = "",
                token = "",
                deviceName = defaultDeviceName,
                autoConnect = false
            )
        }
    }
}
