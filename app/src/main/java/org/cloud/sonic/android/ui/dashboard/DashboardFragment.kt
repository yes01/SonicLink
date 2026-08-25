package org.cloud.sonic.android.ui.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.AppUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.ScreenCaptureActivity
import org.cloud.sonic.android.ScreenCaptureState
import org.cloud.sonic.android.agent.SonicLinkAgentService
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.agent.SonicLinkConnectionState
import org.cloud.sonic.android.agent.SonicLinkDeviceInfo
import org.cloud.sonic.android.agent.SonicLinkStatus
import org.cloud.sonic.android.databinding.FragmentDashboardBinding
import org.cloud.sonic.android.utils.ShizukuManager
import rikka.shizuku.Shizuku

class DashboardFragment : Fragment() {
    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private lateinit var configStore: SonicLinkConfigStore
    private var lastRenderJob: kotlinx.coroutines.Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastRenderJob?.cancel()
            lastRenderJob = viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(100)
                renderStatus()
            }
        }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuManager.REQUEST_CODE_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            executeShizukuAppOps()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()
        configStore = SonicLinkConfigStore(context)

        bindActions()
        loadConfig()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        loadConfig()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {
            // Shizuku not initialized
        }
        val filter = IntentFilter(SonicLinkAgentService.ACTION_STATUS_CHANGED)
        androidx.core.content.ContextCompat.registerReceiver(
            requireContext(),
            statusReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (e: Exception) {
            // ignored
        }
        try {
            requireContext().unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // ignored
        }
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun bindActions() {
        binding.refreshStatus.setOnClickListener {
            renderStatus()
            Snackbar.make(binding.root, "状态已刷新", Snackbar.LENGTH_SHORT).show()
        }

        binding.openConnectionSettings.setOnClickListener {
            startActivity(Intent(requireContext(), ConnectionSettingsActivity::class.java))
        }

        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Snackbar.make(binding.root, R.string.toast_restricted_settings, Snackbar.LENGTH_LONG).show()
            }
        }

        binding.requestCapture.setOnClickListener {
            startActivity(Intent(requireContext(), ScreenCaptureActivity::class.java))
        }

        binding.openAppSettings.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
            )
        }

        binding.startAgent.setOnClickListener {
            SonicLinkAgentService.start(requireContext())
            renderStatus()
        }

        binding.stopAgent.setOnClickListener {
            SonicLinkAgentService.stop(requireContext())
            renderStatus()
        }

        binding.bindShizuku.setOnClickListener {
            if (ShizukuManager.isShizukuAvailable()) {
                if (ShizukuManager.hasPermission()) {
                    executeShizukuAppOps()
                } else {
                    ShizukuManager.requestPermission(requireActivity())
                }
            } else {
                Snackbar.make(
                    binding.root,
                    "Shizuku 未连接，请先打开 Shizuku 应用恢复连接",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun executeShizukuAppOps() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val success = ShizukuManager.grantAppOpsPermissions(ctx.packageName)
            if (!isAdded) return@launch
            if (success) {
                Snackbar.make(binding.root, "提权成功，正在请求录屏授权", Snackbar.LENGTH_LONG).show()
                startActivity(Intent(requireContext(), ScreenCaptureActivity::class.java))
                renderStatus()
            } else {
                Snackbar.make(binding.root, "Shizuku 提权失败，请检查相关日志", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun loadConfig() {
        val config = configStore.getConfig()
        binding.version.text = getString(R.string.version_label, AppUtils.getAppVersionName())
        binding.connectionSettingsSummary.text = if (config.isReady) {
            "${config.webSocketUrl}\n设备：${config.deviceName.ifBlank { "未命名设备" }}"
        } else {
            getString(R.string.connection_settings_summary)
        }
    }

    fun renderStatus() {
        if (_binding == null) return
        val context = context ?: return
        val config = configStore.getConfig()
        val hasConfig = config.isReady
        val accessibilityEnabled = SonicLinkDeviceInfo.isAccessibilityEnabled(context)
        val captureGranted = ScreenCaptureState.hasPermission
        val display = SonicLinkDeviceInfo.displayInfo(context)

        val newBadge = localizedConnectionState()
        if (binding.agentStatusBadge.text != newBadge) {
            binding.agentStatusBadge.text = newBadge
        }

        val newConfigStatus = statusLine(
            getString(R.string.status_platform_address),
            hasConfig,
            if (hasConfig) config.webSocketUrl else getString(R.string.status_missing_ws)
        )
        if (binding.configStatus.text != newConfigStatus) {
            binding.configStatus.text = newConfigStatus
        }

        val newAccessibilityStatus = statusLine(
            getString(R.string.status_accessibility),
            accessibilityEnabled,
            if (accessibilityEnabled) getString(R.string.status_accessibility_enabled) else getString(R.string.status_accessibility_required)
        )
        if (binding.accessibilityStatus.text != newAccessibilityStatus) {
            binding.accessibilityStatus.text = newAccessibilityStatus
        }

        val newCaptureStatus = statusLine(
            getString(R.string.status_screen_capture),
            captureGranted,
            if (captureGranted) getString(R.string.status_capture_granted) else getString(R.string.status_capture_not_granted)
        )
        if (binding.screenCaptureStatus.text != newCaptureStatus) {
            binding.screenCaptureStatus.text = newCaptureStatus
        }

        val newAgentStatus = "${getString(R.string.status_agent)}：${localizedConnectionState()}${agentDetailText()}"
        if (binding.agentStatus.text != newAgentStatus) {
            binding.agentStatus.text = newAgentStatus
        }

        val newDeviceStatus = "${getString(R.string.status_device)}：${getString(R.string.device_status_format, configStore.getOrCreateDeviceId(), display.width, display.height, display.rotation)}"
        if (binding.deviceStatus.text != newDeviceStatus) {
            binding.deviceStatus.text = newDeviceStatus
        }

        val newBlockingStatus = blockingStatus(hasConfig, accessibilityEnabled, captureGranted)
        if (binding.blockingStatus.text != newBlockingStatus) {
            binding.blockingStatus.text = newBlockingStatus
        }

        if (binding.startAgent.isEnabled != hasConfig) {
            binding.startAgent.isEnabled = hasConfig
        }
        binding.startAgent.visibility = if (SonicLinkStatus.serviceRunning) View.GONE else View.VISIBLE
        binding.stopAgent.visibility = if (SonicLinkStatus.serviceRunning) View.VISIBLE else View.GONE
    }

    private fun blockingStatus(hasConfig: Boolean, accessibilityEnabled: Boolean, captureGranted: Boolean): String {
        val issues = mutableListOf<String>()
        if (!hasConfig) issues.add(getString(R.string.issue_configure_ws))
        if (!accessibilityEnabled) issues.add(getString(R.string.issue_enable_accessibility))
        if (!captureGranted) issues.add(getString(R.string.issue_grant_capture))
        SonicLinkStatus.lastStreamEvent?.let {
            issues.add(getString(R.string.issue_stream_event, localizedStreamEvent(it)))
        }
        return if (issues.isEmpty()) {
            getString(R.string.ready_for_agent)
        } else {
            getString(R.string.needs_attention, issues.joinToString("；"))
        }
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
}
