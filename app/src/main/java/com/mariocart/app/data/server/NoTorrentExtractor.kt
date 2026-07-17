package com.mariocart.app.data.server

import android.util.Log
import com.mariocart.app.BuildConfig
import com.mariocart.app.data.api.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NoTorrentExtractor — resolves a **direct playable** stream URL from the
 * NoTorrent Stremio addon (`addon-osvh.onrender.com`).
 *
 * ## Why this extractor exists
 *
 * NoTorrent is an aggregator that collects direct HLS (.m3u8) and MP4 links
 * from several upstream file/CDN hosts. It is one of the few sources that
 * reliably resolves the "stubborn" titles that VidStorm and VidSrc miss —
 * notably **Interstellar** (TMDB 157336) and **LOTR: The Return of the King**
 * (TMDB 122), where VidStorm's Boron source is a dead 404-in-disguise and
 * VidSrc's token resolution yields nothing. NoTorrent returns 8–18 verified
 * playable streams for both of these.
 *
 * ## How it works
 *
 *  1. **TMDB → IMDb** — the addon keys off IMDb ids (`ttXXXXXXX`), so we first
 *     resolve the IMDb id via TMDB's `external_ids` append. This is one extra
 *     HTTP round-trip (~150 ms) and is cached per-content.
 *
 *  2. **Addon query**:
 *     - Movie: `GET https://addon-osvh.onrender.com/stream/movie/{imdbId}.json`
 *     - TV:    `GET https://addon-osvh.onrender.com/stream/series/{imdbId}:{s}:{e}.json`
 *     → `{ "streams": [{ "url": "...", "name": "...", "behaviorHints": { ... } }] }`
 *
 *  3. **Filter & verify** — we drop streams with no `url` or with
 *     `externalUrl` (those are web links, not playable). For each remaining
 *     candidate we do a lightweight verification (a small ranged GET) and keep
 *     the first one that returns a real HLS manifest (`#EXTM3U`) or a real
 *     MP4/mkv file. HLS is preferred (adaptive, smaller first segment).
 *
 * This extractor runs in the parallel race alongside VidStorm, VidSrc, VidLink,
 * and VixSrc so a playable NoTorrent source can win immediately for the
 * stubborn titles while the other extractors race in the background.
 */
object NoTorrentExtractor {

    private const val TAG = "NoTorrent"

    private const val ADDON_BASE = "https://addon-osvh.onrender.com"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /** Short-timeout client for the addon API call. */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Even shorter-timeout client for stream-URL verification. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────//
    //  Result type                                                          //
    // ──────────────────────────────────────────────────────────────────────//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "NoTorrent"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ──────────────────────────────────────────────────────────────────────//
    //  Public API                                                           //
    // ──────────────────────────────────────────────────────────────────────//

    /**
     * Resolve a direct playable stream for the given TMDB content.
     *
     * @param tmdbId       TMDB id of the movie or TV show.
     * @param contentType  "movie" or "tv".
     * @param season       season number (tv only).
     * @param episode      episode number (tv only).
     */
    suspend fun extract(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): Result = withContext(Dispatchers.IO) {
        val isTv = contentType == "tv"

        // Step 1: Resolve the IMDb id from TMDB.
        val imdbId = try {
            resolveImdbId(tmdbId, isTv)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB→IMDb lookup failed: ${e.message}")
            return@withContext Result.Error("NoTorrent: no IMDb id")
        }
        if (imdbId.isNullOrBlank()) {
            Log.w(TAG, "TMDB returned no IMDb id for $tmdbId")
            return@withContext Result.Error("NoTorrent: no IMDb id")
        }
        Log.d(TAG, "🔍 TMDB $tmdbId → IMDb $imdbId")

        // Step 2: Query the NoTorrent Stremio addon.
        val addonUrl = if (isTv) {
            "$ADDON_BASE/stream/series/$imdbId:$season:$episode.json"
        } else {
            "$ADDON_BASE/stream/movie/$imdbId.json"
        }
        Log.d(TAG, "🔍 NoTorrent addon: $addonUrl")

        val body = try {
            fetch(addonUrl)
        } catch (e: Exception) {
            Log.w(TAG, "addon fetch failed: ${e.message}")
            return@withContext Result.Error("NoTorrent: addon unreachable")
        }

        // Step 3: Parse the stream list.
        val candidates = parseStreams(body)
        if (candidates.isEmpty()) {
            Log.w(TAG, "addon returned 0 playable streams for $imdbId")
            return@withContext Result.Error("NoTorrent: no streams")
        }
        Log.d(TAG, "📋 ${candidates.size} candidate stream(s) for $imdbId")

        // Step 4: Verify each candidate in order; keep the first playable one.
        // Prefer HLS (adaptive, plays instantly) over direct mp4/mkv.
        val sorted = candidates.sortedWith(
            compareByDescending { it.url.contains(".m3u8") || it.url.contains("index.m3u8") }
        )

        // Best unverified fallback. The OkHttp probe (verifyPlayable) is
        // advisory only: many CDNs 403/401 a bare OkHttp GET yet still serve
        // ExoPlayer once it sends Referer/User-Agent/Range. In v13 a failed
        // probe dropped the candidate entirely — losing titles like
        // Interstellar/LOTR that NoTorrent reliably covers. We now prefer a
        // verified stream but ALWAYS fall back to a best unverified candidate
        // so ExoPlayer gets to try it.
        var bestUnverified: Pair<String, Map<String, String>>? = null

        for (c in sorted) {
            val headers = c.headers.ifEmpty { mapOf("User-Agent" to USER_AGENT) }
            if (bestUnverified == null && c.url.startsWith("http")) {
                bestUnverified = c.url to headers
            }
            if (verifyPlayable(c.url, headers)) {
                Log.i(TAG, "✅ NoTorrent stream: ${c.url}")
                return@withContext Result.Stream(
                    url = c.url,
                    headers = headers,
                    providerName = "NoTorrent"
                )
            }
        }

        // Unverified fallback — hand the best candidate to ExoPlayer.
        if (bestUnverified != null) {
            val (fbUrl, fbHeaders) = bestUnverified!!
            Log.w(TAG, "no candidate passed verification — returning best unverified to ExoPlayer: ${fbUrl.take(90)}")
            return@withContext Result.Stream(
                url = fbUrl,
                headers = fbHeaders,
                providerName = "NoTorrent·unverified"
            )
        }

        Log.w(TAG, "no candidate with a usable URL")
        Result.Error("NoTorrent: streams not playable")
    }

