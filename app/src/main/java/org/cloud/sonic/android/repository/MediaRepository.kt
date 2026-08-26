package org.cloud.sonic.android.repository

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloud.sonic.android.model.MediaItem

class MediaRepository(private val context: Context) {

    fun hasImagePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasVideoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    suspend fun getImages(
        onlyScreenshots: Boolean = false,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        query: String = ""
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        if (!hasImagePermission()) {
            return@withContext items
        }

        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projectionList = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            projectionList.add(MediaStore.Images.Media.RELATIVE_PATH)
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection,
                projectionList.toTypedArray(),
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dataColumn = it.getColumnIndex(MediaStore.Images.Media.DATA)
                val widthColumn = it.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightColumn = it.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val bucketColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME) else -1
                val relativeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1

                var matchedCount = 0
                while (it.moveToNext() && items.size < limit.coerceAtLeast(1)) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn) ?: "image_$id"
                    val dateModified = it.getLong(dateColumn) * 1000L
                    val size = it.getLong(sizeColumn)
                    val mime = it.getString(mimeColumn) ?: "image/jpeg"
                    val path = if (dataColumn != -1) it.getString(dataColumn) ?: "" else ""
                    val width = if (widthColumn != -1) it.getInt(widthColumn) else 0
                    val height = if (heightColumn != -1) it.getInt(heightColumn) else 0
                    val bucketName = if (bucketColumn != -1) it.getString(bucketColumn) ?: "" else ""
                    val relativePath = if (relativeColumn != -1) it.getString(relativeColumn) ?: "" else ""

                    val isScreenshot = isScreenshotMedia(name, path, bucketName, relativePath)

                    val matchesQuery = query.isBlank() || name.contains(query, ignoreCase = true)
                    if ((!onlyScreenshots || isScreenshot) && matchesQuery) {
                        if (matchedCount++ < offset.coerceAtLeast(0)) continue
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                path = path,
                                name = name,
                                dateModified = dateModified,
                                size = size,
                                mimeType = mime,
                                isVideo = false,
                                width = width,
                                height = height,
                                isScreenshot = isScreenshot
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        items
    }

    suspend fun getVideos(
        onlyScreenRecordings: Boolean = false,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        query: String = ""
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        if (!hasVideoPermission()) {
            return@withContext items
        }

        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projectionList = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projectionList.add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            projectionList.add(MediaStore.Video.Media.RELATIVE_PATH)
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection,
                projectionList.toTypedArray(),
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dataColumn = it.getColumnIndex(MediaStore.Video.Media.DATA)
                val durationColumn = it.getColumnIndex(MediaStore.Video.Media.DURATION)
                val widthColumn = it.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightColumn = it.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val bucketColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME) else -1
                val relativeColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1

                var matchedCount = 0
                while (it.moveToNext() && items.size < limit.coerceAtLeast(1)) {
                    val id = it.getLong(idColumn)
                    val name = it.getString(nameColumn) ?: "video_$id"
                    val dateModified = it.getLong(dateColumn) * 1000L
                    val size = it.getLong(sizeColumn)
                    val mime = it.getString(mimeColumn) ?: "video/mp4"
                    val path = if (dataColumn != -1) it.getString(dataColumn) ?: "" else ""
                    val duration = if (durationColumn != -1) it.getLong(durationColumn) else 0L
                    val width = if (widthColumn != -1) it.getInt(widthColumn) else 0
                    val height = if (heightColumn != -1) it.getInt(heightColumn) else 0
                    val bucketName = if (bucketColumn != -1) it.getString(bucketColumn) ?: "" else ""
                    val relativePath = if (relativeColumn != -1) it.getString(relativeColumn) ?: "" else ""

                    val isScreenRecord = isScreenRecordingMedia(name, path, bucketName, relativePath)

                    val matchesQuery = query.isBlank() || name.contains(query, ignoreCase = true)
                    if ((!onlyScreenRecordings || isScreenRecord) && matchesQuery) {
                        if (matchedCount++ < offset.coerceAtLeast(0)) continue
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        items.add(
                            MediaItem(
                                id = id,
                                uri = contentUri,
                                path = path,
                                name = name,
                                dateModified = dateModified,
                                size = size,
                                mimeType = mime,
                                isVideo = true,
                                durationMs = duration,
                                width = width,
                                height = height,
                                isScreenRecording = isScreenRecord
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        items
    }

    private fun isScreenshotMedia(name: String, path: String, bucketName: String, relativePath: String): Boolean {
        if (bucketName.contains("screenshot", ignoreCase = true) || bucketName.contains("截屏", ignoreCase = true)) return true
        if (relativePath.contains("screenshot", ignoreCase = true) || relativePath.contains("截屏", ignoreCase = true)) return true
        val lower = (name + path).lowercase()
        return lower.contains("screenshot") || lower.contains("截屏") || lower.contains("screencapture")
    }

    private fun isScreenRecordingMedia(name: String, path: String, bucketName: String, relativePath: String): Boolean {
        if (bucketName.contains("screenrecord", ignoreCase = true) || bucketName.contains("录屏", ignoreCase = true)) return true
        if (relativePath.contains("screenrecord", ignoreCase = true) || relativePath.contains("录屏", ignoreCase = true)) return true
        val lower = (name + path).lowercase()
        return lower.contains("screenrecorder") || lower.contains("screen_recording") || lower.contains("screenrecord") || lower.contains("录屏")
    }
}
