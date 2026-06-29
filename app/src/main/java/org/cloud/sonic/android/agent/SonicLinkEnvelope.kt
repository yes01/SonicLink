package org.cloud.sonic.android.agent

import com.google.gson.JsonElement

data class SonicLinkEnvelope(
    val type: String,
    val requestId: String? = null,
    val deviceId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: JsonElement? = null
)
