package org.cloud.sonic.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
            renderStatus()
        }
        binding.openAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, "If Android blocks this sideloaded app, open App info and allow restricted settings.", Toast.LENGTH_LONG).show()
            }
        }
        binding.requestCapture.setOnClickListener {
            startActivity(Intent(this, ScreenCaptureActivity::class.java))
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
        binding.version.text = "Version ${AppUtils.getAppVersionName()}"
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
        binding.configStatus.text = statusLine("Platform address", hasConfig, if (hasConfig) config.webSocketUrl else "Missing WebSocket URL")
        binding.accessibilityStatus.text = statusLine("Accessibility", accessibilityEnabled, if (accessibilityEnabled) "Enabled" else "Required for remote control")
        binding.screenCaptureStatus.text = statusLine("Screen capture", captureGranted, if (captureGranted) "Granted for this process" else "Not granted")
        binding.agentStatus.text = "Agent: ${SonicLinkStatus.connectionState.name.lowercase()}${SonicLinkStatus.lastError?.let { " ($it)" } ?: ""}"
        binding.startAgent.isEnabled = hasConfig
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
        val marker = if (ok) "OK" else "NEEDS ATTENTION"
        return "$label: $marker - $detail"
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 2001
    }
}
