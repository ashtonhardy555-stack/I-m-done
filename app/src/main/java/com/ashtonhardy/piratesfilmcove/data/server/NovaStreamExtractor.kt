package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import com.ashtonhardy.piratesfilmcove.BuildConfig
import com.ashtonhardy.piratesfilmcove.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NovaStreamExtractor — resolves a **direct playable** stream URL from a
 * Stremio addon that aggregates direct HLS/MP4 links from multiple upstream
 * CDN hosts and exposes them through the standard Stremio addon stream API.
 *
 * ## Why this extractor exists
 *
 * This is a **headless, pure-OkHttp** extractor — the same approach as the
 * LookMovieHeadlessExtractor and NoTorrentExtractor. It runs entirely on
 * background coroutines with no WebView, no Kodi runtime, and no JS execution.
 * By querying yet another independent Stremio addon, it provides additional
 * stream coverage that complements the existing extractors in the parallel
 * race. More independent sources means more titles resolved and faster
 * playback for the user.
 *
 * ## How it works
 *
 *  1. **TMDB → IMDb** — the addon keys off IMDb ids, so we first resolve the
 *     IMDb id via TMDB's `external_ids` append.
 *
 *  2. **Addon query**:
 *     - Movie: `GET https://novastream.fetchtv.stream/stream/movie/{imdbId}.json`
 *     - TV:    `GET https://novastream.fetchtv.stream/stream/series/{imdbId}:{s}:{e}.json`
 *     → `{ "streams": [{ "url": "...", "name": "...", "behaviorHints": { ... } }] }`
 *
 *  3. **Filter & verify** — drop streams with no `url` or with `externalUrl`.
 *     Keep the first playable candidate, preferring HLS over MP4.
 *
 * This extractor runs in the parallel race alongside the other direct
 * extractors, providing additional independent stream coverage.
 */
object NovaStreamExtractor {

    private const val TAG = "NovaStream"

