package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.Response

object StreamExtractor {
    private const val TAG = "StreamExtractor"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private const val BACKEND_BASE = "https://your-backend.example.com/api/stream" // TODO: Update or remove if not using

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val providers = listOf(
        "https://lookmovie2.to",
        "https://lookmovie.foundation",  // Common mirror
        "https://fmovies.ink", "https://www.fmovies.to",
        "https://hdtoday.tv", "https://hdtoday.cc",
        "https://myflixerz.nl", "https://myflixer.is",
        "https://dopebox.to",
        "https://soap2day.tf"  // Add more as needed
    )

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int? = null,
        episode: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 Starting multi-provider extraction for TMDB $tmdbId ($contentType S${season}E${episode})")

        // 1. Backend priority (best if you host a robust scraper)
        try {
            val backendUrl = "$BACKEND_BASE?tmdb=$tmdbId&type=$contentType&season=$season&episode=$episode"
            val backendReq = Request.Builder().url(backendUrl).header("User-Agent", USER_AGENT).build()
            val backendResp = client.newCall(backendReq).execute()
            if (backendResp.isSuccessful) {
                val url = backendResp.body?.string()?.trim()
                if (!url.isNullOrBlank() && isDirectPlayable(url)) {
                    Log.i(TAG, "✅ Backend success: $url")
                    return@withContext url
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend unavailable: ${e.message}")
        }

        // 2. Multi-site scraping
        for (base in providers) {
            val searchOrWatchUrl = buildSearchOrWatchUrl(base, tmdbId, contentType, season, episode)
            try {
                Log.d(TAG, "Trying $searchOrWatchUrl")
                val req = Request.Builder()
                    .url(searchOrWatchUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", base)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml")
                    .build()

                val resp: Response = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val html = resp.body?.string() ?: continue

                val directUrl = extractDirectStream(html, base)
                if (directUrl != null) {
                    Log.i(TAG, "✅ Found direct stream from $base: $directUrl")
                    return@withContext directUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed $base: ${e.message}")
            }
        }

        Log.e(TAG, "❌ No direct stream found across providers")
        null
    }

    private fun buildSearchOrWatchUrl(
        base: String, tmdbId: Int, contentType: String, season: Int?, episode: Int?
    ): String {
        return when {
            base.contains("lookmovie") -> {
                if (contentType == "tv") {
                    "$base/tv/view/$tmdbId"
                } else {
                    "$base/movies/view/$tmdbId"
                }
            }
            else -> "$base/search/?q=$tmdbId" // Fallback search; improve with title if available
        }
    }

    private fun extractDirectStream(html: String, base: String): String? {
        val patterns = listOf(
            """["']?(https?://[^\s"']+\.m3u8[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
            """["']?(https?://[^\s"']+\.mp4[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
            """file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            """source["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            """src["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            """master\.m3u8""".toRegex(RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.findAll(html).forEach { match ->
                val url = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                if (isDirectPlayable(url)) {
                    // Make absolute if relative
                    return if (url.startsWith("http")) url else "$base$url"
                }
            }
        }
        return null
    }

    private fun isDirectPlayable(url: String): Boolean {
        val clean = url.lowercase()
        return (clean.contains(".m3u8") || clean.contains(".mp4")) &&
               !clean.contains("embed") && !clean.contains("player") &&
               !clean.contains("ads") && clean.startsWith("http")
    }
}
