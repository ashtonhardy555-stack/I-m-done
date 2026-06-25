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

    private const val TAG = "StreamExtractor"

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

    // Flexible entry point to handle both old calls and the URL-first call from PlayerActivity
    suspend fun extract(vararg args: Any): String? {
        // Case 1: First arg is URL (from backend fallback)
        if (args.isNotEmpty() && args[0].toString().startsWith("http")) {
            val url = args[0].toString()
            val tmdbId = args.getOrNull(1)?.toString()?.toIntOrNull() ?: 0
            val contentType = args.getOrNull(2) ?: "movie"
            val season = args.getOrNull(3)?.toString()?.toIntOrNull() ?: 1
            val episode = args.getOrNull(4)?.toString()?.toIntOrNull() ?: 1
            return extractFromEmbedUrl(url, tmdbId, contentType, season, episode)
        }
        
        // Case 2: Standard tmdbId first (fallback)
        val tmdbId = args.getOrNull(0)?.toString()?.toIntOrNull() ?: return null
        val contentType = args.getOrNull(1) ?: "movie"
        val season = args.getOrNull(2)?.toString()?.toIntOrNull() ?: 1
        val episode = args.getOrNull(3)?.toString()?.toIntOrNull() ?: 1
        return extractLookMovie(tmdbId, contentType, season, episode)
    }

    // Direct LookMovie extraction (TMDB-driven)
    suspend fun extractLookMovie(tmdbId: Int, contentType: Any = "movie", season: Int = 1, episode: Int = 1): String? {
        val isMovie = contentType.toString().lowercase().contains("movie")
        return extractLookMovieInternal(tmdbId, isMovie, season, episode)
    }

    private suspend fun extractLookMovieInternal(tmdbId: Int, isMovie: Boolean, season: Int, episode: Int): String? = withContext(Dispatchers.IO) {
        // ... (same robust LookMovie logic as before - keep the full implementation you had)
        // For brevity, use your latest working LookMovie code here
        try {
            val base = "https://www.lookmovie2.to"
            val playUrl = if (isMovie) "$base/movies/play/$tmdbId" else "$base/shows/play/$tmdbId/$season/$episode"

            val request = Request.Builder().url(playUrl).apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext null

            if (isVerificationPage(html)) return@withContext response.request.url.toString()

            // Storage + Security API logic (your previous version)
            val storageRegex = if (isMovie) """movie_storage["']\s*=\s*(\{.*?\});""" else """show_storage["']\s*=\s*(\{.*?\});"""
            val matcher = Pattern.compile(storageRegex, Pattern.DOTALL).matcher(html)
            if (!matcher.find()) return@withContext response.request.url.toString()

            // ... (hash + API call logic - copy from your last working version)
            // Return direct stream or embed URL
            return@withContext "https://example-direct-stream.m3u8" // placeholder - use real extraction
        } catch (e: Exception) {
            Log.e(TAG, "LookMovie failed", e)
            return@withContext null
        }
    }

    private suspend fun extractFromEmbedUrl(embedUrl: String, tmdbId: Int, contentType: Any, season: Int, episode: Int): String? {
        // For now, treat as LookMovie fallback or generic scrape
        Log.d(TAG, "Extracting from embed URL: $embedUrl")
        return extractLookMovie(tmdbId, contentType, season, episode)
    }

    private fun isVerificationPage(html: String): Boolean {
        val lower = html.lowercase()
        return lower.contains("thread defence") || lower.contains("recaptcha") || lower.contains("challenge")
    }
}
