package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object StreamExtractor {

    private const val TAG = "StreamExtractor"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {

        // === PRIORITY 1: LookMovie2.to (as requested) ===
        val lookmovieUrl = buildLookMovieUrl(tmdbId, contentType, season, episode)
        Log.d(TAG, "Trying primary server: $lookmovieUrl")

        try {
            val request = Request.Builder()
                .url(lookmovieUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://www.lookmovie2.to")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()

                val directUrl = extractDirectUrl(html, "https://www.lookmovie2.to")
                if (!directUrl.isNullOrBlank()) {
                    Log.i(TAG, "✅ LookMovie direct stream found: $directUrl")
                    return@withContext directUrl
                }
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "LookMovie primary failed", e)
        }

        // === FALLBACK: Other reliable servers ===
        val fallbackServers = listOf(
            "https://vidlink.pro",
            "https://vidsrc.to",
            "https://vidsrc-embed.ru"
        )

        for (base in fallbackServers) {
            try {
                val url = if (contentType.lowercase() == "tv") {
                    "$base/tv/$tmdbId/$season/$episode"
                } else {
                    "$base/movie/$tmdbId"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    response.close()

                    val directUrl = extractDirectUrl(html, base)
                    if (!directUrl.isNullOrBlank()) {
                        Log.i(TAG, "✅ Fallback stream found from $base: $directUrl")
                        return@withContext directUrl
                    }
                } else {
                    response.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback server failed: $base", e)
            }
        }

        Log.w(TAG, "No direct stream found. Returning LookMovie embed as last resort.")
        return@withContext lookmovieUrl
    }

    private fun buildLookMovieUrl(tmdbId: Int, contentType: String, season: Int, episode: Int): String {
        return if (contentType.lowercase() == "tv") {
            "https://www.lookmovie2.to/shows/play/$tmdbId/$season/$episode"
        } else {
            "https://www.lookmovie2.to/movies/play/$tmdbId"
        }
    }

    private fun extractDirectUrl(html: String, base: String): String? {
        val patterns = listOf(
            Pattern.compile("""["']([^"']+\.m3u8[^"']*)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""["']([^"']+\.mp4[^"']*)["']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""src=["']([^"']+\.(m3u8|mp4)[^"']*)["']""", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                var url = matcher.group(1) ?: continue
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) url = "$base$url".replace("//", "/")

                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    return url
                }
            }
        }
        return null
    }
}
