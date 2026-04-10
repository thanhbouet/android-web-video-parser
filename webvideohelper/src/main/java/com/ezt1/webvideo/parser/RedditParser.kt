package com.ezt1.webvideo.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

// ════════════════════════════════════════════════════════════════════════════
//  RedditParser
//  Reverse engineered từ RedditImpl.java (Z6.A)
//
//  Flow:
//  1. URL → thêm ".json" → fetch Reddit JSON API
//  2. Parse post data: title, thumbnail
//  3. is_video=true  → DASH MPD XML → video streams + audio stream
//  4. is_video=false → media_metadata (GIF/HLS) / imgur / preview
// ════════════════════════════════════════════════════════════════════════════

class RedditParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        private const val TAG = "RedditParser"

        private val DOMAINS = listOf(
            "reddit.com",
            "redd.it",
            "www.reddit.com",
            "old.reddit.com"
        )
    }

    override fun canHandle(url: String) = DOMAINS.any { url.contains(it) }

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            log(onLog, "[Reddit] $url")

            // Build JSON API URL — thêm ".json" vào cuối post URL
            val apiUrl = buildJsonUrl(url)
            log(onLog, "  [api] $apiUrl")

            val body = core.getRequestPublicWithHeaders(apiUrl, mapOf(
                "User-Agent" to "android:com.ezt1.webvideo:v1.0",
                "Accept"     to "application/json"
            )) ?: return@withContext emptyList<VideoInfo>().also {
                log(onLog, "  [error] API fetch failed")
            }

            parseJson(body, url, onLog)
        }
    }

    // ── Build JSON API URL ─────────────────────────────────────────────────

    private fun buildJsonUrl(url: String): String {
        // Bỏ query string + fragment, đảm bảo có trailing slash, thêm .json
        val clean = url.substringBefore("?").substringBefore("#")
        val base  = if (clean.endsWith("/")) clean else "$clean/"
        return "${base}.json"
    }

    // ── Parse Reddit JSON response ─────────────────────────────────────────

    private fun parseJson(body: String, pageUrl: String, onLog: (String) -> Unit): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            // [0].data.children[0].data
            val postData = JSONArray(body)
                .getJSONObject(0)
                .getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")

            val title     = postData.optString("title")
            // Reddit thumbnail có thể là "self", "default", "nsfw", "spoiler" thay vì URL thật
            val rawThumb  = postData.optString("thumbnail").replace("amp;", "")
            val thumbnail = if (rawThumb.startsWith("http")) rawThumb else {
                // Fallback: lấy từ preview.images[0].source.url
                try {
                    postData.getJSONObject("preview")
                        .getJSONArray("images")
                        .getJSONObject(0)
                        .getJSONObject("source")
                        .getString("url")
                        .replace("amp;", "")
                } catch (_: Exception) { "" }

            }

            Log.e("GetThumbnail", "Thumbnail: $thumbnail")

            log(onLog, "  [post] ${title.take(50)}")

            if (postData.optBoolean("is_video")) {
                // ── Video post — DASH MPD ──────────────────────────────────
                val postUrl = postData.optString("url").let {
                    if (it.endsWith("/")) it else "$it/"
                }
                val redditVideo = postData.getJSONObject("media")
                    .getJSONObject("reddit_video")

                val dashUrl    = redditVideo.getString("dash_url").replace("amp;", "")
                val duration   = redditVideo.optInt("duration") * 1000
                val fallbackUrl = redditVideo.optString("fallback_url").replace("amp;", "")

                log(onLog, "  [dash] $dashUrl")

                // Parse DASH MPD — tương đương i()
                parseDash(dashUrl, postUrl, pageUrl, title, thumbnail, duration, results, onLog)

                // fallback_url — MP4 đơn giản nếu chưa có quality đó
                if (fallbackUrl.isNotEmpty()) {
                    val height = redditVideo.optInt("height", 240)
                    val alreadyHas = results.any { fallbackUrl.contains(it.url.toString().substringAfterLast("/")) }
                    if (!alreadyHas) {
                        log(onLog, "  ✓ fallback ${height}p: ${fallbackUrl.take(70)}")
                        results.add(VideoInfo(
                            url       = fallbackUrl,
                            pageUrl   = pageUrl,
                            mimeType  = "video/mp4",
                            title     = title,
                            height    = height,
                            duration  = duration,
                            thumbnail = thumbnail,
                            isDownloadable = true
                        ))
                    }
                }

            } else {
                // ── Non-video post ────────────────────────────────────────
                parseNonVideo(postData, pageUrl, title, thumbnail, results, onLog)
            }

            log(onLog, "  [done] ${results.size} stream(s)")
        } catch (e: Exception) {
            Log.e(TAG, "parseJson: ${e.message}")
        }
        return results
    }

    // ── i() — Parse DASH MPD XML ───────────────────────────────────────────
    // DASH MPD chứa video tracks theo height + audio track riêng

    private fun parseDash(
        dashUrl: String, baseUrl: String, pageUrl: String,
        title: String, thumbnail: String, duration: Int,
        results: MutableList<VideoInfo>, onLog: (String) -> Unit
    ) {
        try {
            val body = core.getRequestPublicWithHeaders(dashUrl, mapOf(
                "User-Agent" to "android:com.ezt1.webvideo:v1.0",
                "Referer"    to pageUrl
            )) ?: run {
                log(onLog, "  [error] DASH fetch failed")
                return
            }

            // Audio track — contentType="audio" … <BaseURL>…</BaseURL>
            val audioRe = Pattern.compile(
                """contentType="audio".*?<BaseURL>(.*?)</BaseURL>""", Pattern.DOTALL)
            val audioM  = audioRe.matcher(body)
            if (audioM.find()) {
                var audioUrl = audioM.group(1) ?: ""
                if (!audioUrl.startsWith("http")) audioUrl = baseUrl + audioUrl
                audioUrl = audioUrl.replace("amp;", "")
                log(onLog, "  ✓ Audio: ${audioUrl.take(70)}")
                results.add(VideoInfo(
                    url       = audioUrl,
                    pageUrl   = pageUrl,
                    mimeType  = "audio/mp4",
                    title     = title,
                    height    = 10,      // height=10 = audio marker (tương đương app gốc)
                    duration  = duration,
                    thumbnail = thumbnail,
                    isDownloadable = true
                ))
            }

            // Video tracks — height="XXX" … <BaseURL>…</BaseURL>
            val videoRe = Pattern.compile(
                """height="(\d+)".*?<BaseURL>(.*?)</BaseURL>""", Pattern.DOTALL)
            val videoM  = videoRe.matcher(body)
            while (videoM.find()) {
                val height = videoM.group(1)?.toIntOrNull() ?: 240
                var vidUrl = videoM.group(2) ?: continue
                if (!vidUrl.startsWith("http")) vidUrl = baseUrl + vidUrl
                vidUrl = vidUrl.replace("amp;", "")
                log(onLog, "  ✓ ${height}p: ${vidUrl.take(70)}")
                results.add(VideoInfo(
                    url       = vidUrl,
                    pageUrl   = pageUrl,
                    mimeType  = "video/mp4",
                    title     = title,
                    height    = height,
                    duration  = duration,
                    thumbnail = thumbnail,
                    isDownloadable = true
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseDash: ${e.message}")
        }
    }

    // ── Non-video post: GIF / HLS / image / imgur ─────────────────────────

    private fun parseNonVideo(
        postData: JSONObject, pageUrl: String,
        title: String, thumbnail: String,
        results: MutableList<VideoInfo>, onLog: (String) -> Unit
    ) {
        // media_metadata — gallery hoặc GIF/HLS
        if (postData.has("media_metadata")) {
            val meta = postData.getJSONObject("media_metadata")
            val keys = meta.keys()
            while (keys.hasNext()) {
                val item = meta.getJSONObject(keys.next())
                when {
                    // GIF
                    item.has("s") && item.getJSONObject("s").has("gif") -> {
                        val gifUrl = item.getJSONObject("s").getString("gif").replace("amp;", "")
                        log(onLog, "  ✓ GIF: ${gifUrl.take(70)}")
                        results.add(VideoInfo(
                            url = gifUrl, pageUrl = pageUrl,
                            mimeType = "image/gif", title = title,
                            thumbnail = thumbnail, isDownloadable = true
                        ))
                    }
                    // HLS video
                    item.has("hlsUrl") -> {
                        val hlsUrl = item.getString("hlsUrl").replace("amp;", "")
                        log(onLog, "  → HLS: ${hlsUrl.take(70)}")
                        val streams = core.parseHlsMaster(hlsUrl, pageUrl, emptyList(), onLog)
                        for (s in streams) {
                            results.add(VideoInfo(
                                url = s.toJson(), pageUrl = pageUrl,
                                mimeType = "application/x-mpegURL",
                                title = title, height = s.height,
                                duration = s.duration.toInt(),
                                thumbnail = thumbnail, hlsKey = s.keyHex,
                                isDownloadable = s.hasSegments()
                            ))
                        }
                    }
                    // Image
                    item.has("s") -> {
                        val imgUrl = item.getJSONObject("s").optString("u").replace("amp;", "")
                        if (imgUrl.isNotEmpty()) {
                            log(onLog, "  ✓ Image: ${imgUrl.take(70)}")
                            results.add(VideoInfo(
                                url = imgUrl, pageUrl = pageUrl,
                                mimeType = "image/jpeg", title = title,
                                thumbnail = thumbnail, isDownloadable = true
                            ))
                        }
                    }
                }
            }
            return
        }

        // Direct URL
        val directUrl = try { postData.getString("url") } catch (_: Exception) { "" }

        when {
            // i.redd.it image
            directUrl.startsWith("https://i.redd.it/") -> {
                val mime = if (directUrl.contains(".gif")) "image/gif" else "image/jpeg"
                log(onLog, "  ✓ i.redd.it: ${directUrl.take(70)}")
                results.add(VideoInfo(
                    url = directUrl, pageUrl = pageUrl,
                    mimeType = mime, title = title,
                    thumbnail = thumbnail, isDownloadable = true
                ))
            }

            // imgur .gifv → .mp4
            directUrl.startsWith("https://i.imgur.com/") && directUrl.endsWith(".gifv") -> {
                val mp4Url = directUrl.replace(".gifv", ".mp4")
                log(onLog, "  ✓ imgur MP4: ${mp4Url.take(70)}")
                results.add(VideoInfo(
                    url = mp4Url, pageUrl = pageUrl,
                    mimeType = "video/mp4", title = title,
                    height = 360, thumbnail = thumbnail,
                    isDownloadable = true
                ))
            }
        }

        // Fallback: preview.reddit_video_preview
        if (results.isEmpty() && postData.has("preview")) {
            val preview = postData.getJSONObject("preview")
            if (preview.has("reddit_video_preview")) {
                val fallback = preview.getJSONObject("reddit_video_preview")
                    .getString("fallback_url")
                log(onLog, "  ✓ preview fallback: ${fallback.take(70)}")
                results.add(VideoInfo(
                    url = fallback, pageUrl = pageUrl,
                    mimeType = "video/mp4", title = title,
                    height = 720, thumbnail = thumbnail,
                    isDownloadable = true
                ))
            }
        }
    }
}