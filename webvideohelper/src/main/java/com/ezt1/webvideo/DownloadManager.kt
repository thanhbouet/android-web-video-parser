package com.ezt1.webvideo

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import com.ezt1.webvideo.parser.CoreURLParser
import com.ezt1.webvideo.parser.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val CHANNEL = "download_channel"
    private var notifId = 1000

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun start(context: Context, vi: VideoInfo) {
        createChannel(context)
        val id = notifId++
        CoroutineScope(Dispatchers.IO).launch {
            val safeTitle = vi.title
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .replace(Regex("\\s+"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .take(50)
                .ifEmpty { "video" }

            val fname = "${safeTitle}_${vi.label()}.mp4"
            notify(context, id, "Downloading…", fname, 0)

            val ok = when {
                vi.url?.startsWith("{") == true   -> downloadHls(context, vi, fname, id)
                vi.url?.contains(".m3u8") == true -> downloadHlsFromUrl(context, vi, fname, id)
                else                               -> downloadMp4(context, vi, fname, id)
            }

            notify(context, id, if (ok) "✓ Done" else "✗ Failed", fname, -1)
        }
    }

    private fun openOutputStream(context: Context, filename: String): Pair<OutputStream, Uri?>? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                val os = resolver.openOutputStream(uri) ?: return null
                Pair(os, uri)
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, filename)
                Pair(file.outputStream(), null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openOutputStream: ${e.message}")
            null
        }
    }

    private fun finalizeMediaStore(context: Context, uri: Uri?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    // ── MP4 direct download ───────────────────────────────────────────────

    private fun downloadMp4(context: Context, vi: VideoInfo, filename: String, notifId: Int): Boolean {
        val pair = openOutputStream(context, filename) ?: return false
        val (os, uri) = pair
        return try {
            val cookie      = CookieManager.getInstance().getCookie(vi.url) ?: ""
            val isTikTok    = vi.url!!.contains("tiktok.com") || vi.url!!.contains("tiktokv.com")
            val isPinterest = vi.pageUrl.contains("pinterest.com") || vi.pageUrl.contains("pin.it")

            val reqBuilder = Request.Builder()
                .url(vi.url!!)
                .header("Referer", when {
                    isTikTok    -> "https://www.tiktok.com/"
                    isPinterest -> "https://www.pinterest.com/"
                    else        -> vi.effectiveReferer()
                })
                .header("User-Agent", if (isTikTok) CoreURLParser.Companion.UA_DESKTOP else CoreURLParser.Companion.UA_MOBILE)

            if (cookie.isNotEmpty()) reqBuilder.header("Cookie", cookie)
            if (isTikTok) reqBuilder.header("Origin", "https://www.tiktok.com")

            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val total = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                var done = 0L
                os.use { out ->
                    resp.body?.byteStream()?.use { ins ->
                        val buf = ByteArray(32 * 1024)
                        var n: Int
                        while (ins.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0)
                                notify(context, notifId, "Downloading…", filename, (done * 100 / total).toInt())
                        }
                    }
                }
            }
            finalizeMediaStore(context, uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadMp4: ${e.message}")
            false
        }
    }

    // ── HLS từ plain m3u8 URL ─────────────────────────────────────────────

    private fun getReferer(vi: VideoInfo): String = when {
        vi.pageUrl.contains("pinterest.com") || vi.pageUrl.contains("pin.it") -> "https://www.pinterest.com/"
        vi.referer.isNotEmpty() -> vi.referer
        else -> vi.pageUrl
    }

    private fun downloadHlsFromUrl(context: Context, vi: VideoInfo, filename: String, notifId: Int): Boolean {
        return try {
            val m3u8Url = vi.url ?: return false
            val referer = getReferer(vi)
            val ua      = CoreURLParser.Companion.UA_MOBILE

            val manifest = client.newCall(
                Request.Builder().url(m3u8Url)
                    .header("Referer", referer)
                    .header("User-Agent", ua)
                    .build()
            ).execute().use { it.body?.string() } ?: return false

            // Byte-range playlist (Pinterest fMP4)
            if (manifest.contains("#EXT-X-BYTERANGE")) {
                return downloadByteRangeHls(context, vi, manifest, m3u8Url, filename, notifId)
            }

            // Master playlist — chọn stream bandwidth cao nhất
            if (manifest.contains("#EXT-X-STREAM-INF")) {
                val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                val bestStream = manifest.lines()
                    .zipWithNext()
                    .filter { (a, _) -> a.startsWith("#EXT-X-STREAM-INF") }
                    .maxByOrNull { (a, _) ->
                        Regex("BANDWIDTH=(\\d+)").find(a)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    ?.second?.trim() ?: return false
                val streamUrl = if (bestStream.startsWith("http")) bestStream else baseUrl + bestStream
                return downloadHlsFromUrl(context, vi.copy(url = streamUrl), filename, notifId)
            }

            // Normal media playlist
            val baseUrl  = m3u8Url.substringBeforeLast("/") + "/"
            val segments = manifest.lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { if (it.startsWith("http")) it else baseUrl + it }
            if (segments.isEmpty()) return false

            val pair = openOutputStream(context, filename) ?: return false
            val (os, uri) = pair
            val total = segments.size
            os.use { out ->
                segments.forEachIndexed { idx, segUrl ->
                    notify(context, notifId, "Downloading…", "$filename (${idx+1}/$total)", (idx+1)*100/total)
                    val bytes = client.newCall(
                        Request.Builder().url(segUrl)
                            .header("Referer", referer)
                            .header("User-Agent", ua)
                            .build()
                    ).execute().use { it.body?.bytes() } ?: return@forEachIndexed
                    out.write(bytes)
                }
            }
            finalizeMediaStore(context, uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadHlsFromUrl: ${e.message}")
            false
        }
    }

    // ── Byte-range HLS (Pinterest fMP4) ───────────────────────────────────

    private fun downloadByteRangeHls(
        context: Context, vi: VideoInfo, manifest: String,
        m3u8Url: String, filename: String, notifId: Int
    ): Boolean {
        val pair = openOutputStream(context, filename) ?: return false
        val (os, uri) = pair
        val referer = getReferer(vi)
        val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

        data class Segment(val fileUrl: String, val length: Long, val offset: Long)
        val segments = mutableListOf<Segment>()

        // Init segment từ EXT-X-MAP
        Regex("""#EXT-X-MAP:URI="([^"]+)",BYTERANGE="(\d+)@(\d+)"""").find(manifest)?.let {
            val initFile = it.groupValues[1]
            val initUrl  = if (initFile.startsWith("http")) initFile else baseUrl + initFile
            segments.add(Segment(initUrl, it.groupValues[2].toLong(), it.groupValues[3].toLong()))
        }

        // Media segments
        var currentFile = ""
        for (line in manifest.lines()) {
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val m = Regex("""(\d+)@(\d+)""").find(line) ?: continue
                    if (currentFile.isNotEmpty()) {
                        val fileUrl = if (currentFile.startsWith("http")) currentFile else baseUrl + currentFile
                        segments.add(Segment(fileUrl, m.groupValues[1].toLong(), m.groupValues[2].toLong()))
                    }
                }
                !line.startsWith("#") && line.isNotBlank() -> currentFile = line.trim()
            }
        }

        if (segments.isEmpty()) return false

        return try {
            val total = segments.size
            os.use { out ->
                segments.forEachIndexed { idx, seg ->
                    notify(context, notifId, "Downloading…", "$filename (${idx+1}/$total)", (idx+1)*100/total)
                    val bytes = client.newCall(
                        Request.Builder()
                            .url(seg.fileUrl)
                            .header("Range", "bytes=${seg.offset}-${seg.offset + seg.length - 1}")
                            .header("Referer", referer)
                            .header("User-Agent", CoreURLParser.Companion.UA_MOBILE)
                            .build()
                    ).execute().use { it.body?.bytes() } ?: return@forEachIndexed
                    out.write(bytes)
                }
            }
            finalizeMediaStore(context, uri)
            Log.d(TAG, "ByteRange HLS saved: $filename ($total segments)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadByteRangeHls: ${e.message}")
            false
        }
    }

    // ── HLS JSON object (segments pre-parsed, có thể có AES-128) ─────────

    private fun isFmp4Segment(url: String): Boolean {
        val path = url.substringBefore("?").lowercase()
        return path.endsWith(".m4s") || path.endsWith(".fmp4") || path.contains("/mp4/") ||
                path.contains("fmp4") || (!path.endsWith(".ts") && !path.contains(".ts?") &&
                (path.contains(".mp4") || path.contains("m4s")))
    }

    private fun downloadHls(context: Context, vi: VideoInfo, filename: String, notifId: Int): Boolean {
        val meta = try {
            JSONObject(vi.url)
        } catch (e: Exception) {
            Log.e(TAG, "downloadHls: invalid JSON"); return false
        }
        val segments = meta.getJSONArray("ts")
        val keyHex   = meta.optString("key", "")
        val ivStr    = meta.optString("iv", "")
        val prefix   = meta.optString("prefix", "")
        val total    = segments.length()
        if (total == 0) return false

        val isFmp4 = isFmp4Segment(segments.optString(0, ""))
        Log.d(TAG, "HLS JSON: ${if (isFmp4) "fMP4" else "TS"} | $total segments")

        val pair = openOutputStream(context, filename) ?: return false
        val (os, uri) = pair

        var decryptFn: ((ByteArray) -> ByteArray)? = null
        if (keyHex.isNotEmpty()) {
            try {
                val keyBytes = hexToBytes(keyHex)
                val ivBytes  = if (ivStr.isNotEmpty())
                    hexToBytes(ivStr.removePrefix("0x").removePrefix("0X").padStart(32, '0'))
                else ByteArray(16)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(ivBytes)
                )
                decryptFn = { data -> cipher.doFinal(data) }
            } catch (e: Exception) { Log.e(TAG, "AES: ${e.message}") }
        }

        return try {
            os.use { out ->
                for (idx in 0 until total) {
                    var url = segments.getString(idx)
                    if (!url.startsWith("http") && prefix.isNotEmpty()) {
                        url = if (url.startsWith("/"))
                            "${prefix.substringBefore("//")}//  ${URI(prefix).host}$url"
                        else "$prefix$url"
                    }
                    client.newCall(
                        Request.Builder().url(url)
                            .header("Referer", vi.effectiveReferer())
                            .header("User-Agent", CoreURLParser.Companion.UA_MOBILE)
                            .build()
                    ).execute().use { resp ->
                        if (resp.isSuccessful) {
                            var data = resp.body?.bytes() ?: byteArrayOf()
                            if (decryptFn != null) data = decryptFn.invoke(data)
                            out.write(data)
                        }
                    }
                    notify(context, notifId, "Downloading…", "$filename (${idx+1}/$total)", (idx+1)*100/total)
                }
            }
            finalizeMediaStore(context, uri)
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadHls: ${e.message}"); false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").removePrefix("0X")
            .padStart(if (hex.length % 2 == 0) hex.length else hex.length + 1, '0')
        return ByteArray(h.length / 2) { i -> h.substring(i*2, i*2+2).toInt(16).toByte() }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String, progress: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val b  = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text)
            .setOngoing(progress in 0..99).setOnlyAlertOnce(true)
        if (progress in 0..100) b.setProgress(100, progress, false)
        nm.notify(id, b.build())
    }
}