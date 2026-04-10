package com.ezt1.webvideo.parser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// ════════════════════════════════════════════════════════════════════════════
//  DATA MODELS
// ════════════════════════════════════════════════════════════════════════════

data class VideoInfo(
    var id : Long = -1,
    var url: String? = "",
    var pageUrl: String = "",
    var mimeType: String = "",
    var title: String = "",
    var height: Int = 0,
    var duration: Int = 0,
    var thumbnail: String? = "",
    var fileSize: Long = 0L,
    var referer: String = "",
    var hlsKey: String = "",
    var isDownloadable: Boolean = true
) {
    fun effectiveReferer() = referer.ifEmpty { pageUrl }

    fun label() = when {
        height == 10 -> "Audio"
        height > 0 -> "${height}p"
        else -> "Unknown"
    }

    fun sizeStr(): String {
        if (fileSize <= 0) return ""
        val mb = fileSize / 1_048_576.0
        return "%.1f MB".format(mb)
    }

    fun isHls() = mimeType.contains("mpegurl", ignoreCase = true) || (url.toString()).startsWith("{")
}

data class M3u8Stream(
    var height: Int = 0,
    var duration: Double = 0.0,
    var sourceUrl: String = "",
    var prefix: String = "",
    val segments: MutableList<String> = mutableListOf(),
    var keyHex: String = "",
    var ivStr: String = "",
    var isEncrypted: Boolean = false
) {
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("ts", JSONArray(segments))
        if (keyHex.isNotEmpty()) obj.put("key", keyHex)
        if (ivStr.isNotEmpty()) obj.put("iv", ivStr)
        if (prefix.isNotEmpty()) obj.put("prefix", prefix)
        return obj.toString()
    }

    fun hasSegments() = segments.isNotEmpty()
}

// ════════════════════════════════════════════════════════════════════════════
//  CORE URL PARSER
// ════════════════════════════════════════════════════════════════════════════

class CoreURLParser(private val context: Context) {

    companion object {
        private const val TAG = "CoreURLParser"

        // Decoded từ CoreServiceUtils.i() / g()
        const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"

        const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Safari/537.36"

        // Decoded từ BaseParse.d()
        val QUALITY_MAP = mapOf(
            "HD" to 720, "HQ" to 540, "SD" to 480, "LQ" to 360, "LOW" to 240
        )
    }

    // Cookie string nhập tay từ user
    var cookieString: String = ""

    // WebView UA (được set từ ngoài sau khi WebView load xong)
    var webViewUserAgent: String = UA_MOBILE

    // OkHttpClient — tương đương OkhttpUtil.a()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(SimpleCookieJar())
            .build()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  ENTRY POINT — CommonWebImpl.h()
    // ════════════════════════════════════════════════════════════════════════

