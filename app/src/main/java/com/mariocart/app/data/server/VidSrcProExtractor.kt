package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VidSrcProExtractor — resolves a direct playable HLS (m3u8) stream URL
 * from the VidSrc.pro JSON API.
 *
 * ## How it works
 * VidSrc.pro exposes a simple JSON API that returns HLS playlist URLs
 * directly (no WebView, no JS execution needed):
 *
 *  - Movie:  `GET https://vidsrc.pro/api/movie/{tmdbId}`
 *  - TV:     `GET https://vidsrc.pro/api/tv/{tmdbId}/{season}/{episode}`
 *
 * The response is JSON containing a `sources` array (or a `url` field)
 * with direct m3u8 URLs:
 *   `{ "sources": [ { "url": "https://.../playlist.m3u8", "quality": "1080" } ] }`
 *   — or —
 *   `{ "url": "https://.../playlist.m3u8" }`
 *
 * We pick the highest quality source and return it as a direct playable
 * stream. This is a primary extractor (runs in the parallel race) because
 * it's a single JSON round-trip with no JS execution.
 */
object VidSrcProExtractor {

    private const val TAG = "VidSrcPro"
    private const val API_BASE = "https://vidsrc.pro/api"
    private const val REFERER = "https://vidsrc.pro"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidSrcPro"
        ) : Result()

        data class Error(val message: String) : Result()
    }

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val type = if (contentType == "tv") "tv" else "movie"
        val apiUrl = if (type == "tv") {
            "$API_BASE/tv/$tmdbId/$season/$episode"
        } else {
            "$API_BASE/movie/$tmdbId"
        }
        Log.d(TAG, "API: $apiUrl")

        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "API fetch failed: ${e.message}")
            return@withContext Result.Error("VidSrcPro: API unreachable")
        }
        if (body.isBlank()) return@withContext Result.Error("VidSrcPro: empty response")

        val streamUrl = try {
            parseStreamUrl(body)
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed: ${e.message}")
            return@withContext Result.Error("VidSrcPro: invalid response")
        }

        if (streamUrl.isNullOrBlank()) {
            Log.w(TAG, "No stream URL in response")
            return@withContext Result.Error("VidSrcPro: no stream found")
        }

        Log.i(TAG, "VidSrcPro stream: $streamUrl")
        Result.Stream(
            url = streamUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "Origin" to REFERER
            ),
            providerName = "VidSrcPro"
        )
    }

    /**
     * Parse the VidSrc.pro API JSON and return the best playable stream URL.
     * Supports multiple response shapes:
     *   1. `{ "sources": [ { "url": "...", "quality": "1080" }, ... ] }`
     *   2. `{ "url": "..." }`
     *   3. `{ "data": { "url": "..." } }`
     */
    private fun parseStreamUrl(body: String): String? {
        val json = JSONObject(body)

        // Shape 1: sources array
        val sources = json.optJSONArray("sources")
        if (sources != null && sources.length() > 0) {
            val candidates = mutableListOf<Pair<Int, String>>()
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val url = src.optString("url").ifBlank { src.optString("file") }
                if (url.isBlank()) continue
                val quality = src.optString("quality", "0")
                    .replace(Regex("[^0-9]"), "")
                    .toIntOrNull() ?: 0
                if (looksPlayable(url)) {
                    candidates.add(quality to url)
                }
            }
            // Pick highest quality
            return candidates.sortedByDescending { it.first }.firstOrNull()?.second
        }

        // Shape 2: top-level url
        val directUrl = json.optString("url").ifBlank { json.optString("link") }
        if (!directUrl.isNullOrBlank() && looksPlayable(directUrl)) return directUrl

        // Shape 3: nested data.url
        val data = json.optJSONObject("data")
        val dataUrl = data?.optString("url")?.ifBlank { data?.optString("link") }
        if (!dataUrl.isNullOrBlank() && looksPlayable(dataUrl)) return dataUrl

        return null
    }

    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains(".mkv") || lower.contains("hls") ||
            lower.contains("/stream/") || lower.contains("/playlist")
    }

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .header("Referer", REFERER)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }
}
