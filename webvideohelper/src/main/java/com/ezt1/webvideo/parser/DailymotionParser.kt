package com.ezt1.webvideo.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.regex.Pattern

// ════════════════════════════════════════════════════════════════════════════
//  DailymotionParser
//  Reverse engineered từ DailymotionImpl.java (Z6.j)
//
//  Flow:
//  1. Extract video ID từ URL
//  2. Tìm __PLAYER_CONFIG__ JSON trong HTML → lấy metadata_template_url
//  3. Fetch metadata API → JSON chứa qualities, title, thumbnail, duration
//  4. Parse qualities:
//     - "qualities" → { "auto": [ {type, url} ] } → HLS URL
//     - "thumbnails" → { "200": "url" } → thumbnail
// ════════════════════════════════════════════════════════════════════════════

class DailymotionParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        private const val TAG = "DailymotionParser"

        // Các domain của Dailymotion
        private val DOMAINS = listOf(
            "dailymotion.com",
            "dai.ly"
        )

        // Regex tìm __PLAYER_CONFIG__ trong HTML
        // Decoded từ: StringFog "AWELIBNfGkwPWQRSLUN8TjxJBl8dIAogby5FPx87Ti8EYwtpPHQ-"
        private val PLAYER_CONFIG_REGEX =
            Pattern.compile("var __PLAYER_CONFIG__ = (.*?);</script>")
    }

    override fun canHandle(url: String) = DOMAINS.any { url.contains(it) }

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            log(onLog, "[Dailymotion] $url")

            // BƯỚC 1: Extract video ID từ URL — h()
            val videoId = extractVideoId(url)
            if (videoId.isEmpty()) {
                log(onLog, "  [error] cannot extract video ID")
                return@withContext emptyList()
            }
            log(onLog, "  [id] $videoId")

            // BƯỚC 2: Tìm metadata URL từ __PLAYER_CONFIG__ trong HTML — i()
            var metadataUrl = extractMetadataUrl(html, videoId)

            // BƯỚC 3: Fallback — build API URL thủ công
            if (metadataUrl.isEmpty()) {
                log(onLog, "  [fallback] building API URL manually")
                metadataUrl = buildApiUrl(url, videoId)
            }
            log(onLog, "  [api] ${metadataUrl.take(80)}")

            // BƯỚC 4: Fetch metadata JSON
            val json = fetchMetadata(metadataUrl, url) ?: run {
                log(onLog, "  [error] failed to fetch metadata")
                return@withContext emptyList()
            }

            // BƯỚC 5: Parse response — k()
            parseMetadata(json, url, onLog)
        }
    }

    // ── h() — Extract video ID từ URL ─────────────────────────────────────
    // "https://www.dailymotion.com/video/x8abc12?playlist=xxx"
    //  → "x8abc12"
    private fun extractVideoId(url: String): String {
        return try {
            // Cắt query string tại "?playlist"
            val cleanUrl = url.substringBefore("?playlist").substringBefore("?")
            // Lấy phần sau dấu / cuối
            cleanUrl.substringAfterLast("/")
        } catch (e: Exception) {
            ""
        }
    }

    // ── i() — Extract metadata URL từ __PLAYER_CONFIG__ ───────────────────
    private fun extractMetadataUrl(html: String, videoId: String): String {
        try {
            val m = PLAYER_CONFIG_REGEX.matcher(html)
            if (m.find()) {
                // "context" object bên trong __PLAYER_CONFIG__
                val context = JSONObject(m.group(1)).getJSONObject("context")

                // Thử "metadata_template_url" trước
                val templateUrl = when {
                    context.has("metadata_template_url") ->
                        context.optString("metadata_template_url", "")
                    context.has("metadata_template_url1") ->
                        context.optString("metadata_template_url1", "")
                    else -> ""
                }

                // Replace ":videoId" placeholder
                if (templateUrl.isNotEmpty() && templateUrl.contains(":videoId")) {
                    return templateUrl.replace(":videoId", videoId)
                }
                return templateUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractMetadataUrl: ${e.message}")
        }
        return ""
    }

    // ── i() fallback — Build Dailymotion embed API URL ────────────────────
    // Format: https://{partner_host}/{videoId}?embedder={encodedUrl}&integration=inline&GK_PV5_NEON=1
    private fun buildApiUrl(pageUrl: String, videoId: String): String {
        return try {
            val encodedUrl = URLEncoder.encode(pageUrl, "UTF-8")
            "https://www.dailymotion.com/player/metadata/video/$videoId" +
                    "?embedder=$encodedUrl" +
                    "&integration=inline&GK_PV5_NEON=1"
        } catch (e: Exception) {
            "https://www.dailymotion.com/player/metadata/video/$videoId"
        }
    }

    // ── Fetch metadata JSON ───────────────────────────────────────────────
    private fun fetchMetadata(metadataUrl: String, referer: String): JSONObject? {
        return try {
            // Dailymotion cần UA desktop + Origin header đúng
            val headers = mapOf(
                "Referer"    to referer,
                "User-Agent" to CoreURLParser.UA_DESKTOP,
                "Origin"     to "https://www.dailymotion.com",
                "Accept"     to "application/json, text/plain, */*"
            )
            val body = core.getRequestPublicWithHeaders(metadataUrl, headers)
                ?: return null
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMetadata: ${e.message}")
            null
        }
    }

    // ── k() — Parse metadata JSON → List<VideoInfo> ───────────────────────
    private fun parseMetadata(
        json: JSONObject, pageUrl: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            // Duration: "duration" (seconds → ms)
            val durationMs = json.optInt("duration", 0) * 1000

            // Thumbnail: "thumbnails" → { "200": "url", "480": "url" }
            var thumbnail = ""
            if (json.has("thumbnails")) {
                val thumbs = json.optJSONObject("thumbnails")
                if (thumbs != null) {
                    // Lấy thumbnail có resolution cao nhất (key >= 200)
                    var maxRes = 0
                    val keys = thumbs.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val res = key.toIntOrNull() ?: 0
                        if (res >= 200 && res > maxRes) {
                            val url = thumbs.optString(key)
                            if (url.isNotEmpty()) {
                                thumbnail = url
                                maxRes = res
                            }
                        }
                    }
                }
            }

            // Title: "title"
            val title = json.optString("title", "").ifEmpty {
                json.optString("name", "")
            }

            log(onLog, "  [meta] title=$title | thumb=${if(thumbnail.isNotEmpty()) "✓" else "—"} | dur=${durationMs/1000}s")

            // Video URLs: "qualities" → { "auto": [ {type, url} ] }


            val qualities = json.optJSONObject("qualities")
            if (qualities == null) {
                log(onLog, "  [error] no 'qualities' field")
                return results
            }

            // "auto" array chứa các format
            val autoArr = qualities.optJSONArray("auto")
            if (autoArr == null) {
                log(onLog, "  [error] no 'auto' quality")
                return results
            }

            val seenHeights = mutableSetOf<Int>()

            for (i in 0 until autoArr.length()) {
                val item = autoArr.getJSONObject(i)
                val type = item.optString("type", "")
                val url  = item.optString("url", "")

                if (url.isEmpty()) continue

                when {
                    // HLS stream — "application/x-mpegURL"
                    type == "application/x-mpegURL" || url.contains(".m3u8") -> {
                        log(onLog, "  → HLS: ${url.take(70)}")
                        val streams = core.parseHlsMaster(url, pageUrl, emptyList(), onLog)
                        for (s in streams) {
                            if (s.height !in seenHeights) {
                                results.add(VideoInfo(
                                    url       = s.toJson(),
                                    pageUrl   = pageUrl,
                                    mimeType  = "application/x-mpegURL",
                                    title     = title,
                                    height    = s.height,
                                    duration  = s.duration.toInt(),
                                    thumbnail = thumbnail,
                                    hlsKey    = s.keyHex,
                                    isDownloadable = s.hasSegments()
                                ))
                                seenHeights.add(s.height)
                            }
                        }
                    }

                    // MP4 direct
                    type.contains("video/mp4") || url.contains(".mp4") -> {
                        val height = core.heightFromUrl(url)
                        if (height !in seenHeights) {
                            log(onLog, "  ✓ MP4 ${height}p: ${url.take(70)}")
                            results.add(VideoInfo(
                                url       = url,
                                pageUrl   = pageUrl,
                                mimeType  = "video/mp4",
                                title     = title,
                                height    = height,
                                duration  = durationMs,
                                thumbnail = thumbnail,
                                isDownloadable = true
                            ))
                            seenHeights.add(height)
                        }
                    }
                }
            }

            log(onLog, "  [done] ${results.size} stream(s)")

        } catch (e: Exception) {
            Log.e(TAG, "parseMetadata: ${e.message}")
        }
        return results
    }
}