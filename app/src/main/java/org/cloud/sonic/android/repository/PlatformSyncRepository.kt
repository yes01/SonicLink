package org.cloud.sonic.android.repository

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.media.MobileMediaBridgeService
import org.cloud.sonic.android.model.PlatformConfig
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

data class UploadedAttachment(
    val fileId: String,
    val fileName: String,
    val url: String,
    val markdown: String,
    val type: String,
    val projectId: Int,
    val timestamp: Long = System.currentTimeMillis()
)

data class PlatformBindingCredential(
    val config: PlatformConfig,
    val deviceToken: String
)

class PlatformSyncRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val secureTokenStore = SecureTokenStore(appContext)
    private val configStore = SonicLinkConfigStore(appContext)
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    fun getConfig(): PlatformConfig {
        val bindings = getBindings().filter { it.isBound }
        if (bindings.isEmpty()) return PlatformConfig()
        val selectedKey = prefs.getString(KEY_SELECTED_BINDING, null)
        val selected = bindings.firstOrNull { it.bindingKey == selectedKey } ?: bindings.first()
        if (selected.bindingKey != selectedKey) selectBinding(selected.bindingKey)
        return selected
    }

    fun getBindings(): List<PlatformConfig> {
        migrateLegacyConfig()
        val json = prefs.getString(KEY_BINDINGS, null) ?: return emptyList()
        val list = runCatching {
            val listType = object : com.google.gson.reflect.TypeToken<List<PlatformConfig>>() {}.type
            gson.fromJson<List<PlatformConfig>>(json, listType) ?: emptyList()
        }.getOrDefault(emptyList())
        var changed = false
        val validated = list.map { config ->
            if (config.deviceCredentialPresent && secureTokenStore.get(config.bindingKey).isBlank()) {
                changed = true
                config.copy(deviceCredentialPresent = false, token = "")
            } else {
                config.copy(token = "")
            }
        }
        if (changed) saveBindings(validated)
        return validated
    }

    fun getBindingCredentials(): List<PlatformBindingCredential> =
        getBindings().filter { it.isBound }.mapNotNull { config ->
            secureTokenStore.get(config.bindingKey).takeIf { it.isNotBlank() }?.let { token ->
                PlatformBindingCredential(config, token)
            }
        }

    fun selectBinding(bindingKey: String): Boolean {
        val exists = getBindings().any { it.bindingKey == bindingKey && it.isBound }
        if (exists) prefs.edit().putString(KEY_SELECTED_BINDING, bindingKey).apply()
        return exists
    }

    fun saveConfig(config: PlatformConfig) {
        val bindings = getBindings().toMutableList()
        val index = bindings.indexOfFirst { it.bindingKey == config.bindingKey }
        val sanitized = config.copy(token = "")
        if (index >= 0) bindings[index] = sanitized else bindings.add(sanitized)
        saveBindings(bindings)
        prefs.edit().putString(KEY_SELECTED_BINDING, sanitized.bindingKey).apply()
    }

    fun clearBinding(bindingKey: String = getConfig().bindingKey) {
        if (bindingKey.isBlank()) return
        secureTokenStore.remove(bindingKey)
        val remaining = getBindings().filterNot { it.bindingKey == bindingKey }
        saveBindings(remaining)
        val selectedKey = prefs.getString(KEY_SELECTED_BINDING, null)
        if (selectedKey == bindingKey) {
            val editor = prefs.edit()
            remaining.firstOrNull { it.isBound }?.let { editor.putString(KEY_SELECTED_BINDING, it.bindingKey) }
                ?: editor.remove(KEY_SELECTED_BINDING)
            editor.apply()
        }
        MobileMediaBridgeService.sync(appContext, force = true)
    }

    private fun parseConfig(json: String): PlatformConfig {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { return PlatformConfig() }
        val hadLegacyUserToken = root.string("token").isNotBlank()
        return runCatching {
            PlatformConfig(
                serverUrl = root.string("serverUrl"),
                username = root.string("username"),
                userId = root.int("userId"),
                deviceId = root.string("deviceId"),
                sessionKey = root.string("sessionKey"),
                deviceCredentialPresent = !hadLegacyUserToken && root.boolean("deviceCredentialPresent"),
                defaultProjectId = root.int("defaultProjectId"),
                autoSyncScreenshots = root.boolean("autoSyncScreenshots"),
                boundAt = root.long("boundAt")
            )
        }.getOrElse { return PlatformConfig() }
    }

    private fun migrateLegacyConfig() {
        if (prefs.contains(KEY_BINDINGS)) return
        val legacyJson = prefs.getString(KEY_CONFIG, null)
        if (legacyJson.isNullOrBlank()) {
            saveBindings(emptyList())
            return
        }
        var config = parseConfig(legacyJson)
        if (config.serverUrl.isBlank() || config.userId <= 0) {
            saveBindings(emptyList())
            prefs.edit().remove(KEY_CONFIG).apply()
            return
        }
        val credentialPresent = config.deviceCredentialPresent && secureTokenStore.migrateLegacyToken(config.bindingKey)
        config = config.copy(deviceCredentialPresent = credentialPresent, token = "")
        saveBindings(listOf(config))
        prefs.edit()
            .putString(KEY_SELECTED_BINDING, config.bindingKey)
            .remove(KEY_CONFIG)
            .apply()
    }

    private fun saveBindings(bindings: List<PlatformConfig>) {
        prefs.edit().putString(KEY_BINDINGS, gson.toJson(bindings.map { it.copy(token = "") })).apply()
    }

    suspend fun revokeBinding(bindingKey: String = getConfig().bindingKey): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = getBindings().firstOrNull { it.bindingKey == bindingKey } ?: return@runCatching
            val deviceToken = secureTokenStore.get(config.bindingKey)
            if (config.serverUrl.isNotBlank() && config.deviceId.isNotBlank() && deviceToken.isNotBlank()) {
                val request = Request.Builder()
                    .url("${config.serverUrl}/api/v1/bug-submit/mobile/device")
                    .addHeader("X-Mobile-Device-Id", config.deviceId)
                    .addHeader("X-Mobile-Device-Token", deviceToken)
                    .delete()
                    .build()
                executeForData(request)
            }
            clearBinding(config.bindingKey)
        }
    }

    suspend fun pairWithQrCode(qrContent: String): Result<PlatformConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JsonParser.parseString(qrContent).asJsonObject
            if (payload.get("type")?.asString != "testmate_mobile_pairing") {
                error("不是 TestMate AI 手机配对二维码")
            }
            val baseUrl = normalizedServerUrl(payload.get("server_url")?.asString.orEmpty())
            val sessionKey = payload.get("session_key")?.asString.orEmpty()
            val pairingToken = payload.get("pairing_token")?.asString.orEmpty()
            require(sessionKey.isNotBlank() && pairingToken.isNotBlank()) { "配对二维码缺少必要字段" }

            val localConfig = configStore.getConfig()
            val deviceId = configStore.getOrCreateDeviceId()
            val body = JsonObject().apply {
                addProperty("session_key", sessionKey)
                addProperty("pairing_token", pairingToken)
                addProperty("device_id", deviceId)
                addProperty("device_name", localConfig.deviceName)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/v1/bug-submit/mobile/pair")
                .post(gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val data = executeForData(request)
            val deviceToken = data.get("device_token")?.asString.orEmpty()
            require(deviceToken.isNotBlank()) { "平台未返回设备凭证" }
            val config = PlatformConfig(
                serverUrl = baseUrl,
                username = data.get("username")?.asString.orEmpty(),
                userId = data.get("user_id")?.asInt ?: 0,
                deviceId = deviceId,
                sessionKey = data.get("session_key")?.asString ?: sessionKey,
                deviceCredentialPresent = true,
                defaultProjectId = data.get("project_id")?.asInt ?: payload.get("project_id")?.asInt ?: 0,
                boundAt = System.currentTimeMillis()
            )
            secureTokenStore.save(config.bindingKey, deviceToken)
            saveConfig(config)
            MobileMediaBridgeService.sync(appContext, force = true)
            config
        }
    }

    suspend fun uploadAttachment(
        uri: Uri,
        fileName: String,
        projectId: Int? = null,
        targetConfig: PlatformConfig? = null
    ): Result<UploadedAttachment> =
        withContext(Dispatchers.IO) {
            val mimeType = appContext.contentResolver.getType(uri).orEmpty().ifBlank { mimeTypeFor(fileName) }
            val body = ContentUriRequestBody(appContext, uri, mimeType.toMediaTypeOrNull())
            upload(fileName, body, projectId, targetConfig)
        }

    suspend fun uploadAttachment(
        file: File,
        projectId: Int? = null,
        targetConfig: PlatformConfig? = null
    ): Result<UploadedAttachment> =
        withContext(Dispatchers.IO) {
            upload(file.name, file.asRequestBody(mimeTypeFor(file.name).toMediaTypeOrNull()), projectId, targetConfig)
        }

    fun getUploadHistory(): List<UploadedAttachment> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val listType = object : com.google.gson.reflect.TypeToken<List<UploadedAttachment>>() {}.type
            gson.fromJson<List<UploadedAttachment>>(json, listType)
        }.getOrDefault(emptyList())
    }

    private fun upload(
        fileName: String,
        fileBody: RequestBody,
        projectId: Int?,
        targetConfig: PlatformConfig?
    ): Result<UploadedAttachment> {
        return runCatching {
            val config = targetConfig ?: getConfig()
            require(config.isBound) { "未绑定测试平台，请先在缺陷协同页扫码" }
            val deviceToken = secureTokenStore.get(config.bindingKey)
            require(deviceToken.isNotBlank()) { "设备凭证已失效，请重新扫码绑定" }
            if (projectId != null && projectId > 0 && projectId != config.defaultProjectId) {
                error("当前手机会话属于项目 ${config.defaultProjectId}，请在网页切换项目后重新扫码")
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_key", config.sessionKey)
                .addFormDataPart("file", sanitizedFileName(fileName), fileBody)
                .build()
            val request = Request.Builder()
                .url("${config.serverUrl}/api/v1/bug-submit/mobile/attachments/upload")
                .addHeader("X-Mobile-Device-Id", config.deviceId)
                .addHeader("X-Mobile-Device-Token", deviceToken)
                .post(requestBody)
                .build()
            val data = executeForData(request)
            UploadedAttachment(
                fileId = data.get("file_id")?.asString.orEmpty(),
                fileName = data.get("file_name")?.asString ?: fileName,
                url = data.get("url")?.asString.orEmpty(),
                markdown = data.get("markdown")?.asString.orEmpty(),
                type = data.get("type")?.asString ?: "file",
                projectId = data.get("project_id")?.asInt ?: config.defaultProjectId
            ).also(::saveToHistory)
        }
    }

    private fun executeForData(request: Request): JsonObject {
        okHttpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("请求失败 HTTP ${response.code}: ${responseBody.take(300)}")
            }
            val root = JsonParser.parseString(responseBody).asJsonObject
            val code = root.get("code")?.asInt ?: 200
            if (code != 0 && code != 200) {
                error(root.get("message")?.asString ?: "平台接口返回错误")
            }
            val data = root.get("data")
            return if (data != null && data.isJsonObject) data.asJsonObject else JsonObject()
        }
    }

    private fun saveToHistory(item: UploadedAttachment) {
        val current = getUploadHistory().toMutableList()
        current.add(0, item)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(current.take(50))).apply()
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.int(name: String): Int =
        get(name)?.takeIf { !it.isJsonNull }?.asInt ?: 0

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { !it.isJsonNull }?.asLong ?: 0L

    private fun JsonObject.boolean(name: String): Boolean =
        get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private fun normalizedServerUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        val uri = runCatching { URI(value) }.getOrElse { error("平台地址无效") }
        require(uri.scheme == "https" || uri.scheme == "http") { "平台地址必须使用 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "平台地址缺少主机名" }
        if (uri.scheme == "http") {
            require(isPrivateHost(uri.host)) { "非局域网平台必须使用 HTTPS" }
        }
        return value
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        return runCatching {
            val address = InetAddress.getByName(host)
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
        }.getOrDefault(false)
    }

    private fun mimeTypeFor(fileName: String): String = when (File(fileName).extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun sanitizedFileName(fileName: String): String =
        File(fileName).name.replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_").ifBlank { "attachment" }

    private class ContentUriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mediaType: MediaType?
    ) : RequestBody() {
        override fun contentType(): MediaType? = mediaType

        override fun contentLength(): Long =
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取附件")
            input.source().use { source -> sink.writeAll(source) }
        }
    }

    companion object {
        private const val PREFS_NAME = "sonic_link_platform_config"
        private const val KEY_CONFIG = "platform_config_json"
        private const val KEY_BINDINGS = "platform_bindings_json"
        private const val KEY_SELECTED_BINDING = "platform_selected_binding"
        private const val KEY_HISTORY = "platform_upload_history_json"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
