package org.cloud.sonic.android.logs

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloud.sonic.android.utils.ShizukuManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class YesLogCollector(private val context: Context) : AppLogCollector {
    override val targetApp = LogTargetApp.YES

    private data class ScannedFile(
        val path: String,
        val relativePath: String,
        val size: Long,
        val modifiedAt: Long,
        val kind: LogSourceKind,
        val file: File? = null
    )

    override fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(targetApp.packageName, 0)
    }.isSuccess

    override suspend fun scan(eventTime: Long, scenario: LogScenario): LogScanResult = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        var scanned = scanDirectRoot()
        var sourceSummary = "Yes 日志目录"

        if (scanned.isEmpty()) {
            if (runCatching { ShizukuManager.hasPermission() }.getOrDefault(false)) {
                scanned = scanWithShizuku().getOrElse {
                    warnings += "Shizuku 自动扫描失败：${it.message ?: "未知错误"}"
                    emptyList()
                }
                sourceSummary = if (scanned.isEmpty()) "公共下载目录" else "Yes 日志目录（Shizuku）"
            } else {
                warnings += "Android 未允许直接读取 Yes 目录，且当前没有 Shizuku 授权"
                sourceSummary = "公共下载目录"
            }
        }

        var matched = applyRules(scanned, eventTime, scenario)
        if (matched.isEmpty()) {
            val downloaded = scanDownloads()
            if (downloaded.isNotEmpty()) {
                matched = applyRules(downloaded, eventTime, scenario)
                sourceSummary = "公共下载目录"
            }
        }
        if (matched.isEmpty()) warnings += "未找到本地日志，可使用手动导入兜底"
        LogScanResult(matched, sourceSummary, warnings)
    }

    private fun scanDirectRoot(): List<ScannedFile> {
        val root = File(YES_ROOT)
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return emptyList()
        return runCatching {
            root.walkTopDown()
                .onEnter { !it.name.startsWith('.') }
                .filter { file ->
                    file.isFile && isPotentialLogFile(file.absolutePath) &&
                        runCatching { file.canonicalPath.startsWith("$canonicalRoot${File.separator}") }.getOrDefault(false)
                }
                .take(MAX_SCANNED_FILES)
                .map { file ->
                    ScannedFile(
                        path = file.absolutePath,
                        relativePath = file.relativeTo(root).invariantSeparatorsPath,
                        size = file.length(),
                        modifiedAt = file.lastModified(),
                        kind = LogSourceKind.DIRECT,
                        file = file
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private suspend fun scanWithShizuku(): Result<List<ScannedFile>> = runCatching {
        val paths = ShizukuManager.listFilesRecursively(YES_ROOT).getOrThrow()
        val result = mutableListOf<ScannedFile>()
        for (path in paths.asSequence().filter(::isPotentialLogFile).take(MAX_SCANNED_FILES)) {
            val stat = ShizukuManager.statFile(path)
            result += ScannedFile(
                path = path,
                relativePath = path.removePrefix("$YES_ROOT/"),
                size = stat.size,
                modifiedAt = stat.modifiedAt,
                kind = LogSourceKind.SHIZUKU
            )
        }
        result
    }

    private fun scanDownloads(): List<ScannedFile> {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: return emptyList()
        return runCatching {
            root.walkTopDown()
                .onEnter { !it.name.startsWith('.') }
                .filter { file ->
                    file.isFile && isPotentialLogFile(file.absolutePath) &&
                        runCatching { file.canonicalPath.startsWith("$canonicalRoot${File.separator}") }.getOrDefault(false)
                }
                .take(MAX_SCANNED_FILES)
                .map { file ->
                    ScannedFile(
                        path = file.absolutePath,
                        relativePath = "Download/${file.relativeTo(root).invariantSeparatorsPath}",
                        size = file.length(),
                        modifiedAt = file.lastModified(),
                        kind = LogSourceKind.DIRECT,
                        file = file
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun applyRules(files: List<ScannedFile>, eventTime: Long, scenario: LogScenario): List<LogCandidate> {
        if (files.isEmpty()) return emptyList()
        val eventDates = relatedDates(eventTime)
        val previousDate = formattedDate(eventTime - ONE_DAY_MS)
        val sorted = files.sortedByDescending { it.modifiedAt }
        val today = sorted.filter { matchesDate(it, eventDates) }
        val nearest = sorted.filter { it.modifiedAt > 0 }
            .minByOrNull { abs(it.modifiedAt - eventTime) }
            ?: sorted.firstOrNull()
        val selected = linkedMapOf<String, Pair<ScannedFile, String>>()

        fun add(file: ScannedFile?, reason: String) {
            if (file != null) selected.putIfAbsent(file.path, file to reason)
        }

        when (scenario) {
            LogScenario.GENERAL -> add(today.firstOrNull() ?: nearest, if (today.isNotEmpty()) "当天最新日志" else "最近修改日志")
            LogScenario.CRASH -> {
                today.take(12).forEach { add(it, "当天日志") }
                add(nearest, "最近修改日志")
            }
            else -> {
                val todayAndPrevious = sorted.filter { matchesDate(it, eventDates + previousDate) }
                todayAndPrevious.take(20).forEach { file ->
                    add(file, if (matchesDate(file, eventDates)) "当天日志" else "前一天日志")
                }
                add(sorted.firstOrNull { it.path.substringAfterLast('.').equals("zip", true) }, "最新压缩包")
                if (selected.isEmpty()) add(nearest, "最近修改日志")
            }
        }
        return selected.values.map { (file, reason) -> file.toCandidate(reason) }
    }

    private fun matchesDate(file: ScannedFile, dates: Set<String>): Boolean {
        val path = file.path.lowercase(Locale.ROOT)
        if (dates.any { path.contains(it) || path.contains(it.replace("-", "")) }) return true
        if (file.modifiedAt <= 0) return false
        return dates.contains(formattedDate(file.modifiedAt))
    }

    private fun relatedDates(eventTime: Long): Set<String> {
        val values = linkedSetOf(formattedDate(eventTime))
        val calendar = Calendar.getInstance().apply { timeInMillis = eventTime }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (minutes < 30) values += formattedDate(eventTime - THIRTY_MINUTES_MS)
        if (minutes >= 24 * 60 - 30) values += formattedDate(eventTime + THIRTY_MINUTES_MS)
        return values
    }

    private fun formattedDate(time: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    private fun isPotentialLogFile(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
        val extension = normalized.substringAfterLast('.', "")
        val supportedExtension = extension in setOf("log", "xlog", "zip", "txt", "dmp")
        return extension == "zip" || (supportedExtension && LOG_HINTS.any(normalized::contains))
    }

    private fun ScannedFile.toCandidate(reason: String): LogCandidate = LogCandidate(
        id = "${kind.name}:$path",
        displayName = File(path).name,
        relativePath = relativePath,
        size = size,
        modifiedAt = modifiedAt,
        reason = reason,
        sourceKind = kind,
        file = file,
        privilegedPath = if (kind == LogSourceKind.SHIZUKU) path else ""
    )

    companion object {
        const val YES_ROOT = "/storage/emulated/0/Android/data/com.funnyheart.fish/files"
        private val LOG_HINTS = listOf("log", "logs", "xlog", "mars", "zygote", "feedback")
        private const val MAX_SCANNED_FILES = 20_000
        private const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
        private const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    }
}
