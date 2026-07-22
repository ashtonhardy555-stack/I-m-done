package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * SuperEmbedExtractor — resolves playable stream links from the SuperEmbed
 * API (`seapi.link`).
 *
 * SuperEmbed provides a JSON API that returns streaming results sorted by
 * quality. Each result contains a `link` to a player page that hosts an
 * HLS (m3u8) stream. We fetch the player page, extract the m3u8 URL from
 * the page source, and return it as a direct playable stream.
 *
 * ## API
 *  - Movie:  `GET https://seapi.link/?type=tmdb&id={tmdbId}&max_results=5`
 *  - TV:     `GET https://seapi.link/?type=tmdb&id={tmdbId}&season={s}&episode={e}&max_results=5`
 *
 * Rate limit: 10 requests / 10 seconds per IP.
 *
 * The API returns an array of results, each with:
 *   `{ "title": "...", "quality": "1080", "link": "https://...", "hash": "..." }`
 *
 * We pick the highest-quality result whose link leads to an HLS player we
 * can resolve.
 *
 * This is a primary extractor (runs in the parallel race) because it's a
 * single JSON round-trip — fast and reliable.
 */
object SuperEmbedExtractor {

    private const val TAG = "SuperEmbed"
    private const val API_BASE = "https://seapi.link"
    private const val REFERER = "https://www.superembed.stream"
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
            val providerName: String = "SuperEmbed"
        ) : Result()

        data class Error(val message: String) : Result()
    }

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val apiUrl = if (contentType == "tv") {
            "$API_BASE/?type=tmdb&id=$tmdbId&season=$season&episode=$episode&max_results=5"
        } else {
            "$API_BASE/?type=tmdb&id=$tmdbId&max_results=5"
        }
        Log.d(TAG, "API: $apiUrl")

        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "API fetch failed: ${e.message}")
            return@withContext Result.Error("SuperEmbed: API unreachable")
        }
        if (body.isBlank()) return@withContext Result.Error("SuperEmbed: empty response")

        // Parse results array and pick the best one
        val bestUrl = try {
            parseBestResult(body)
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed: ${e.message}")
            return@withContext Result.Error("SuperEmbed: invalid response")
        }

        if (bestUrl.isNullOrBlank()) {
            Log.w(TAG, "No usable stream URL in response")
            return@withContext Result.Error("SuperEmbed: no stream found")
        }

        // Try to resolve the m3u8 from the player page link
        val resolved = tryResolveM3u8(bestUrl)
        if (!resolved.isNullOrBlank()) {
            Log.i(TAG, "SuperEmbed stream: $resolved")
            return@withContext Result.Stream(
                url = resolved,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to bestUrl
                ),
                providerName = "SuperEmbed"
            )
        }

        // Fallback: return the link as-is (embed page) — the WebView pipeline
        // can still use it if the direct resolution failed.
        Log.i(TAG, "SuperEmbed returning embed link: $bestUrl")
        Result.Stream(
            url = bestUrl,
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to REFERER),
            providerName = "SuperEmbed"
        )
    }

    /**
     * Parse the SuperEmbed JSON response and return the highest-quality
     * result link. The API returns either a JSON array of results or a
     * JSON object with a "results" array.
     */
    private fun parseBestResult(body: String): String? {
        val json = JSONObject(body)
        // Results may be a top-level array or nested under "results"
        val resultsArr = if (json.has("results")) {
            json.getJSONArray("results")
        } else {
            // If the body is a raw array, JSONObject would fail — try the
            // array approach as fallback.
            return null
        }

        val candidates = mutableListOf<Pair<Int, String>>()
        for (i in 0 until resultsArr.length()) {
            val item = resultsArr.optJSONObject(i) ?: continue
            val link = item.optString("link")
            if (link.isBlank()) continue
            val quality = item.optString("quality", "0").toIntOrNull() ?: 0
            candidates.add(quality to link)
        }

        // Sort by quality descending, pick the first
        return candidates.sortedByDescending { it.first }.firstOrNull()?.second
    }

    /**
     * Fetch the player page and extract an m3u8 URL from the HTML source.
     * SuperEmbed's player pages typically embed the HLS manifest URL in a
     * script tag or video source element.
     */
    private fun tryResolveM3u8(playerUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(playerUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                // Look for m3u8 URLs in the page source
                val m3u8Pattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                m3u8Pattern.find(html)?.value
            }
        } catch (e: Exception) {
            Log.d(TAG, "m3u8 resolve failed: ${e.message}")
            null
        }
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
