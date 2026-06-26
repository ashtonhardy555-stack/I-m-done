package com.mariocart.app.data.server

import android.util.Log
import com.mariocart.app.data.api.StreamingBackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StreamExtractor {

    private const val TAG = "StreamExtractor"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // TODO: Change this to your deployed backend URL
    private const val BACKEND_BASE = "https://your-backend.onrender.com"  // ← UPDATE THIS

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Try Backend First (recommended)
            val backendUrl = "$BACKEND_BASE/api/stream?tmdbId=$tmdbId&type=$contentType" +
                    (if (contentType.lowercase() == "tv") "&season=$season&episode=$episode" else "")

            Log.d(TAG, "Trying backend: $backendUrl")
            val backendResponse = try {
                val request = Request.Builder()
                    .url(backendUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Backend not reachable", e)
                null
            }

            if (backendResponse?.isSuccessful == true) {
                val body = backendResponse.body?.string() ?: ""
                backendResponse.close()
                // Simple parse for direct URL (adjust based on your backend response)
                if (body.contains(".m3u8") || body.contains(".mp4")) {
                    val direct = Regex("""(https?://[^\s"']+\.(m3u8|mp4)[^\s"']*)""").find(body)?.value
                    if (direct != null) {
                        Log.i(TAG, "✅ Backend direct stream: $direct")
                        return@withContext direct
                    }
                }
            } else {
                backendResponse?.close()
            }

            // 2. Fallback: Enhanced client-side for LookMovie2.to
            Log.d(TAG, "Backend fallback - trying LookMovie directly")
            val lookmovieUrl = if (contentType.lowercase() == "tv") {
                "https://www.lookmovie2.to/shows/play/$tmdbId/$season/$episode"
            } else {
                "https://www.lookmovie2.to/movies/play/$tmdbId"
            }

            val request = Request.Builder()
                .url(lookmovieUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Referer", "https://www.lookmovie2.to/")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                response.close()

                // Look for streams in LookMovie JS data
                val streamRegex = Regex("""["']?url["']?\s*:\s*["']([^"']+\.(m3u8|mp4))["']""", RegexOption.IGNORE_CASE)
                streamRegex.find(html)?.let {
                    var url = it.groupValues[1]
                    if (url.startsWith("//")) url = "https:$url"
                    Log.i(TAG, "✅ LookMovie direct found: $url")
                    return@withContext url
                }

                // Alternative: security API pattern or master.m3u8
                val masterRegex = Regex("""(https?://[^\s"']+master\.m3u8[^\s"']*)""")
                masterRegex.find(html)?.let {
                    Log.i(TAG, "✅ Master playlist: ${it.value}")
                    return@withContext it.value
                }
            } else {
                response.close()
            }

            Log.w(TAG, "No direct stream found for $tmdbId")
            null

        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed", e)
            null
        }
    }
}
