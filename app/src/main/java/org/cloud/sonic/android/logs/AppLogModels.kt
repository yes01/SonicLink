package org.cloud.sonic.android.logs

import android.net.Uri
import java.io.File

enum class LogScenario(val displayName: String) {
    GENERAL("通用问题"),
    CRASH("闪退/崩溃"),
    MESSAGE("网络/消息/推送"),
    RTC("语音/通话/房间"),
    DOWNLOAD("下载/资源失败")
}

enum class LogSourceKind(val displayName: String) {
    DIRECT("自动读取"),
    SHIZUKU("Shizuku"),
    DOCUMENT("手动导入")
}

data class LogCandidate(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val modifiedAt: Long,
    val reason: String,
    val sourceKind: LogSourceKind,
    val file: File? = null,
    val privilegedPath: String = "",
    val contentUri: Uri? = null
)

data class LogScanResult(
    val files: List<LogCandidate>,
    val sourceSummary: String,
    val warnings: List<String> = emptyList()
)
