package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object StreamExtractor {

    private const val TAG = "StreamExtractor_LookMovie"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Referer" to "https://www.lookmovie2.to/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    // Only LookMovie — called from PlayerActivity
    suspend fun extract(tmdbId: Any, contentType: Any = "movie", season: Any = 1, episode: Any = 1): String? {
        val id = tmdbId.toString().toIntOrNull() ?: return null
        val isMovie = contentType.toString().lowercase().contains("movie")
        val s = season.toString().toIntOrNull() ?: 1
        val e = episode.toString().toIntOrNull() ?: 1
        return extractLookMovieOnly(id, isMovie, s, e)
    }

    private suspend fun extractLookMovieOnly(tmdbId: Int, isMovie: Boolean, season: Int, episode: Int): String? = withContext(Dispatchers.IO) {
        try {
            val base = "https://www.lookmovie2.to"
            val playUrl = if (isMovie) {
                "$base/movies/play/$tmdbId"
            } else {
                "$base/shows/play/$tmdbId/$season/$episode"
            }

            Log.d(TAG, "→ LookMovie Play URL: $playUrl")

            val request = Request.Builder().url(playUrl).apply {
                headers.forEach { (k, v) -> addHeader(k, v) }
            }.build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null
            val finalUrl = response.request.url.toString()

            if (isVerificationPage(html)) {
                Log.w(TAG, "Verification required")
                return@withContext finalUrl
            }

            // Extract storage data
            val storagePattern = if (isMovie) {
                """movie_storage["']\s*=\s*(\{.*?\});"""
            } else {
                """show_storage["']\s*=\s*(\{.*?\});"""
            }

            val matcher = Pattern.compile(storagePattern, Pattern.DOTALL).matcher(html)
            if (!matcher.find()) {
                Log.d(TAG, "Storage not found — fallback embed")
                return@withContext finalUrl
            }

            val storage = matcher.group(1)
            val hashMatcher = Pattern.compile("""hash["']?\s*:\s*["']([^"']+)""").matcher(storage)
            val idKey = if (isMovie) "id_movie" else "id_episode"
            val idMatcher = Pattern.compile("""$idKey["']?\s*:\s*(\d+)""").matcher(storage)

            if (hashMatcher.find() && idMatcher.find()) {
                val hash = hashMatcher.group(1)
                val itemId = idMatcher.group(1)
                val apiPath = if (isMovie) "movie-access" else "episode-access"
                val apiUrl = "$base/api/v1/security/$apiPath?$idKey=$itemId&hash=$hash"

                val apiRequest = Request.Builder().url(apiUrl).apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }.build()

                val apiResp = client.newCall(apiRequest).execute()
                val jsonStr = apiResp.body?.string() ?: return@withContext finalUrl

                val json = JSONObject(jsonStr)
                val streams = json.optJSONObject("streams") ?: json.optJSONObject("data")?.optJSONObject("streams")

                if (streams != null && streams.length() > 0) {
                    val directUrl = streams.getString(streams.keys().next())
                    Log.i(TAG, "✅ DIRECT STREAM: $directUrl")
                    return@withContext directUrl
                }
            }

            return@withContext finalUrl
        } catch (e: Exception) {
            Log.e(TAG, "LookMovie failed", e)
            return@withContext null
        }
    }

    private fun isVerificationPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("thread defence") || lower.contains("recaptcha") ||
               lower.contains("challenge") || lower.contains("verify you are human")
    }
}