    suspend fun extractVideoUrls(
        pageUrl: String,
        injectedHtml: String? = null,   // HTML từ WebView (đã render JS)
        onLog: (String) -> Unit = {}
    ): List<VideoInfo> = withContext(Dispatchers.IO) {

        val results = mutableListOf<VideoInfo>()

        // BƯỚC 1: Lấy HTML
        // Ưu tiên HTML từ WebView (đã render JS), fallback fetch thường
        val (html, finalUrl) = if (!injectedHtml.isNullOrBlank()) {
            onLog("[html] using WebView rendered HTML")
            Pair(injectedHtml, pageUrl)
        } else {
            onLog("[fetch] $pageUrl")
            fetchHtml(pageUrl) ?: run {
                onLog("[error] failed to fetch page")
                return@withContext results
            }
        }

        val doc = Jsoup.parse(html)

        // BƯỚC 2: Metadata
        // ── Title ────────────────────────────────────────────────────────
        val title = getMetaProperty(doc, "og:title")
            .ifEmpty { getMetaName(doc, "twitter:title") }
            .ifEmpty { getMetaProperty(doc, "og:title") }   // đã có quote, nhưng retry
            .ifEmpty {
                // JSON-LD fallback
                doc.select("script[type='application/ld+json']")
                    .firstNotNullOfOrNull { el ->
                        try {
                            JSONObject(el.data()).optString("name").takeIf { it.isNotEmpty() }
                        } catch (_: Exception) {
                            null
                        }
                    } ?: ""
            }
            .ifEmpty { doc.title() }  // <title> tag

        // ── Thumbnail ─────────────────────────────────────────────────────
        val thumbnail = resolveThumb(doc, finalUrl)

        // ── Duration ─────────────────────────────────────────────────────
        // Thứ tự ưu tiên: og:duration → video:duration → JSON-LD duration → meta name
        val durationMs = run {
            val raw = getMetaProperty(doc, "og:duration")
                .ifEmpty { getMetaProperty(doc, "video:duration") }
                .ifEmpty { getMetaName(doc, "duration") }
                .ifEmpty {
                    // JSON-LD: duration dạng "PT2M9S" (ISO 8601)
                    doc.select("script[type='application/ld+json']")
                        .firstNotNullOfOrNull { el ->
                            try {
                                JSONObject(el.data()).optString("duration")
                                    .takeIf { it.isNotEmpty() }
                            } catch (_: Exception) {
                                null
                            }
                        } ?: ""
                }
            parseIsoDuration(raw)
        }
        onLog("[meta] title: ${title.take(60)}")
        onLog("[meta] thumb: ${thumbnail.take(60).ifEmpty { "—" }}")

        // BƯỚC 3: <source type="video/mp4">
        var candidates = parseSourceTags(html, finalUrl, title, durationMs, thumbnail)
        onLog("[step 3] source tags: ${candidates.size}")

        // BƯỚC 4: JS patterns
        if (candidates.isEmpty()) {
            candidates = parseJsPatterns(html, finalUrl, title, durationMs, thumbnail)
            onLog("[step 4] js patterns: ${candidates.size}")
        }

        // BƯỚC 5: <video src>
        if (candidates.isEmpty()) {
            doc.select("video[src]").forEach { el ->
                val src = el.attr("src")
                if (src.startsWith("http"))
                    candidates.add(
                        VideoInfo(
                            url = src, pageUrl = finalUrl, title = title,
                            thumbnail = thumbnail, height = 480, duration = durationMs
                        )
                    )
            }
            onLog("[step 5] video[src]: ${candidates.size}")
        }

        // BƯỚC 6: Verify — HEAD request
        onLog("[step 6] verifying ${candidates.size} candidate(s)...")
        val seenHeights = mutableListOf<Int>()

        for (vi in candidates) {
            try {
                onLog("  HEAD ${vi.url.toString().take(70)}…")
                val resp = headRequest(vi.url.toString(), finalUrl) ?: continue
                val ct = resp.header("Content-Type") ?: ""
                val finalVideoUrl = resp.request.url.toString()
                resp.close()

                when {
                    "video/mp4" in ct || "video/webm" in ct ||
                            "video/ogg" in ct || "video/" in ct -> {
                        vi.url = finalVideoUrl
                        vi.mimeType = ct.split(";")[0].trim()
                        vi.fileSize = headContentLength(vi.url.toString(), finalUrl)
                        onLog("  ✓ MP4 ${vi.label()} ${vi.sizeStr()}")
                        results.add(vi)
                        seenHeights.add(vi.height)
                    }

                    "mpegurl" in ct.lowercase() ||
                            "x-mpegurl" in ct.lowercase() ||
                            ".m3u8" in vi.url.toString().lowercase() -> {
                        onLog("  → HLS, parsing master playlist...")
                        val streams = parseHlsMaster(finalVideoUrl, finalUrl, seenHeights, onLog)
                        for (s in streams) {
                            results.add(
                                VideoInfo(
                                    url = s.toJson(),
                                    pageUrl = finalUrl,
                                    mimeType = "application/x-mpegURL",
                                    title = title,
                                    height = s.height,
                                    duration = s.duration.toInt(),
                                    thumbnail = thumbnail,
                                    hlsKey = s.keyHex,
                                    isDownloadable = s.hasSegments()
                                )
                            )
                            seenHeights.add(s.height)
                        }
                    }

                    else -> onLog("  ✗ content-type: $ct")
                }
            } catch (e: Exception) {
                onLog("  [error] ${e.message}")
            }
        }

        onLog("[done] ${results.size} stream(s) found")
        results
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PARSE <source type="video/mp4">  — BaseParse.c()
    // ════════════════════════════════════════════════════════════════════════

    private fun parseSourceTags(
        html: String, pageUrl: String, title: String,
        durationMs: Int, thumbnail: String
    ): MutableList<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        val doc = Jsoup.parse(html)
        val domain = extractDomain(pageUrl)

        doc.select("source[type=video/mp4]").forEach { el ->
            var url = el.attr("src").trim()
            if (url.isEmpty() || ".m3u8" in url) return@forEach

            // Quality: đọc từ attr → fallback parse từ URL
            val height = listOf("res", "title", "size", "label", "quality", "height")
                .firstNotNullOfOrNull { el.attr(it).takeIf { v -> v.isNotEmpty() } }
                ?.let { qualityToHeight(it) }
                ?.takeIf { it > 0 }
                ?: heightFromUrl(url)
                    .takeIf { it > 0 }
                ?: 480

            url = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$domain$url"
                url.startsWith("http") -> url
                else -> return@forEach
            }

            results.add(
                VideoInfo(
                    url = url, pageUrl = pageUrl, mimeType = "video/mp4",
                    title = title, height = height, duration = durationMs,
                    thumbnail = thumbnail, isDownloadable = true
                )
            )
        }
        return results
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PARSE JS PATTERNS  — BaseParse.g() + FunnyUtils.h()
    // ════════════════════════════════════════════════════════════════════════

