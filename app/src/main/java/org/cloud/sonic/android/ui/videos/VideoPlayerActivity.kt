package org.cloud.sonic.android.ui.videos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.MainActivity
import org.cloud.sonic.android.databinding.ActivityVideoPlayerBinding
import org.cloud.sonic.android.model.PlatformConfig
import org.cloud.sonic.android.repository.PlatformSyncRepository
import org.cloud.sonic.android.ui.common.PlatformAccountPicker
import org.cloud.sonic.android.ui.common.applySystemBarMargins
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var platformRepo: PlatformSyncRepository
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        platformRepo = PlatformSyncRepository(this)

        immersionBar {
            statusBarColor(R.color.black)
            navigationBarColor(R.color.black)
            statusBarDarkFont(false)
        }

        val uriStr = intent.getStringExtra("EXTRA_URI") ?: ""
        val path = intent.getStringExtra("EXTRA_PATH") ?: ""
        val name = intent.getStringExtra("EXTRA_NAME") ?: "播放视频"

        binding.toolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnUploadPlatform.applySystemBarMargins()

        val videoUri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else if (path.isNotBlank()) Uri.fromFile(File(path)) else null

        if (videoUri != null) {
            val mediaController = MediaController(this)
            mediaController.setAnchorView(binding.videoView)
            binding.videoView.setMediaController(mediaController)
            binding.videoView.setVideoURI(videoUri)

            binding.loadingProgress.visibility = View.VISIBLE
            binding.videoView.setOnPreparedListener { mp ->
                binding.loadingProgress.visibility = View.GONE
                mp.isLooping = true
                binding.videoView.start()
                isPlaying = true
            }

            binding.videoView.setOnErrorListener { _, what, extra ->
                binding.loadingProgress.visibility = View.GONE
                MaterialAlertDialogBuilder(this)
                    .setIcon(R.drawable.ic_nav_videos)
                    .setTitle("视频播放失败")
                    .setMessage("该视频格式可能不受支持或文件已损坏 (Error code: $what, extra: $extra)")
                    .setPositiveButton("确定") { _, _ -> finish() }
                    .show()
                true
            }
        } else {
            binding.loadingProgress.visibility = View.GONE
            Snackbar.make(binding.root, "无效的视频地址", Snackbar.LENGTH_LONG).show()
        }

        binding.btnUploadPlatform.setOnClickListener {
            if (videoUri == null) {
                Snackbar.make(binding.root, "视频无效，无法上传", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            PlatformAccountPicker.show(
                context = this,
                repository = platformRepo,
                onMissing = {
                    Snackbar.make(binding.root, "请先绑定测试平台账号", Snackbar.LENGTH_LONG)
                        .setAction(R.string.action_go_to_binding) { openDefectAssistant() }
                        .show()
                }
            ) { target -> uploadVideo(videoUri, path, name, target) }
        }
    }

    private fun uploadVideo(
        videoUri: Uri,
        path: String,
        name: String,
        target: PlatformConfig
    ) {
        binding.btnUploadPlatform.isEnabled = false
        binding.btnUploadPlatform.setText(R.string.sending)

        lifecycleScope.launch {
            val result = if (path.isNotBlank() && File(path).exists()) {
                platformRepo.uploadAttachment(File(path), targetConfig = target)
            } else {
                platformRepo.uploadAttachment(videoUri, name, targetConfig = target)
            }

            binding.btnUploadPlatform.isEnabled = true
            binding.btnUploadPlatform.setText(R.string.send_to_defect_assistant_preview)

            result.onSuccess {
                val account = target.username.ifBlank { "用户 ${target.userId}" }
                Snackbar.make(binding.root, "已发送至 $account", Snackbar.LENGTH_LONG)
                    .setAction(R.string.nav_defect) { openDefectAssistant() }
                    .show()
            }.onFailure { err ->
                Snackbar.make(binding.root, "上传失败：${err.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (binding.videoView.isPlaying) {
            binding.videoView.pause()
            isPlaying = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (isPlaying) {
            binding.videoView.start()
        }
    }

    override fun onDestroy() {
        binding.videoView.stopPlayback()
        super.onDestroy()
    }

    private fun openDefectAssistant() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DEFECT, true)
        )
    }
}
