package org.cloud.sonic.android.ui.images

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.gyf.immersionbar.ktx.immersionBar
import kotlinx.coroutines.launch
import org.cloud.sonic.android.R
import org.cloud.sonic.android.MainActivity
import org.cloud.sonic.android.databinding.ActivityImagePreviewBinding
import org.cloud.sonic.android.repository.PlatformSyncRepository
import org.cloud.sonic.android.ui.common.applySystemBarMargins
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

        binding.toolbar.title = name
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnUploadPlatform.applySystemBarMargins()

        val imageUri = if (uriStr.isNotBlank()) Uri.parse(uriStr) else if (path.isNotBlank()) Uri.fromFile(File(path)) else null
        if (imageUri != null) {
            Glide.with(this)
                .load(imageUri)
                .into(binding.photoView)
        }

        binding.btnUploadPlatform.setOnClickListener {
            if (imageUri == null) {
                Snackbar.make(binding.root, "图片无效，无法上传", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!platformRepo.getConfig().isBound) {
                Snackbar.make(binding.root, "请先绑定测试平台账号", Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_go_to_binding) { openDefectAssistant() }
                    .show()
                return@setOnClickListener
            }

            binding.btnUploadPlatform.isEnabled = false
            binding.btnUploadPlatform.setText(R.string.sending)

            lifecycleScope.launch {
                val result = if (path.isNotBlank() && File(path).exists()) {
                    platformRepo.uploadAttachment(File(path))
                } else {
                    platformRepo.uploadAttachment(imageUri, name)
                }

                binding.btnUploadPlatform.isEnabled = true
                binding.btnUploadPlatform.setText(R.string.send_to_defect_assistant_preview)

                result.onSuccess {
                    Snackbar.make(binding.root, "已发送至缺陷助手", Snackbar.LENGTH_LONG)
                        .setAction(R.string.nav_defect) { openDefectAssistant() }
                        .show()
                }.onFailure { err ->
                    Snackbar.make(binding.root, "上传失败：${err.message}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun openDefectAssistant() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_DEFECT, true)
        )
    }
}
