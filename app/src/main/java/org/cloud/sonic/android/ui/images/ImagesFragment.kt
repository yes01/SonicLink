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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.MainActivity
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.FragmentImagesBinding
import org.cloud.sonic.android.model.MediaItem
import org.cloud.sonic.android.model.PlatformConfig
import org.cloud.sonic.android.repository.MediaRepository
import org.cloud.sonic.android.repository.PlatformSyncRepository
import org.cloud.sonic.android.ui.common.PlatformAccountPicker
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
                renderSelectionState(count)
            }
        )

        val recyclerView = binding.recyclerView
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 1)
        recyclerView.adapter = adapter
        recyclerView.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                updateGridSpanCount(recyclerView)
            }
        }
        recyclerView.post { updateGridSpanCount(recyclerView) }

        binding.swipeRefresh.setOnRefreshListener { loadImages() }

        binding.btnSelect.setOnClickListener {
            if (adapter.isSelectionMode) adapter.clearSelection() else adapter.enterSelectionMode()
        }

        binding.btnSelectAll.setOnClickListener { adapter.selectAll() }

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

        PlatformAccountPicker.show(
            context = requireContext(),
            repository = platformRepo,
            onMissing = {
                Snackbar.make(binding.root, "请先绑定测试平台账号", Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_go_to_binding) {
                        (activity as? MainActivity)?.openDefectTab()
                    }
                    .show()
            }
        ) { target -> uploadSelectedImages(selected, target) }
    }

    private fun uploadSelectedImages(
        selected: List<MediaItem>,
        target: PlatformConfig
    ) {
        binding.btnUploadPlatform.isEnabled = false
        binding.btnUploadPlatform.setText(R.string.sending)

        viewLifecycleOwner.lifecycleScope.launch {
            var successCount = 0
            var failCount = 0

            for (item in selected) {
                val res = if (item.path.isNotBlank() && File(item.path).exists()) {
                    platformRepo.uploadAttachment(File(item.path), targetConfig = target)
                } else {
                    platformRepo.uploadAttachment(item.uri, item.name, targetConfig = target)
                }
                if (res.isSuccess) successCount++ else failCount++
            }

            binding.btnUploadPlatform.isEnabled = true
            binding.btnUploadPlatform.setText(R.string.send_to_defect_assistant)
            adapter.clearSelection()

            val message = if (failCount == 0) {
                "已发送 $successCount 个附件到 ${target.username.ifBlank { "用户 ${target.userId}" }}"
            } else {
                "发送完成：成功 $successCount 个，失败 $failCount 个"
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.nav_defect) {
                    (activity as? MainActivity)?.openDefectTab()
                }
                .show()
        }
    }

    private fun renderSelectionState(count: Int) {
        binding.btnSelect.setText(if (adapter.isSelectionMode) R.string.action_cancel_selection else R.string.action_select)
        binding.bottomActionBar.visibility = if (adapter.isSelectionMode) View.VISIBLE else View.GONE
        binding.tvSelectedCount.text = getString(R.string.selected_count_format, count)
        binding.btnUploadPlatform.isEnabled = count > 0
        binding.btnSelectAll.isEnabled = adapter.itemCount > 0 && count < adapter.itemCount
    }

    private fun updateGridSpanCount(recyclerView: androidx.recyclerview.widget.RecyclerView) {
        val minCellWidth = resources.getDimensionPixelSize(R.dimen.media_image_min_cell_width)
        val availableWidth = recyclerView.width - recyclerView.paddingLeft - recyclerView.paddingRight
        if (availableWidth <= 0 || minCellWidth <= 0) return
        val spanCount = (availableWidth / minCellWidth).coerceIn(1, 7)
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
        if (layoutManager.spanCount != spanCount) layoutManager.spanCount = spanCount
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
