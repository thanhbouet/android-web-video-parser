package com.ezt1.webvideo.parser

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ════════════════════════════════════════════════════════════════════════════
//  TwitterParser
//
//  Flow:
//  1. Extract tweetId từ URL (/status/ID)
//  2. Lấy csrf token từ cookie ct0
//  3. Call TweetDetail GraphQL API
//  4. Parse variants → filter video/mp4 → sort by bitrate
// ════════════════════════════════════════════════════════════════════════════

class TwitterParser(core: CoreURLParser) : BaseParser(core) {

    companion object {
        const val TAG = "TwitterParser"

        // Bearer token — public token của X web app, không đổi theo user
        const val BEARER_TOKEN =
            "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        // Operation ID — sniff từ shouldInterceptRequest hoặc hardcode
        // Nên để trên remote config để thay đổi mà không cần update app
        var queryId: String = "rU08O-YiXdr0IZfE7qaUMg"

        // features — hardcode, thay đổi rất ít
        const val FEATURES =
            """{"rweb_video_screen_enabled":false,"profile_label_improvements_pcf_label_in_post_enabled":true,"responsive_web_profile_redirect_enabled":false,"rweb_tipjar_consumption_enabled":false,"verified_phone_label_enabled":false,"creator_subscriptions_tweet_preview_api_enabled":true,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"premium_content_api_read_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"responsive_web_grok_analyze_button_fetch_trends_enabled":false,"responsive_web_grok_analyze_post_followups_enabled":true,"responsive_web_jetfuel_frame":true,"responsive_web_grok_share_attachment_enabled":true,"responsive_web_grok_annotations_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"content_disclosure_indicator_enabled":true,"content_disclosure_ai_generated_indicator_enabled":true,"responsive_web_grok_show_grok_translated_post":false,"responsive_web_grok_analysis_button_from_backend":true,"post_ctas_fetch_enabled":true,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":false,"responsive_web_grok_image_annotation_enabled":true,"responsive_web_grok_imagine_annotation_enabled":true,"responsive_web_grok_community_note_auto_translation_is_enabled":false,"responsive_web_enhance_cards_enabled":false}"""

        const val FIELD_TOGGLES =
            """{"withArticleRichContentState":true,"withArticlePlainText":false,"withArticleSummaryText":true,"withArticleVoiceOver":true,"withGrokAnalyze":false,"withDisallowedReplyControls":false}"""
    }

    override fun canHandle(url: String) =
        (url.contains("twitter.com") || url.contains("x.com")) && url.contains("/status/")

    override suspend fun parse(
        url: String, html: String, onLog: (String) -> Unit
    ): List<VideoInfo> = withContext(Dispatchers.IO) {
        log(onLog, "[Twitter] $url")

        val tweetId = extractTweetId(url)
        if (tweetId.isEmpty()) {
            log(onLog, "  [error] cannot extract tweetId")
            return@withContext emptyList()
        }
        log(onLog, "  [tweetId] $tweetId")

        val cookie = CookieManager.getInstance().getCookie("https://x.com") ?: ""
        val csrfToken = extractCsrf(cookie)
        if (csrfToken.isEmpty()) {
            log(onLog, "  [error] no csrf token — user may not be logged in")
            return@withContext emptyList()
        }

        val apiUrl = buildApiUrl(tweetId)
        log(onLog, "  [api] queryId=$queryId")

        val headers = mapOf(
            "authorization" to BEARER_TOKEN,
            "x-csrf-token" to csrfToken,
            "x-twitter-auth-type" to "OAuth2Session",
            "x-twitter-active-user" to "yes",
            "x-twitter-client-language" to "en",
            "content-type" to "application/json",
            "Cookie" to cookie,
            "Referer" to url
        )

        val body = core.getRequestPublicWithHeaders(apiUrl, headers)
            ?: return@withContext emptyList<VideoInfo>().also {
                log(onLog, "  [error] API request failed")
            }

        parseResponse(body, url, onLog)
    }

    // ── Extract tweetId từ URL ─────────────────────────────────────────────

    private fun extractTweetId(url: String): String {
        val m = Regex("/status/(\\d+)").find(url)
        return m?.groupValues?.get(1) ?: ""
    }

