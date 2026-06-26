package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Starting extraction for TMDB $tmdbId ($contentType)")

            // Priority 1: LookMovie2.to (as requested)
            val lookmovieUrl = if (contentType.lowercase() == "tv") {
                "https://www.lookmovie2.to/shows/play/$tmdbId/$season/$episode"
            } else {
                "https://www.lookmovie2.to/movies/play/$tmdbId"
            }

            Log.d(TAG, "Trying LookMovie2.to: $lookmovieUrl")

            val request = Request.Builder()
                .url(lookmovieUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://www.lookmovie2.to/")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()

                val directUrl = extractDirectUrl(html, "https://www.lookmovie2.to")
                if (!directUrl.isNullOrBlank()) {
                    Log.i(TAG, "✅ Found direct stream from LookMovie2.to: $directUrl")
                    return@withContext directUrl
                }
            } else {
                response.close()
            }

            // Fallback to other servers
            Log.w(TAG, "⚠️ LookMovie2.to failed, trying fallbacks")
            val fallbackUrl = getFallbackUrl(tmdbId, contentType, season, episode)
            return@withContext fallbackUrl

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }

    private fun extractDirectUrl(html: String, base: String): String? {
        val patterns = listOf(
            // Master HLS playlists
            """["']([^"']*master\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            """["']([^"']*\.m3u8[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            // Direct MP4
            """["']([^"']*\.mp4[^"']*)["']""".toRegex(RegexOption.IGNORE_CASE),
            // JSON-like sources
            """source["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """file["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """url["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            pattern.findAll(html).forEach { match ->
                var url = match.groupValues[1]
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) url = "$base$url"

                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    return url
                }
            }
        }
        return null
    }

    private fun getFallbackUrl(tmdbId: Int, contentType: String, season: Int, episode: Int): String? {
        return when (contentType.lowercase()) {
            "tv" -> "https://vidlink.pro/tv/$tmdbId/$season/$episode"
            else -> "https://vidlink.pro/movie/$tmdbId"
        }
    }
}
