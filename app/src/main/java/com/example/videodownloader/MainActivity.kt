package com.example.videodownloader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.videodownloader.databinding.ActivityMainBinding
import com.ezt1.webvideo.parser.InstagramParser
import com.ezt1.webvideo.parser.TwitterParser
import com.ezt1.webvideo.parser.VideoInfo
import com.ezt1.webvideo.parser.tiktok.TikTokUtils
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    val vm: MainViewModel by viewModels()

    // Instagram headers — sniffed từ shouldInterceptRequest
    private var igAppId = ""
    private var igCsrfToken = ""
    private var igAsbdId = ""
    private var renderedHtml: String? = null
    var currentPageUrl: String = "file:///android_asset/homepage/home.html"

    // Decoded từ CoreServiceUtils.i() — UA gốc của app
    val mobileUA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

    // ── Các site có parser riêng — không cần JS inject ───────────────────
    private fun shouldSkipJsInject(url: String): Boolean {
        return listOf(
            "dailymotion.com",
            "dai.ly",
            "tiktok.com",
            "tiktokv.com",
            "twitter.com",
            "x.com",
            "pinterest.com",
            "pin.it",
        ).any {
            url.contains(
                it
            )
        }
    }

    private fun isTweet(url: String): Boolean {
        return (url.contains("twitter.com") || url.contains("x.com")) && url.contains("/status/")
    }

    private fun isFC2Page(url: String): Boolean {
        return url.contains("video.fc2.com")
    }

    private fun isFacebookPage(url: String): Boolean {
        return url.contains("facebook.com") || url.contains("fb.watch")
    }

    private fun isPinterestPage(url: String): Boolean {
        return url.contains("pinterest.com") || url.contains("pin.it")
    }

    private fun isInstagramPage(url: String): Boolean {
        return url.contains("instagram.com")
    }

    private fun isVimeoPage(url: String) =
        url.contains("vimeo.com")
    private fun selectInjectScript(url: String): String = when {
        isFacebookPage(url) -> FACEBOOK_INJECT_JS
//        isInstagramStoryPage(url) -> INSTAGRAM_STORY_INJECT_JS
//        isInstagramPage(url) -> INSTAGRAM_INJECT_JS
        isVimeoPage(url)          -> VIMEO_INJECT_JS
        isFC2Page(url)            -> FC2_INJECT_JS

        else -> INJECT_JS
    }

    private var lastInjectTime = 0L

    fun fetchTwitterViaWebView(webView: WebView, tweetId: String) {
        val js = """
        (function() {
            var csrf = (document.cookie.match(/ct0=([^;]+)/) || [])[1] || '';
            var variables = encodeURIComponent(JSON.stringify({
                focalTweetId: '$tweetId',
                referrer: 'home',
                with_rux_injections: false,
                rankingMode: 'Relevance',
                includePromotedContent: true,
                withCommunity: true,
                withQuickPromoteEligibilityTweetFields: true,
                withBirdwatchNotes: true,
                withVoice: true
            }));
            var features = encodeURIComponent('${TwitterParser.FEATURES}');
            var fieldToggles = encodeURIComponent('${TwitterParser.FIELD_TOGGLES}');
            var url = 'https://x.com/i/api/graphql/${TwitterParser.queryId}/TweetDetail?variables=' 
                      + variables + '&features=' + features + '&fieldToggles=' + fieldToggles;

            fetch(url, {
                headers: {
                    'authorization': '${TwitterParser.BEARER_TOKEN}',
                    'x-csrf-token': csrf,
                    'x-twitter-auth-type': 'OAuth2Session',
                    'x-twitter-active-user': 'yes',
                    'x-twitter-client-language': 'en',
                    'content-type': 'application/json'
                }
            })
            .then(function(r) { return r.json(); })
            .then(function(d) {
                var instructions = d && d.data && 
                    d.data.threaded_conversation_with_injections_v2 &&
                    d.data.threaded_conversation_with_injections_v2.instructions;
                if (!instructions) return;

                var entries = null;
                for (var i = 0; i < instructions.length; i++) {
                    if (instructions[i].entries) { entries = instructions[i].entries; break; }
                }
                if (!entries || entries.length === 0) return;

                var legacy = entries[0].content &&
                    entries[0].content.itemContent &&
                    entries[0].content.itemContent.tweet_results &&
                    entries[0].content.itemContent.tweet_results.result &&
                    entries[0].content.itemContent.tweet_results.result.legacy;
                if (!legacy) return;

                var media = legacy.extended_entities && 
                    legacy.extended_entities.media &&
                    legacy.extended_entities.media[0];
                if (!media) return;

                var variants = media.video_info && media.video_info.variants;
                if (!variants) return;

                // Chỉ lấy video/mp4, extract các field cần thiết
                var results = [];
                for (var j = 0; j < variants.length; j++) {
                    var v = variants[j];
                    if (v.content_type !== 'video/mp4') continue;
                    var hm = v.url && v.url.match(/\/(\d+)x(\d+)\//);
                    results.push({
                        url:     v.url,
                        bitrate: v.bitrate || 0,
                        height:  hm ? parseInt(hm[2]) : 0
                    });
                }

                var payload = {
                    results: results,
                    title:   (legacy.full_text || '').slice(0, 200),
                    thumb:   media.media_url_https || ''
                };
                var encoded = btoa(unescape(encodeURIComponent(JSON.stringify(payload))));
                console.log('video://web?method=twitterVideo#' + encoded);
            })
            .catch(function(e) {
                console.log('video://web?method=twitterVideo#error:' + e.message);
            });
        })();
    """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private var lastSeenUrl = ""
    private val INSTAGRAM_STORY_INJECT_JS = """
    (function() {
        window.__igStorySrcs = window.__igStorySrcs || {};

        function getUsername() {
            var m = window.location.pathname.match(/\/stories\/([^\/]+)\//);
            return m ? m[1] : '';
        }

        function scan() {
            var videos = document.querySelectorAll('video');
            videos.forEach(function(v) {
                var src = v.src || v.currentSrc || '';
                if (!src || !src.startsWith('http')) return;
                if (window.__igStorySrcs[src]) return;
                window.__igStorySrcs[src] = true;

                var title = '';
                var p = v;
                for (var i = 0; i < 15; i++) {
                    p = p.parentElement;
                    if (!p) break;
                    var span = p.querySelector('span.xuxw1ft[dir="auto"]');
                    if (span && span.textContent.trim().length > 3) {
                        title = span.textContent.trim();
                        break;
                    }
                }
                if (!title) {
                    var username = getUsername();
                    if (username) title = username + ' - Story';
                }

                console.log('video://web?method=parseVideo#' + JSON.stringify({
                    urls:  [{ url: src, height: 720, isHls: false }],
                    title: title || 'Instagram Story',
                    thumb: ''
                }));
            });
        }

        setInterval(scan, 500);
        scan();
    })();
""".trimIndent()

    private val FC2_INJECT_JS = """
    (function() {
        if (window.__fc2Hooked) return;
        window.__fc2Hooked = true;

        var origOpen    = XMLHttpRequest.prototype.open;
        var origSetHeader = XMLHttpRequest.prototype.setRequestHeader;
        var origSend    = XMLHttpRequest.prototype.send;

        XMLHttpRequest.prototype.open = function(method, url) {
            this._url = url;
            this._headers = {};
            return origOpen.apply(this, arguments);
        };

        XMLHttpRequest.prototype.setRequestHeader = function(key, value) {
            this._headers[key] = value;
            return origSetHeader.apply(this, arguments);
        };

        XMLHttpRequest.prototype.send = function() {
            var self  = this;
            var url   = this._url || '';
            var token = this._headers['X-FC2-Video-Access-Token'] || '';

            if (url.includes('videoplaylist') && token) {
                this.addEventListener('load', function() {
                    try {
                        var json    = JSON.parse(self.responseText);
                        var baseUrl = 'https://video.fc2.com';
                        var nqPath  = json && json.playlist && json.playlist.nq;
                        if (!nqPath) return;

                        // Title + thumbnail từ DOM
                        var title = document.querySelector('h2.videoCnt_title')
                            ?.textContent?.trim() || '';
                        var thumb = document.querySelector('video.main-video')
                            ?.getAttribute('poster') || '';

                        // Fetch nq để follow redirect → lấy URL thật
                        fetch(baseUrl + nqPath, {
                            method: 'GET',
                            headers: {
                                'Range': 'bytes=0-0',
                                'X-FC2-Video-Access-Token': token
                            }
                        }).then(function(r) {
                            var finalUrl = r.url;
                            if (!finalUrl || !finalUrl.startsWith('http')) return;

                            var results = [{ url: finalUrl, height: 480, isHls: false }];

                            // HQ và LQ nếu có
                            if (json.playlist.hq) {
                                results.push({ url: baseUrl + json.playlist.hq, height: 720, isHls: false });
                            }
                            if (json.playlist.lq) {
                                results.push({ url: baseUrl + json.playlist.lq, height: 360, isHls: false });
                            }

                            console.log('video://web?method=parseVideo#' + JSON.stringify({
                                urls:  results,
                                title: title,
                                thumb: thumb
                            }));
                        }).catch(function(e) {});
                    } catch(e) {}
                });
            }
            return origSend.apply(this, arguments);
        };
    })();
""".trimIndent()

    private val INJECT_JS = """
        (function() {
            try {
                var urls = [];
                var seen = {};

                function add(url, height, isHls) {
                    if (!url || url.length < 10) return;
                    if (url.startsWith('//')) url = 'https:' + url;
                    if (!url.startsWith('http')) return;
                    var key = url.split('?')[0];
                    if (seen[key]) return;
                    seen[key] = true;
                    // Parse quality từ URL nếu chưa có
                    if (!height || height === 480) {
                        var qm = url.match(/_(\d{3,4})p[.\-_?]/);
                        if (!qm) qm = url.match(/\/(\d{3,4})p\//);
                        if (!qm) qm = url.match(/[?&](?:quality|q|res)=(\d{3,4})/);
                        if (qm) height = parseInt(qm[1]);
                    }
                    urls.push({ url: url, height: height || 0, isHls: !!isHls });
                }

                // 1. <source type="video/mp4">
                document.querySelectorAll('source[type="video/mp4"]').forEach(function(el) {
                    var src = el.src || el.getAttribute('src') || '';
                    if (!src || src.indexOf('.m3u8') >= 0) return;
                    var h = 0;
                    ['res','quality','label','size','title','height'].forEach(function(a) {
                        if (!h) { var v = el.getAttribute(a); if (v) h = parseInt(v) || 0; }
                    });
                    add(src, h, false);
                });

                // 1b. <video src="..."> trực tiếp (JWPlayer inject src vào video tag)
                document.querySelectorAll('video[src]').forEach(function(el) {
                    var src = el.src || el.getAttribute('src') || '';
                    if (!src || src.indexOf('.m3u8') >= 0) return;
                    if (src.indexOf('.mp4') >= 0 || src.indexOf('video') >= 0) {
                        add(src, 0, false);  // height sẽ được parse từ URL trong add()
                    }
                });

                // 2. <source> hoặc <video> bất kỳ
                document.querySelectorAll('source, video').forEach(function(el) {
                    var src = el.src || el.getAttribute('src') || '';
                    if (src && src.indexOf('.mp4') >= 0) add(src, 480, false);
                    if (src && src.indexOf('.m3u8') >= 0) add(src, 720, true);
                });

                // 3. data attributes
                document.querySelectorAll('[data-src],[data-url],[data-video],[data-file]').forEach(function(el) {
                    ['data-src','data-url','data-video','data-file'].forEach(function(a) {
                        var v = el.getAttribute(a);
                        if (!v) return;
                        if (v.indexOf('.mp4') >= 0) add(v, 480, false);
                        if (v.indexOf('.m3u8') >= 0) add(v, 720, true);
                    });
                });

                // 4. Scan tất cả script inline
                var scripts = '';
                document.querySelectorAll('script:not([src])').forEach(function(s) {
                    scripts += s.textContent + '\n';
                });

                // 4a. Các function của app gốc
                [
                    { re: /setVideoUrlHigh\s*\(\s*["']?(.*?)["']?\s*\)/g,  h: 360, hls: false },
                    { re: /setVideoUrlLow\s*\(\s*["']?(.*?)["']?\s*\)/g,   h: 250, hls: false },
                    { re: /setVideoHLS\s*\(\s*["']?(.*?)["']?\s*\)/g,      h: 720, hls: true  },
                ].forEach(function(p) {
                    var m;
                    while ((m = p.re.exec(scripts)) !== null) add(m[1], p.h, p.hls);
                });

                // 4b. JSON keys phổ biến
                var mp4Keys = ['videoUrl','video_url','mp4','mp4Url','playUrl','play_url',
                               'high','low','hd','sd','normal','streamUrl','contentUrl'];
                var hlsKeys = ['m3u8','m3u8Url','hlsUrl','hls_url','masterUrl','manifestUrl','playlist'];

                function scanKeys(keys, isHls, defH) {
                    keys.forEach(function(k) {
                        var re = new RegExp('["\\\']?' + k + '["\\\']?\\s*[:=]\\s*["\\\']([^"\'{}]+)["\\\']', 'gi');
                        var m;
                        while ((m = re.exec(scripts)) !== null) {
                            var v = m[1];
                            if (isHls ? v.indexOf('m3u8') >= 0 : (v.indexOf('mp4') >= 0 || v.indexOf('video') >= 0))
                                add(v, defH, isHls);
                        }
                    });
                }
                scanKeys(mp4Keys, false, 480);
                scanKeys(hlsKeys, true,  720);

                // 4c. Raw URL scan trong scripts
                var mp4Re  = /https?:\/\/[^\s"'<>\\]+\.mp4[^\s"'<>\\]*/g;
                var hlsRe  = /https?:\/\/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*/g;
                var m2;
                while ((m2 = mp4Re.exec(scripts)) !== null) add(m2[0], 480, false);
                while ((m2 = hlsRe.exec(scripts)) !== null) add(m2[0], 720, true);

                // 4d. JWPlayer
                var jwRe = /file\s*:\s*["']([^"']+)["']/g;
                while ((m2 = jwRe.exec(scripts)) !== null) {
                    var v = m2[1];
                    add(v, v.indexOf('m3u8') >= 0 ? 720 : 480, v.indexOf('m3u8') >= 0);
                }

                // 5. window.__INITIAL_STATE__ hoặc các global objects
                var globals = ['__INITIAL_STATE__','__NUXT__','__NEXT_DATA__','pageData','videoData','playerConfig'];
                globals.forEach(function(g) {
                    try {
                        var obj = window[g];
                        if (!obj) return;
                        var str = JSON.stringify(obj);
                        var m3;
                        while ((m3 = mp4Re.exec(str)) !== null) add(m3[0], 480, false);
                        while ((m3 = hlsRe.exec(str)) !== null) add(m3[0], 720, true);
                    } catch(e) {}
                });

                // ── Lấy title ────────────────────────────────────────────
                var title = '';
                try {
                    var ogTitle = document.querySelector('meta[property="og:title"]');
                    if (ogTitle) title = ogTitle.getAttribute('content') || '';
                    if (!title) title = document.title || '';
                } catch(e) {}

                // ── Tách riêng: gửi thumbnail ────────────────────────────
                // JWPlayer set image qua JS config, không có trong HTML static
                var thumb = '';

                // 1. JWPlayer: jwplayer().getPlaylist()[0].image
                try {
                    var jw = window.jwplayer && window.jwplayer();
                    if (jw) {
                        var pl = jw.getPlaylist && jw.getPlaylist();
                        if (pl && pl[0] && pl[0].image) thumb = pl[0].image;
                        if (!thumb) { var c = jw.getConfig && jw.getConfig(); if (c && c.image) thumb = c.image; }
                    }
                } catch(e) {}

                // 2. og:image meta (đã render)
                if (!thumb) {
                    var og = document.querySelector('meta[property="og:image"]');
                    if (og) thumb = og.getAttribute('content') || '';
                }

                // 3. <video poster>
                if (!thumb) {
                    var vid = document.querySelector('video[poster]');
                    if (vid) thumb = vid.getAttribute('poster') || '';
                }

                // 4. data-* attributes
                if (!thumb) {
                    var el = document.querySelector('[data-poster],[data-thumb],[data-thumbnail],[data-image]');
                    if (el) thumb = el.getAttribute('data-poster') || el.getAttribute('data-thumb')
                        || el.getAttribute('data-thumbnail') || el.getAttribute('data-image') || '';
                }

                // 5. Scan scripts tìm "image":"https://..."
                if (!thumb) {
                    var imgRe = /["']?image["']?\s*:\s*["'](https?:\/\/[^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']/i;
                    var m4 = imgRe.exec(scripts);
                    if (m4) thumb = m4[1];
                }

                // 6. JSON-LD thumbnailUrl
                if (!thumb) {
                    document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s) {
                        if (thumb) return;
                        try {
                            var d = JSON.parse(s.textContent);
                            thumb = d.thumbnailUrl || (d.thumbnail && d.thumbnail.contentUrl) || '';
                        } catch(e) {}
                    });
                }

                if (thumb && thumb.startsWith('//')) thumb = 'https:' + thumb;

                // Gửi tất cả: video URLs + title + thumbnail trong 1 message
                var payload = {
                    urls: urls,
                    title: title,
                    thumb: thumb
                };
                if (urls.length > 0 || thumb) {
                    console.log('video://web?method=parseVideo#' + JSON.stringify(payload));
                }
            } catch(e) {
                console.log('video://web?method=parseVideo#[]');
            }
        })();
    """.trimIndent()

    private val FACEBOOK_INJECT_JS = """
    (function() {
        if (window.__fbHooked) return;
        window.__fbHooked = true;
        window.__fbLastVideoUrl = '';

        function decode(str) {
            return (str || '').replace(/&amp;/g, '&')
                              .replace(/&lt;/g, '<')
                              .replace(/&gt;/g, '>')
                              .replace(/&quot;/g, '"')
                              .replace(/&#39;/g, "'");
        }

        function getMeta(el) {
            var title = '';
            var thumb = '';
            var p = el;
            for (var i = 0; i < 20; i++) {
                p = p.parentElement;
                if (!p) break;
                var dirs = p.querySelectorAll('[dir="auto"]');
                dirs.forEach(function(d) {
                    if (!title && d.textContent.trim().length > 5)
                        title = d.textContent.trim().slice(0, 150);
                });
                if (!thumb) {
                    var imgs = p.querySelectorAll('img[src*="fbcdn"][src*="t15"]');
                    if (imgs.length > 0) thumb = imgs[0].src;
                }
                if (title && thumb) break;
            }
            return { title: title, thumb: thumb };
        }

        function extractQualities(el) {
            var results = [];
            var seen = {};

            // Progressive URL (audio + video trong 1 file)
            var progressiveUrl = decode(el.getAttribute('data-video-url') || '');
            if (progressiveUrl && progressiveUrl.startsWith('http')) {
                var progressiveHeight = 360;
                try {
                    var extra = JSON.parse(el.getAttribute('data-extra') || '{}');
                    var reps = ((extra.dash_prefetch_representations || {}).representations || []);
                    var videoReps = reps.filter(function(r) { return r.mime_type === 'video/mp4'; });
                    if (videoReps.length > 0) progressiveHeight = videoReps[0].height || 360;
                } catch(e) {}
                var key = progressiveUrl.split('?')[0];
                if (!seen[key]) {
                    seen[key] = true;
                    results.push({ url: progressiveUrl, height: progressiveHeight, isProgressive: true });
                }
            }

            // DASH qualities từ data-extra (video only)
            try {
                var extra2 = JSON.parse(el.getAttribute('data-extra') || '{}');
                var reps2 = ((extra2.dash_prefetch_representations || {}).representations || []);
                reps2.forEach(function(r) {
                    if (r.mime_type !== 'video/mp4') return;
                    var url = decode(r.base_url || '');
                    if (!url.startsWith('http')) return;
                    var key2 = url.split('?')[0];
                    if (seen[key2]) return;
                    seen[key2] = true;
                    results.push({ url: url, height: r.height || 0, isProgressive: false });
                });
            } catch(e) {}

            return results;
        }

        function onActiveVideo(el) {
            var videoUrl = el.getAttribute('data-video-url') || '';
            if (!videoUrl || videoUrl === window.__fbLastVideoUrl) return;
            window.__fbLastVideoUrl = videoUrl;

            var qualities = extractQualities(el);
            if (qualities.length === 0) return;

            var meta = getMeta(el);
            qualities.forEach(function(q) {
                console.log('video://web?method=fbVideo#' + JSON.stringify({
                    url:           q.url,
                    height:        q.height,
                    isProgressive: q.isProgressive,
                    duration:      0,
                    title:         meta.title,
                    thumb:         meta.thumb
                }));
            });
        }

        function watchElement(el) {
            if (el.__fbWatched) return;
            el.__fbWatched = true;

            var io = new IntersectionObserver(function(entries) {
                entries.forEach(function(entry) {
                    if (entry.isIntersecting && entry.intersectionRatio > 0.5) {
                        onActiveVideo(el);
                    }
                });
            }, { threshold: 0.5 });

            io.observe(el);
        }

        function scanAll() {
            document.querySelectorAll('[data-video-url]').forEach(function(el) {
                watchElement(el);
            });
        }

        // Watch DOM thay đổi để bắt elements mới khi lướt
        new MutationObserver(function() { scanAll(); })
            .observe(document.body, { childList: true, subtree: true });

        // Scan ngay lần đầu
        scanAll();
    })();
""".trimIndent()


    private val INSTAGRAM_INJECT_JS = """
    (function() {
        if (window.__igHooked) return;
        window.__igHooked = true;
        window.__igLastUrl = '';

        function scan() {
            var video = document.querySelector('video');
            if (!video || !video.src || !video.src.startsWith('http')) return;
            if (video.src === window.__igLastUrl) return;
            window.__igLastUrl = video.src;

            var title = (document.querySelector('meta[property="og:title"]') || {}).content
                        || document.title || '';
            var thumb = (document.querySelector('meta[property="og:image"]') || {}).content || '';

            console.log('video://web?method=parseVideo#' + JSON.stringify({
                urls:  [{ url: video.src, height: 720, isHls: false }],
                title: title,
                thumb: thumb
            }));
        }

        scan();
        new MutationObserver(function() { scan(); })
            .observe(document.body, { childList: true, subtree: true });
    })();
""".trimIndent()

    private val VIMEO_INJECT_JS = """
    (function() {
        if (window.__vimeoHooked) return;
        window.__vimeoHooked = true;

        var config = null;
        document.querySelectorAll('script').forEach(function(s) {
            if (s.textContent.includes('window.playerConfig') && s.textContent.includes('thumbnail_url')) {
                var m = s.textContent.match(/window\.playerConfig\s*=\s*(\{.*\})/s);
                if (m) { try { config = JSON.parse(m[1]); } catch(e) {} }
            }
        });

        if (!config) return;

        var video    = config.video || {};
        var title    = video.title || '';
        var thumb    = video.thumbnail_url || '';
        var duration = (video.duration || 0) * 1000;
        var results  = [];

        // Progressive MP4
        var progressive = (config.request || {}).files?.progressive || [];
        progressive.forEach(function(v) {
            if (!v.url) return;
            var h = parseInt((v.quality || '0').replace('p', '')) || 0;
            results.push({ url: v.url, height: h, isHls: false });
        });

        // HLS fallback
        if (results.length === 0) {
            var cdns = (config.request || {}).files?.hls?.cdns || {};
            var cdn  = cdns.akfire_interconnect_quic
                    || cdns.fastly_skyfire
                    || cdns[Object.keys(cdns)[0]];
            if (cdn && cdn.url) {
                results.push({ url: cdn.url, height: 0, isHls: true });
            }
        }

        if (results.length === 0) return;

        console.log('video://web?method=parseVideo#' + JSON.stringify({
            urls:  results,
            title: title,
            thumb: thumb
        }));
    })();
""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        tiktok = TikTokUtils()

        tiktok?.completeVideos?.observe(this) {
            vm.updateFromSingle(it)
        }

        vm.setWebViewUserAgent(mobileUA)
        setupViewPager()
        setupButtons()
        observeViewModel()
        handleShareIntent(intent)

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { url ->
                binding.etUrl.setText(url)
                loadUrl(url)
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  VIEWPAGER + TABS
    // ════════════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.isUserInputEnabled = false
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = arrayOf("BROWSER", "RESULTS", "LOG")[pos]
        }.attach()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  WEBVIEW SETUP
    // ════════════════════════════════════════════════════════════════════════

    // ── Xử lý khi URL thay đổi — dùng cho cả SPA lẫn normal navigation ──
    // Tương đương ParseWebChromeClient.onProgressChanged() URL change logic
    private fun handleUrlChange(webView: WebView, url: String) {
        if (url.isEmpty() || url == lastSeenUrl || url == "about:blank") return
        lastSeenUrl = url
        currentPageUrl = url
        runOnUiThread {
            binding.etUrl.setText(url)
            binding.tvWebStatus.text = "↻ ${url.take(55)}"
            binding.btnExtract.isEnabled = false
        }
        if (url.contains("vimeo.com/")
            && !url.contains("player.vimeo.com")
            && !url.contains("/channels/")
            && !url.contains("/groups/")) {

            val videoId = Regex("vimeo\\.com/(\\d+)").find(url)?.groupValues?.get(1)
            if (!videoId.isNullOrEmpty()) {
                val playerUrl = "https://player.vimeo.com/video/$videoId"
                appendLog("[vimeo] redirect → $playerUrl")
                val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment
                frag?.getWebView()?.postDelayed({
                    frag.getWebView()?.loadUrl(playerUrl)
                }, 1500)
                return
            }
        }
        appendLog("[url-change] $url")
        vm.resetForNewUrl()
        // Inject JS ngay nếu cần
        if (!shouldSkipJsInject(url)) {

            val now = System.currentTimeMillis()
            if (now - lastInjectTime > 300) {
                lastInjectTime = now
                val script = selectInjectScript(url)
                webView.evaluateJavascript(script, null)

            }
        }
        if (isTweet(url)) {
            val tweetId = Regex("/status/(\\d+)").find(url)?.groupValues?.get(1) ?: ""
            if (tweetId.isNotEmpty()) {
                val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment
                frag?.getWebView()?.postDelayed({
                    fetchTwitterViaWebView(frag.getWebView()!!, tweetId)
                }, 1500)
            }
            return
        }
        // Trigger router sau 800ms để DOM kịp render
        webView.postDelayed({
            runOnUiThread { binding.btnExtract.isEnabled = true }
            if (!isTiktok(url) && !isFacebookPage(url) && !isPinterestPage(url)) {
                if (isInstagramPage(url)) {
                    tryInstagramExtract(webView, url)
                } else {
                    vm.extractFromUrl(url)
                }

            }

        }, 800)
    }

    private fun fetchInstagramGraphQL(webView: WebView, shortcode: String) {
        val js = """
        (function() {
            fetch('https://www.instagram.com/graphql/query', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'X-CSRFToken': (document.cookie.match(/csrftoken=([^;]+)/) || [])[1] || '',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: 'variables=' + encodeURIComponent(JSON.stringify({shortcode:'$shortcode'}))
                      + '&doc_id=9510064595728286'
            })
            .then(function(r) { return r.json(); })
            .then(function(d) {
                var media = d.data && d.data.xdt_shortcode_media;
                if (!media) return;
                
                // Chỉ lấy những field cần thiết — tránh JSON quá lớn
                var result = {
                    videoUrl: media.video_url || '',
                    thumbUrl: media.display_url || media.thumbnail_src || '',
                    title: '',
                    isVideo: media.is_video || false
                };
                
                // Title từ caption
                try {
                    result.title = media.edge_media_to_caption.edges[0].node.text.slice(0, 200);
                } catch(e) {}
                
                console.log('video://web?method=igGraphQL#' + JSON.stringify(result));
            })
            .catch(function(e) {
                console.log('video://web?method=igGraphQL#' + JSON.stringify({error: e.message}));
            });
        })();
    """.trimIndent()
        webView.evaluateJavascript(js) {

        }
    }

    private fun isInstagramStoryPage(url: String): Boolean {
        return url.contains("instagram.com/stories/")
    }

    private fun tryInstagramExtract(webView: WebView, url: String) {
        if (!isInstagramPage(url)) return
        if (!url.contains("/p/") && !url.contains("/reels/") && !url.contains("/tv/")) return
        val shortcode =
            Regex("/(?:p|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return
        appendLog("[ig] shortcode=$shortcode → fetching via WebView")
        webView.postDelayed({
            fetchInstagramGraphQL(webView, shortcode)
            vm.extractFromUrl(url)
        }, 1000)

    }

    @SuppressLint("SetJavaScriptEnabled")
    fun setupWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = mobileUA
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)

            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        webView.webChromeClient = object : WebChromeClient() {

            // ── IMPROVEMENT 1: Inject tại 75% — tương đương CommonWebChromeClient ──
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                val now = System.currentTimeMillis()

                // Throttle 300ms, inject khi >= 75% — bỏ qua nếu site có parser riêng
                val currentUrl = view.url ?: ""

                // Detect SPA URL change — tương đương ParseWebChromeClient.onProgressChanged
                if (currentUrl.isNotEmpty() && currentUrl != lastSeenUrl) {
                    if (currentUrl.startsWith("https://vimeo.com/") || currentUrl.startsWith("https://www.vimeo.com/")) {
                        if (lastSeenUrl.startsWith("https://player.vimeo.com/") || lastSeenUrl.startsWith("https://www.player.vimeo.com/")) {
                            return
                        }
                    }
                    handleUrlChange(view, currentUrl)
                }

                // Inject JS tại 75%
                if (newProgress >= 75 && now - lastInjectTime > 300 && !shouldSkipJsInject(
                        currentUrl
                    )
                ) {

                    lastInjectTime = now

                    val script = selectInjectScript(currentUrl)
                    webView.evaluateJavascript(script, null)
                }


                // Update progress bar
                binding.progressBar.visibility =
                    if (newProgress < 100) View.VISIBLE else View.GONE
                binding.tvWebStatus.text =
                    if (newProgress < 100) "Loading $newProgress%…" else "✓ ${view.url?.take(55)}"
            }

            // ── IMPROVEMENT 2: console.log channel — "video://web?method=..." ──
            // Tương đương CommonWebChromeClient.onConsoleMessage()
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val message = msg.message() ?: return false



                if (message.startsWith("video://web?")) {
                    val uri = Uri.parse(message)
                    val method = uri.getQueryParameter("method")
                    val fragment = message.substringAfter("#", "")

                    when (method) {
                        "igTest" -> {
                            Log.e("test_ins", "$message")
                        }


                        "parseVideo" -> handleParseVideoResult(fragment)
                        "twitterVideo" -> {
                            try {
                                if (fragment.startsWith("error:")) {
                                    appendLog("[twitter] error: ${fragment.removePrefix("error:")}")
                                    return@onConsoleMessage true
                                }
                                val json = JSONObject(
                                    String(
                                        android.util.Base64.decode(
                                            fragment,
                                            android.util.Base64.DEFAULT
                                        )
                                    )
                                )
                                val title = json.optString("title")
                                val thumb = json.optString("thumb")
                                val results =
                                    json.optJSONArray("results") ?: return@onConsoleMessage true

                                for (i in 0 until results.length()) {
                                    val r = results.getJSONObject(i)
                                    val vi = VideoInfo(
                                        url = r.optString("url"),
                                        pageUrl = currentPageUrl,
                                        mimeType = "video/mp4",
                                        title = title,
                                        thumbnail = thumb,
                                        height = r.optInt("height", 0),
                                        isDownloadable = true
                                    )
                                    vm.updateFromSingle(vi)
                                }
                                appendLog("[twitter] ✓ ${results.length()} quality(s)")
                                runOnUiThread { binding.tabLayout.getTabAt(1)?.select() }
                            } catch (e: Exception) {
                                appendLog("[twitter] parse error: ${e.message}")
                            }
                        }
                        "pinterestMeta" -> {
                            val obj    = JSONObject(fragment)
                            val hlsUrl = obj.optString("url")
                            val title  = obj.optString("title")
                            val thumb  = obj.optString("thumb")
                            if (hlsUrl.isNotEmpty() && thumb.isNotEmpty()) {
                                val height = Regex("_(\\d+)w\\.m3u8").find(hlsUrl)
                                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                vm.updateFromSingle(VideoInfo(
                                    url       = hlsUrl,
                                    pageUrl   = currentPageUrl,
                                    mimeType  = "application/x-mpegURL",
                                    title     = title,
                                    thumbnail = thumb,
                                    height    = height,
                                    isDownloadable = true
                                ))
                            }
                        }
                        "thumb" -> {
                            if (fragment.isNotEmpty()) {
                                vm.pendingThumbnail = fragment
                                appendLog("[thumb] $fragment")
                            }
                        }

                        "removeAd" -> appendLog("[removeAd] idx=$fragment")
                        "gaData" -> appendLog("[gaData] $fragment")
                        "igGraphQL" -> {
                            try {
                                val obj = JSONObject(fragment)
                                if (obj.has("error")) {
                                    appendLog("[ig] error: ${obj.optString("error")}")
                                    return@onConsoleMessage true
                                }
                                val videoUrl = obj.optString("videoUrl")
                                val thumbUrl = obj.optString("thumbUrl")
                                val title = obj.optString("title")
                                if (videoUrl.isNotEmpty()) {
                                    appendLog("[ig] ✓ ${videoUrl.take(60)}")
                                    vm.updateFromSingle(
                                        VideoInfo(
                                            url = videoUrl,
                                            pageUrl = currentPageUrl,
                                            mimeType = "video/mp4",
                                            title = title,
                                            thumbnail = thumbUrl,
                                            isDownloadable = true
                                        )
                                    )
                                    runOnUiThread { binding.tabLayout.getTabAt(1)?.select() }
                                }
                            } catch (e: Exception) {
                                appendLog("[ig] parse error: ${e.message}")
                            }
                        }

                        "fbVideo" -> {
                            try {
                                val seg = JSONObject(fragment)
                                val url = seg.optString("url")
                                val height = seg.optInt("height", 0)
                                val duration = seg.optInt("duration", 0)
                                val title = seg.optString("title")
                                val thumb = seg.optString("thumb")
                                val isProgressive = seg.optBoolean("isProgressive", false)
                                if (url.isNotEmpty()) {
                                    appendLog("[fb] ${if (isProgressive) "progressive" else "DASH"} ${height}p")
                                    val vi = VideoInfo(
                                        url = url,
                                        pageUrl = currentPageUrl,
                                        mimeType = "video/mp4",
                                        title = title,
                                        thumbnail = thumb,
                                        height = height,
                                        duration = duration,
                                        isDownloadable = true
                                    )
                                    Log.e("Faceboook_video", "found item ${url} ")

                                    vm.updateFromSingle(vi)
                                }
                            } catch (e: Exception) {
                                Log.e("Faceboook_video", "parse error")
                            }
                        }
                    }
                    return true
                }
                return false
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {

                val context = view!!.context

                // 1. Tạo một Dialog (Có thể dùng BottomSheetDialog hoặc Dialog thường)
                val dialog = Dialog(context)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

                // 2. Tạo WebView mới để nhúng vào Dialog
                val popupWebView = WebView(context)
                popupWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(true)

                    // Nếu bạn dùng trick đổi User-Agent ở màn chính, nhớ apply vào đây luôn
                    userAgentString = userAgentString.replace("; wv", "")
                }

                // Set layout full width/height cho WebView
                popupWebView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // 3. Xử lý sự kiện đóng cửa sổ (RẤT QUAN TRỌNG)
                // Khi Google login xong, nó sẽ gọi window.close() bằng JS
                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        super.onCloseWindow(window)
                        // Hủy Dialog khi luồng popup kết thúc
                        dialog.dismiss()
                        popupWebView.destroy()
                    }
                }

                // Đảm bảo các link click trong popup không bị bật ra browser ngoài
                popupWebView.webViewClient = WebViewClient()

                // Gắn WebView vào Dialog
                dialog.setContentView(popupWebView)

                // Chỉnh lại kích thước của Dialog (Ví dụ: full màn hình hoặc 90%)
                dialog.window?.apply {
                    setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT // Hoặc dùng WRAP_CONTENT/kích thước DP tùy ý
                    )
                }

                dialog.show()

                // 4. "Bắn" WebView mới tạo vào message transport để hệ thống load URL đăng nhập
                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = popupWebView
                resultMsg.sendToTarget()

                return true
            }

            // Detect URL change qua title change — tương đương ParseWebChromeClient.onReceivedTitle
            override fun onReceivedTitle(view: WebView, title: String) {
                super.onReceivedTitle(view, title)
                val url = view.url ?: return
                if (url.isNotEmpty() && url != lastSeenUrl) {
                    handleUrlChange(view, url)
                }
            }

            // Grant DRM permission — tương đương onPermissionRequest trong app gốc
            // "android.webkit.resource.PROTECTED_MEDIA_ID"
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        // ── WebViewClient — intercept + onPageFinished ────────────────────
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""
                if (scheme != "http" && scheme != "https") {
                    appendLog("[blocked] custom scheme: $scheme")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                val headers = request.requestHeaders

                // Sniff Instagram headers — tương đương ParseWebViewClient.shouldInterceptRequest
                if (isInstagramPage(currentPageUrl)) {
                    headers["X-IG-App-ID"]?.let {
                        if (it.isNotEmpty()) {
                            igAppId = it;
                            InstagramParser.igAppId = it
                        }
                    }
                    headers["X-CSRFToken"]?.let {
                        if (it.isNotEmpty()) {
                            igCsrfToken = it;
                            InstagramParser.igCsrfToken = it
                        }
                    }
                    headers["X-ASBD-ID"]?.let {
                        if (it.isNotEmpty()) {
                            igAsbdId = it;
                            InstagramParser.igAsbdId = it
                        }
                    }
                }
                if ((url.contains("twitter.com") || url.contains("x.com"))
                    && url.contains("/graphql/") && url.contains("TweetDetail")
                ) {
                    val id =
                        Regex("/graphql/([^/]+)/TweetDetail").find(url)?.groupValues?.get(1) ?: ""
                    if (id.isNotEmpty()) TwitterParser.queryId = id
                }
                // Log video network requests
                if (url.contains(".mp4") || url.contains(".m3u8") ||
                    url.contains(".ts?") || url.contains("manifest")
                ) {
                    runOnUiThread { appendLog("[net] ${url.take(80)}") }
                }
                return null
            }

            override fun onLoadResource(view: WebView, url: String) {
                if (url.contains("tplv-photomode-zoomcover") || url.contains("tplv-tiktok-logom-rs")) {
                    Log.e("TikTokLoader", "found images : " + url)
                    tiktok?.updateImageUrl(url)
                    return
                } else if (url.contains("https://v16-webapp-prime.tiktok.com/video")) {
                    Log.e("TikTokLoader", "found videos : " + url)
                    tiktok?.updateVideoUrl(url)
                    return
                } else if (url.contains("scontent.fhan5-11.fna.fbcdn.net/o1/v/")) {

                    Log.e("FbStory", "onLoadResource: $url")
                    if (url.contains(".mp4")
                        && (url.contains("&tag=progressive") || url.contains("&tag=sve_sd"))
                        && url.contains("&bitrate=")
                        && isFacebookPage(currentPageUrl)
                    ) {
                        runOnUiThread {
                            appendLog("[story] ${url.take(80)}")
                            val vi = VideoInfo(
                                url = url,
                                pageUrl = currentPageUrl,
                                mimeType = "video/mp4",
                                title = "Facebook Story - ${System.currentTimeMillis()}}",
                                thumbnail = "",
                                height = 0,
                                isDownloadable = true
                            )
                            vm.updateFromSingle(vi)
                        }
                    }

                } else if (url.contains("https://instagram.fhan5")
                    && url.contains(".mp4")
                    && isInstagramStoryPage(currentPageUrl)
                ) {

                    // Bỏ bytestart/byteend → full file URL
                    val fullUrl = url.replace(Regex("[?&]bytestart=\\d+"), "")
                        .replace(Regex("[?&]byteend=\\d+"), "")


                    val username = Regex("/stories/([^/]+)/")
                        .find(currentPageUrl)?.groupValues?.get(1) ?: ""
                    val title =
                        if (username.isNotEmpty()) "$username - Story" else "Instagram Story"

                    runOnUiThread {
                        appendLog("[ig-story] ${fullUrl.take(80)}")

                        Log.e("UpdateResource", "fixed " + fullUrl)
                        Log.e("UpdateResource", "real " + url)
                        vm.updateFromSingle(
                            VideoInfo(
                                url = fullUrl,
                                pageUrl = currentPageUrl,
                                mimeType = "video/mp4",
                                title = title,
                                isDownloadable = true
                            )
                        )
                    }
                } else if (url.contains("v1.pinimg.com/videos/iht/")
                    && url.contains(".m3u8")
                    && !url.contains(".cmf")  // bỏ qua HLS segments
                    && isPinterestPage(currentPageUrl)
                ) {

                    val mp4Url = url
                        .replace("/hls/", "/720p/")
                        .replace(".m3u8", ".mp4")

                    val hlsUrl = url
                    val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment
                    frag?.getWebView()?.evaluateJavascript(
                        """
        (function() {
            var title = document.querySelector('[data-test-id="pinTitle"] h1')
                ?.textContent?.trim() || '';
            var thumb = document.querySelector('[data-test-id="duplo-hls-video"]')
                ?.getAttribute('poster') || '';
            console.log('video://web?method=pinterestMeta#' + JSON.stringify({
                url:   '${hlsUrl.replace("'", "\\'")}',
                title: title,
                thumb: thumb
            }));
        })();
    """, null
                    )

//                    runOnUiThread {
//                        appendLog("[pinterest] HLS: ${url.take(70)}")
//                        // HLS
//                        vm.updateFromSingle(
//                            VideoInfo(
//                                url = url,
//                                pageUrl = currentPageUrl,
//                                mimeType = "application/x-mpegURL",
//                                title = "",
//                                isDownloadable = true,
//                                referer = "https://www.pinterest.com/"
//                            )
//                        )
//
//                    }
                    return
                }

            }

            override fun onPageFinished(view: WebView, url: String) {
                currentPageUrl = url
                runOnUiThread {
                    binding.btnExtract.isEnabled = true
                    binding.tvWebStatus.text = "✓ ${url.take(55)}"
                }

                val cookies = CookieManager.getInstance().getCookie(url)
                Log.e("REQUEST", "Cookies: $cookies")
                lastCookies = cookies ?: ""

                appendLog("[ready] $url")

                if (!shouldSkipJsInject(url)) {
                    val script = selectInjectScript(url)
                    webView.evaluateJavascript(script, null)
                }


                // Trigger router
                if (!isTiktok(url) && !isFacebookPage(url) && !isPinterestPage(url)) {
                    if (isInstagramPage(url)) {
                        tryInstagramExtract(webView, url)
                    } else {
                        vm.extractFromUrl(url)
                    }

                }

            }

            override fun onReceivedError(
                view: WebView, req: WebResourceRequest, err: WebResourceError
            ) {
                if (req.isForMainFrame)
                    runOnUiThread { binding.tvWebStatus.text = "✗ ${err.description}" }
            }
        }

        if (currentPageUrl.isNotEmpty()) webView.loadUrl(currentPageUrl)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  XỬ LÝ KẾT QUẢ TỪ JS — "video://web?method=parseVideo#[...]"
    //  Tương đương InjectJsParseVideo.k() trong app gốc
    // ════════════════════════════════════════════════════════════════════════

    private fun isTiktok(url: String): Boolean {
        return url.contains("tiktok.com") || url.contains("tiktokv.com")
    }

    private fun handleParseVideoResult(fragment: String) {
        if (fragment.isBlank() || fragment == "[]") return

        try {
            // Payload mới: { urls: [...], title: "...", thumb: "..." }
            val payload = org.json.JSONObject(fragment)
            val urlsArr = payload.optJSONArray("urls") ?: org.json.JSONArray(fragment)
            val titleHint = payload.optString("title", "")
            val thumbHint = payload.optString("thumb", "")

            if (urlsArr.length() == 0 && thumbHint.isEmpty()) return

            appendLog(
                "[parseVideo] JS found ${urlsArr.length()} URL(s)" +
                        if (thumbHint.isNotEmpty()) " | thumb ✓" else ""
            )

            val candidates = mutableListOf<VideoInfo>()
            for (i in 0 until urlsArr.length()) {
                val obj = urlsArr.getJSONObject(i)
                val url = obj.optString("url")
                val height = obj.optInt("height", 480)
                val isHls = obj.optBoolean("isHls", false)
                if (url.isBlank()) continue
                appendLog("  [js] ${if (isHls) "HLS" else "MP4"} ${height}p → ${url.take(70)}")
                candidates.add(
                    VideoInfo(
                        url = url,
                        pageUrl = currentPageUrl,
                        mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                        height = height,
                        title = titleHint,
                        thumbnail = thumbHint,
                        isDownloadable = true
                    )
                )
            }

            if (candidates.isNotEmpty()) {
                vm.extractFromCandidates(currentPageUrl, candidates, titleHint, thumbHint)
                binding.btnExtract.isEnabled = true
                runOnUiThread { binding.tabLayout.getTabAt(1)?.select() }
            }
        } catch (e: Exception) {
            appendLog("[parseVideo error] ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LOAD URL
    // ════════════════════════════════════════════════════════════════════════

    fun loadUrl(raw: String) {
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        currentPageUrl = url
        renderedHtml = null
        lastInjectTime = 0L
        lastSeenUrl = ""
        binding.btnExtract.isEnabled = false
        binding.tvWebStatus.text = "Loading…"
        vm.resetForNewUrl()  // reset JS flag cho URL mới
        appendLog("[load] $url")

        val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment
        frag?.getWebView()?.loadUrl(url)
        binding.tabLayout.getTabAt(0)?.select()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  BUTTONS
    // ════════════════════════════════════════════════════════════════════════

    private fun setupButtons() {
        binding.btnLoad.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadUrl(url)
        }

        // Manual extract (fallback nếu JS inject không tự động)
        binding.btnExtract.setOnClickListener {
            val url = currentPageUrl.ifEmpty { binding.etUrl.text.toString().trim() }
            if (url.isBlank()) return@setOnClickListener
            appendLog("[manual extract] triggered")

            // Inject lại JS — nếu JS tìm được URL sẽ tự động switch tab
            val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment

            if (!shouldSkipJsInject(url)) {
                val script = selectInjectScript(url)
                frag?.getWebView()?.evaluateJavascript(script, null)
            }

            // Song song: extract qua HTTP fetch (fallback cho site JS không inject được)
            if (!isTiktok(url) && !isFacebookPage(url) && !isPinterestPage(url)) {
                vm.extractFromUrl(url)
            }

            binding.tabLayout.getTabAt(1)?.select()
        }

        binding.btnCookie.setOnClickListener { showCookieDialog() }
    }

    private fun showCookieDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "name=value; name2=value2"
            setText(vm.cookieString.value ?: "")
            minLines = 4
            gravity = android.view.Gravity.TOP
            setPadding(32, 16, 32, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("🍪  Cookie")
            .setMessage("Paste từ browser DevTools → Network → Request Headers → Cookie")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                vm.cookieString.value = input.text.toString().trim()
            }
            .setNegativeButton("Clear") { _, _ -> vm.cookieString.value = "" }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  OBSERVE
    // ════════════════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        vm.parseState.observe(this) { state ->
            binding.progressBar.visibility =
                if (state is ParseState.Loading) View.VISIBLE else View.GONE
        }
    }

    fun appendLog(msg: String) {
        val cur = vm.logMessages.value?.toMutableList() ?: mutableListOf()
        cur.add(msg)
        vm.logMessages.value = cur
    }

    var tiktok: TikTokUtils? = null
    var lastCookies = ""

    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        val frag = supportFragmentManager.findFragmentByTag("f0") as? WebViewFragment
        if (frag?.getWebView()?.canGoBack() == true) frag.getWebView()?.goBack()
        else super.onBackPressed()
    }

}