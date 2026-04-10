package com.ezt1.webvideo.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

// ════════════════════════════════════════════════════════════════════════════
//  VkParser — vk.com video downloader
//  Reverse engineered từ VkImpl.java (Z6.J)
//
//  Flow:
//  1. Extract owner_id + video_id từ URL
//  2. Fetch API: api.vk.com/method/video.get?videos={owner_id}_{video_id}
//  3. Parse response: image[], title, duration, files{mp4_XXX}
// ════════════════════════════════════════════════════════════════════════════

class VkParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        private const val TAG = "VkParser"
        private const val API = "https://api.vk.com/method/video.get"
        private const val VERSION = "5.275"
        private const val CLIENT_ID = "7879029"

        private val DOMAINS = listOf("vk.com", "vk.ru", "vkvideo.ru")

        // Match: /video-220754053_456245515 hoặc /video220754053_456245515
        private val VIDEO_ID_RE = Pattern.compile(
            """video(-?\d+)_(\d+)""", Pattern.CASE_INSENSITIVE
        )
    }

    override fun canHandle(url: String) = DOMAINS.any { url.contains(it) }
            && (url.contains("/video") || url.contains("clip"))

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            log(onLog, "[VK] $url")

            // BƯỚC 1: Extract owner_id + video_id từ URL
            val m = VIDEO_ID_RE.matcher(url)
            if (!m.find()) {
                log(onLog, "  [error] cannot extract video ID from URL")
                return@withContext emptyList()
            }
            val ownerId = m.group(1) ?: return@withContext emptyList()
            val videoId = m.group(2) ?: return@withContext emptyList()
            val videoKey = "${ownerId}_${videoId}"
            log(onLog, "  [id] $videoKey")

            // BƯỚC 2: Fetch API
            val apiUrl = "$API?videos=$videoKey&v=$VERSION&client_id=$CLIENT_ID"
            log(onLog, "  [api] $apiUrl")

            val body = core.getRequestPublicWithHeaders(
                apiUrl, mapOf(
                    "User-Agent" to CoreURLParser.UA_MOBILE,
                    "Referer" to "https://vk.com/",
                    "Origin" to "https://vk.com"
                )
            ) ?: return@withContext emptyList<VideoInfo>().also {
                log(onLog, "  [error] API fetch failed")
            }

            // BƯỚC 3: Parse
            parseResponse(body, url, onLog)
        }
    }

    // ── Parse JSON response ────────────────────────────────────────────────

    private fun parseResponse(
        body: String, pageUrl: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            val json = JSONObject(body)
            val response = json.optJSONObject("response")
                ?: return emptyList<VideoInfo>().also { log(onLog, "  [error] no response field") }

            // Lấy video object — tương đương VkImpl.h()
            val videoObj = when {
                response.opt("current_video") is JSONObject ->
                    response.getJSONObject("current_video")

                response.opt("items") != null ->
                    response.getJSONArray("items").getJSONObject(0)

                else -> return emptyList<VideoInfo>().also {
                    log(
                        onLog,
                        "  [error] no video object"
                    )
                }
            }

            parseVideoObject(videoObj, pageUrl, results, onLog)

        } catch (e: Exception) {
            Log.e(TAG, "parseResponse: ${e.message}")
        }
        return results
    }

    // ── i() — Parse single video object ───────────────────────────────────

    private fun parseVideoObject(
        obj: JSONObject, pageUrl: String,
        results: MutableList<VideoInfo>, onLog: (String) -> Unit
    ) {
        try {
            // Thumbnail: image[] — lấy ảnh có width lớn nhất, không quá 1280
            val thumbnail = try {
                val images = obj.getJSONArray("image")
                var bestUrl = ""
                var bestWidth = 0
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val width = img.optInt("width", 0)
                    if (width in (bestWidth + 1)..1280) {
                        bestUrl = img.optString("url")
                        bestWidth = width
                    }
                }
                bestUrl
            } catch (_: Exception) {
                ""
            }

            val title = obj.optString("title")
            val durationMs = obj.optInt("duration") * 1000
            val files = obj.getJSONObject("files")

            log(
                onLog,
                "  [meta] title=${title.take(40)} | thumb=${if (thumbnail.isNotEmpty()) "✓" else "—"} | dur=${durationMs / 1000}s"
            )

            // files: { "mp4_144": url, "mp4_240": url, ..., "hls": url }
            val keys = files.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val url = files.getString(key)

                when {
                    // MP4 streams — "mp4_720" → height 720
                    key.startsWith("mp4_") -> {
                        val height = key.substring(4).toIntOrNull() ?: 480
                        log(onLog, "  ✓ MP4 ${height}p [$key]")
                        results.add(
                            VideoInfo(
                                url = url,
                                pageUrl = pageUrl,
                                mimeType = "video/mp4",
                                title = title,
                                height = height,
                                duration = durationMs,
                                thumbnail = thumbnail,
                                isDownloadable = true
                            )
                        )
                    }

                    // HLS — dùng "hls" (không dùng "hls_fmp4" vì sẽ bị corrupt)
                    key == "hls" -> {
                        log(onLog, "  → HLS: ${url.take(70)}")
                        val streams = core.parseHlsMaster(url, pageUrl, emptyList(), onLog)
                        for (s in streams) {
                            if (results.none { it.height == s.height && !it.isHls() }) {
                                results.add(
                                    VideoInfo(
                                        url = s.toJson(),
                                        pageUrl = pageUrl,
                                        mimeType = "application/x-mpegURL",
                                        title = title,
                                        height = s.height,
                                        duration = s.duration.toInt(),
                                        thumbnail = thumbnail,
                                        hlsKey = s.keyHex,
                                        isDownloadable = s.hasSegments()
                                    )
                                )
                            }
                        }
                    }
                    // Bỏ qua: hls_fmp4, dash_sep (fMP4 — concat bị corrupt), failover_host
                }
            }

            log(onLog, "  [done] ${results.size} stream(s)")

        } catch (e: Exception) {
            Log.e(TAG, "parseVideoObject: ${e.message}")
        }
    }
}