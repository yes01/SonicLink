package org.cloud.sonic.android.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.FragmentFilesBinding
import org.cloud.sonic.android.model.FileItem
import org.cloud.sonic.android.repository.FileRepository
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.io.File
import java.util.Locale

class FilesFragment : Fragment() {
    private var _binding: FragmentFilesBinding? = null
    private val binding get() = _binding!!
    private lateinit var fileRepo: FileRepository
    private lateinit var platformRepo: PlatformSyncRepository
    private lateinit var adapter: FileAdapter
    private var currentDirectory: File = Environment.getExternalStorageDirectory()
    private var isCategoryMode = false
    private var currentCategory: String = ""

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkPermissionAndLoad()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fileRepo = FileRepository(requireContext())
        platformRepo = PlatformSyncRepository(requireContext())

        adapter = FileAdapter(
            onItemClick = { item ->
                if (item.isDirectory) {
                    isCategoryMode = false
                    currentCategory = ""
                    currentDirectory = item.file
                    loadDirectory(currentDirectory)
                } else {
                    showFileActionsDialog(item)
                }
            },
            onUploadClick = { item ->
                uploadSingleFile(item.file)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.btnGrantStorage.setOnClickListener {
            val intent = fileRepo.getRequestStorageAccessIntent()
            manageStorageLauncher.launch(intent)
        }

        binding.swipeRefresh.setOnRefreshListener {
            renderStorageStats()
            if (isCategoryMode && currentCategory.isNotBlank()) {
                loadCategory(currentCategory)
            } else {
                loadDirectory(currentDirectory)
            }
        }

        bindCategoryChips()
        checkPermissionAndLoad()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndLoad()
    }

    private fun checkPermissionAndLoad() {
        if (_binding == null) return
        val hasAccess = fileRepo.hasStorageAccess()
        binding.cardPermissionNotice.visibility = if (hasAccess) View.GONE else View.VISIBLE
        renderStorageStats()
        if (isCategoryMode && currentCategory.isNotBlank()) {
            loadCategory(currentCategory)
        } else {
            loadDirectory(currentDirectory)
        }
    }

    private fun bindCategoryChips() {
        binding.chipAllFiles.setOnClickListener {
            isCategoryMode = false
            currentCategory = ""
            currentDirectory = Environment.getExternalStorageDirectory()
            loadDirectory(currentDirectory)
        }
        binding.chipDownloads.setOnClickListener {
            loadCategory("downloads")
        }
        binding.chipApk.setOnClickListener {
            loadCategory("apk")
        }
        binding.chipDocs.setOnClickListener {
            loadCategory("docs")
        }
        binding.chipZip.setOnClickListener {
            loadCategory("zip")
        }
    }

    private fun renderStorageStats() {
        val stats = fileRepo.getStorageStats()
        binding.storageProgress.progress = stats.usedPercentage
        val usedGb = stats.usedBytes / (1024.0 * 1024 * 1024)
        val totalGb = stats.totalBytes / (1024.0 * 1024 * 1024)
        binding.tvStorageText.text = getString(
            R.string.storage_used_format,
            String.format(Locale.getDefault(), "%.1f GB", usedGb),
            String.format(Locale.getDefault(), "%.1f GB", totalGb)
        )
    }

    private fun loadDirectory(dir: File) {
        binding.tvPath.text = dir.absolutePath
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val list = fileRepo.getFiles(dir)
            binding.swipeRefresh.isRefreshing = false
            adapter.submitList(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun loadCategory(category: String) {
        isCategoryMode = true
        currentCategory = category
        binding.tvPath.text = "分类浏览: ${category.uppercase()}"
        binding.swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val list = fileRepo.getCategoryFiles(category)
            binding.swipeRefresh.isRefreshing = false
            adapter.submitList(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showFileActionsDialog(item: FileItem) {
        val context = requireContext()
        val actions = arrayOf("发送到 AI 缺陷助手", "用系统应用打开", "复制文件路径")

        MaterialAlertDialogBuilder(context)
            .setTitle(item.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> uploadSingleFile(item.file)
                    1 -> openFileWithSystem(item.file, item.mimeType)
                    2 -> {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("path", item.path))
                        Toast.makeText(context, "路径已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openFileWithSystem(file: File, mimeType: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "打开文件"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "无法打开此文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadSingleFile(file: File) {
        val config = platformRepo.getConfig()
        if (!config.isBound) {
            Toast.makeText(requireContext(), "请先在「缺陷协同」Tab 绑定测试平台账号", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(requireContext(), "正在上传 ${file.name} 到 AI 缺陷助手...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val res = platformRepo.uploadAttachment(file)
            res.onSuccess {
                Toast.makeText(requireContext(), "上传成功！已推送到 AI 缺陷助手", Toast.LENGTH_LONG).show()
            }.onFailure { err ->
                Toast.makeText(requireContext(), "上传失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun handleBackPressed(): Boolean {
        val root = Environment.getExternalStorageDirectory()
        if (isCategoryMode) {
            isCategoryMode = false
            currentCategory = ""
            binding.chipAllFiles.isChecked = true
            loadDirectory(currentDirectory)
            return true
        }
        if (currentDirectory.absolutePath != root.absolutePath && currentDirectory.parentFile != null) {
            currentDirectory = currentDirectory.parentFile!!
            loadDirectory(currentDirectory)
            return true
        }
        return false
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}