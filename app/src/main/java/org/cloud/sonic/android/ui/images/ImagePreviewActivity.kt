package org.cloud.sonic.android.ui.images

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ActivityImagePreviewBinding
import org.cloud.sonic.android.repository.PlatformSyncRepository
import java.io.File

class ImagePreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var platformRepo: PlatformSyncRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        platformRepo = PlatformSyncRepository(this)

        immersionBar {
            statusBarColor(R.color.black)
            navigationBarColor(R.color.black)
            statusBarDarkFont(false)
        }

        val uriStr = intent.getStringExtra("EXTRA_URI") ?: ""
        val path = intent.getStringExtra("EXTRA_PATH") ?: ""
        val name = intent.getStringExtra("EXTRA_NAME") ?: "预览图片"

        binding.tvTitle.text = name
        binding.btnBack.setOnClickListener { finish() }

        val imageUri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else if (path.isNotBlank()) Uri.fromFile(File(path)) else null
        if (imageUri != null) {
            Glide.with(this)
                .load(imageUri)
                .into(binding.photoView)
        }

        binding.btnUploadPlatform.setOnClickListener {
            if (imageUri == null) {
                Toast.makeText(this, "图片无效，无法上传", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnUploadPlatform.isEnabled = false
            binding.btnUploadPlatform.text = "上传中..."

            lifecycleScope.launch {
                val result = if (path.isNotBlank() && File(path).exists()) {
                    platformRepo.uploadAttachment(File(path))
                } else {
                    platformRepo.uploadAttachment(imageUri, name)
                }

                binding.btnUploadPlatform.isEnabled = true
                binding.btnUploadPlatform.text = "发送至缺陷助手"

                result.onSuccess {
                    Toast.makeText(this@ImagePreviewActivity, "上传成功！已推送至 AI 缺陷助手", Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    Toast.makeText(this@ImagePreviewActivity, "上传失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}