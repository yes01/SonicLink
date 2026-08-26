package org.cloud.sonic.android.utils

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object ShizukuManager {

    enum class PermissionRequestResult {
        REQUESTED,
        ALREADY_GRANTED,
        SERVICE_UNAVAILABLE,
        UNSUPPORTED,
        FAILED
    }

    data class PrivilegedFileStat(
        val size: Long = -1L,
        val modifiedAt: Long = 0L
    )

    const val REQUEST_CODE_SHIZUKU = 1001

    /**
     * 检查 Shizuku 服务是否可用
     */
    fun isShizukuAvailable(): Boolean {
        return Shizuku.pingBinder()
    }

    /**
     * 检查是否已经获得 Shizuku 权限
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return if (Shizuku.isPreV11()) {
            false
        } else {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestPermission(): PermissionRequestResult {
        if (!isShizukuAvailable()) {
            return PermissionRequestResult.SERVICE_UNAVAILABLE
        }

        if (hasPermission()) {
            return PermissionRequestResult.ALREADY_GRANTED
        }

        if (Shizuku.isPreV11()) {
            return PermissionRequestResult.UNSUPPORTED
        }

        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                SLog.i("Shizuku permission rationale should be shown by the system authorization flow")
            }
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            PermissionRequestResult.REQUESTED
        } catch (e: Exception) {
            SLog.w("Shizuku permission request failed: ${e.message}")
            PermissionRequestResult.FAILED
        }
    }

    /**
     * 执行底层 Shell 指令来赋予关键 AppOps 权限
     */
    suspend fun grantAppOpsPermissions(packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            SLog.e("ShizukuManager: Cannot grant AppOps: No Shizuku permission")
            return@withContext false
        }

        val commands = listOf(
            "appops set $packageName PROJECT_MEDIA allow",
            "appops set $packageName SYSTEM_ALERT_WINDOW allow"
        )

        var success = true
        for (cmd in commands) {
            SLog.i("ShizukuManager: Executing Shizuku command: $cmd")
            val result = executeShellCommand(cmd)
            SLog.i("ShizukuManager: Result: $result")
            if (result.contains("Error") || result.contains("Exception") || result.contains("not found")) {
                SLog.e("ShizukuManager: Failed to execute: $cmd\n$result")
                success = false
            }
        }
        return@withContext success
    }

    suspend fun installApk(apkPath: String): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext "Error: No Shizuku permission"
        }
        val command = "pm install -r '$apkPath'"
        return@withContext executeShellCommand(command)
    }

    suspend fun listFilesRecursively(rootPath: String): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(hasPermission()) { "Shizuku 未授权" }
            require(isTrustedLogRoot(rootPath)) { "日志根目录不受信任" }
            val process = Shizuku.newProcess(arrayOf("find", rootPath, "-type", "f"), null, null)
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            process.destroy()
            if (exitCode != 0) error(error.ifBlank { "无法扫描应用日志目录" })
            output.map(String::trim).filter { it.startsWith("$rootPath/") && !it.contains("/../") }
        }
    }

    suspend fun statFile(path: String): PrivilegedFileStat = withContext(Dispatchers.IO) {
        if (!hasPermission() || !isTrustedExternalAppPath(path)) return@withContext PrivilegedFileStat()
        runCatching {
            val process = Shizuku.newProcess(arrayOf("stat", "-c", "%s|%Y", path), null, null)
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.errorStream.close()
            if (process.waitFor() != 0) {
                process.destroy()
                return@runCatching PrivilegedFileStat()
            }
            process.destroy()
            val parts = output.lineSequence().firstOrNull().orEmpty().split('|')
            PrivilegedFileStat(
                size = parts.getOrNull(0)?.toLongOrNull() ?: -1L,
                modifiedAt = (parts.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000L
            )
        }.getOrDefault(PrivilegedFileStat())
    }

    suspend fun copyFile(path: String, destination: File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(hasPermission()) { "Shizuku 未授权" }
            require(isTrustedExternalAppPath(path)) { "日志文件路径不受信任" }
            destination.parentFile?.mkdirs()
            val process = Shizuku.newProcess(arrayOf("cat", path), null, null)
            destination.outputStream().buffered().use { output ->
                process.inputStream.use { input -> input.copyTo(output) }
            }
            val error = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            process.destroy()
            if (exitCode != 0 || !destination.isFile) {
                destination.delete()
                error(error.ifBlank { "读取日志文件失败" })
            }
        }
    }

    private fun isTrustedLogRoot(path: String): Boolean = TRUSTED_LOG_ROOTS.any { path == it }

    private fun isTrustedExternalAppPath(path: String): Boolean =
        TRUSTED_LOG_ROOTS.any { path.startsWith("$it/") } && !path.contains("/../")

    private fun executeShellCommand(command: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append("Error: ").append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }

    private val TRUSTED_LOG_ROOTS = setOf(
        "/storage/emulated/0/Android/data/com.ywxk.fluorine/files",
        "/storage/emulated/0/Android/data/com.ywxk.fluorine:downloader",
        "/storage/emulated/0/Android/data/com.funnyheart.fish/files"
    )
}
