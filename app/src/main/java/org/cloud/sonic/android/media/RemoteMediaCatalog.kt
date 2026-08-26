package org.cloud.sonic.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.cloud.sonic.android.model.PlatformConfig
import org.cloud.sonic.android.repository.FileRepository
import org.cloud.sonic.android.repository.MediaRepository
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class RemoteMediaCatalog(private val context: Context) {
    private data class Source(
        val bindingKey: String,
        val uri: Uri? = null,
        val file: File? = null,
        val name: String,
        val mimeType: String,
        val mediaStoreId: Long = 0L,
        val mediaType: String = "file"
    )

    private val mediaRepository = MediaRepository(context)
    private val fileRepository = FileRepository(context)
    private val platformRepository = PlatformSyncRepository(context)
    private val sources = ConcurrentHashMap<String, Source>()
    private val thumbnailSemaphore = Semaphore(2)

    suspend fun list(bindingKey: String, payload: JsonObject): JsonObject {
        val type = payload.string("mediaType").ifBlank { "image" }
        val offset = payload.int("offset", 0).coerceAtLeast(0)
        val limit = payload.int("limit", 40).coerceIn(1, 80)
        val query = payload.string("query").trim()
        return when (type) {
            "image" -> listImages(bindingKey, offset, limit, query, payload.boolean("screenshotsOnly"))
            "video" -> listVideos(bindingKey, offset, limit, query, payload.boolean("screenRecordsOnly"))
            "file" -> listFiles(bindingKey, offset, limit, query, payload.string("rootId"))
            else -> errorPayload("unsupported_media_type", "不支持的媒体类型")
        }
    }

    suspend fun thumbnail(bindingKey: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val source = resolve(bindingKey, payload.string("itemId"))
            ?: return@withContext errorPayload("item_expired", "媒体条目已失效，请刷新列表")
        val width = payload.int("width", 320).coerceIn(96, 1600)
        val height = payload.int("height", 320).coerceIn(96, 1600)
        thumbnailSemaphore.withPermit {
            val bitmap = createThumbnail(source, width, height)
                ?: return@withPermit errorPayload("preview_unavailable", "该文件暂不支持预览")
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)
            bitmap.recycle()
            JsonObject().apply {
                addProperty("success", true)
                addProperty("contentType", "image/jpeg")
                addProperty("data", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            }
        }
    }

    suspend fun attach(bindingKey: String, payload: JsonObject): JsonObject {
        val config = platformRepository.getBindings().firstOrNull { it.bindingKey == bindingKey && it.isBound }
            ?: return errorPayload("binding_missing", "平台账号绑定已失效")
        val sessionKey = payload.string("sessionKey")
        val projectId = payload.int("projectId", config.defaultProjectId)
        val ids = payload.getAsJsonArray("itemIds")?.mapNotNull { it.asString }?.take(20).orEmpty()
        if (sessionKey.isBlank() || ids.isEmpty()) return errorPayload("invalid_payload", "请选择要添加的媒体")

        var successCount = 0
        val errors = JsonArray()
        val target = config.copy(sessionKey = sessionKey, defaultProjectId = projectId)
        ids.forEach { itemId ->
            val source = resolve(bindingKey, itemId)
            if (source == null) {
                errors.add("媒体条目已失效")
                return@forEach
            }
            val result = when {
                source.uri != null -> platformRepository.uploadAttachment(source.uri, source.name, targetConfig = target)
                source.file != null -> platformRepository.uploadAttachment(source.file, targetConfig = target)
                else -> Result.failure(IllegalStateException("无法读取媒体"))
            }
            result.onSuccess { successCount++ }.onFailure { errors.add("${source.name}: ${it.message ?: "上传失败"}") }
        }
        return JsonObject().apply {
            addProperty("success", errors.size() == 0)
            addProperty("successCount", successCount)
            addProperty("failCount", errors.size())
            add("errors", errors)
        }
    }

    fun capabilities(): JsonObject = JsonObject().apply {
        addProperty("images", mediaRepository.hasImagePermission())
        addProperty("videos", mediaRepository.hasVideoPermission())
        addProperty("files", fileRepository.hasStorageAccess())
    }

    private suspend fun listImages(bindingKey: String, offset: Int, limit: Int, query: String, screenshotsOnly: Boolean): JsonObject {
        if (!mediaRepository.hasImagePermission()) return permissionPayload("image")
        val items = mediaRepository.getImages(screenshotsOnly, offset, limit + 1, query)
        return listPayload(items.take(limit).map { item ->
            register(bindingKey, Source(bindingKey, uri = item.uri, name = item.name, mimeType = item.mimeType, mediaStoreId = item.id, mediaType = "image")) {
                addProperty("name", item.name)
                addProperty("size", item.size)
                addProperty("modifiedAt", item.dateModified)
                addProperty("mimeType", item.mimeType)
                addProperty("width", item.width)
                addProperty("height", item.height)
                addProperty("isScreenshot", item.isScreenshot)
            }
        }, items.size > limit, offset)
    }

    private suspend fun listVideos(bindingKey: String, offset: Int, limit: Int, query: String, screenRecordsOnly: Boolean): JsonObject {
        if (!mediaRepository.hasVideoPermission()) return permissionPayload("video")
        val items = mediaRepository.getVideos(screenRecordsOnly, offset, limit + 1, query)
        return listPayload(items.take(limit).map { item ->
            register(bindingKey, Source(bindingKey, uri = item.uri, name = item.name, mimeType = item.mimeType, mediaStoreId = item.id, mediaType = "video")) {
                addProperty("name", item.name)
                addProperty("size", item.size)
                addProperty("modifiedAt", item.dateModified)
                addProperty("mimeType", item.mimeType)
                addProperty("durationMs", item.durationMs)
                addProperty("width", item.width)
                addProperty("height", item.height)
                addProperty("isScreenRecord", item.isScreenRecording)
            }
        }, items.size > limit, offset)
    }

    private suspend fun listFiles(bindingKey: String, offset: Int, limit: Int, query: String, rootId: String): JsonObject {
        if (!fileRepository.hasStorageAccess()) return permissionPayload("file")
        val directory = if (rootId.isBlank()) Environment.getExternalStorageDirectory() else resolve(bindingKey, rootId)?.file
        if (directory == null || !directory.isDirectory) return errorPayload("directory_missing", "目录已失效，请返回根目录")
        val all = fileRepository.getFiles(directory).filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        val page = all.drop(offset).take(limit)
        val parentId = directory.parentFile?.takeIf { it.absolutePath.startsWith(Environment.getExternalStorageDirectory().absolutePath) }?.let { parent ->
            registerToken(bindingKey, Source(bindingKey, file = parent, name = parent.name, mimeType = "inode/directory"))
        }.orEmpty()
        val rows = page.map { item ->
            register(bindingKey, Source(bindingKey, file = item.file, name = item.name, mimeType = item.mimeType)) {
                addProperty("name", item.name)
                addProperty("size", item.size)
                addProperty("modifiedAt", item.lastModified)
                addProperty("mimeType", item.mimeType)
                addProperty("isDirectory", item.isDirectory)
                addProperty("childCount", item.childCount)
            }
        }
        return listPayload(rows, offset + page.size < all.size, offset).apply {
            addProperty("parentId", parentId)
            addProperty("pathLabel", if (directory == Environment.getExternalStorageDirectory()) "手机存储" else directory.name)
        }
    }

    private fun register(bindingKey: String, source: Source, block: JsonObject.() -> Unit): JsonObject = JsonObject().apply {
        addProperty("id", registerToken(bindingKey, source))
        addProperty("mediaType", source.mediaType)
        block()
    }

    private fun registerToken(bindingKey: String, source: Source): String {
        trimRegistry()
        val token = UUID.randomUUID().toString()
        sources[token] = source.copy(bindingKey = bindingKey)
        return token
    }

    private fun resolve(bindingKey: String, token: String): Source? = sources[token]?.takeIf { it.bindingKey == bindingKey }

    private fun trimRegistry() {
        if (sources.size <= 4000) return
        sources.keys.take(1000).forEach(sources::remove)
    }

    private fun listPayload(items: List<JsonObject>, hasMore: Boolean, offset: Int) = JsonObject().apply {
        addProperty("success", true)
        add("items", JsonArray().also { array -> items.forEach(array::add) })
        addProperty("hasMore", hasMore)
        addProperty("nextOffset", offset + items.size)
    }

    private fun permissionPayload(type: String) = errorPayload("permission_required", when (type) {
        "image" -> "手机尚未授予照片访问权限"
        "video" -> "手机尚未授予视频访问权限"
        else -> "手机尚未授予所有文件访问权限"
    })

    private fun errorPayload(code: String, message: String) = JsonObject().apply {
        addProperty("success", false)
        addProperty("code", code)
        addProperty("message", message)
    }

    private fun createThumbnail(source: Source, width: Int, height: Int): Bitmap? {
        source.uri?.let { uri ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return runCatching { context.contentResolver.loadThumbnail(uri, Size(width, height), null) }.getOrNull()
            }
            val kind = MediaStore.Images.Thumbnails.MINI_KIND
            return if (source.mediaType == "video") {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, source.mediaStoreId, kind, null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, source.mediaStoreId, kind, null)
            }
        }
        val file = source.file ?: return null
        if (source.mimeType.startsWith("video/")) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(width, height), null) }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
            }
        }
        if (!source.mimeType.startsWith("image/")) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = max(1, max(bounds.outWidth / width, bounds.outHeight / height))
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.int(name: String, default: Int): Int = get(name)?.takeIf { !it.isJsonNull }?.asInt ?: default
    private fun JsonObject.boolean(name: String): Boolean = get(name)?.takeIf { !it.isJsonNull }?.asBoolean ?: false
}
