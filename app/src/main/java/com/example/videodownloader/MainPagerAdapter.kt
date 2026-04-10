package com.example.videodownloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.videodownloader.databinding.FragmentLogBinding
import com.example.videodownloader.databinding.FragmentResultsBinding
import com.example.videodownloader.databinding.FragmentWebviewBinding
import com.ezt1.webvideo.DownloadManager

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 3
    override fun createFragment(position: Int): Fragment = when (position) {
        0    -> WebViewFragment()
        1    -> ResultsFragment()
        else -> LogFragment()
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  BROWSER FRAGMENT
// ════════════════════════════════════════════════════════════════════════════

class WebViewFragment : Fragment() {
    private var _b: FragmentWebviewBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentWebviewBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.setupWebView(b.webView)
    }

    fun getWebView(): WebView? = _b?.webView

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ════════════════════════════════════════════════════════════════════════════
//  RESULTS FRAGMENT
// ════════════════════════════════════════════════════════════════════════════

class ResultsFragment : Fragment() {
    private var _b: FragmentResultsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentResultsBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        val adapter = VideoResultAdapter { vi ->
            DownloadManager.start(requireContext(), vi)
//            val intent =Intent(Intent.ACTION_VIEW, vi.url.toUri())
//            startActivity(intent)
        }
        b.rvResults.layoutManager = LinearLayoutManager(requireContext())
        b.rvResults.adapter = adapter

        vm.parseState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ParseState.Success -> {
                    adapter.submitList(state.videos)
                    state.videos?.forEach {vd->
                        Log.e("VideoFound", "$vd")
                    }
                    b.tvResultCount.text = "${state.videos.size} stream(s) found"
                }
                is ParseState.SuccessSingle -> {
                    val listOld = adapter.currentList.toMutableList()
                    if (listOld.contains(state.video)) return@observe
                    listOld.add(state.video)
                    adapter.submitList(listOld)

                    Log.e("VideoFound", "${state.video}")
                    b.tvResultCount.text = "${listOld.size} stream(s) found"

                }
                is ParseState.Error   -> {
                    adapter.submitList(emptyList())
                    b.tvResultCount.text = state.message
                }
                is ParseState.Loading -> b.tvResultCount.text = "Scanning…"
                else -> {}
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }
}

// ════════════════════════════════════════════════════════════════════════════
//  LOG FRAGMENT
// ════════════════════════════════════════════════════════════════════════════

class LogFragment : Fragment() {
    private var _b: FragmentLogBinding? = null
    private val b get() = _b!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        FragmentLogBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vm = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        val adapter = LogAdapter()
        b.rvLog.layoutManager = LinearLayoutManager(requireContext())
        b.rvLog.adapter = adapter

        vm.logMessages.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs.toList())
            if (logs.isNotEmpty())
                b.rvLog.scrollToPosition(logs.size - 1)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView();
        _b = null
    }
}
