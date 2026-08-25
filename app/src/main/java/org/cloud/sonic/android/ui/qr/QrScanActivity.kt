package org.cloud.sonic.android.ui.qr

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.gyf.immersionbar.ktx.immersionBar
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.ActivityQrScanBinding

class QrScanActivity : AppCompatActivity() {
    private lateinit var binding: ActivityQrScanBinding
    private var scannerConfigured = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            configureScanner()
            binding.barcodeScannerView.resume()
        } else {
            Toast.makeText(this, "扫码绑定需要相机权限", Toast.LENGTH_LONG).show()
            finish()
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

        binding.btnBack.setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            configureScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun configureScanner() {
        if (scannerConfigured) return
        scannerConfigured = true
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

    override fun onResume() {
        super.onResume()
        if (scannerConfigured) {
            binding.barcodeScannerView.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.barcodeScannerView.pause()
    }
}