    // NovaStream Stremio addon endpoint.
    private const val ADDON_BASE = "https://novastream.fetchtv.stream"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val verifier by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Result type                                                          //
    // ─────────────────────────────────────────────────────────────────────//

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "NovaStream"
        ) : Result()

        data class Error(val message: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Public API                                                           //
    // ─────────────────────────────────────────────────────────────────────//

    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val isTv = contentType == "tv"

        val imdbId = try {
            resolveImdbId(tmdbId, isTv)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB→IMDb lookup failed: ${e.message}")
            return@withContext Result.Error("NovaStream: no IMDb id")
        }
        if (imdbId.isNullOrBlank()) {
            Log.w(TAG, "TMDB returned no IMDb id for $tmdbId")
            return@withContext Result.Error("NovaStream: no IMDb id")
        }
        Log.d(TAG, "🔍 TMDB $tmdbId → IMDb $imdbId")

        val addonUrl = if (isTv) {
            "$ADDON_BASE/stream/series/$imdbId:$season:$episode.json"
        } else {
            "$ADDON_BASE/stream/movie/$imdbId.json"
        }
        Log.d(TAG, "🔍 NovaStream addon: $addonUrl")

        val body = try {
            fetch(addonUrl)
        } catch (e: Exception) {
            Log.w(TAG, "addon fetch failed: ${e.message}")
            return@withContext Result.Error("NovaStream: addon unreachable")
        }

        val candidates = parseStreams(body)
        if (candidates.isEmpty()) {
            Log.w(TAG, "addon returned 0 playable streams for $imdbId")
            return@withContext Result.Error("NovaStream: no streams")
        }
        Log.d(TAG, "🎲 ${candidates.size} candidate stream(s) for $imdbId")

        val sorted = candidates.sortedWith(
            compareByDescending { it.url.contains(".m3u8") || it.url.contains("index.m3u8") }
        )

        var bestUnverified: Pair<String, Map<String, String>>? = null

        for (c in sorted) {
            val headers = c.headers.ifEmpty { mapOf("User-Agent" to USER_AGENT) }
            if (bestUnverified == null && c.url.startsWith("http")) {
                bestUnverified = c.url to headers
            }
            if (verifyPlayable(c.url, headers)) {
                Log.i(TAG, "✅ NovaStream stream: ${c.url}")
                return@withContext Result.Stream(
                    url = c.url,
                    headers = headers,
                    providerName = "NovaStream"
                )
            }
        }

        if (bestUnverified != null) {
            val (fbUrl, fbHeaders) = bestUnverified!!
            Log.w(TAG, "no candidate passed verification — returning best unverified: ${fbUrl.take(90)}")
            return@withContext Result.Stream(
                url = fbUrl,
                headers = fbHeaders,
                providerName = "NovaStream·unverified"
            )
        }

        Log.w(TAG, "no candidate with a usable URL")
        Result.Error("NovaStream: streams not playable")
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  TMDB → IMDb resolution                                              //
    // ─────────────────────────────────────────────────────────────────────//

    private suspend fun resolveImdbId(tmdbId: Int, isTv: Boolean): String? {
        val key = BuildConfig.TMDB_API_KEY
        val resp = if (isTv) {
            ApiClient.tmdbApi.getTvExternalIds(tmdbId, key)
        } else {
            ApiClient.tmdbApi.getMovieExternalIds(tmdbId, key)
        }
        return resp.imdbId
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Stream parsing                                                       //
    // ─────────────────────────────────────────────────────────────────────//

    private data class Candidate(
        val url: String,
        val name: String,
        val headers: Map<String, String>
    )

    private fun parseStreams(body: String): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "addon JSON parse failed: ${e.message}")
            return emptyList()
        }
        val streams = json.optJSONArray("streams") ?: return emptyList()
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            if (s.has("externalUrl") && !s.has("url")) continue
            val url = s.optString("url").orEmpty()
            if (url.isBlank()) continue
            if (url.contains("github.com") || url.contains("googleusercontent")) continue

            val bh = s.optJSONObject("behaviorHints")
            val hdrs = linkedMapOf<String, String>()
            if (bh != null) {
                bh.optJSONObject("headers")?.let { h ->
                    for (k in h.keys()) {
                        val v = h.optString(k)
                        if (v.isNotBlank()) hdrs[normalizeHeader(k)] = v
                    }
                }
                bh.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                    for (k in h.keys()) {
                        val v = h.optString(k)
                        if (v.isNotBlank()) hdrs[normalizeHeader(k)] = v
                    }
                }
            }
            out.add(Candidate(url = url, name = s.optString("name"), headers = hdrs))
        }
        return out
    }

    private fun normalizeHeader(key: String): String = when (key.lowercase()) {
        "referer" -> "Referer"
        "origin" -> "Origin"
        "user-agent" -> "User-Agent"
        "range" -> "Range"
        else -> key
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Verification                                                         //
    // ─────────────────────────────────────────────────────────────────────//

    private fun verifyPlayable(url: String, headers: Map<String, String>): Boolean {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (!headers.containsKey("Range")) builder.header("Range", "bytes=0-2047")
            builder.get()
            verifier.newCall(builder.build()).execute().use { resp ->
                if (resp.code != 200 && resp.code != 206) {
                    Log.d(TAG, "verify: HTTP ${resp.code} for ${url.take(70)}")
                    return false
                }
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("mpegurl") || ct.contains("x-mpegurl")) return true
                val raw = resp.body?.bytes() ?: ByteArray(0)
                if (raw.size >= 8 && String(raw, 4, 4, Charsets.US_ASCII) == "ftyp") return true
                if (ct.contains("video/mp4") || ct.contains("video/webm") ||
                    ct.contains("video/x-matroska")
                ) return true
                val head = String(raw, Charsets.US_ASCII)
                if (head.contains("#EXTM3U")) return true
                if (ct.contains("text/html") || head.contains("<!DOCTYPE") ||
                    head.contains("<html")
                ) {
                    return false
                }
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "verify: connection failed (${e.message}) for ${url.take(70)}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  HTTP helper                                                          //
    // ─────────────────────────────────────────────────────────────────────//

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }
}
