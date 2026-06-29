package org.cloud.sonic.android.agent

import android.content.Context
import android.os.Build
import java.util.UUID

class SonicLinkConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConfig(): SonicLinkConfig {
        return SonicLinkConfig(
            serverHost = prefs.getString(KEY_SERVER_HOST, "").orEmpty(),
            httpUrl = prefs.getString(KEY_HTTP_URL, "").orEmpty(),
            webSocketUrl = prefs.getString(KEY_WS_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            deviceName = prefs.getString(KEY_DEVICE_NAME, null) ?: defaultDeviceName(),
            autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false)
        )
    }

    fun saveConfig(config: SonicLinkConfig) {
        prefs.edit()
            .putString(KEY_SERVER_HOST, config.serverHost.trim())
            .putString(KEY_HTTP_URL, config.httpUrl.trim())
            .putString(KEY_WS_URL, config.webSocketUrl.trim())
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_DEVICE_NAME, config.deviceName.trim().ifBlank { defaultDeviceName() })
            .putBoolean(KEY_AUTO_CONNECT, config.autoConnect)
            .apply()
    }

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun defaultDeviceName(): String {
        val rawManufacturer = Build.MANUFACTURER.orEmpty()
        val manufacturer = if (rawManufacturer.isBlank()) {
            ""
        } else {
            rawManufacturer.substring(0, 1).uppercase() + rawManufacturer.substring(1)
        }
        val model = Build.MODEL.orEmpty()
        return "$manufacturer $model".trim().ifBlank { "Android Device" }
    }

    companion object {
        private const val PREFS_NAME = "sonic_link_config"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_HTTP_URL = "http_url"
        private const val KEY_WS_URL = "web_socket_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
