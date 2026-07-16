package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * VidLinkExtractor — resolves a **direct playable** stream URL from the
 * VidLink provider (`vidlink.pro`) using only plain HTTP (no WebView).
 *
 * ## How it works
 *
 * VidLink encrypts the TMDB id with a custom cipher hosted at `enc-dec.app`,
 * then exposes a JSON API that returns one or more direct stream URLs.
 *
 *  1. **Encrypt** the TMDB id:
 *     `GET https://enc-dec.app/api/enc-vidlink?text={tmdbId}`
 *     → `{ "status": 200, "result": "<encodedId>" }`
 *
 *  2. **Fetch the stream manifest**:
 *     - Movie: `GET https://vidlink.pro/api/b/movie/{encodedId}?multiLang=0`
 *     - TV:    `GET https://vidlink.pro/api/b/tv/{encodedId}/{season}/{episode}?multiLang=0`
 *
 * The current API shape is:
 *     `{ "stream": { "qualities": {
 *         "360": { "type": "mp4", "url": "https://…/file.mp4?headers={…}" },
 *         "480": { "type": "mp4", "url": "https://…/file.mp4?headers={…}" },
 *         "720": { "type": "mp4", "url": "https://…/file.mp4?headers={…}" }
 *       } } }`
 *
 * Each quality URL may carry an embedded `?headers={…}` query parameter
 * (URL-encoded JSON) that tells the client which `Referer` / `Origin` to
 * send when fetching the media. We parse that and forward it to ExoPlayer.
 *
 *  3. **Pick** the highest resolution quality whose URL looks playable.
 *
 *  4. **Verify** the chosen URL returns a real media response (2xx and a
 *     video-ish content-type, or an HLS manifest, or a byte-range-capable
 *     mp4). Verification is best-effort — on some network paths the media
 *     host returns 403 to HEAD/probe requests while still serving ExoPlayer,
 *     so a verification failure does NOT hard-block playback.
 *
 * This is a primary extractor (runs in the parallel race alongside VidStorm
 * and VidSrc) because it resolves a direct URL in ~2 HTTP round-trips with
 * no JS execution — fast and reliable on real user devices.
 */
object VidLinkExtractor {

    private const val TAG = "VidLink"

    private const val ENC_DEC_BASE = "https://enc-dec.app/api"
    private const val VIDLINK_BASE = "https://vidlink.pro"
    private const val REFERER = "https://vidlink.pro"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Separate short-timeout client for URL verification. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  Result type                                                          //
    // ═══════════════════════════════════════════════════════════════════════//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidLink"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  Public API                                                           //
    // ═══════════════════════════════════════════════════════════════════════//

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
        val type = if (contentType == "tv") "tv" else "movie"

        // Step 1: Encrypt the TMDB id via enc-dec.app.
        val encodedId = try {
            encryptTmdbId(tmdbId.toString())
        } catch (e: Exception) {
            Log.w(TAG, "enc-dec.app encryption failed: ${e.message}")
            return@withContext Result.Error("VidLink: encryption failed")
        }
        Log.d(TAG, "🔎 VidLink encoded id: $encodedId")

