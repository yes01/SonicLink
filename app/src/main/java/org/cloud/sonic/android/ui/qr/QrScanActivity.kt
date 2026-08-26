package org.cloud.sonic.android.ui.qr

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.gyf.immersionbar.ktx.immersionBar
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ActivityQrScanBinding
import org.cloud.sonic.android.ui.common.applySystemBarMargins

class QrScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScanBinding
    private var scannerConfigured = false
    private var torchEnabled = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            configureScanner()
            binding.barcodeScannerView.resume()
        } else {
            showCameraPermissionRecovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        immersionBar {
            statusBarColor(R.color.black)
            navigationBarColor(R.color.black)
            statusBarDarkFont(false)
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.bottomPanel.applySystemBarMargins()
        binding.btnTorch.isEnabled = false
        binding.btnTorch.setOnClickListener {
            torchEnabled = !torchEnabled
            if (torchEnabled) {
                binding.barcodeScannerView.setTorchOn()
                binding.btnTorch.setText(R.string.torch_close)
            } else {
                binding.barcodeScannerView.setTorchOff()
                binding.btnTorch.setText(R.string.torch_open)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            configureScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun configureScanner() {
        if (scannerConfigured) return
        scannerConfigured = true
        binding.btnTorch.isEnabled = true
        binding.barcodeScannerView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        binding.barcodeScannerView.initializeFromIntent(intent)
        binding.barcodeScannerView.decodeSingle(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let { qrText ->
                    val intent = Intent().apply {
                        putExtra("SCAN_RESULT", qrText)
                    }
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }

    private fun showCameraPermissionRecovery() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_qr_code)
            .setTitle("需要相机权限")
            .setMessage("扫码绑定需要使用相机。可以前往应用设置重新授权。")
            .setPositiveButton("前往设置") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!scannerConfigured &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            configureScanner()
        }
        if (scannerConfigured) {
            binding.barcodeScannerView.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (scannerConfigured) {
            binding.barcodeScannerView.pauseAndWait()
        }
    }

    override fun onDestroy() {
        if (scannerConfigured) {
            binding.barcodeScannerView.destroyDrawingCache()
        }
        super.onDestroy()
    }
}
