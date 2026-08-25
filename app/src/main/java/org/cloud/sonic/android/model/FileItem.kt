package org.cloud.sonic.android.model

import java.io.File

data class FileItem(
    val file: File,
    val name: String,
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long,
    val mimeType: String = "",
    val childCount: Int = 0,
    var isSelected: Boolean = false
)