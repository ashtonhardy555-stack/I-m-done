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
    private const val BACKEND_BASE = "https://your-backend.example.com/api/stream" // Update if you have one; fallback to client scraping
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int? = null,
        episode: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val title = "unknown" // You may want to pass or fetch title for better lookup
        Log.d(TAG, "🔍 Starting extraction for TMDB $tmdbId ($contentType)")

        // 1. Try backend first (recommended for reliability)
        try {
            val backendUrl = "$BACKEND_BASE?tmdb=$tmdbId&type=$contentType&season=$season&episode=$episode"
            val backendReq = Request.Builder()
                .url(backendUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            val backendResp = client.newCall(backendReq).execute()
            if (backendResp.isSuccessful) {
                val backendStream = backendResp.body?.string()?.trim()
                if (!backendStream.isNullOrBlank() && isDirectPlayable(backendStream)) {
                    Log.i(TAG, "✅ Backend direct URL: $backendStream")
                    return@withContext backendStream
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend failed, falling back to scraping: ${e.message}")
        }

        // 2. Fallback: Scrape LookMovie2.to (or mirrors)
        val searchUrl = "https://lookmovie2.to/search/?q=$title" // Improve with actual title if possible
        val urlsToTry = listOf(
            "https://lookmovie2.to/movies/view/$tmdbId", // Adjust based on their URL pattern
            // Add more specific player/watch URLs if known
        )

        for (baseUrl in urlsToTry) {
            try {
                Log.d(TAG, "Scraping $baseUrl")
                val request = Request.Builder()
                    .url(baseUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://lookmovie2.to/")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()

                val response: Response = client.newCall(request).execute()
                val html = response.body?.string() ?: continue

                // Expanded regex patterns for m3u8/mp4 (handle master, variants, sources in JS)
                val streamPatterns = listOf(
                    """["']?(https?://[^\s"']+\.m3u8[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
                    """["']?(https?://[^\s"']+\.mp4[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
                    """file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                    """source["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
                    """master\.m3u8""".toRegex(RegexOption.IGNORE_CASE), // Catch and reconstruct if partial
                    // Add more if you inspect Network tab (e.g., data-url, src in player scripts)
                )

                for (pattern in streamPatterns) {
                    val matches = pattern.findAll(html)
                    for (match in matches) {
                        val url = match.groupValues.getOrNull(1)?.trim() ?: match.value.trim()
                        if (isDirectPlayable(url)) {
                            Log.i(TAG, "✅ Found direct stream: $url")
                            return@withContext url
                        }
                    }
                }

                // Fallback: Look for player init JS or data attributes
                if (html.contains(".m3u8") || html.contains("hls.js")) {
                    Log.d(TAG, "Player JS detected - may need advanced parsing or backend")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scraping $baseUrl: ${e.message}")
            }
        }

        Log.e(TAG, "❌ No direct stream found")
        null
    }

    private fun isDirectPlayable(url: String): Boolean {
        return url.contains(".m3u8") || url.contains(".mp4") && 
               !url.contains("embed") && !url.contains("player") && url.startsWith("http")
    }
}
