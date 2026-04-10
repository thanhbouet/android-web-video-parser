package com.ezt1.webvideo.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class TikTokParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        val DOMAINS = listOf("tiktok.com", "tiktokv.com")
    }

    override fun canHandle(url: String) = DOMAINS.any { url.contains(it) }

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            // JS inject trong INJECT_JS sẽ tìm playAddr, downloadAddr từ bitrateInfo
            // Cookie được WebView CookieManager tự quản lý
            // DownloadManager sẽ đọc cookie khi download
            log(onLog, "[TikTok] JS inject sẽ detect video URLs — cookies auto-managed by WebView")
            emptyList()
        }
    }
}