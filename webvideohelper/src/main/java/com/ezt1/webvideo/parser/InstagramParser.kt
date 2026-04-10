package com.ezt1.webvideo.parser

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.regex.Pattern

// ════════════════════════════════════════════════════════════════════════════
//  InstagramParser
//  Reverse engineered từ InstagramImpl.java (Z6.p)
//
//  Flow:
//  1. shouldInterceptRequest sniff X-IG-App-ID, X-CSRFToken, X-ASBD-ID
//  2. Extract shortcode từ URL (/p/CODE/ hoặc /reel/CODE/)
//  3. POST graphql/query với doc_id phù hợp
//  4. Parse response → video_url + thumbnail + title
// ════════════════════════════════════════════════════════════════════════════

class InstagramParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        private const val TAG = "InstagramParser"
        private const val GRAPHQL_URL = "https://www.instagram.com/graphql/query"

        // doc_id tương đương app gốc
        private const val DOC_ID_LOGGED_IN   = "9140397392731123"
        private const val DOC_ID_LOGGED_OUT  = "9510064595728286"
        private const val DOC_ID_MEDIA_ID    = "24206836168985659"

        // Headers sniffed từ shouldInterceptRequest — shared across instances
        var igAppId:    String = ""
        var igCsrfToken: String = ""
        var igAsbdId:   String = ""
    }

    override fun canHandle(url: String) =
        url.contains("instagram.com") &&
                (url.contains("/p/") || url.contains("/reels/") || url.contains("/tv/"))

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            log(onLog, "[Instagram] $url")

            // Extract shortcode
            val shortcode = extractShortcode(url)
            if (shortcode.isEmpty()) {
                log(onLog, "  [error] cannot extract shortcode")
                return@withContext emptyList()
            }
            log(onLog, "  [shortcode] $shortcode")

            val cookie = CookieManager.getInstance().getCookie(url) ?: ""
            // Check login
            val isLoggedIn = isLoggedIn()
            log(onLog, "  [login] ${if (isLoggedIn) "✓ logged in" else "not logged in"}")

            // Lấy csrftoken trực tiếp từ cookie — y chang browser
            val csrfFromCookie = Regex("csrftoken=([^;]+)").find(cookie)?.groupValues?.get(1) ?: ""

            val body = "variables=" +
                    java.net.URLEncoder.encode("""{"shortcode":"$shortcode"}""", "UTF-8") +
                    "&doc_id=$DOC_ID_LOGGED_OUT"

            log(onLog, "  [graphql] shortcode=$shortcode | csrf=${csrfFromCookie.take(10)}")

            val headers = mutableMapOf(
                "Content-Type"     to "application/x-www-form-urlencoded",
                "X-CSRFToken"      to csrfFromCookie,
                "X-Requested-With" to "XMLHttpRequest",
                "Referer"          to "https://www.instagram.com/",
                "Origin"           to "https://www.instagram.com",
                "Cookie"           to cookie
            )

            // Debug log
            log(onLog, "  [csrf] ${csrfFromCookie.take(10)} | cookie: ${cookie.take(50)}")
            log(onLog, "  [body] ${body.take(100)}")

            val responseBody = core.postRequestWithHeaders(GRAPHQL_URL, body, headers)
                ?: return@withContext emptyList<VideoInfo>().also {
                    log(onLog, "  [error] graphql request failed")
                }

            parseResponse(responseBody, url, isLoggedIn, onLog)
        }
    }

    // ── Extract shortcode từ URL ───────────────────────────────────────────

    private fun extractShortcode(url: String): String {
        val m = Pattern.compile("""/(?:p|reels|tv)/([A-Za-z0-9_-]+)""").matcher(url)
        return if (m.find()) m.group(1) ?: "" else ""
    }

    // ── Check login ────────────────────────────────────────────────────────

    private fun isLoggedIn(): Boolean {
        return try {
            val cookie = CookieManager.getInstance().getCookie("https://www.instagram.com/") ?: ""
            cookie.contains("ds_user_id=")
        } catch (e: Exception) { false }
    }

    // ── Extract csrftoken từ cookie ────────────────────────────────────────

    private fun extractCsrf(cookie: String): String {
        val idx = cookie.indexOf("csrftoken=")
        if (idx < 0) return ""
        val start = idx + 10
        val end   = cookie.indexOf(";", start).let { if (it < 0) cookie.length else it }
        return cookie.substring(start, end)
    }

    // ── Build request body ─────────────────────────────────────────────────

    private fun buildBody(shortcode: String, isLoggedIn: Boolean): String {
        return if (isLoggedIn) {
            "variables=%7B%22shortcode%22%3A%22$shortcode%22%2C%22__relay_internal__pv__PolarisShareSheetV3relayprovider%22%3Afalse%7D&doc_id=$DOC_ID_LOGGED_IN"
        } else {
            "variables=%7B%22shortcode%22%3A%22$shortcode%22%7D&doc_id=$DOC_ID_LOGGED_OUT"
        }
    }

    // ── Parse GraphQL response ─────────────────────────────────────────────

    private fun parseResponse(
        body: String, pageUrl: String,
        isLoggedIn: Boolean, onLog: (String) -> Unit
    ): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: run {
                log(onLog, "  [error] no data field | body: ${body.take(100)}")
                return emptyList()
            }

            // Thử logged-out path trước (xdt_shortcode_media)
            val media = data.optJSONObject("xdt_shortcode_media")
            if (media != null) {
                log(onLog, "  [path] xdt_shortcode_media")
                parseShortcodeMedia(media, pageUrl, results, onLog)
                if (results.isNotEmpty()) return results
            }

            // Thử logged-in path (xdt_api__v1__media__shortcode__web_info)
            val items = data.optJSONObject("xdt_api__v1__media__shortcode__web_info")
                ?.optJSONArray("items")
            if (items != null && items.length() > 0) {
                log(onLog, "  [path] xdt_api__v1__media__shortcode__web_info")
                parseMediaObject(items.getJSONObject(0), pageUrl, results, onLog)
                if (results.isNotEmpty()) return results
            }

            // Thử fetch__XDTMediaDict
            val mediaDict = data.optJSONObject("fetch__XDTMediaDict")
            if (mediaDict != null) {
                log(onLog, "  [path] fetch__XDTMediaDict")
                parseMediaObject(mediaDict, pageUrl, results, onLog)
            }

            if (results.isEmpty()) {
                log(onLog, "  [error] no known data path found | keys: ${data.keys().asSequence().toList()}")
            }

        } catch (e: Exception) {
            log(onLog, "  [error] parse exception: ${e.message}")
        }
        log(onLog, "  [done] ${results.size} result(s)")
        return results
    }



    private fun parseShortcodeMedia(
        media: JSONObject, pageUrl: String,
        results: MutableList<VideoInfo>, onLog: (String) -> Unit
    ) {
        if (media.optBoolean("is_video")) {
            val videoUrl = media.optString("video_url")
            val thumbUrl = media.optString("display_url")
                .ifEmpty { media.optString("thumbnail_src") }
            val title = try {
                media.getJSONObject("edge_media_to_caption")
                    .getJSONArray("edges")
                    .getJSONObject(0)
                    .getJSONObject("node")
                    .getString("text")
            } catch (_: Exception) { "" }

            // Height từ dimensions field
            val height = media.optJSONObject("dimensions")?.optInt("height", 0) ?: 0
            val durationMs = (media.optDouble("video_duration", 0.0) * 1000).toInt()

            if (videoUrl.isNotEmpty()) {
                log(onLog, "  ✓ video: ${videoUrl.take(70)}")
                results.add(VideoInfo(
                    url       = videoUrl,
                    pageUrl   = pageUrl,
                    mimeType  = "video/mp4",
                    height = height,
                    duration = durationMs,
                    title     = title,
                    thumbnail = thumbUrl,
                    isDownloadable = true
                ))
            }
        } else if (media.optString("__typename") == "GraphSidecar") {
            val edges = media.optJSONObject("edge_sidecar_to_children")
                ?.optJSONArray("edges") ?: return
            for (i in 0 until edges.length()) {
                val node = edges.getJSONObject(i).getJSONObject("node")
                if (node.optBoolean("is_video")) {
                    results.add(VideoInfo(
                        url       = node.optString("video_url"),
                        pageUrl   = pageUrl,
                        mimeType  = "video/mp4",
                        thumbnail = node.optString("display_url"),
                        isDownloadable = true
                    ))
                } else {
                    results.add(VideoInfo(
                        url       = node.optString("display_url"),
                        pageUrl   = pageUrl,
                        mimeType  = "image/png",
                        isDownloadable = true
                    ))
                }
            }
        } else {
            // Image
            val url = media.optString("display_url")
            if (url.isNotEmpty()) {
                results.add(VideoInfo(
                    url       = url,
                    pageUrl   = pageUrl,
                    mimeType  = "image/png",
                    isDownloadable = true
                ))
            }
        }
    }

    // ── m() — Parse logged-in media object ────────────────────────────────

    private fun parseMediaObject(
        obj: JSONObject, pageUrl: String,
        results: MutableList<VideoInfo>, onLog: (String) -> Unit
    ) {
        try {
            val title = try {
                obj.getJSONObject("caption").optString("text")
            } catch (_: Exception) { "" }

            when (obj.optInt("media_type")) {
                1 -> {
                    // Image
                    val url = getImageUrl(obj) ?: return
                    results.add(VideoInfo(url = url, pageUrl = pageUrl,
                        mimeType = "image/png", title = title, isDownloadable = true))
                }
                2 -> {
                    // Video
                    val videoUrl = getVideoUrl(obj) ?: return
                    val thumbUrl = getImageUrl(obj) ?: ""
                    log(onLog, "  ✓ video: ${videoUrl.take(70)}")
                    results.add(VideoInfo(
                        url       = videoUrl,
                        pageUrl   = pageUrl,
                        mimeType  = "video/mp4",
                        title     = title,
                        thumbnail = thumbUrl,
                        isDownloadable = true
                    ))
                }
                8 -> {
                    // Carousel
                    val carousel = obj.optJSONArray("carousel_media") ?: return
                    for (i in 0 until carousel.length()) {
                        val item = carousel.getJSONObject(i)
                        when (item.optInt("media_type")) {
                            1 -> {
                                val url = getImageUrl(item) ?: continue
                                results.add(VideoInfo(url = url, pageUrl = pageUrl,
                                    mimeType = "image/png", title = title, isDownloadable = true))
                            }
                            2 -> {
                                val videoUrl = getVideoUrl(item) ?: continue
                                val thumbUrl = getImageUrl(item) ?: ""
                                results.add(VideoInfo(
                                    url = videoUrl, pageUrl = pageUrl,
                                    mimeType = "video/mp4", title = title,
                                    thumbnail = thumbUrl, isDownloadable = true
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseMediaObject: ${e.message}")
        }
    }

    // ── h() — Get image URL ────────────────────────────────────────────────

    private fun getImageUrl(obj: JSONObject): String? {
        val key = if (obj.has("image_versions")) "image_versions" else "image_versions2"
        val versions = obj.optJSONObject(key)
        val arr = versions?.optJSONArray("candidates") ?: obj.optJSONArray(key)
        return arr?.optJSONObject(0)?.optString("url")?.ifEmpty { null }
    }

    // ── i() — Get video URL ────────────────────────────────────────────────

    private fun getVideoUrl(obj: JSONObject): String? {
        val key = if (obj.has("video_versions")) "video_versions" else "video_versions2"
        val versions = obj.optJSONObject(key)
        val arr = versions?.optJSONArray("candidates") ?: obj.optJSONArray(key)
        return arr?.optJSONObject(0)?.optString("url")?.ifEmpty { null }
    }
}