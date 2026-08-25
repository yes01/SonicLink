package org.cloud.sonic.android.ui.media

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.cloud.sonic.android.R
import org.cloud.sonic.android.databinding.FragmentMediaBinding
import org.cloud.sonic.android.ui.images.ImagesFragment
import org.cloud.sonic.android.ui.videos.VideosFragment

class MediaFragment : Fragment() {
    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mediaPager.isUserInputEnabled = false
        binding.mediaPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 2

            override fun createFragment(position: Int): Fragment {
                return if (position == 0) ImagesFragment() else VideosFragment()
            }
        }

        binding.mediaToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val page = if (checkedId == R.id.btnVideos) 1 else 0
            binding.mediaPager.setCurrentItem(page, false)
        }

        binding.mediaPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.mediaToggle.check(if (position == 0) R.id.btnImages else R.id.btnVideos)
            }
        })
    }

    override fun onDestroyView() {
        binding.mediaPager.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
