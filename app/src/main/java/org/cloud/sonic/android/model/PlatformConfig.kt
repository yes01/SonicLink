package org.cloud.sonic.android.model

data class PlatformConfig(
    val serverUrl: String = "",
    val token: String = "",
    val username: String = "",
    val userId: Int = 0,
    val deviceId: String = "",
    val sessionKey: String = "",
    val deviceCredentialPresent: Boolean = false,
    val defaultProjectId: Int = 0,
    val autoSyncScreenshots: Boolean = false,
    val boundAt: Long = 0L
) {
    val bindingKey: String
        get() = "${serverUrl.trim().trimEnd('/')}|$userId"

    val isBound: Boolean
        get() = serverUrl.isNotBlank() && deviceId.isNotBlank() && sessionKey.isNotBlank() && deviceCredentialPresent
}
