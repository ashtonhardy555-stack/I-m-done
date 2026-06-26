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
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String = "movie",
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {

        // === PRIMARY: LookMovie2.to ===
        val lookmovieBase = "https://www.lookmovie2.to"
        val playUrl = if (contentType.lowercase() == "tv") {
            "$lookmovieBase/shows/play/$tmdbId/$season/$episode"
        } else {
            "$lookmovieBase/movies/play/$tmdbId"
        }

        Log.d(TAG, "Trying LookMovie primary: $playUrl")

        try {
            val request = Request.Builder()
                .url(playUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", lookmovieBase)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()

                // Look for direct stream in page (common patterns + LookMovie specific)
                val direct = extractDirectUrl(html, lookmovieBase)
                if (!direct.isNullOrBlank()) {
                    Log.i(TAG, "✅ LookMovie direct stream: $direct")
                    return@withContext direct
                }

                // LookMovie often has streams in JS variables
                val streamMatch = Pattern.compile("""streams["']\s*:\s*\{([^}]+)""").matcher(html)
                if (streamMatch.find()) {
                    val streamsBlock = streamMatch.group(1) ?: ""
                    val urlMatch = Pattern.compile("""["']([^"']+\.(m3u8|mp4))["']""").matcher(streamsBlock)
                    if (urlMatch.find()) {
                        var url = urlMatch.group(1)
                        if (!url.startsWith("http")) url = "https:$url"
                        Log.i(TAG, "✅ LookMovie JS stream: $url")
                        return@withContext url
                    }
                }
            } else {
                response.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "LookMovie primary failed", e)
        }

        // === STRONG FALLBACKS ===
        val fallbacks = listOf("https://vidlink.pro", "https://vidsrc.to", "https://vidsrc-embed.ru")
        for (base in fallbacks) {
            try {
                val url = if (contentType.lowercase() == "tv") {
                    "$base/tv/$tmdbId/$season/$episode"
                } else "$base/movie/$tmdbId"

                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    resp.close()
                    val direct = extractDirectUrl(html, base)
                    if (!direct.isNullOrBlank()) {
                        Log.i(TAG, "✅ Fallback from $base: $direct")
                        return@withContext direct
                    }
                } else resp.close()
            } catch (e: Exception) {}
        }

        // Last resort: return the LookMovie play page
        Log.w(TAG, "No direct link found — returning LookMovie embed")
        return@withContext playUrl
    }

    private fun extractDirectUrl(html: String, base: String): String? {
        val patterns = listOf(
            """["']([^"']+\.m3u8[^"']*)["']""",
            """["']([^"']+\.mp4[^"']*)["']""",
            """src=["']([^"']+\.(m3u8|mp4)[^"']*)["']"""
        )

        for (p in patterns) {
            val matcher = Pattern.compile(p, Pattern.CASE_INSENSITIVE).matcher(html)
            while (matcher.find()) {
                var url = matcher.group(1) ?: continue
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) url = "$base$url"
                if (url.contains(".m3u8") || url.contains(".mp4")) return url
            }
        }
        return null
    }
}
