package org.cloud.sonic.android.ui.defect

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.FragmentDefectAssistantBinding
import org.cloud.sonic.android.model.ChatMessage
import org.cloud.sonic.android.repository.ChatRepository
import org.cloud.sonic.android.repository.PlatformSyncRepository
import org.cloud.sonic.android.ui.common.PlatformAccountPicker
import org.cloud.sonic.android.ui.logs.AppLogUploadActivity
import org.cloud.sonic.android.ui.qr.QrScanActivity

class DefectAssistantFragment : Fragment() {
    private var _binding: FragmentDefectAssistantBinding? = null
    private val binding get() = _binding!!
    private lateinit var platformRepo: PlatformSyncRepository
    private lateinit var chatRepo: ChatRepository
    private lateinit var historyAdapter: UploadHistoryAdapter
    private lateinit var chatAdapter: ChatAdapter

    private val scanQrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val qrText = result.data?.getStringExtra("SCAN_RESULT") ?: ""
            if (qrText.isNotBlank()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    platformRepo.pairWithQrCode(qrText)
                        .onSuccess { config ->
                            val account = config.username.ifBlank { "用户 ${config.userId}" }
                            Snackbar.make(binding.root, "已绑定并选择 $account", Snackbar.LENGTH_LONG).show()
                            renderBindingCard()
                        }
                        .onFailure { error ->
                            Snackbar.make(binding.root, error.message ?: "绑定失败", Snackbar.LENGTH_LONG).show()
                        }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDefectAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        platformRepo = PlatformSyncRepository(requireContext())
        chatRepo = ChatRepository(requireContext())

        historyAdapter = UploadHistoryAdapter(
            onCopyMarkdown = { markdown ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("markdown", markdown)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, R.string.markdown_copied, Snackbar.LENGTH_SHORT).show()
            }
        )

        chatAdapter = ChatAdapter()

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = historyAdapter

        binding.rvChat.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChat.adapter = chatAdapter

        binding.btnScanQr.setOnClickListener {
            val intent = Intent(requireContext(), QrScanActivity::class.java)
            scanQrLauncher.launch(intent)
        }

        binding.btnManualConfig.setOnClickListener {
            showBindingActions()
        }

        binding.cardBinding.setOnClickListener {
            showBindingActions()
        }

        binding.btnUploadAppLogs.setOnClickListener {
            startActivity(Intent(requireContext(), AppLogUploadActivity::class.java))
        }

        binding.defectTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    binding.rvHistory.visibility = View.VISIBLE
                    binding.rvChat.visibility = View.GONE
                    binding.layoutChatInput.visibility = View.GONE
                    loadHistory()
                } else {
                    binding.rvHistory.visibility = View.GONE
                    binding.rvChat.visibility = View.VISIBLE
                    binding.layoutChatInput.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnSendChat.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotBlank()) {
                chatRepo.addMessage(
                    ChatMessage(
                        senderName = "我",
                        text = text,
                        isMine = true
                    )
                )
                binding.etChatInput.setText("")
            }
        }
        binding.btnSendChat.isEnabled = false
        binding.etChatInput.doAfterTextChanged { text ->
            binding.btnSendChat.isEnabled = !text.isNullOrBlank()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatRepo.messagesFlow.collectLatest { list ->
                chatAdapter.submitList(list)
                if (list.isNotEmpty()) {
                    binding.rvChat.scrollToPosition(list.size - 1)
                }
            }
        }

        renderBindingCard()
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        renderBindingCard()
        loadHistory()
    }

    private fun renderBindingCard() {
        if (_binding == null) return
        val context = context ?: return
        val config = platformRepo.getConfig()
        val bindingCount = platformRepo.getBindings().count { it.isBound }
        if (config.isBound) {
            val userDisplay = if (config.username.isNotBlank()) config.username else "用户ID: ${config.userId}"
            binding.tvBindingStatus.text = if (bindingCount > 1) {
                "当前发送账号: $userDisplay（共 $bindingCount 个）"
            } else {
                getString(R.string.platform_bound_status, userDisplay)
            }
            binding.tvBindingStatus.setTextColor(ContextCompat.getColor(context, R.color.status_success))
            binding.tvServerUrl.text = "服务器: ${config.serverUrl} · 项目 ${config.defaultProjectId}"
            binding.btnScanQr.text = "绑定新账号"
            binding.btnManualConfig.text = "管理绑定"
        } else {
            binding.tvBindingStatus.text = getString(R.string.platform_unbound_status)
            binding.tvBindingStatus.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            binding.tvServerUrl.text = "请在电脑端 AI 缺陷助手点击“手机”，再扫描配对二维码"
            binding.btnScanQr.text = getString(R.string.scan_qr_to_bind)
            binding.btnManualConfig.text = "查看绑定说明"
        }
    }

    private fun showBindingActions() {
        val context = requireContext()
        val current = platformRepo.getConfig()
        if (!current.isBound) {
            MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.ic_qr_code)
                .setTitle("扫码绑定")
                .setMessage("请在电脑端 AI 缺陷助手点击“手机”，再使用本页的扫码按钮扫描 5 分钟内有效的二维码。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val bindings = platformRepo.getBindings().filter { it.isBound }
        val actions = buildList {
            if (bindings.size > 1) add("切换发送账号")
            add("绑定新账号")
            add("解除当前账号")
        }
        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_phone_android)
            .setTitle("管理平台绑定")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "切换发送账号" -> showAccountSwitcher()
                    "绑定新账号" -> scanQrLauncher.launch(Intent(context, QrScanActivity::class.java))
                    "解除当前账号" -> confirmRevokeCurrent()
                }
            }
            .show()
    }

    private fun showAccountSwitcher() {
        PlatformAccountPicker.show(
            context = requireContext(),
            repository = platformRepo,
            title = "选择默认发送账号",
            positiveLabel = "选择",
            onMissing = { renderBindingCard() }
        ) { selected ->
            renderBindingCard()
            val account = selected.username.ifBlank { "用户 ${selected.userId}" }
            Snackbar.make(binding.root, "已切换到 $account", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmRevokeCurrent() {
        val current = platformRepo.getConfig()
        val account = current.username.ifBlank { "用户 ${current.userId}" }
        MaterialAlertDialogBuilder(requireContext())
            .setIcon(R.drawable.ic_delete)
            .setTitle("解除 $account")
            .setMessage("只解除当前账号，手机上的其他平台账号仍可继续使用。")
            .setPositiveButton("解除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    platformRepo.revokeBinding(current.bindingKey)
                        .onSuccess {
                            renderBindingCard()
                            Snackbar.make(binding.root, "已解除 $account", Snackbar.LENGTH_SHORT).show()
                        }
                        .onFailure { error ->
                            Snackbar.make(binding.root, error.message ?: "解除绑定失败", Snackbar.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadHistory() {
        val history = platformRepo.getUploadHistory()
        historyAdapter.submitList(history)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