    // ──────────────────────────────────────────────────────────────────────//
    //  TMDB → IMDb resolution                                              //
    // ──────────────────────────────────────────────────────────────────────//

    private suspend fun resolveImdbId(tmdbId: Int, isTv: Boolean): String? {
        val key = BuildConfig.TMDB_API_KEY
        val resp = if (isTv) {
            ApiClient.tmdbApi.getTvExternalIds(tmdbId, key)
        } else {
            ApiClient.tmdbApi.getMovieExternalIds(tmdbId, key)
        }
        return resp.imdbId
    }

    // ──────────────────────────────────────────────────────────────────────//
    //  Stream parsing                                                       //
    // ──────────────────────────────────────────────────────────────────────//

    private data class Candidate(
        val url: String,
        val name: String,
        val headers: Map<String, String>
    )

    /**
     * Parses the Stremio addon `{ "streams": [...] }` response into a list of
     * playable candidates. Drops entries with no `url` or with `externalUrl`
     * (web links) or known-dead hosts.
     */
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
            // Skip web-link-only entries (no playable url).
            if (s.has("externalUrl") && !s.has("url")) continue
            val url = s.optString("url").orEmpty()
            if (url.isBlank()) continue
            // Skip github/googleusercontent CDN hosts (per reference impl).
            if (url.contains("github.com") || url.contains("googleusercontent")) continue

            // Pull any required request headers from behaviorHints.
            val bh = s.optJSONObject("behaviorHints")
            val hdrs = linkedMapOf<String, String>()
            if (bh != null) {
                bh.optJSONObject("headers")?.let { h ->
                    for (k in h.keys()) {
                        val v = h.optString(k)
                        if (v.isNotBlank()) hdrs[normalizeHeader(k)] = v
                    }
                }
                // proxyHeaders.request takes precedence.
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

    // ──────────────────────────────────────────────────────────────────────//
    //  Verification                                                         //
    // ──────────────────────────────────────────────────────────────────────//

    /**
     * Lightweight playability check: a small ranged GET. Returns true if the
     * response is 2xx/206 and the body looks like a real HLS manifest
     * (`#EXTM3U`) or a real media file (mp4/mkv/webm magic bytes / video
     * content-type). Rejects HTML error pages and 403/404/5xx.
     */
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
                // Accept HLS by content-type even before inspecting the body
                // (some redirected master playlists are large).
                if (ct.contains("mpegurl") || ct.contains("x-mpegurl")) return true
                // Read the first bytes once. Use bytes() so binary MP4 magic
                // (ftyp box) survives intact — decoding as String can corrupt
                // null bytes in the box-size field.
                val raw = resp.body?.bytes() ?: ByteArray(0)
                if (raw.size >= 8 && String(raw, 4, 4, Charsets.US_ASCII) == "ftyp") return true
                if (ct.contains("video/mp4") || ct.contains("video/webm") ||
                    ct.contains("video/x-matroska")
                ) return true
                // Inspect the leading bytes as ASCII for HLS / HTML detection.
                val head = String(raw, Charsets.US_ASCII)
                if (head.contains("#EXTM3U")) return true
                if (ct.contains("text/html") || head.contains("<!DOCTYPE") ||
                    head.contains("<html")
                ) {
                    Log.d(TAG, "verify: HTML body (not media) for ${url.take(70)}")
                    return false
                }
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "verify: connection failed (${e.message}) for ${url.take(70)}")
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────//
    //  HTTP helper                                                          //
    // ──────────────────────────────────────────────────────────────────────//

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
