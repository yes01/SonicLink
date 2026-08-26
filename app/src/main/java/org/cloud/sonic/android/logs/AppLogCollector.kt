package org.cloud.sonic.android.logs

interface AppLogCollector {
    val targetApp: LogTargetApp
    fun isInstalled(): Boolean
    suspend fun scan(eventTime: Long, scenario: LogScenario): LogScanResult
}
