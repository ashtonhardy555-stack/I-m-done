package com.mariocart.app.data.server

import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Try backend first (if you have one deployed)
            // val backendUrl = "https://your-backend.onrender.com/api/stream?tmdbId=$tmdbId&type=$contentType..."
            // ... call it here if available

            // Fallback: Try VidLink + common mirrors
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

                Log.d(TAG, "Trying: $url")

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    response.close()

                    // Extract direct video URL (m3u8 or mp4)
                    val directUrl = extractDirectUrl(body, base)
                    if (!directUrl.isNullOrBlank()) {
                        Log.i(TAG, "✅ Found direct stream: $directUrl")
                        return@withContext directUrl
                    }
                }
                response.close()
            }

            // Last resort: return embed URL for WebView fallback (if you have one)
            val fallback = when (contentType.lowercase()) {
                "tv" -> "https://vidlink.pro/tv/$tmdbId/$season/$episode"
                else -> "https://vidlink.pro/movie/$tmdbId"
            }
            Log.w(TAG, "Using fallback embed: $fallback")
            fallback

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }

    private fun extractDirectUrl(html: String, base: String): String? {
        // Common patterns for .m3u8
        val m3u8Regex = """["']([^"']*\.m3u8[^"']*)["']""".toRegex()
        m3u8Regex.find(html)?.let {
            var url = it.groupValues[1]
            if (!url.startsWith("http")) url = base + url
            return url
        }

        // Common patterns for .mp4
        val mp4Regex = """["']([^"']*\.mp4[^"']*)["']""".toRegex()
        mp4Regex.find(html)?.let {
            var url = it.groupValues[1]
            if (!url.startsWith("http")) url = base + url
            return url
        }

        return null
    }
}
