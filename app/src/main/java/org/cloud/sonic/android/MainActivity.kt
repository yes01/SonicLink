package org.cloud.sonic.android

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.gyf.immersionbar.ktx.immersionBar
import org.cloud.sonic.android.agent.SonicLinkAgentService
import org.cloud.sonic.android.agent.SonicLinkConfigStore
import org.cloud.sonic.android.agent.SonicLinkStatus
import org.cloud.sonic.android.databinding.ActivityMainBinding
import org.cloud.sonic.android.ui.dashboard.DashboardFragment
import org.cloud.sonic.android.ui.defect.DefectAssistantFragment
import org.cloud.sonic.android.ui.files.FilesFragment
import org.cloud.sonic.android.ui.images.ImagesFragment
import org.cloud.sonic.android.ui.videos.VideosFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: SonicLinkConfigStore

    private val fragments = listOf(
        DashboardFragment(),
        ImagesFragment(),
        VideosFragment(),
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
            statusBarColor(R.color.bg_page)
            navigationBarColor(R.color.bg_card)
            statusBarDarkFont(!isDark)
            navigationBarDarkIcon(!isDark)
            fitsSystemWindows(true)
            autoDarkModeEnable(true)
        }

        setupViewPagerAndNavigation()

        if (configStore.getConfig().autoConnect && !SonicLinkStatus.serviceRunning) {
            SonicLinkAgentService.start(this)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentPosition = binding.viewPager.currentItem
                if (currentPosition == 3) {
                    val filesFragment = fragments[3] as? FilesFragment
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
                    1 -> R.id.nav_images
                    2 -> R.id.nav_videos
                    3 -> R.id.nav_files
                    4 -> R.id.nav_defect
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
                R.id.nav_images -> 1
                R.id.nav_videos -> 2
                R.id.nav_files -> 3
                R.id.nav_defect -> 4
                else -> 0
            }
            binding.viewPager.setCurrentItem(targetPosition, false)
            true
        }
    }
}