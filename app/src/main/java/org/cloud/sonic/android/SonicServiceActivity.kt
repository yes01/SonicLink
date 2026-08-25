package org.cloud.sonic.android

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.gyf.immersionbar.ktx.immersionBar
import org.cloud.sonic.android.databinding.ActivityMainBinding
import org.cloud.sonic.android.service.SonicManagerServiceV2

class SonicServiceActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding: ActivityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        immersionBar {
            statusBarColor(R.color.bg_page)
            navigationBarColor(R.color.bg_card)
            statusBarDarkFont(true)
            autoDarkModeEnable(true)
        }

        SonicManagerServiceV2.start(this)

        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 1500)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}