    // ── Extract csrf từ cookie ─────────────────────────────────────────────

    private fun extractCsrf(cookie: String): String {
        val m = Regex("ct0=([^;]+)").find(cookie)
        return m?.groupValues?.get(1) ?: ""
    }

    // ── Build API URL ──────────────────────────────────────────────────────

    private fun buildApiUrl(tweetId: String): String {
        val variables = java.net.URLEncoder.encode(
            """{"focalTweetId":"$tweetId","referrer":"home","with_rux_injections":false,"rankingMode":"Relevance","includePromotedContent":true,"withCommunity":true,"withQuickPromoteEligibilityTweetFields":true,"withBirdwatchNotes":true,"withVoice":true}""",
            "UTF-8"
        )
        val features = java.net.URLEncoder.encode(FEATURES, "UTF-8")
        val fieldToggles = java.net.URLEncoder.encode(FIELD_TOGGLES, "UTF-8")

        return "https://x.com/i/api/graphql/$queryId/TweetDetail" +
                "?variables=$variables&features=$features&fieldToggles=$fieldToggles"
    }

    // ── Parse response ─────────────────────────────────────────────────────

    private fun parseResponse(
        body: String, pageUrl: String, onLog: (String) -> Unit
    ): List<VideoInfo> {
        val results = mutableListOf<VideoInfo>()
        try {
            val json = JSONObject(body)
            val instructions = json
                .optJSONObject("data")
                ?.optJSONObject("threaded_conversation_with_injections_v2")
                ?.optJSONArray("instructions")
                ?: run {
                    log(onLog, "  [error] no instructions | body: ${body.take(100)}")
                    return emptyList()
                }

            // Tìm instruction có entries (bỏ qua TimelineClearCache)
            var entries = org.json.JSONArray()
            for (i in 0 until instructions.length()) {
                val inst = instructions.getJSONObject(i)
                if (inst.has("entries")) {
                    entries = inst.getJSONArray("entries")
                    break
                }
            }

            if (entries.length() == 0) {
                log(onLog, "  [error] no entries found")
                return emptyList()
            }

            // Parse tweet từ entry đầu tiên
            val tweet = entries.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONObject("itemContent")
                ?.optJSONObject("tweet_results")
                ?.optJSONObject("result")
                ?.optJSONObject("legacy")
                ?: run {
                    log(onLog, "  [error] cannot find tweet legacy")
                    return emptyList()
                }

            val title = tweet.optString("full_text", "")
            val media = tweet.optJSONObject("extended_entities")
                ?.optJSONArray("media")
                ?.optJSONObject(0)
                ?: run {
                    log(onLog, "  [info] no media found — tweet may be text only")
                    return emptyList()
                }

            val thumb = media.optString("media_url_https", "")
            val variants = media.optJSONObject("video_info")
                ?.optJSONArray("variants")
                ?: run {
                    log(onLog, "  [info] no video variants")
                    return emptyList()
                }

            // Filter video/mp4 và sort theo bitrate
            for (i in 0 until variants.length()) {
                val v = variants.getJSONObject(i)
                val contentType = v.optString("content_type")
                val videoUrl = v.optString("url")
                val bitrate = v.optInt("bitrate", 0)

                if (contentType != "video/mp4") continue
                if (videoUrl.isEmpty()) continue

                // Extract height từ URL: /320x568/ → 568
                val height = Regex("/(\\d+)x(\\d+)/").find(videoUrl)
                    ?.groupValues?.get(2)?.toIntOrNull() ?: 0

                log(onLog, "  ✓ ${height}p | ${bitrate / 1000}kbps")
                results.add(
                    VideoInfo(
                        url = videoUrl,
                        pageUrl = pageUrl,
                        mimeType = "video/mp4",
                        title = title,
                        thumbnail = thumb,
                        height = height,
                        isDownloadable = true
                    )
                )
            }

            // Sort theo height giảm dần
            results.sortByDescending { it.height }
            log(onLog, "  [done] ${results.size} quality(s)")

        } catch (e: Exception) {
            Log.e(TAG, "parseResponse: ${e.message}")
            log(onLog, "  [error] ${e.message}")
        }
        return results
    }
}