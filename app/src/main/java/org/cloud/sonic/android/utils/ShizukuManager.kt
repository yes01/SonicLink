package org.cloud.sonic.android.utils

import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuManager {

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
    fun requestPermission(context: android.app.Activity) {
        if (!isShizukuAvailable()) {
            Toast.makeText(context, "Shizuku 服务未运行，请先启动服务", Toast.LENGTH_LONG).show()
            return
        }

        if (hasPermission()) {
            Toast.makeText(context, "Shizuku 权限已获取", Toast.LENGTH_SHORT).show()
            return
        }

        if (Shizuku.isPreV11()) {
            Toast.makeText(context, "Shizuku 版本过低或未启动", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(context, "请在 Shizuku 应用中允许授权", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            SLog.w("Shizuku check rationale failed: \${e.message}")
        }

        Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
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
}
