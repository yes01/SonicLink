package org.cloud.sonic.android.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager

class SonicLinkAgentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val shouldStart = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ConnectivityManager.CONNECTIVITY_ACTION
        if (!shouldStart) {
            return
        }
        val config = SonicLinkConfigStore(context).getConfig()
        if (config.autoConnect && config.isReady) {
            SonicLinkAgentService.start(context)
        }
    }
}
