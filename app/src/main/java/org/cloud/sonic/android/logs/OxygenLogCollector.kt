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

class OxygenLogCollector(private val context: Context) : AppLogCollector {
    override val targetApp = LogTargetApp.OXYGEN
    private data class ScannedFile(
        val path: String,
        val relativePath: String,
        val size: Long,
        val modifiedAt: Long,
        val kind: LogSourceKind,
        val file: File? = null
    )

    override fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(OXYGEN_PACKAGE, 0)
    }.isSuccess

    override suspend fun scan(eventTime: Long, scenario: LogScenario): LogScanResult = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        var scanned = scanDirectRoot()
        var sourceSummary = "氧气日志目录"

        if (scanned.isEmpty()) {
            if (runCatching { ShizukuManager.hasPermission() }.getOrDefault(false)) {
                val result = scanWithShizuku()
                scanned = result.getOrElse {
                    warnings += "Shizuku 自动扫描失败：${it.message ?: "未知错误"}"
                    emptyList()
                }
                sourceSummary = if (scanned.isEmpty()) "公共下载目录" else "氧气日志目录（Shizuku）"
            } else {
                warnings += "Android 未允许直接读取氧气目录，且当前没有 Shizuku 授权"
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
        if (matched.isEmpty()) {
            warnings += "没有找到符合当前日期和问题类型的日志，可使用手动导入兜底"
        }
        LogScanResult(matched, sourceSummary, warnings)
    }

    private fun scanDirectRoot(): List<ScannedFile> {
        val result = mutableListOf<ScannedFile>()
        for ((label, rootPath) in OXYGEN_ROOTS) {
            val root = File(rootPath)
            if (!root.isDirectory || !root.canRead()) continue
            val canonicalRoot = runCatching { root.canonicalPath }.getOrNull() ?: continue
            runCatching {
                root.walkTopDown()
                    .onEnter { directory -> !directory.name.startsWith('.') }
                    .filter { file ->
                        file.isFile && !file.name.startsWith('.') && isPotentialLogFile(file.absolutePath) &&
                            runCatching { file.canonicalPath.startsWith("$canonicalRoot${File.separator}") }.getOrDefault(false)
                    }
                    .take(MAX_SCANNED_FILES - result.size)
                    .forEach { file ->
                        result += ScannedFile(
                            path = file.absolutePath,
                            relativePath = "$label/${file.relativeTo(root).invariantSeparatorsPath}",
                            size = file.length(),
                            modifiedAt = file.lastModified(),
                            kind = LogSourceKind.DIRECT,
                            file = file
                        )
                    }
            }
            if (result.size >= MAX_SCANNED_FILES) break
        }
        return result
    }

    private suspend fun scanWithShizuku(): Result<List<ScannedFile>> {
        return runCatching {
            val result = mutableListOf<ScannedFile>()
            for ((label, rootPath) in OXYGEN_ROOTS) {
                val paths = ShizukuManager.listFilesRecursively(rootPath).getOrElse { emptyList() }
                for (path in paths.asSequence().filter(::isPotentialLogFile).take(MAX_SCANNED_FILES - result.size)) {
                    val stat = ShizukuManager.statFile(path)
                    result += ScannedFile(
                        path = path,
                        relativePath = "$label/${path.removePrefix("$rootPath/")}",
                        size = stat.size,
                        modifiedAt = stat.modifiedAt,
                        kind = LogSourceKind.SHIZUKU
                    )
                }
                if (result.size >= MAX_SCANNED_FILES) break
            }
            result
        }
    }

    private fun scanDownloads(): List<ScannedFile> {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!root.isDirectory || !root.canRead()) return emptyList()
        return runCatching {
            val canonicalRoot = root.canonicalPath
            root.walkTopDown()
                .onEnter { directory -> !directory.name.startsWith('.') }
                .filter {
                    it.isFile && isPotentialLogFile(it.absolutePath) &&
                        runCatching { it.canonicalPath.startsWith("$canonicalRoot${File.separator}") }.getOrDefault(false)
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
        val dates = relatedDates(eventTime)
        val selected = linkedMapOf<String, Pair<ScannedFile, String>>()
        val normalized = files.associateWith { it.path.replace('\\', '/').lowercase(Locale.ROOT) }

        fun addMatches(reason: String, predicate: (ScannedFile, String) -> Boolean) {
            normalized.forEach { (file, path) ->
                if (predicate(file, path)) selected.putIfAbsent(file.path, file to reason)
            }
        }

        addMatches("主业务日志") { file, path ->
            !isDownloaderLog(path) && dates.any { fileName(file.path) == "logs_$it.xlog" }
        }
        if (selected.values.none { it.second == "主业务日志" }) {
            addMatches("主业务日志（兼容文件）") { file, path ->
                !isDownloaderLog(path) && fileName(file.path) == "logs.txt"
            }
        }

        addMatches("未捕获异常日志") { file, _ -> dates.any { fileName(file.path) == "uncaught_exception_$it.xlog" } }
        if (selected.values.none { it.second == "未捕获异常日志" }) {
            addMatches("未捕获异常日志（兼容文件）") { file, _ -> fileName(file.path) == "uncaught_exception.txt" }
        }

        when (scenario) {
            LogScenario.CRASH -> {
                normalized.keys
                    .filter { fileName(it.path).endsWith(".dmp") }
                    .sortedBy { if (it.modifiedAt > 0) abs(it.modifiedAt - eventTime) else Long.MAX_VALUE }
                    .take(3)
                    .forEach { selected.putIfAbsent(it.path, it to "崩溃转储") }
            }
            LogScenario.MESSAGE -> addMatches("IM/消息专项日志") { file, path ->
                path.contains("/log/tencent/imsdk/") && dates.any { fileName(file.path) == "imsdk_c_$it.xlog" }
            }
            LogScenario.RTC -> addMatches("音视频/房间专项日志") { file, _ ->
                val name = fileName(file.path)
                name == "nertc_sdk.log" || dates.any { name.startsWith("liteav_c_$it") || name.startsWith("qavsdk_$it") }
            }
            LogScenario.DOWNLOAD -> addMatches("下载器专项日志") { file, path ->
                isDownloaderLog(path) && dates.any { fileName(file.path) == "logs_$it.xlog" }
            }
            LogScenario.GENERAL -> Unit
        }

        return selected.values.map { (file, reason) -> file.toCandidate(reason) }
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

    private fun relatedDates(eventTime: Long): Set<String> {
        val formatter = SimpleDateFormat("yyyyMMdd", Locale.US)
        val values = linkedSetOf(formatter.format(Date(eventTime)))
        val calendar = Calendar.getInstance().apply { timeInMillis = eventTime }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        if (minutes < 30) values += formatter.format(Date(eventTime - THIRTY_MINUTES_MS))
        if (minutes >= 24 * 60 - 30) values += formatter.format(Date(eventTime + THIRTY_MINUTES_MS))
        return values
    }

    private fun isPotentialLogFile(path: String): Boolean {
        val name = fileName(path)
        return name.endsWith(".xlog") || name.endsWith(".dmp") || name == "logs.txt" ||
            name == "uncaught_exception.txt" || name == "nertc_sdk.log" ||
            name.startsWith("liteav_c_") || name.startsWith("qavsdk_")
    }

    private fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)

    private fun isDownloaderLog(path: String): Boolean = path.contains("downloader") && path.contains("/logs/")

    companion object {
        const val OXYGEN_PACKAGE = "com.ywxk.fluorine"
        const val OXYGEN_ROOT = "/storage/emulated/0/Android/data/$OXYGEN_PACKAGE/files"
        const val OXYGEN_DOWNLOADER_ROOT = "/storage/emulated/0/Android/data/$OXYGEN_PACKAGE:downloader"
        private val OXYGEN_ROOTS = listOf("main" to OXYGEN_ROOT, "downloader" to OXYGEN_DOWNLOADER_ROOT)
        private const val MAX_SCANNED_FILES = 20_000
        private const val THIRTY_MINUTES_MS = 30 * 60 * 1000L
    }
}
