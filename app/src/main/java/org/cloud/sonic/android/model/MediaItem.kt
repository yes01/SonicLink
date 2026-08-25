package org.cloud.sonic.android.model

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val path: String,
    val name: String,
    val dateModified: Long,
    val size: Long,
    val mimeType: String,
    val isVideo: Boolean = false,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val isScreenshot: Boolean = false,
    val isScreenRecording: Boolean = false,
    var isSelected: Boolean = false
)