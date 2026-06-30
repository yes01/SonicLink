package org.cloud.sonic.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.AppUtils
import com.gyf.immersionbar.ktx.immersionBar
import org.cloud.sonic.android.agent.SonicLinkAgentService
import org.cloud.sonic.android.agent.SonicLinkConfig
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.agent.SonicLinkConnectionState
import org.cloud.sonic.android.agent.SonicLinkDeviceInfo
import org.cloud.sonic.android.agent.SonicLinkStatus
import org.cloud.sonic.android.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: SonicLinkConfigStore

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = SonicLinkConfigStore(this)

        immersionBar {
            statusBarColor(R.color.sonic_link_bg)
            navigationBarColor(R.color.sonic_link_bg)
            statusBarDarkFont(true)
            autoDarkModeEnable(true)
        }

        bindActions()
        loadConfig()
        requestNotificationPermissionIfNeeded()
        renderStatus()
        if (configStore.getConfig().autoConnect && !SonicLinkStatus.serviceRunning) {
            SonicLinkAgentService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(SonicLinkAgentService.ACTION_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun bindActions() {
        binding.saveConfig.setOnClickListener {
            saveConfig()
            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show()
            renderStatus()
        }
        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.toast_restricted_settings, Toast.LENGTH_LONG).show()
            }
        }
        binding.requestCapture.setOnClickListener {
            startActivity(Intent(this, ScreenCaptureActivity::class.java))
        }
        binding.openAppSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
        binding.startAgent.setOnClickListener {
            saveConfig()
            SonicLinkAgentService.start(this)
            renderStatus()
        }
        binding.stopAgent.setOnClickListener {
            SonicLinkAgentService.stop(this)
            renderStatus()
        }
    }

    private fun loadConfig() {
        val config = configStore.getConfig()
        binding.serverHost.setText(config.serverHost)
        binding.httpUrl.setText(config.httpUrl)
        binding.webSocketUrl.setText(config.webSocketUrl)
        binding.token.setText(config.token)
        binding.deviceName.setText(config.deviceName)
        binding.autoConnect.isChecked = config.autoConnect
        binding.version.text = getString(R.string.version_label, AppUtils.getAppVersionName())
    }

    private fun saveConfig() {
        val config = SonicLinkConfig(
            serverHost = binding.serverHost.text.toString(),
            httpUrl = binding.httpUrl.text.toString(),
            webSocketUrl = binding.webSocketUrl.text.toString(),
            token = binding.token.text.toString(),
            deviceName = binding.deviceName.text.toString(),
            autoConnect = binding.autoConnect.isChecked
        )
        configStore.saveConfig(config)
    }

    private fun renderStatus() {
        val config = configStore.getConfig()
        val hasConfig = config.isReady
        val accessibilityEnabled = SonicLinkDeviceInfo.isAccessibilityEnabled(this)
        val captureGranted = ScreenCaptureState.hasPermission
        val display = SonicLinkDeviceInfo.displayInfo(this)
        binding.configStatus.text = statusLine(
            getString(R.string.status_platform_address),
            hasConfig,
            if (hasConfig) config.webSocketUrl else getString(R.string.status_missing_ws)
        )
        binding.accessibilityStatus.text = statusLine(
            getString(R.string.status_accessibility),
            accessibilityEnabled,
            if (accessibilityEnabled) getString(R.string.status_accessibility_enabled) else getString(R.string.status_accessibility_required)
        )
        binding.screenCaptureStatus.text = statusLine(
            getString(R.string.status_screen_capture),
            captureGranted,
            if (captureGranted) getString(R.string.status_capture_granted) else getString(R.string.status_capture_not_granted)
        )
        binding.agentStatus.text = "${getString(R.string.status_agent)}：${localizedConnectionState()}${agentDetailText()}"
        binding.deviceStatus.text = "${getString(R.string.status_device)}：${getString(R.string.device_status_format, configStore.getOrCreateDeviceId(), display.width, display.height, display.rotation)}"
        binding.blockingStatus.text = blockingStatus(hasConfig, accessibilityEnabled, captureGranted)
        binding.startAgent.isEnabled = hasConfig
    }

    private fun blockingStatus(hasConfig: Boolean, accessibilityEnabled: Boolean, captureGranted: Boolean): String {
        val issues = mutableListOf<String>()
        if (!hasConfig) issues.add(getString(R.string.issue_configure_ws))
        if (!accessibilityEnabled) issues.add(getString(R.string.issue_enable_accessibility))
        if (!captureGranted) issues.add(getString(R.string.issue_grant_capture))
        SonicLinkStatus.lastStreamEvent?.let { issues.add(getString(R.string.issue_stream_event, localizedStreamEvent(it))) }
        return if (issues.isEmpty()) {
            getString(R.string.ready_for_agent)
        } else {
            getString(R.string.needs_attention, issues.joinToString("；"))
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun statusLine(label: String, ok: Boolean, detail: String): String {
        val marker = if (ok) getString(R.string.status_ok) else getString(R.string.status_needs_attention)
        return "$label：$marker - $detail"
    }

    private fun localizedConnectionState(): String {
        return when (SonicLinkStatus.connectionState) {
            SonicLinkConnectionState.CONNECTED -> getString(R.string.sonic_link_status_connected)
            SonicLinkConnectionState.CONNECTING -> getString(R.string.sonic_link_status_connecting)
            SonicLinkConnectionState.RECONNECTING -> getString(R.string.sonic_link_status_reconnecting)
            SonicLinkConnectionState.ERROR -> getString(R.string.sonic_link_status_error)
            SonicLinkConnectionState.DISCONNECTED -> getString(R.string.sonic_link_status_disconnected)
            SonicLinkConnectionState.STOPPED -> getString(R.string.sonic_link_status_stopped)
        }
    }

    private fun agentDetailText(): String {
        SonicLinkStatus.lastError?.let { return " ($it)" }
        if (SonicLinkStatus.connectionState != SonicLinkConnectionState.CONNECTED) {
            return ""
        }
        return if (SonicLinkStatus.lastHeartbeatAt == 0L) {
            " - ${getString(R.string.heartbeat_waiting)}"
        } else {
            " - ${getString(R.string.heartbeat_active)}"
        }
    }

    private fun localizedStreamEvent(event: String): String {
        return when (event) {
            "stream_started" -> "投屏已开始"
            "stream_stopped" -> "投屏已停止"
            "screen_capture_revoked" -> "屏幕采集授权已被系统收回"
            "stream_format_changed" -> "投屏画面格式已更新"
            else -> event
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 2001
    }
}
