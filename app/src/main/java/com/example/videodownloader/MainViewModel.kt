package com.example.videodownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ezt1.webvideo.ParserRouter
import com.ezt1.webvideo.parser.CoreURLParser
import com.ezt1.webvideo.parser.VideoInfo
import kotlinx.coroutines.launch

sealed class ParseState {
    object Idle : ParseState()
    object Loading : ParseState()
    data class Success(val videos: List<VideoInfo>) : ParseState()
    data class SuccessSingle(val video: VideoInfo) : ParseState()
    data class Error(val message: String) : ParseState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val parseState = MutableLiveData<ParseState>(ParseState.Idle)
    val logMessages = MutableLiveData<List<String>>(emptyList())
    val cookieString = MutableLiveData<String>("")

    private val core = CoreURLParser(app)
    private val router = ParserRouter(core)
    private val logs = mutableListOf<String>()

    // JS đã tìm được URL → router không cần chạy nữa
    private var jsFoundResults = false

    var pendingThumbnail: String = ""

    fun setWebViewUserAgent(ua: String) {
        core.webViewUserAgent = ua
    }

    private fun log(msg: String) {
        logs.add(msg)
        logMessages.postValue(logs.toList())
    }

    private fun prepareExtract() {
        parseState.value = ParseState.Loading
        logs.clear()
        logMessages.value = emptyList()
        core.cookieString = cookieString.value ?: ""
    }

    // Reset khi load URL mới
    fun resetForNewUrl() {
        jsFoundResults = false
        pendingThumbnail = ""
        parseState.value = ParseState.Idle
        logs.clear()
        logMessages.value = emptyList()
    }

    // ── Router path — gọi sau onPageFinished cho các parser dùng API ─────
    // Quan trọng với Dailymotion, v.v. — URL không có trong HTML/JS
    fun extractFromUrl(pageUrl: String) {
        if (pageUrl.isBlank()) return
        // JS đã thắng rồi → skip
        if (jsFoundResults) {
            log("[router] skipped — JS already found results")
            return
        }
        prepareExtract()
        viewModelScope.launch {
            val results = router.parse(pageUrl, "", ::log)
            // Kiểm tra lại: JS có thể đã xong trong lúc router đang chạy
            if (!jsFoundResults) {
                parseState.postValue(
                    if (results.isNotEmpty()) ParseState.Success(results)
                    else ParseState.Error("No video streams found")
                )
            }
        }
    }

    // ── JS inject path — ưu tiên cao hơn router ───────────────────────────
    fun extractFromCandidates(
        pageUrl: String,
        candidates: List<VideoInfo>,
        titleHint: String = "",
        thumbHint: String = ""
    ) {
        if (candidates.isEmpty()) return
        jsFoundResults = true  // JS thắng, router sẽ bị skip
        prepareExtract()
        viewModelScope.launch {
            val results = core.verifyAndEnrichCandidates(
                pageUrl = pageUrl,
                candidates = candidates,
                titleHint = titleHint,
                thumbHint = thumbHint,
                onLog = ::log
            )
            parseState.postValue(
                if (results.isNotEmpty()) ParseState.Success(results)
                else ParseState.Error("No downloadable streams found")
            )
        }
    }

    fun updateFromSingle(
        vid: VideoInfo?,
    ) {
        if (vid == null) return
        parseState.postValue(
            ParseState.SuccessSingle(vid)
        )

    }

}