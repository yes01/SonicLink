package org.cloud.sonic.android.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.cloud.sonic.android.model.PlatformConfig
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.net.URI

object PlatformAccountPicker {
    fun show(
        context: Context,
        repository: PlatformSyncRepository,
        title: String = "发送到平台账号",
        positiveLabel: String = "发送",
        onMissing: () -> Unit,
        onSelected: (PlatformConfig) -> Unit
    ) {
        val bindings = repository.getBindings().filter { it.isBound }
        if (bindings.isEmpty()) {
            onMissing()
            return
        }
        if (bindings.size == 1) {
            repository.selectBinding(bindings.first().bindingKey)
            onSelected(bindings.first())
            return
        }

        val currentKey = repository.getConfig().bindingKey
        var selectedIndex = bindings.indexOfFirst { it.bindingKey == currentKey }.coerceAtLeast(0)
        val labels = bindings.map(::bindingLabel).toTypedArray()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which -> selectedIndex = which }
            .setPositiveButton(positiveLabel) { _, _ ->
                val selected = bindings[selectedIndex]
                repository.selectBinding(selected.bindingKey)
                onSelected(selected)
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
    }

    fun bindingLabel(config: PlatformConfig): String {
        val account = config.username.ifBlank { "用户ID: ${config.userId}" }
        val host = runCatching { URI(config.serverUrl).authority }.getOrNull().orEmpty()
            .ifBlank { config.serverUrl }
        return "$account\n$host · 项目 ${config.defaultProjectId}"
    }
}