    private fun parseJsPatterns(
        html: String, pageUrl: String, title: String,
        durationMs: Int, thumbnail: String
    ): MutableList<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        val seen = mutableSetOf<String>()
        val domain = extractDomain(pageUrl)
        val doc = Jsoup.parse(html)
        val scripts = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val full = html + "\n" + scripts

        fun add(raw: String, height: Int, isHls: Boolean, src: String) {
            var url = raw.trim().trim('"', '\'', '\\')
            if (url.length < 10) return
            url = when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$domain$url"
                url.startsWith("http") -> url
                else -> return
            }
            val key = url.substringBefore("?")
            if (!seen.add(key)) return
            Log.d(TAG, "[$src] ${height}p ${if (isHls) "HLS" else "MP4"} → ${url.take(80)}")
            results.add(
                VideoInfo(
                    url = url, pageUrl = pageUrl, mimeType = "video/mp4",
                    title = title, height = height, duration = durationMs,
                    thumbnail = thumbnail, isDownloadable = true
                )
            )
        }

        // Nhóm 1: JS player functions (app gốc)
        listOf(
            Triple("setVideoUrlHigh\\s*\\(\\s*[\"']?(.*?)[\"']?\\s*\\)", 360, false),
            Triple("setVideoUrlLow\\s*\\(\\s*[\"']?(.*?)[\"']?\\s*\\)", 250, false),
            Triple("setVideoHLS\\s*\\(\\s*[\"']?(.*?)[\"']?\\s*\\)", 720, true),
        ).forEach { (pat, h, hls) ->
            Pattern.compile(pat).matcher(full).apply {
                while (find()) add(group(1) ?: "", h, hls, "js-func")
            }
        }

        // Nhóm 2: JSON keys phổ biến
        val mp4Keys = listOf(
            "videoUrl", "video_url", "mp4", "mp4Url", "playUrl",
            "play_url", "streamUrl", "contentUrl", "high", "low", "hd", "sd", "normal"
        )
        val hlsKeys = listOf(
            "m3u8", "m3u8Url", "hlsUrl", "hls_url",
            "masterUrl", "manifestUrl", "playlist"
        )

        fun scanKeys(keys: List<String>, isHls: Boolean, defH: Int) = keys.forEach { k ->
            val pat = """["\']?${Pattern.quote(k)}["\']?\s*[:=]\s*["\']([^"'\{\}]+)["\']"""
            Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(full).apply {
                while (find()) {
                    val v = group(1)?.trim() ?: return@apply
                    if ("mp4" in v || "video" in v || "stream" in v ||
                        ("m3u8" in v) == isHls
                    )
                        add(v, defH, isHls, "json:$k")
                }
            }
        }
        scanKeys(mp4Keys, false, 480)
        scanKeys(hlsKeys, true, 720)

        // Nhóm 3: Raw URL scan
        Pattern.compile("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
            .matcher(full).apply { while (find()) add(group(), 480, false, "raw-mp4") }
        Pattern.compile("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
            .matcher(full).apply { while (find()) add(group(), 720, true, "raw-m3u8") }

        // Nhóm 4: JWPlayer
        Pattern.compile("""file\s*:\s*["']([^"']+)["']""")
            .matcher(full).apply {
                while (find()) {
                    val v = group(1) ?: continue
                    add(v, if ("m3u8" in v) 720 else 480, "m3u8" in v, "jwplayer")
                }
            }

        // Nhóm 5: data attributes
        doc.allElements.forEach { el ->
            el.attributes().forEach { attr ->
                val v = attr.value;
                val k = attr.key.lowercase()
                if (("video" in k || "src" in k || "file" in k || "url" in k) &&
                    v.startsWith("http") &&
                    ("mp4" in v.lowercase() || "m3u8" in v.lowercase())
                )
                    add(v, if ("m3u8" in v) 720 else 480, "m3u8" in v, "attr:${attr.key}")
            }
        }

        // Nhóm 6: JSON-LD
        doc.select("script[type=application/ld+json]").forEach { el ->
            try {
                fun walk(o: Any?) {
                    when (o) {
                        is JSONObject -> o.keys().forEach { k ->
                            val v = o.get(k)
                            if (v is String && v.startsWith("http") &&
                                ("mp4" in v || "video" in k.lowercase())
                            )
                                add(v, if ("m3u8" in v) 720 else 480, "m3u8" in v, "ld+json")
                            else walk(v)
                        }

                        is JSONArray -> (0 until o.length()).forEach { walk(o.get(it)) }
                    }
                }
                walk(JSONObject(el.data()))
            } catch (_: Exception) {
            }
        }

        return results
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HLS MASTER PARSER  — M3u8Util.g()
    // ════════════════════════════════════════════════════════════════════════

    fun parseHlsMaster(
        masterUrl: String, referer: String,
        existingHeights: List<Int> = emptyList(),
        onLog: (String) -> Unit = {}
    ): List<M3u8Stream> {
        val result = mutableListOf<M3u8Stream>()

        val resp = getRequest(masterUrl, referer) ?: return result
        val cl = resp.header("Content-Length")?.toLongOrNull() ?: 0L
        if (cl > 3_145_728L) {
            resp.close(); return result
        }  // > 3MB guard

        val body = resp.body?.string() ?: run { resp.close(); return result }
        val finalUrl = resp.request.url.toString()
        resp.close()

        val domain = extractDomain(finalUrl)
        val baseDir = baseDirOf(finalUrl)
        val lines = body.lines()

        if (lines.isEmpty() || !lines[0].trim().equals("#EXTM3U", ignoreCase = true)) {
            onLog("  [skip] not a valid M3U8"); return result
        }
        if ("#EXTINF:" in body && "#EXT-X-ENDLIST" !in body) {
            onLog("  [skip] live stream"); return result
        }

        var audioAdded = false
        var i = 1
        while (i < lines.size) {
            val line = lines[i].trim(); i++
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val height = extractResolution(line)
                    if (height in existingHeights) {
                        i++; continue
                    }
                    if (i < lines.size) {
                        val url = normalizeUrl(lines[i].trim(), domain, baseDir); i++
                        onLog("  [stream] ${height}p → ${url.take(60)}")
                        val s = fetchSegmentPlaylist(url, referer, height)
                        if (s?.hasSegments() == true) result.add(s)
                    }
                }

                line.startsWith("#EXT-X-MEDIA:") && "TYPE=AUDIO" in line && !audioAdded -> {
                    val m = Pattern.compile("""URI="(.*?)"""").matcher(line)
                    if (m.find()) {
                        val url = normalizeUrl(m.group(1) ?: "", domain, baseDir)
                        val s = fetchSegmentPlaylist(url, referer, 10)
                        if (s?.hasSegments() == true) {
                            result.add(s); audioAdded = true
                        }
                    }
                }

                line.startsWith("#EXTINF") -> {
                    // Đây là segment playlist trực tiếp
                    val s = parseSegmentText(body, finalUrl, referer)
                    if (s?.hasSegments() == true) result.add(s)
                    break
                }
            }
        }
        return result
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SEGMENT PLAYLIST PARSER  — M3u8Util.i() / j()
    // ════════════════════════════════════════════════════════════════════════

    private fun fetchSegmentPlaylist(url: String, referer: String, height: Int): M3u8Stream? {
        val resp = getRequest(url, referer) ?: return null
        val body = resp.body?.string() ?: run { resp.close(); return null }
        val finalUrl = resp.request.url.toString(); resp.close()
        return parseSegmentText(body, finalUrl, referer)?.also {
            it.sourceUrl = url; it.height = height
        }
    }

    private fun parseSegmentText(text: String, contextUrl: String, referer: String): M3u8Stream? {
        if ("#EXTINF:" in text && "#EXT-X-ENDLIST" !in text) return null
        val lines = text.lines()
        if (lines.isEmpty() || !lines[0].trim().equals("#EXTM3U", ignoreCase = true)) return null

        val domain = extractDomain(contextUrl)
        val baseDir = baseDirOf(contextUrl)
        val stream = M3u8Stream()
        var keyHex = "";
        var ivStr = "";
        var totalDur = 0.0
        var initSegmentUrl = ""   // fMP4 init segment
        var i = 1

        while (i < lines.size) {
            val line = lines[i].trim(); i++
            if (line.isEmpty()) continue
            when {
                // fMP4 init segment — PHẢI được đặt đầu tiên trong segments list
                // #EXT-X-MAP:URI="init-0.mp4" hoặc #EXT-X-MAP:URI="init.mp4",BYTERANGE="..."
                line.startsWith("#EXT-X-MAP:URI=") -> {
                    val mU = Pattern.compile("""URI="([^"]+)"""").matcher(line)
                    if (mU.find()) {
                        initSegmentUrl = normalizeUrl(mU.group(1) ?: "", domain, baseDir)
                    }
                }

                line.startsWith("#EXT-X-KEY") -> {
                    val mM = Pattern.compile("METHOD=(.*?)[,\\s]").matcher("$line,")
                    if (mM.find() && mM.group(1) == "AES-128") {
                        val mU = Pattern.compile("""URI="(.*?)"""").matcher(line)
                        if (mU.find())
                            keyHex = fetchAesKey(
                                normalizeUrl(mU.group(1) ?: "", domain, baseDir), referer
                            )
                        val mI = Pattern.compile("IV=(0x[0-9a-fA-F]+)").matcher(line)
                        ivStr = if (mI.find()) mI.group(1) ?: "" else ""
                        stream.isEncrypted = true
                    }
                    return stream // EXT-X-KEY → return, caller parses rest
                }

                line.startsWith("#EXTINF") -> {
                    try {
                        totalDur += line.substring(8, line.indexOf(',')).toDouble()
                    } catch (_: Exception) {
                    }
                    if (i < lines.size) {
                        val seg = lines[i].trim(); i++
                        if (seg.startsWith("#EXT-X-BYTERANGE:")) {
                            if (i < lines.size) {
                                val url = normalizeUrl(lines[i].trim(), domain, baseDir); i++
                                if (url.isNotEmpty()) stream.segments.add(url)
                            }
                            break
                        }
                        val url = normalizeUrl(seg, domain, baseDir)
                        if (url.isNotEmpty()) {
                            stream.segments.add(url)
                            if (stream.prefix.isEmpty()) stream.prefix = when {
                                seg.startsWith("/") -> domain
                                !seg.startsWith("http") -> baseDir
                                else -> ""
                            }
                        }
                    }
                }

                line == "#EXT-X-DISCONTINUITY" -> stream.isEncrypted = true
            }
        }
        if (stream.duration == 0.0 && totalDur > 0) stream.duration = totalDur * 1000
        if (stream.hasSegments()) {
            stream.keyHex = keyHex; stream.ivStr = ivStr
        }

        // fMP4: thêm init segment vào ĐẦU danh sách
        // Init segment chứa codec info — thiếu nó thì player không decode được
        if (initSegmentUrl.isNotEmpty() && stream.hasSegments()) {
            stream.segments.add(0, initSegmentUrl)
        }

        return if (stream.hasSegments()) stream else null
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UTILS
    // ════════════════════════════════════════════════════════════════════════

    fun extractDomain(url: String): String = try {
        val p = URI(url); "${p.scheme ?: "http"}://${p.host ?: ""}"
    } catch (_: Exception) {
        ""
    }

    fun normalizeUrl(url: String, domain: String, baseDir: String): String {
        if (url.isEmpty() || url.startsWith("#")) return ""
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$domain$url"
            else -> "$baseDir$url"
        }
    }

    private fun baseDirOf(url: String): String {
        val noQ = url.substringBefore("?")
        return noQ.substring(0, noQ.lastIndexOf('/') + 1)
    }

    private fun extractResolution(line: String): Int {
        Pattern.compile("""NAME="(\d+)""").matcher(line).let {
            if (it.find()) {
                val v = it.group(1)?.toIntOrNull() ?: 0; if (v >= 120) return v
            }
        }
        Pattern.compile("""RESOLUTION=(\d+)x(\d+)""").matcher(line).let {
            if (it.find()) return it.group(2)?.toIntOrNull() ?: 1080
        }
        return 1080
    }

    /**
     * Extract height từ URL nếu có pattern _720p, _480p, _1080p...
     * Ví dụ: "vi9598922249_480p.mp4" → 480
     */
    fun heightFromUrl(url: String): Int {
        // Pattern: _720p, _480p, _1080p, _360p, _240p trong tên file
        val m = Regex("""_(\d{3,4})p[.\-_?]""").find(url)
        if (m != null) return m.groupValues[1].toIntOrNull() ?: 0

        // Pattern: /720p/, /480p/ trong path
        val m2 = Regex("""/(\d{3,4})p/""").find(url)
        if (m2 != null) return m2.groupValues[1].toIntOrNull() ?: 0

        // Pattern: quality=720, q=480 trong query string
        val m3 = Regex("""[?&](?:quality|q|res)=(\d{3,4})""").find(url)
        if (m3 != null) return m3.groupValues[1].toIntOrNull() ?: 0

        return 0
    }

    fun qualityToHeight(s: String): Int {
        val up = s.trim().uppercase()
        QUALITY_MAP[up]?.let { return it }
        if (up == "4K") return 2160
        return (if (up.endsWith("P")) up.dropLast(1) else up).toIntOrNull() ?: 480
    }

    // ── Helper dùng chung: lấy <meta property="..." content="..."> ─────────
    // Phải dùng quotes hoặc iterate thủ công vì ":" trong property name
    // bị Jsoup CSS parser hiểu thành pseudo-class selector
    private fun getMetaProperty(doc: Document, prop: String): String {
        return doc.select("meta[property='$prop']").firstOrNull()
            ?.attr("content")?.trim()
            ?: doc.select("meta").firstOrNull { it.attr("property") == prop }
                ?.attr("content")?.trim()
            ?: ""
    }

    // Lấy <meta name="..." content="..."> — dùng cho twitter:title, description...
    private fun getMetaName(doc: Document, name: String): String {
        return doc.select("meta[name='$name']").firstOrNull()
            ?.attr("content")?.trim()
            ?: doc.select("meta").firstOrNull { it.attr("name") == name }
                ?.attr("content")?.trim()
            ?: ""
    }

    fun resolveThumb(doc: Document, pageUrl: String = ""): String {
        val domain = if (pageUrl.isNotEmpty()) extractDomain(pageUrl) else ""

        fun norm(url: String): String {
            if (url.isBlank()) return ""
            return when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "$domain$url"
                else -> ""
            }
        }

        fun check(vararg candidates: String): String {
            for (c in candidates) {
                val n = norm(c.trim())
                if (n.isNotEmpty()) return n
            }
            return ""
        }

        // 1. Open Graph — pổ biến nhất
        check(
            getMetaProperty(doc, "og:image"),
            getMetaProperty(doc, "og:image:url"),
            getMetaProperty(doc, "og:image:secure_url")
        ).let { if (it.isNotEmpty()) return it }

        // 2. Twitter card
        check(
            doc.select("meta[name=twitter:image]").firstOrNull()?.attr("content") ?: "",
            doc.select("meta[name=twitter:image:src]").firstOrNull()?.attr("content") ?: ""
        ).let { if (it.isNotEmpty()) return it }

        // 3. <video poster="...">
        check(
            doc.selectFirst("video")?.attr("abs:poster") ?: "",
            doc.selectFirst("video")?.attr("poster") ?: ""
        ).let { if (it.isNotEmpty()) return it }

        // 4. itemprop — JSON-LD style
        check(
            doc.selectFirst("[itemprop=thumbnailUrl]")?.attr("href") ?: "",
            doc.selectFirst("[itemprop=thumbnailUrl]")?.attr("content") ?: "",
            doc.selectFirst("[itemprop=image]")?.attr("href") ?: "",
            doc.selectFirst("[itemprop=image]")?.attr("content") ?: "",
            doc.selectFirst("[itemprop=image]")?.attr("src") ?: ""
        ).let { if (it.isNotEmpty()) return it }

        // 5. <link rel="image_src">
        check(
            doc.selectFirst("link[rel=image_src]")?.attr("href") ?: "",
            doc.selectFirst("link[rel=thumbnail]")?.attr("href") ?: ""
        ).let { if (it.isNotEmpty()) return it }

        // 6. JSON-LD structured data
        doc.select("script[type=application/ld+json]").forEach { el ->
            try {
                val json = JSONObject(el.data())
                listOf("thumbnailUrl", "thumbnail", "image").forEach { key ->
                    val v = json.optString(key)
                    val n = norm(v)
                    if (n.isNotEmpty()) return n
                }
            } catch (_: Exception) {
            }
        }

        // 7. Scan script tags inline
        val scriptText = doc.select("script:not([src])").joinToString("\n") { it.data() }
        val thumbPatterns = listOf(
            // JWPlayer: image: "https://..." hoặc "image":"https://..."
            """["']?image["']?\s*:\s*["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
            // JWPlayer setup object: {image:"...", file:"..."}
            """jwplayer\s*\([^)]*\)\s*\.setup\s*\([^{]*\{[^}]*["']?image["']?\s*:\s*["'](https?://[^"']+)["']""",
            // Generic: thumbnail/poster/preview/thumb = "url"
            """["']?(?:thumbnail|poster|preview|thumb|cover|image)["']?\s*[:=]\s*["'](https?://[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']""",
            // thumbnailUrl/posterUrl
            """["']?(?:thumbnailUrl|posterUrl|previewUrl|coverUrl|imageUrl)["']?\s*[:=]\s*["'](https?://[^"']+)["']""",
            // Bất kỳ URL ảnh nào trong script
            """(https?://[^\s"'<>]+\.(?:jpg|jpeg|png|webp))(?:\?[^\s"'<>]*)?"""
        )
        for (pat in thumbPatterns) {
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(scriptText)
            if (m.find()) {
                val n = norm(m.group(1) ?: "")
                if (n.isNotEmpty()) return n
            }
        }

        // 8. data-* attributes trên video container
        listOf(
            "data-poster", "data-thumb", "data-thumbnail",
            "data-preview", "data-cover", "data-image"
        ).forEach { attr ->
            val v = doc.selectFirst("[$attr]")?.attr(attr) ?: ""
            val n = norm(v)
            if (n.isNotEmpty()) return n
        }

        return ""
    }

    // FunnyUtils.g() — "120" → 120000ms
    private fun parseDuration(s: String) =
        try {
            s.toInt() * 1000
        } catch (_: Exception) {
            0
        }

    /**
     * Parse duration string → ms
     * Hỗ trợ:
     *   - Số nguyên: "129"       → 129000ms
     *   - ISO 8601:  "PT2M9S"    → 129000ms
     *   - ISO 8601:  "PT1H2M9S"  → 3729000ms
     */
    fun parseIsoDuration(s: String): Int {
        if (s.isBlank()) return 0
        // Thử parse số nguyên trước (og:duration thường là giây)
        s.toIntOrNull()?.let { return it * 1000 }

        // ISO 8601 duration: PT#H#M#S
        if (s.uppercase().startsWith("PT")) {
            return try {
                var total = 0
                val upper = s.uppercase().removePrefix("PT")
                val h = Regex("(\\d+)H").find(upper)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val m = Regex("(\\d+)M").find(upper)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val sec =
                    Regex("(\\d+\\.?\\d*)S").find(upper)?.groupValues?.get(1)?.toDoubleOrNull()
                        ?: 0.0
                total = (h * 3600 + m * 60 + sec.toInt()) * 1000
                total
            } catch (_: Exception) {
                0
            }
        }
        return 0
    }

    private fun fetchAesKey(keyUrl: String, referer: String): String = try {
        val req = Request.Builder().url(keyUrl)
            .header("Referer", referer)
            .header("User-Agent", webViewUserAgent)
            .header("Origin", extractDomain(referer))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful)
                resp.body?.bytes()?.joinToString("") { "%02x".format(it) } ?: ""
            else ""
        }
    } catch (e: Exception) {
        Log.e(TAG, "key: ${e.message}"); ""
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HTTP HELPERS
    // ════════════════════════════════════════════════════════════════════════

    private fun buildHeaders(referer: String) = Headers.Builder()
        .add("User-Agent", webViewUserAgent)
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Referer", referer)
        .apply { if (cookieString.isNotEmpty()) add("Cookie", cookieString) }
        .build()

    private fun fetchHtml(url: String): Pair<String, String>? = try {
        val req = Request.Builder().url(url).headers(buildHeaders(url)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            Pair(resp.body?.string() ?: "", resp.request.url.toString())
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchHtml: ${e.message}"); null
    }

    // Public version cho các site-specific parser dùng
    fun getRequestPublic(url: String, referer: String): String? = try {
        getRequest(url, referer)?.body?.string()
    } catch (_: Exception) {
        null
    }

    // Public version với custom headers — dùng cho site cần header đặc biệt
    fun getRequestPublicWithHeaders(url: String, headers: Map<String, String>): String? = try {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.newCall(builder.build()).execute()
        Log.d(TAG, "getRequestPublicWithHeaders: ${resp.code} ${url}")
        if (resp.isSuccessful) {
            val body = resp.body?.string()
            resp.close()
            body
        } else {
            Log.e(TAG, "getRequestPublicWithHeaders failed: HTTP ${resp.code} with msg : ${resp.body?.string()}")
            resp.close()
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "getRequestPublicWithHeaders exception: ${e.message}")
        null
    }

    private fun getRequest(url: String, referer: String): Response? = try {
        val req = Request.Builder().url(url).headers(buildHeaders(referer)).build()
        val resp = client.newCall(req).execute()
        if (resp.isSuccessful) resp else {
            resp.close(); null
        }
    } catch (e: Exception) {
        Log.e(TAG, "GET: ${e.message}"); null
    }

    private fun headRequest(url: String, referer: String): Response? = try {
        val req = Request.Builder().url(url).head().headers(buildHeaders(referer)).build()
        val resp = client.newCall(req).execute()
        if (resp.isSuccessful) resp else {
            resp.close(); null
        }
    } catch (e: Exception) {
        Log.e(TAG, "HEAD: ${e.message}"); null
    }

    private fun headContentLength(url: String, referer: String): Long = try {
        headRequest(url, referer)?.use { it.header("Content-Length")?.toLongOrNull() ?: 0L } ?: 0L
    } catch (_: Exception) {
        0L
    }

    // ════════════════════════════════════════════════════════════════════════
    //  COOKIE JAR  — tương đương X6.a
    // ════════════════════════════════════════════════════════════════════════

    inner class SimpleCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.getOrPut(url.host) { mutableListOf() }.apply {
                removeAll { c -> cookies.any { it.name == c.name } }
                addAll(cookies)
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val auto = store[url.host]?.toList() ?: emptyList()
            if (cookieString.isEmpty()) return auto
            val manual = cookieString.split(";").mapNotNull { pair ->
                val parts = pair.trim().split("=", limit = 2)
                if (parts.size == 2)
                    Cookie.Builder().name(parts[0].trim()).value(parts[1].trim())
                        .domain(url.host).build()
                else null
            }
            return auto + manual
        }
    }

    suspend fun verifyAndEnrichCandidates(
        pageUrl: String,
        candidates: List<VideoInfo>,
        titleHint: String = "",    // title từ JS channel
        thumbHint: String = "",    // thumbnail từ JS channel (ưu tiên cao nhất)
        onLog: (String) -> Unit = {}
    ): List<VideoInfo> = withContext(Dispatchers.IO) {

        // Nếu JS đã cung cấp đủ title + thumb → bỏ qua HTTP fetch
        val (html, _) = if (titleHint.isBlank() || thumbHint.isBlank())
            fetchHtml(pageUrl) ?: Pair("", pageUrl)
        else
            Pair("", pageUrl)

        val doc = if (html.isNotEmpty()) Jsoup.parse(html) else null

        // Title: JS hint > HTTP parse
        val title = titleHint.ifEmpty {
            doc?.let {
                getMetaProperty(it, "og:title")
                    .ifEmpty { getMetaName(it, "twitter:title") }
                    .ifEmpty {
                        it.select("script[type='application/ld+json']")
                            .firstNotNullOfOrNull { el ->
                                try {
                                    JSONObject(el.data()).optString("name")
                                        .takeIf { n -> n.isNotEmpty() }
                                } catch (_: Exception) {
                                    null
                                }
                            } ?: ""
                    }
                    .ifEmpty { it.title() }
            } ?: ""
        }

        // Thumbnail: JS hint > HTTP parse
        val thumbnail = thumbHint.ifEmpty {
            doc?.let { resolveThumb(it, pageUrl) } ?: ""
        }

        val durationMs = doc?.let {
            val raw = getMetaProperty(it, "og:duration")
                .ifEmpty { getMetaProperty(it, "video:duration") }
                .ifEmpty {
                    it.select("script[type='application/ld+json']")
                        .firstNotNullOfOrNull { el ->
                            try {
                                JSONObject(el.data()).optString("duration")
                                    .takeIf { d -> d.isNotEmpty() }
                            } catch (_: Exception) {
                                null
                            }
                        } ?: ""
                }
            parseIsoDuration(raw)
        } ?: 0

        onLog(
            "[verify] ${candidates.size} | title: ${
                title.take(30).ifEmpty { "—" }
            } | thumb: ${if (thumbnail.isNotEmpty()) "✓" else "—"}"
        )

        val results = mutableListOf<VideoInfo>()
        val seenHeights = mutableListOf<Int>()

        for (vi in candidates) {
            vi.pageUrl = pageUrl
            vi.title = title.ifEmpty { vi.title }
            vi.thumbnail = thumbnail.ifEmpty { vi.thumbnail }
            vi.duration = if (vi.duration == 0) durationMs else vi.duration
            // Nếu height vẫn là 0 hoặc 480 mặc định → thử parse từ URL
            if (vi.height == 0 || vi.height == 480) {
                val fromUrl = heightFromUrl(vi.url.toString())
                if (fromUrl > 0) vi.height = fromUrl
            }

            try {
                onLog("  HEAD ${vi.url.toString().take(70)}…")
                val resp = headRequest(vi.url.toString(), pageUrl) ?: continue
                val ct = resp.header("Content-Type") ?: ""
                val finalUrl = resp.request.url.toString()
                resp.close()

                when {
                    "video/mp4" in ct || "video/webm" in ct || "video/" in ct -> {
                        vi.url = finalUrl
                        vi.mimeType = ct.split(";")[0].trim()
                        vi.fileSize = headContentLength(vi.url.toString(), pageUrl)
                        onLog("  ✓ MP4 ${vi.label()} ${vi.sizeStr()}")
                        results.add(vi)
                        seenHeights.add(vi.height)
                    }

                    "mpegurl" in ct.lowercase() || ".m3u8" in vi.url.toString().lowercase() -> {
                        onLog("  → HLS, parsing…")
                        val streams = parseHlsMaster(finalUrl, pageUrl, seenHeights, onLog)
                        for (s in streams) {
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
                            seenHeights.add(s.height)
                        }
                    }
                    // Content-Type chưa rõ nhưng URL có dấu hiệu video → thêm trực tiếp
                    vi.url.toString().contains(".mp4") -> {
                        vi.url = finalUrl
                        vi.mimeType = "video/mp4"
                        onLog("  ✓ MP4 (by ext) ${vi.label()}")
                        results.add(vi)
                        seenHeights.add(vi.height)
                    }

                    vi.url.toString().contains(".m3u8") -> {
                        onLog("  → HLS (by ext), parsing…")
                        val streams = parseHlsMaster(finalUrl, pageUrl, seenHeights, onLog)
                        for (s in streams) {
                            results.add(
                                VideoInfo(
                                    url = s.toJson(), pageUrl = pageUrl,
                                    mimeType = "application/x-mpegURL",
                                    title = title, height = s.height,
                                    duration = s.duration.toInt(), thumbnail = thumbnail,
                                    hlsKey = s.keyHex, isDownloadable = s.hasSegments()
                                )
                            )
                            seenHeights.add(s.height)
                        }
                    }

                    else -> onLog("  ✗ $ct")
                }
            } catch (e: Exception) {
                onLog("  [error] ${e.message}")
            }
        }

        onLog("[done] ${results.size} verified stream(s)")
        results
    }

    fun postRequestWithHeaders(url: String, body: String, headers: Map<String, String>): String? = try {
        val requestBody = okhttp3.RequestBody.create(
            "application/x-www-form-urlencoded".toMediaTypeOrNull(), body
        )
        val builder = okhttp3.Request.Builder().url(url).post(requestBody)
        headers.forEach { (k, v) -> builder.header(k, v) }
        val resp = client.newCall(builder.build()).execute()
        Log.d(TAG, "postRequestWithHeaders: ${resp.code} ${url.take(60)}")
        if (resp.isSuccessful) {
            val respBody = resp.body?.string()
            resp.close()
            respBody
        } else {
            val errorBody = resp.body?.string() ?: ""
            Log.e(TAG, "postRequestWithHeaders failed: HTTP ${resp.code} | body: ${errorBody}")
            resp.close()
            null
        }
    } catch (e: Exception) {
        Log.e(TAG, "postRequestWithHeaders exception: ${e.message}")
        null
    }

}


