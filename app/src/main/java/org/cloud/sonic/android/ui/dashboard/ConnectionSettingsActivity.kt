package org.cloud.sonic.android.ui.dashboard

import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.gyf.immersionbar.ktx.immersionBar
import org.cloud.sonic.android.R
import org.cloud.sonic.android.agent.SonicLinkConfig
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.databinding.ActivityConnectionSettingsBinding

class ConnectionSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityConnectionSettingsBinding
    private lateinit var configStore: SonicLinkConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = SonicLinkConfigStore(this)

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        immersionBar {
            statusBarColor(R.color.bg_card)
            navigationBarColor(R.color.bg_page)
            statusBarDarkFont(!isDark)
            navigationBarDarkIcon(!isDark)
            fitsSystemWindows(true)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.saveConfig.setOnClickListener { saveConfig() }
        loadConfig()
    }

    private fun loadConfig() {
        val config = configStore.getConfig()
        binding.serverHost.setText(config.serverHost)
        binding.httpUrl.setText(config.httpUrl)
        binding.webSocketUrl.setText(config.webSocketUrl)
        binding.token.setText(config.token)
        binding.deviceName.setText(config.deviceName)
        binding.autoConnect.isChecked = config.autoConnect
    }

    private fun saveConfig() {
        val webSocketUrl = binding.webSocketUrl.text?.toString()?.trim().orEmpty()
        binding.webSocketInput.error = when {
            webSocketUrl.isBlank() -> getString(R.string.error_websocket_required)
            !webSocketUrl.startsWith("ws://") && !webSocketUrl.startsWith("wss://") ->
                getString(R.string.error_websocket_scheme)
            else -> null
        }
        if (binding.webSocketInput.error != null) {
            binding.webSocketUrl.requestFocus()
            return
        }

        configStore.saveConfig(
            SonicLinkConfig(
                serverHost = binding.serverHost.text?.toString()?.trim().orEmpty(),
                httpUrl = binding.httpUrl.text?.toString()?.trim().orEmpty(),
                webSocketUrl = webSocketUrl,
                token = binding.token.text?.toString()?.trim().orEmpty(),
                deviceName = binding.deviceName.text?.toString()?.trim().orEmpty(),
                autoConnect = binding.autoConnect.isChecked
            )
        )
        Snackbar.make(binding.root, R.string.toast_config_saved, Snackbar.LENGTH_SHORT).show()
    }
}
