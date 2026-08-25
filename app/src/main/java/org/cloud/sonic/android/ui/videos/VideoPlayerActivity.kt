package org.cloud.sonic.android.ui.videos

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.MediaController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ActivityVideoPlayerBinding
import org.cloud.sonic.android.repository.PlatformSyncRepository
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

        binding.tvTitle.text = name
        binding.btnBack.setOnClickListener { finish() }

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
                    .setTitle("视频播放失败")
                    .setMessage("该视频格式可能不受支持或文件已损坏 (Error code: $what, extra: $extra)")
                    .setPositiveButton("确定") { _, _ -> finish() }
                    .show()
                true
            }
        } else {
            binding.loadingProgress.visibility = View.GONE
            Toast.makeText(this, "无效的视频地址", Toast.LENGTH_SHORT).show()
        }

        binding.btnUploadPlatform.setOnClickListener {
            if (videoUri == null) {
                Toast.makeText(this, "视频无效，无法上传", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnUploadPlatform.isEnabled = false
            binding.btnUploadPlatform.text = "上传中..."

            lifecycleScope.launch {
                val result = if (path.isNotBlank() && File(path).exists()) {
                    platformRepo.uploadAttachment(File(path))
                } else {
                    platformRepo.uploadAttachment(videoUri, name)
                }

                binding.btnUploadPlatform.isEnabled = true
                binding.btnUploadPlatform.text = "发送至缺陷助手"

                result.onSuccess {
                    Toast.makeText(this@VideoPlayerActivity, "视频上传成功！已推送至 AI 缺陷助手", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(this@VideoPlayerActivity, "上传失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
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
}