        // Step 2: Fetch the stream manifest from the VidLink API.
        val apiUrl = if (type == "tv") {
            "$VIDLINK_BASE/api/b/tv/$encodedId/$season/$episode?multiLang=0"
        } else {
            "$VIDLINK_BASE/api/b/movie/$encodedId?multiLang=0"
        }
        Log.d(TAG, "🔎 VidLink API: $apiUrl")

        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "VidLink API fetch failed: ${e.message}")
            return@withContext Result.Error("VidLink: API unreachable")
        }
        if (body.isBlank()) {
            Log.w(TAG, "VidLink API returned empty body")
            return@withContext Result.Error("VidLink: empty response")
        }

        // Step 3: Parse the best quality URL from the new `stream.qualities` shape.
        val (rawUrl, embeddedHeaders) = try {
            parseBestQuality(body)
        } catch (e: Exception) {
            Log.w(TAG, "VidLink JSON parse failed: ${e.message}")
            return@withContext Result.Error("VidLink: invalid response")
        }

        if (rawUrl.isNullOrBlank()) {
            Log.w(TAG, "VidLink: no stream URL in response")
            return@withContext Result.Error("VidLink: no stream found")
        }
        Log.d(TAG, "🔎 VidLink best URL: $rawUrl  headers=$embeddedHeaders")

        // Step 4: Build the final header set (embedded headers win, fallback to our own).
        val headers = buildHeaders(embeddedHeaders)
        val cleanUrl = stripHeadersParam(rawUrl)

        // Step 5: Verify (best-effort — a 403 probe doesn't kill playback).
        val verified = verifyMedia(cleanUrl, headers)
        if (!verified) {
            Log.w(TAG, "VidLink: verification non-2xx, but returning anyway (host may reject probes)")
        }

        Log.i(TAG, "✅ VidLink stream: $cleanUrl")
        Result.Stream(
            url = cleanUrl,
            headers = headers,
            providerName = "VidLink"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  Response parsing — new `stream.qualities` shape                      //
    // ═══════════════════════════════════════════════════════════════════════//

    /**
     * Parse the VidLink API JSON and return the best (highest-resolution)
     * playable URL plus any embedded headers parsed from a `?headers={…}`
     * query parameter.
     *
     * Supports both the new shape:
     *   `{ "stream": { "qualities": { "720": { "type":"mp4", "url":"…" } } } }`
     * and falls back to the legacy single-URL shapes:
     *   `{ "stream": { "playlist": "…" } }`  /  `{ "playlist": "…" }`
     */
    private fun parseBestQuality(body: String): Pair<String?, Map<String, String>> {
        val json = JSONObject(body)
        val stream = json.optJSONObject("stream")

        // New shape: stream.qualities is a dict keyed by resolution string.
        val qualities = stream?.optJSONObject("qualities")
        if (qualities != null) {
            val best = qualities.keys().asSequence()
                .mapNotNull { key ->
                    val q = qualities.optJSONObject(key) ?: return@mapNotNull null
                    val u = q.optString("url")
                    if (u.isBlank()) null else key.toIntOrNull() to u
                }
                .filter { it.first != null }
                .sortedByDescending { it.first!! }
                .map { it.second }
                .firstOrNull { url -> looksPlayable(url) }
            if (!best.isNullOrBlank()) {
                return best to parseEmbeddedHeaders(best)
            }
        }

        // Legacy fallback: stream.playlist or top-level playlist (HLS).
        val legacy = stream?.optString("playlist")?.takeIf { it.isNotBlank() }
            ?: json.optString("playlist")?.takeIf { it.isNotBlank() }
        if (!legacy.isNullOrBlank()) {
            return legacy to parseEmbeddedHeaders(legacy)
        }

        return null to emptyMap()
    }

    /** Quick heuristic — accept .mp4 / .m3u8 / .mkv URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") ||
            lower.contains(".mkv") || lower.contains(".m4v") ||
            lower.contains("/resource/") || lower.contains("/stream/")
    }

    /**
     * Some VidLink URLs embed required request headers as a URL-encoded JSON
     * query param, e.g. `…?headers={"referer":"https://filmboom.top/","origin":"https://filmboom.top"}`.
     * Parse that into a header map (keys normalized to canonical casing).
     */
    private fun parseEmbeddedHeaders(url: String): Map<String, String> {
        return try {
            val qi = url.indexOf("?headers=")
            if (qi < 0) return emptyMap()
            val rawParam = url.substring(qi + "?headers=".length)
            // The value may itself contain further query params after it; take up to
            // the next "&" that isn't part of the JSON. In practice VidLink appends
            // nothing after headers, so just URL-decode the tail.
            val decoded = java.net.URLDecoder.decode(rawParam, "UTF-8")
            val json = JSONObject(decoded)
            val out = linkedMapOf<String, String>()
            for (key in json.keys()) {
                val v = json.optString(key)
                if (v.isNotBlank()) {
                    // Normalize common header names.
                    val norm = when (key.lowercase()) {
                        "referer" -> "Referer"
                        "origin" -> "Origin"
                        "user-agent" -> "User-Agent"
                        else -> key
                    }
                    out[norm] = v
                }
            }
            out
        } catch (e: Exception) {
            Log.d(TAG, "parseEmbeddedHeaders: ${e.message}")
            emptyMap()
        }
    }

    /** Remove the `?headers=…` param from the URL before handing to ExoPlayer. */
    private fun stripHeadersParam(url: String): String {
        val qi = url.indexOf("?headers=")
        if (qi < 0) return url
        return url.substring(0, qi)
    }

    /** Build the final ExoPlayer header set: embedded headers + UA fallback. */
    private fun buildHeaders(embedded: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["User-Agent"] = USER_AGENT
        if ("Referer" !in embedded) out["Referer"] = REFERER
        if ("Origin" !in embedded) out["Origin"] = VIDLINK_BASE
        out.putAll(embedded) // embedded win over defaults
        if ("User-Agent" !in embedded) out["User-Agent"] = USER_AGENT
        return out
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  Encryption (delegated to enc-dec.app)                                //
    // ═══════════════════════════════════════════════════════════════════════//

    /**
     * Calls the enc-dec.app API to encrypt the TMDB id in VidLink's custom
     * format. Returns the encoded string, or throws on failure.
     */
    private fun encryptTmdbId(tmdbId: String): String {
        val encUrl = "$ENC_DEC_BASE/enc-vidlink?text=${URLEncoder.encode(tmdbId, "UTF-8")}"
        val req = Request.Builder()
            .url(encUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: throw java.io.IOException("empty body")
            val json = JSONObject(body)
            val result = json.optString("result")
            if (result.isNullOrBlank()) {
                throw java.io.IOException("enc-dec.app returned no result")
            }
            return result
        }
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  Verification                                                         //
    // ═══════════════════════════════════════════════════════════════════════//

    /**
     * Best-effort media verification: a small GET that ideally returns 2xx
     * with a video/manifest content type. Returns false on 403/429/5xx or
     * connection error — but the caller still returns the URL because some
     * media hosts reject probe requests while serving range requests fine.
     */
    private fun verifyMedia(url: String, headers: Map<String, String>): Boolean {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.get()
            verifier.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                Log.d(TAG, "VidLink verify: HTTP $code  ct=$ct")
                if (code !in 200..299) return false
                val body = resp.body?.string().orEmpty()
                val isHls = body.contains("#EXTM3U")
                val isVideo = ct.contains("video") || ct.contains("mp4") ||
                    ct.contains("mpegurl") || ct.contains("octet-stream")
                isHls || isVideo
            }
        } catch (e: Exception) {
            Log.d(TAG, "VidLink verify: connection failed (${e.message})")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════//
    //  HTTP helper                                                          //
    // ═══════════════════════════════════════════════════════════════════════//

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", REFERER)
            .header("Origin", VIDLINK_BASE)
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
