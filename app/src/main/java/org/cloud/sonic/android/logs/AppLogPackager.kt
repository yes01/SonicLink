package org.cloud.sonic.android.logs

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cloud.sonic.android.utils.ShizukuManager
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppLogPackager(private val context: Context) {
    suspend fun createPackage(
        files: List<LogCandidate>,
        eventTime: Long,
        scenario: LogScenario,
        targetApp: LogTargetApp
    ): File = withContext(Dispatchers.IO) {
        require(files.isNotEmpty()) { "没有选择日志文件" }
        val outputDir = File(context.cacheDir, "bug_logs").also { it.mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val output = File(outputDir, "buglog_${targetApp.packageName}_$timestamp.zip")
        val stagingDir = File(outputDir, "staging_${UUID.randomUUID()}").also { it.mkdirs() }
        val usedEntries = mutableSetOf<String>()

        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                files.forEachIndexed { index, candidate ->
                    val staged = if (candidate.sourceKind == LogSourceKind.SHIZUKU) {
                        File(stagingDir, "${index}_${safeSegment(candidate.displayName)}").also { destination ->
                            ShizukuManager.copyFile(candidate.privilegedPath, destination).getOrThrow()
                        }
                    } else null
                    val entryName = uniqueEntryName(
                        "logs/${safeRelativePath(candidate.relativePath)}",
                        usedEntries
                    )
                    zip.putNextEntry(ZipEntry(entryName).apply {
                        if (candidate.modifiedAt > 0) time = candidate.modifiedAt
                    })
                    open(candidate, staged).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }

                val manifest = buildManifest(files, eventTime, scenario, targetApp)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    @SuppressLint("Recycle")
    private fun open(candidate: LogCandidate, staged: File?): InputStream {
        staged?.let { return it.inputStream().buffered() }
        candidate.file?.let { return it.inputStream().buffered() }
        candidate.contentUri?.let { uri ->
            return context.contentResolver.openInputStream(uri)?.buffered()
                ?: error("无法读取 ${candidate.displayName}")
        }
        error("日志来源已失效：${candidate.displayName}")
    }

    private fun buildManifest(
        files: List<LogCandidate>,
        eventTime: Long,
        scenario: LogScenario,
        targetApp: LogTargetApp
    ): String {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(targetApp.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val appVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            packageInfo?.versionCode?.toLong()
        }
        val payload = linkedMapOf<String, Any?>(
            "target_app" to targetApp.displayName,
            "package_name" to targetApp.packageName,
            "app_version_name" to packageInfo?.versionName,
            "app_version_code" to appVersionCode,
            "event_time" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(eventTime)),
            "scenario" to scenario.name,
            "scenario_name" to targetApp.ruleDisplayName(scenario),
            "collected_at" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()),
            "collector" to "SonicLink",
            "files" to files.map { file ->
                linkedMapOf(
                    "name" to file.displayName,
                    "relative_path" to file.relativePath,
                    "size" to file.size,
                    "modified_at" to file.modifiedAt,
                    "reason" to file.reason,
                    "source" to file.sourceKind.name
                )
            }
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(payload)
    }

    private fun safeRelativePath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        return parts.joinToString("/") { safeSegment(it) }.ifBlank { "log_file" }
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[\\u0000-\\u001f<>:\"/\\\\|?*]"), "_").ifBlank { "log_file" }

    private fun uniqueEntryName(preferred: String, used: MutableSet<String>): String {
        if (used.add(preferred)) return preferred
        val dot = preferred.lastIndexOf('.')
        val base = if (dot > preferred.lastIndexOf('/')) preferred.substring(0, dot) else preferred
        val extension = if (dot > preferred.lastIndexOf('/')) preferred.substring(dot) else ""
        var index = 2
        while (!used.add("${base}_$index$extension")) index++
        return "${base}_$index$extension"
    }
}
