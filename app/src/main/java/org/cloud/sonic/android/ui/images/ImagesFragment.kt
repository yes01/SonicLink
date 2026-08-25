package org.cloud.sonic.android.ui.images

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.FragmentImagesBinding
import org.cloud.sonic.android.model.MediaItem
import org.cloud.sonic.android.repository.MediaRepository
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.io.File

class ImagesFragment : Fragment() {
    private var _binding: FragmentImagesBinding? = null
    private val binding get() = _binding!!
    private lateinit var mediaRepository: MediaRepository
    private lateinit var platformRepo: PlatformSyncRepository
    private lateinit var adapter: ImageAdapter
    private var onlyScreenshots = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (mediaRepository.hasImagePermission()) {
            loadImages()
        } else {
            showNoPermissionUI()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentImagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mediaRepository = MediaRepository(requireContext())
        platformRepo = PlatformSyncRepository(requireContext())

        adapter = ImageAdapter(
            onItemClick = { item ->
                val intent = Intent(requireContext(), ImagePreviewActivity::class.java).apply {
                    putExtra("EXTRA_URI", item.uri.toString())
                    putExtra("EXTRA_PATH", item.path)
                    putExtra("EXTRA_NAME", item.name)
                }
                startActivity(intent)
            },
            onSelectionChanged = { count ->
                if (count > 0) {
                    binding.bottomActionBar.visibility = View.VISIBLE
                    binding.tvSelectedCount.text = getString(R.string.selected_count_format, count)
                } else {
                    binding.bottomActionBar.visibility = View.GONE
                }
            }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { loadImages() }

        binding.btnGrantPermission.setOnClickListener {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            permissionLauncher.launch(perms)
        }

        binding.imageTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                onlyScreenshots = tab?.position == 1
                loadImages()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        binding.btnUploadPlatform.setOnClickListener {
            uploadSelectedImages()
        }

        loadImages()
    }

    override fun onResume() {
        super.onResume()
        if (mediaRepository.hasImagePermission()) {
            loadImages()
        } else {
            showNoPermissionUI()
        }
    }

    private fun loadImages() {
        if (!mediaRepository.hasImagePermission()) {
            showNoPermissionUI()
            return
        }

        binding.layoutNoPermission.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = true

        viewLifecycleOwner.lifecycleScope.launch {
            val list = mediaRepository.getImages(onlyScreenshots)
            binding.swipeRefresh.isRefreshing = false
            adapter.submitList(list)
            binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showNoPermissionUI() {
        binding.swipeRefresh.isRefreshing = false
        binding.layoutNoPermission.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        adapter.submitList(emptyList())
    }

    private fun uploadSelectedImages() {
        val selected = adapter.getSelectedItems()
        if (selected.isEmpty()) return

        val config = platformRepo.getConfig()
        if (!config.isBound) {
            Toast.makeText(requireContext(), "请先在「缺陷协同」Tab 绑定测试平台账号", Toast.LENGTH_LONG).show()
            return
        }

        binding.btnUploadPlatform.isEnabled = false
        binding.btnUploadPlatform.text = "正在上传..."

        viewLifecycleOwner.lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for (item in selected) {
                val res = if (item.path.isNotBlank() && File(item.path).exists()) {
                    platformRepo.uploadAttachment(File(item.path))
                } else {
                    platformRepo.uploadAttachment(item.uri, item.name)
                }
                if (res.isSuccess) successCount++ else failCount++
            }

            binding.btnUploadPlatform.isEnabled = true
            binding.btnUploadPlatform.text = "发送到缺陷助手"
            adapter.clearSelection()

            Toast.makeText(
                requireContext(),
                "上传完成：成功 $successCount 个，失败 $failCount 个（已推送到 AI 缺陷助手）",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}