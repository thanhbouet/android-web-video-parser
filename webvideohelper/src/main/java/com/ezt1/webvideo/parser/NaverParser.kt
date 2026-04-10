package com.ezt1.webvideo.parser

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

// ════════════════════════════════════════════════════════════════════════════
//  NaverParser
//  Reverse engineered từ NaverImpl.java + NaverShortsImpl.java (Z6.s, Z6.t)
//
//  Hỗ trợ 2 loại:
//  1. Naver TV  — tv.naver.com/v/...  → NaverImpl flow
//  2. Naver Clips (Shorts) — naver.me/... hoặc clip path → NaverShortsImpl flow
// ════════════════════════════════════════════════════════════════════════════

class NaverParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        private const val TAG = "NaverParser"

        // Naver TV API base — tương đương CoreLogUtils.L()
        private const val API_BASE = "https://apis.naver.com/now_web/oldpc_listview/v1/call/clip-info/videoSeq/"

        private val DOMAINS = listOf(
            "tv.naver.com",
            "naver.me",
            "clip.naver.com"
        )
    }

    override fun canHandle(url: String) = DOMAINS.any { url.contains(it) }

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        return withContext(Dispatchers.IO) {
            log(onLog, "[Naver] $url")

            // NaverShortsImpl path — clip.naver.com hoặc Naver Clips
            if (url.contains("clip.naver.com") || url.contains("naver.me")) {
                return@withContext parseShorts(url, html, onLog)
            }

            // NaverImpl path — tv.naver.com
            parseTv(url, html, onLog)
        }
    }

    // ── NaverImpl.h() — Naver TV ──────────────────────────────────────────

    private fun parseTv(url: String, html: String, onLog: (String) -> Unit): List<VideoInfo> {
        var videoId = ""
        var inKey   = ""

        try {
            if (url.contains("/v/")) {
                // TV path — parse __NEXT_DATA__ từ HTML
                val doc    = Jsoup.parse(html.ifEmpty {
                    core.getRequestPublic(url, url) ?: return emptyList()
                })
                val script = doc.selectFirst("script#__NEXT_DATA__")?.data()
                    ?: doc.select("script").firstOrNull { it.data().contains("__NEXT_DATA__") }?.data()
                    ?: return emptyList<VideoInfo>().also { log(onLog, "  [error] __NEXT_DATA__ not found") }

                // JSON: props.pageProps.vodInfo
                val json    = JSONObject(script)
                val vodInfo = json.getJSONObject("props")
                    .getJSONObject("pageProps")
                    .getJSONObject("vodInfo")

                // clip.videoId + play.inKey
                videoId = vodInfo.getJSONObject("clip").optString("videoId")
                inKey   = vodInfo.getJSONObject("play").optString("inKey")
                log(onLog, "  [tv] videoId=$videoId inKey=${inKey.take(10)}…")

            } else {
                // Direct JSON path — str3 là raw JSON
                val json = JSONObject(html)
                val result = json.getJSONObject("result")
                videoId = result.optString("masterVid")
                inKey   = result.optString("inkey")
                log(onLog, "  [direct] videoId=$videoId")
            }

            if (videoId.isEmpty()) {
                log(onLog, "  [error] no videoId")
                return emptyList()
            }

            // Build API URL — tương đương CoreLogUtils.L() + videoId + "?key=" + inKey
            val apiUrl = "$API_BASE$videoId?key=$inKey"
            log(onLog, "  [api] $apiUrl")

            // Fetch + parse
            val body = core.getRequestPublicWithHeaders(apiUrl, mapOf(
                "Referer"    to url,
                "User-Agent" to CoreURLParser.UA_MOBILE,
                "Accept"     to "application/json"
            )) ?: return emptyList<VideoInfo>().also { log(onLog, "  [error] API fetch failed") }

            return parseTvJson(JSONObject(body), url, onLog)

        } catch (e: Exception) {
            Log.e(TAG, "parseTv: ${e.message}")
            return emptyList()
        }
    }

    // ── NaverImpl.i() — parse video JSON ─────────────────────────────────

    private fun parseTvJson(json: JSONObject, pageUrl: String, onLog: (String) -> Unit): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            // meta.subject = title, meta.cover.source = thumbnail
            val meta      = json.optJSONObject("meta")
            val title     = meta?.optString("subject") ?: ""
            val thumbnail = meta?.optJSONObject("cover")?.optString("source") ?: ""

            log(onLog, "  [meta] title=${title.take(40)} | thumb=${if(thumbnail.isNotEmpty()) "✓" else "—"}")

            // videos.list[]
            val list = json.getJSONObject("videos").getJSONArray("list")
            val seenHeights = mutableSetOf<Int>()

            for (i in 0 until list.length()) {
                val item     = list.getJSONObject(i)
                val videoUrl = item.optString("source")
                val duration = (item.optDouble("duration") * 1000).toInt()

                // quality: encodingOption.name → VideoInfo.p() style parsing
                val height = if (item.has("encodingOption")) {
                    qualityNameToHeight(
                        item.getJSONObject("encodingOption").optString("name")
                    )
                } else {
                    core.heightFromUrl(videoUrl).takeIf { it > 0 } ?: 480
                }

                if (videoUrl.isNotEmpty() && height !in seenHeights) {
                    log(onLog, "  ✓ MP4 ${height}p: ${videoUrl.take(70)}")
                    results.add(VideoInfo(
                        url       = videoUrl,
                        pageUrl   = pageUrl,
                        mimeType  = "video/mp4",
                        title     = title,
                        height    = height,
                        duration  = duration,
                        thumbnail = thumbnail,
                        isDownloadable = true
                    ))
                    seenHeights.add(height)
                }
            }
            log(onLog, "  [done] ${results.size} stream(s)")
        } catch (e: Exception) {
            Log.e(TAG, "parseTvJson: ${e.message}")
        }
        return results
    }

    // ── NaverShortsImpl.h() — Naver Clips (DASH/MPD as JSON) ─────────────

    private fun parseShorts(url: String, html: String, onLog: (String) -> Unit): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            val rawHtml = html.ifEmpty {
                core.getRequestPublic(url, url) ?: return emptyList()
            }

            // Lấy __NEXT_DATA__ JSON
            val doc    = Jsoup.parse(rawHtml)
            val script = doc.selectFirst("script#__NEXT_DATA__")?.data()
                ?: return emptyList<VideoInfo>().also { log(onLog, "  [error] __NEXT_DATA__ not found") }

            val root = JSONObject(script)

            // card.content
            val content = root.getJSONObject("card").getJSONObject("content")
            val title   = content.optString("title")

            // vod.playback.MPD[0]
            val mpd     = content.getJSONObject("vod")
                .getJSONObject("playback")
                .getJSONArray("MPD")
                .getJSONObject(0)

            // Duration: @mediaPresentationDuration (ISO 8601)
            val durationMs = core.parseIsoDuration(
                mpd.optString("@mediaPresentationDuration")
            )

            // Period (array or object)
            val period = mpd.getJSONArray("Period").getJSONObject(0)

            // Thumbnail: SupplementalProperty → nvod:Summary → nvod:Cover → #text
            var thumbnail = ""
            try {
                thumbnail = period.getJSONArray("SupplementalProperty")
                    .getJSONObject(0)
                    .getJSONObject("nvod:Summary")
                    .getJSONObject("nvod:Cover")
                    .optString("#text")
            } catch (_: Exception) {}

            log(onLog, "  [shorts] title=${title.take(40)} | thumb=${if(thumbnail.isNotEmpty()) "✓" else "—"}")

            // AdaptationSet[0].Representation[]
            val adaptSets = period.getJSONArray("AdaptationSet")
            for (i in 0 until adaptSets.length()) {
                val adaptSet = adaptSets.getJSONObject(i)
                val reps     = adaptSet.optJSONArray("Representation") ?: continue

                for (j in 0 until reps.length()) {
                    val rep     = reps.getJSONObject(j)
                    val baseUrl = rep.getJSONArray("BaseURL").getString(0)
                    val width   = rep.optInt("@width", 0)

                    if (baseUrl.isNotEmpty()) {
                        log(onLog, "  ✓ ${width}p: ${baseUrl.take(70)}")
                        results.add(VideoInfo(
                            url       = baseUrl,
                            pageUrl   = url,
                            mimeType  = "video/mp4",
                            title     = title,
                            height    = width,
                            duration  = durationMs,
                            thumbnail = thumbnail,
                            isDownloadable = true
                        ))
                    }
                }
            }

            log(onLog, "  [done] ${results.size} stream(s)")
        } catch (e: Exception) {
            Log.e(TAG, "parseShorts: ${e.message}")
        }
        return results
    }

    // ── Helper — tương đương VideoInfo.p() trong app gốc ─────────────────
    // Convert quality name → height
    // "1080p" → 1080, "720p HD" → 720, "480p" → 480, etc.
    private fun qualityNameToHeight(name: String): Int {
        if (name.isBlank()) return 480
        val m = Regex("""(\d{3,4})""").find(name)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: when {
            name.contains("1080", ignoreCase = true) -> 1080
            name.contains("720",  ignoreCase = true) -> 720
            name.contains("480",  ignoreCase = true) -> 480
            name.contains("360",  ignoreCase = true) -> 360
            name.contains("270",  ignoreCase = true) -> 270
            name.contains("HD",   ignoreCase = true) -> 720
            name.contains("SD",   ignoreCase = true) -> 480
            else -> 480
        }
    }
}