package com.example.videodownloader.base

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.example.videodownloader.databinding.ActivityMainImplBinding
import com.ezt1.webvideo.ParseWebClient
import com.ezt1.webvideo.parser.VideoInfo

class MainImplActivity : AppCompatActivity() {
    lateinit var binding: ActivityMainImplBinding
    lateinit var parseClient: ParseWebClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainImplBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parseClient = object : ParseWebClient(this) {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                runOnUiThread {
                    binding.tvTitle.text = title.toString()
                }
            }
        }
        parseClient.setListener(object : ParseWebClient.WebResponseListener() {
            override fun onNewVideo(vi: VideoInfo) {

               Log.e("onNewVideo","$vi")

            }

            override fun onNewVideos(vis: List<VideoInfo>) {
                Log.e("onNewVideos","listOF: ")
                vis.forEach {
                    Log.e("onNewVideos","$it")
                }
            }

            override fun onVideoError(s: String) {
                Log.e("onVideoError", s)
            }
        })

        parseClient.setupWith(webView = binding.webView)
        binding.webView.loadUrl(parseClient.currentPageUrl)
    }

}