package org.cloud.sonic.android

import android.content.res.Configuration
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.core.content.ContextCompat
import com.gyf.immersionbar.ktx.immersionBar
import org.cloud.sonic.android.agent.SonicLinkAgentService
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.agent.SonicLinkStatus
import org.cloud.sonic.android.databinding.ActivityMainBinding
import org.cloud.sonic.android.ui.dashboard.DashboardFragment
import org.cloud.sonic.android.ui.defect.DefectAssistantFragment
import org.cloud.sonic.android.ui.files.FilesFragment
import org.cloud.sonic.android.ui.media.MediaFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: SonicLinkConfigStore

    private val fragments = listOf(
        DashboardFragment(),
        MediaFragment(),
        FilesFragment(),
        DefectAssistantFragment()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = SonicLinkConfigStore(this)

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        immersionBar {
            transparentStatusBar()
            transparentNavigationBar()
            statusBarDarkFont(!isDark)
            navigationBarDarkIcon(!isDark)
        }

        setupViewPagerAndNavigation()
        handleNavigationIntent(intent)

        if (configStore.getConfig().autoConnect && !SonicLinkStatus.serviceRunning) {
            SonicLinkAgentService.start(this)
        }

        // Apply iOS-like Glassmorphism (Blur) for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Remove RenderEffect since it blurs the whole view tree (including icons)
            binding.bottomNavContainer.setRenderEffect(null)
            
            // To achieve true iOS style glassmorphism on the *background* of the view only
            // without blurring the icons, we need to use a custom RenderNode or third-party blur library.
            // window.setBackgroundBlurRadius blurs the entire activity background behind the window.
            // For now, we rely on a beautiful semi-transparent acrylic color to simulate the glass,
            // avoiding the bug where the navigation icons become invisible.
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val bgColor = if (isDark) 0xD90F172A.toInt() else 0xD9FFFFFF.toInt() // 85% opacity
            binding.bottomNavContainer.setCardBackgroundColor(bgColor)
            binding.bottomNav.setBackgroundColor(0x00000000.toInt())
        } else {
            // Fallback
            binding.bottomNavContainer.setCardBackgroundColor(ContextCompat.getColor(this, R.color.bg_card))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentPosition = binding.viewPager.currentItem
                if (currentPosition == 2) {
                    val filesFragment = fragments[2] as? FilesFragment
                    if (filesFragment?.handleBackPressed() == true) {
                        return
                    }
                }
                if (currentPosition != 0) {
                    binding.viewPager.currentItem = 0
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    private fun setupViewPagerAndNavigation() {
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = fragments.size
            override fun createFragment(position: Int): Fragment = fragments[position]
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val itemId = when (position) {
                    0 -> R.id.nav_dashboard
                    1 -> R.id.nav_media
                    2 -> R.id.nav_files
                    3 -> R.id.nav_defect
                    else -> R.id.nav_dashboard
                }
                if (binding.bottomNav.selectedItemId != itemId) {
                    binding.bottomNav.selectedItemId = itemId
                }
            }
        })

        binding.bottomNav.setOnItemSelectedListener { item ->
            val targetPosition = when (item.itemId) {
                R.id.nav_dashboard -> 0
                R.id.nav_media -> 1
                R.id.nav_files -> 2
                R.id.nav_defect -> 3
                else -> 0
            }
            binding.viewPager.setCurrentItem(targetPosition, false)
            true
        }
    }

    fun openDefectTab() {
        binding.viewPager.setCurrentItem(3, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DEFECT, false) == true) {
            openDefectTab()
            intent.removeExtra(EXTRA_OPEN_DEFECT)
        }
    }

    companion object {
        const val EXTRA_OPEN_DEFECT = "extra_open_defect"
    }
}
