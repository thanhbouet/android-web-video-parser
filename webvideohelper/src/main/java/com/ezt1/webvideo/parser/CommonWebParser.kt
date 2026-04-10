package com.ezt1.webvideo.parser

// ════════════════════════════════════════════════════════════════════════════
//  CommonWebParser — Generic fallback parser
//  Tương đương CommonWebImpl.java trong app gốc
//  Dùng cho mọi site không có parser riêng
// ════════════════════════════════════════════════════════════════════════════

class CommonWebParser(core: CoreURLParser) : BaseParser(core) {

    override fun canHandle(url: String) = true  // fallback, handle tất cả

    override suspend fun parse(
        url: String,
        html: String,
        onLog: (String) -> Unit
    ): List<VideoInfo> {
        log(onLog, "[CommonWebParser] $url")
        return core.extractVideoUrls(
            pageUrl      = url,
            injectedHtml = html,
            onLog        = onLog
        )
    }
}