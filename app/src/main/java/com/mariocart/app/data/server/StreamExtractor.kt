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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            // TODO: Uncomment and use your backend when deployed for best results
            // val backendUrl = "https://your-backend.onrender.com/api/stream?tmdbId=$tmdbId&..."
            // ... call backend first

            val servers = listOf(
                "https://vidlink.pro",
                "https://vidsrc.to",
                "https://vidsrc-embed.ru"
            )

            for (base in servers) {
                val url = when (contentType.lowercase()) {
                    "tv" -> "$base/tv/$tmdbId/$season/$episode"
                    else -> "$base/movie/$tmdbId"
                }

                Log.d(TAG, "Trying server: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S911B) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    val directUrl = extractDirectUrl(body, base)
                    if (!directUrl.isNullOrBlank()) {
                        Log.i(TAG, "✅ Found direct stream: $directUrl")
                        return@withContext directUrl
                    }
                } else {
                    response.close()
                }
            }

            // Fallback (embed) - will be caught as non-direct in PlayerScreen
            val fallback = when (contentType.lowercase()) {
                "tv" -> "https://vidlink.pro/tv/$tmdbId/$season/$episode"
                else -> "https://vidlink.pro/movie/$tmdbId"
            }
            Log.w(TAG, "⚠️ No direct stream, using fallback: $fallback")
            fallback

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }

    private fun extractDirectUrl(html: String, base: String): String? {
        val patterns = listOf(
            """["']([^"']*\.m3u8[^"']*)["']""".toRegex(),
            """["']([^"']*\.mp4[^"']*)["']""".toRegex(),
            """source["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """file["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE),
            """["']url["']\s*:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
        )

        for (regex in patterns) {
            regex.findAll(html).forEach { match ->
                var url = match.groupValues[1].trim()
                if (url.startsWith("//")) url = "https:$url"
                if (!url.startsWith("http")) url = "$base$url".replace("https://https://", "https://")

                if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("video")) {
                    return url
                }
            }
        }
        return null
    }
}
