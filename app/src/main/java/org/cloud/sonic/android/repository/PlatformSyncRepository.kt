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
        val json = prefs.getString(KEY_CONFIG, null) ?: return PlatformConfig()
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrElse { return PlatformConfig() }
        val hadLegacyUserToken = root.string("token").isNotBlank()
        val config = runCatching {
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
        if (hadLegacyUserToken) saveConfig(config)
        if (config.deviceCredentialPresent && secureTokenStore.get().isBlank()) {
            val invalid = config.copy(deviceCredentialPresent = false)
            saveConfig(invalid)
            return invalid
        }
        return config
    }

    fun saveConfig(config: PlatformConfig) {
        prefs.edit().putString(KEY_CONFIG, gson.toJson(config.copy(token = ""))).apply()
    }

    fun clearBinding() {
        secureTokenStore.clear()
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    suspend fun revokeBinding(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val config = getConfig()
            val deviceToken = secureTokenStore.get()
            if (config.serverUrl.isNotBlank() && config.deviceId.isNotBlank() && deviceToken.isNotBlank()) {
                val request = Request.Builder()
                    .url("${config.serverUrl}/api/v1/bug-submit/mobile/device")
                    .addHeader("X-Mobile-Device-Id", config.deviceId)
                    .addHeader("X-Mobile-Device-Token", deviceToken)
                    .delete()
                    .build()
                executeForData(request)
            }
            clearBinding()
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
            secureTokenStore.save(deviceToken)

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
            saveConfig(config)
            config
        }
    }

    suspend fun uploadAttachment(uri: Uri, fileName: String, projectId: Int? = null): Result<UploadedAttachment> =
        withContext(Dispatchers.IO) {
            val mimeType = appContext.contentResolver.getType(uri).orEmpty().ifBlank { mimeTypeFor(fileName) }
            val body = ContentUriRequestBody(appContext, uri, mimeType.toMediaTypeOrNull())
            upload(fileName, body, projectId)
        }

    suspend fun uploadAttachment(file: File, projectId: Int? = null): Result<UploadedAttachment> =
        withContext(Dispatchers.IO) {
            upload(file.name, file.asRequestBody(mimeTypeFor(file.name).toMediaTypeOrNull()), projectId)
        }

    fun getUploadHistory(): List<UploadedAttachment> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val listType = object : com.google.gson.reflect.TypeToken<List<UploadedAttachment>>() {}.type
            gson.fromJson<List<UploadedAttachment>>(json, listType)
        }.getOrDefault(emptyList())
    }

    private fun upload(fileName: String, fileBody: RequestBody, projectId: Int?): Result<UploadedAttachment> {
        return runCatching {
            val config = getConfig()
            require(config.isBound) { "未绑定测试平台，请先在缺陷协同页扫码" }
            val deviceToken = secureTokenStore.get()
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
        private const val KEY_HISTORY = "platform_upload_history_json"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaTypeOrNull()
    }
}
