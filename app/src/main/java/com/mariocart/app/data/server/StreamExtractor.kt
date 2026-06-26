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
        .readTimeout(30, TimeUnit.SECONDS)  // Increased for redirect-heavy sites like LookMovie
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Priority: LookMovie2.to (as you mentioned it loads then redirects)
            val lookmovieUrl = if (contentType.lowercase() == "tv") {
                "https://www.lookmovie2.to/shows/play/$tmdbId/$season/$episode"
            } else {
                "https://www.lookmovie2.to/movies/play/$tmdbId"
            }

            Log.d(TAG, "Trying LookMovie: $lookmovieUrl")

            val request = Request.Builder()
                .url(lookmovieUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://www.lookmovie2.to/")
                .header("Accept", "text/html,application/xhtml+xml")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()

                // Enhanced patterns for LookMovie / similar sites (handles delayed JS loads)
                val streamPatterns = listOf(
                    """["']?(https?://[^\s"']+\.m3u8[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
                    """["']?(https?://[^\s"']+\.mp4[^\s"']*)["']?""".toRegex(RegexOption.IGNORE_CASE),
                    """source["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
                    """file["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
                    """master\.m3u8""".toRegex(RegexOption.IGNORE_CASE)  // Common HLS entry
                )

                for (pattern in streamPatterns) {
                    pattern.findAll(html).forEach { match ->
                        var url = match.groupValues.getOrNull(1) ?: match.value
                        if (url.startsWith("//")) url = "https:$url"
                        if (url.contains(".m3u8") || url.contains(".mp4")) {
                            Log.i(TAG, "✅ Direct stream found: $url")
                            return@withContext url
                        }
                    }
                }
            } else {
                response.close()
            }

            Log.w(TAG, "⚠️ No direct stream extracted from LookMovie")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }
}
