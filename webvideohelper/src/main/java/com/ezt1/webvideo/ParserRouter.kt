package com.ezt1.webvideo

import com.ezt1.webvideo.parser.BaseParser
import com.ezt1.webvideo.parser.CommonWebParser
import com.ezt1.webvideo.parser.CoreURLParser
import com.ezt1.webvideo.parser.DailymotionParser
import com.ezt1.webvideo.parser.InstagramParser
import com.ezt1.webvideo.parser.NaverParser
import com.ezt1.webvideo.parser.RedditParser
import com.ezt1.webvideo.parser.TikTokParser
import com.ezt1.webvideo.parser.TwitterParser
import com.ezt1.webvideo.parser.VideoInfo
import com.ezt1.webvideo.parser.VkParser

class ParserRouter(private val core: CoreURLParser) {

    // Danh sách parser theo thứ tự ưu tiên
    // Thêm site-specific parser vào đây khi có
    private val parsers: List<BaseParser> = listOf(
        DailymotionParser(core),
        VkParser(core),
        TikTokParser(core),
        NaverParser(core),
        RedditParser(core),
        InstagramParser(core),
        TwitterParser(core),

        CommonWebParser(core),)

    /**
     * Tìm parser phù hợp và parse URL.
     * Giống logic dispatch của app gốc: duyệt qua từng parser,
     * dùng parser đầu tiên có canHandle() = true.
     */
    suspend fun parse(
        url: String,
        html: String,
        onLog: (String) -> Unit
    ): List<VideoInfo> {
        val parser = parsers.firstOrNull { it.canHandle(url) }
            ?: CommonWebParser(core)

        onLog("[router] ${parser::class.simpleName} → $url")
        return parser.parse(url, html, onLog)
    }

    /**
     * Trả về tên parser sẽ được dùng cho URL này.
     * Dùng để debug / hiển thị UI.
     */
    fun resolveParserName(url: String): String =
        parsers.firstOrNull { it.canHandle(url) }
            ?.let { it::class.simpleName ?: "Unknown" }
            ?: "CommonWebParser"
}