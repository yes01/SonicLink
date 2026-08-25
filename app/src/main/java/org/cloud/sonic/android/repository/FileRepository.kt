package org.cloud.sonic.android.repository

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloud.sonic.android.model.FileItem
import java.io.File

class FileRepository(private val context: Context) {

    data class StorageStats(
        val totalBytes: Long,
        val freeBytes: Long,
        val usedBytes: Long,
        val usedPercentage: Int
    )

    fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRequestStorageAccessIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (e: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun getStorageStats(): StorageStats {
        val root = Environment.getExternalStorageDirectory()
        return try {
            val stat = StatFs(root.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val total = totalBlocks * blockSize
            val free = availableBlocks * blockSize
            val used = (total - free).coerceAtLeast(0L)
            val percentage = if (total > 0) ((used * 100) / total).toInt() else 0

            StorageStats(
                totalBytes = total,
                freeBytes = free,
                usedBytes = used,
                usedPercentage = percentage
            )
        } catch (e: Exception) {
            StorageStats(0L, 0L, 0L, 0)
        }
    }

    suspend fun getFiles(directory: File): List<FileItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<FileItem>()
        if (!directory.exists() || !directory.isDirectory) {
            return@withContext list
        }

        val files = directory.listFiles() ?: return@withContext list
        for (f in files) {
            if (f.name.startsWith(".")) continue
            val isDir = f.isDirectory
            val childCount = if (isDir) f.listFiles()?.size ?: 0 else 0
            list.add(
                FileItem(
                    file = f,
                    name = f.name,
                    path = f.absolutePath,
                    size = if (isDir) 0L else f.length(),
                    isDirectory = isDir,
                    lastModified = f.lastModified(),
                    mimeType = getMimeType(f),
                    childCount = childCount
                )
            )
        }

        list.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        list
    }

    suspend fun getCategoryFiles(category: String): List<FileItem> = withContext(Dispatchers.IO) {
        val results = mutableListOf<FileItem>()

        if (category.equals("downloads", ignoreCase = true)) {
            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return@withContext getFiles(dlDir)
        }

        if (hasStorageAccess()) {
            val root = Environment.getExternalStorageDirectory()
            when (category.lowercase()) {
                "apk" -> scanByExtensions(root, setOf("apk"), results, maxDepth = 4)
                "docs" -> scanByExtensions(root, setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt"), results, maxDepth = 4)
                "zip" -> scanByExtensions(root, setOf("zip", "rar", "7z", "tar", "gz"), results, maxDepth = 4)
            }
            results.sortByDescending { it.lastModified }
            return@withContext results
        }

        // Fallback to MediaStore query if full storage manager is not granted yet
        val extensions = when (category.lowercase()) {
            "apk" -> setOf("apk")
            "docs" -> setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt")
            "zip" -> setOf("zip", "rar", "7z", "tar", "gz")
            else -> emptySet()
        }
        queryMediaStoreByExtensions(extensions, results)
        results.sortByDescending { it.lastModified }
        results
    }

    private fun scanByExtensions(
        dir: File,
        extensions: Set<String>,
        accumulator: MutableList<FileItem>,
        currentDepth: Int = 0,
        maxDepth: Int = 4
    ) {
        if (currentDepth > maxDepth || !dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return

        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.name.equals("data", ignoreCase = true) && f.parentFile?.name.equals("Android", ignoreCase = true)) continue
                scanByExtensions(f, extensions, accumulator, currentDepth + 1, maxDepth)
            } else {
                val ext = f.extension.lowercase()
                if (extensions.contains(ext)) {
                    accumulator.add(
                        FileItem(
                            file = f,
                            name = f.name,
                            path = f.absolutePath,
                            size = f.length(),
                            isDirectory = false,
                            lastModified = f.lastModified(),
                            mimeType = getMimeType(f)
                        )
                    )
                }
            }
        }
    }

    private fun queryMediaStoreByExtensions(extensions: Set<String>, accumulator: MutableList<FileItem>) {
        if (extensions.isEmpty()) return
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )

        val selectionArgs = extensions.map { "%.$it" }.toTypedArray()
        val selection = extensions.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }

        try {
            val cursor: Cursor? = context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )
            cursor?.use {
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val mimeCol = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: continue
                    val file = File(path)
                    val name = it.getString(nameCol) ?: file.name
                    val size = it.getLong(sizeCol)
                    val dateModified = it.getLong(dateCol) * 1000L
                    val mime = if (mimeCol != -1) it.getString(mimeCol) ?: getMimeType(file) else getMimeType(file)

                    accumulator.add(
                        FileItem(
                            file = file,
                            name = name,
                            path = path,
                            size = size,
                            isDirectory = false,
                            lastModified = dateModified,
                            mimeType = mime
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "apk" -> "application/vnd.android.package-archive"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "zip", "rar", "7z" -> "application/zip"
            "txt" -> "text/plain"
            "jpg", "jpeg", "png", "webp" -> "image/*"
            "mp4", "mkv", "mov" -> "video/*"
            else -> "application/octet-stream"
        }
    }
}