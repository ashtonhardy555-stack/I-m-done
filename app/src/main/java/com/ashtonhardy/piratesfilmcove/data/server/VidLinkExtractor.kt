package com.ashtonhardy.piratesfilmcove.data.server

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
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Separate short-timeout client for URL verification. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
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

        // DASH/HLS alternate manifest (stream.alternates.dash.playlist etc).
        // ExoPlayer plays DASH natively and it is often more reliable than the
        // raw mp4 qualities, so use it as a fallback when qualities are empty.
        val alternates = stream?.optJSONObject("alternates")
        if (alternates != null) {
            val dash = alternates.optJSONObject("dash")
            val dashUrl = dash?.optString("playlist")?.takeIf { it.isNotBlank() }
            if (!dashUrl.isNullOrBlank() && looksPlayable(dashUrl)) {
                return dashUrl to parseEmbeddedHeaders(dashUrl)
            }
            val hlsAlt = alternates.optJSONObject("hls")
            val hlsUrl = hlsAlt?.optString("playlist")?.takeIf { it.isNotBlank() }
            if (!hlsUrl.isNullOrBlank() && looksPlayable(hlsUrl)) {
                return hlsUrl to parseEmbeddedHeaders(hlsUrl)
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

    /** Quick heuristic — accept .mp4 / .m3u8 / .mkv / .mpd URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8") ||
            lower.contains(".mkv") || lower.contains(".m4v") ||
            lower.contains(".mpd") || lower.contains("dash") ||
            lower.contains("/resource/") || lower.contains("/stream/")
    }

    /**
     * Some VidLink URLs embed required request headers as a URL-encoded JSON
     * query param, e.g.
     *   `…?headers={"referer":"https://filmboom.top/","origin":"https://filmboom.top"}&host=https://bcdnxw.hakunaymatata.com&sign=…&t=…`
     *
     * CRITICAL: the `headers` param is just ONE of several query params that
     * follow the `?`. The CDN also requires `host`, `sign`, and `t` for byte-range
     * authentication. We must extract ONLY the JSON object that is the value of
     * `headers=` and leave the rest of the query string untouched.
     *
     * The previous implementation URL-decoded the ENTIRE tail after `?headers=`
     * (which included `&host=…&sign=…&t=…`) and then tried `JSONObject(decoded)`
     * — that threw because of the trailing `&host=…`, so embedded Referer/Origin
     * were silently lost. This version isolates just the JSON value.
     */
    private fun parseEmbeddedHeaders(url: String): Map<String, String> {
        return try {
            val marker = "?headers="
            val qi = url.indexOf(marker)
            if (qi < 0) return emptyMap()

            // The `headers` value is a JSON object. It is URL-encoded in the
            // query string, so the raw `{` / `}` / `:` / `"` appear as %7B etc.
            // The value ends at the first literal `&` (the next query param) or
            // at end-of-string. A JSON object value itself never contains a raw
            // `&` (JSON uses \u0026 for an ampersand), so splitting on `&` is safe.
            val afterMarker = url.substring(qi + marker.length)
            val rawValue = if ('&' in afterMarker) {
                afterMarker.substring(0, afterMarker.indexOf('&'))
            } else {
                afterMarker
            }

            val decoded = java.net.URLDecoder.decode(rawValue, "UTF-8")
            val json = JSONObject(decoded)
            val out = linkedMapOf<String, String>()
            for (key in json.keys()) {
                val v = json.optString(key)
                if (v.isNotBlank()) {
                    // Normalize common header names to canonical casing.
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

    /**
     * Remove ONLY the `headers=…` query param from the URL, preserving every
     * other query param (notably `host`, `sign`, `t` which the CDN requires for
     * byte-range authentication).
     *
     * The previous implementation did `url.substring(0, url.indexOf("?headers="))`,
     * which deleted the ENTIRE query string — including `host`/`sign`/`t`. With
     * those gone the CDN accepted the initial moov-atom request (so ExoPlayer
     * showed a duration / scrubber) but rejected all subsequent byte-range
     * requests, producing a black screen that then skipped to the next server.
     */
    private fun stripHeadersParam(url: String): String {
        val marker = "?headers="
        val qi = url.indexOf(marker)
        if (qi < 0) {
            // Some URLs may carry headers as a non-first param: `…&headers=…`
            val ampMarker = "&headers="
            val ai = url.indexOf(ampMarker)
            if (ai < 0) return url
            return removeSingleQueryParam(url, ai, ampMarker)
        }
        return removeSingleQueryParam(url, qi, marker)
    }

    /**
     * Remove a single query param (identified by the start index of its marker
     * string, e.g. "?headers=" or "&headers=") and its value, correctly handling
     * the case where more params follow it.
     *
     * Examples:
     *   "...mp4?headers={...}&host=H&sign=S&t=T"  (marker="?headers=" at qi)
     *      -> "...mp4?host=H&sign=S&t=T"   (the following & becomes the new ?)
     *   "...mp4?a=1&headers={...}&host=H"   (marker="&headers=" at ai)
     *      -> "...mp4?a=1&host=H"
     *   "...mp4?headers={...}"              (only param)
     *      -> "...mp4"
     */
    private fun removeSingleQueryParam(url: String, markerStart: Int, marker: String): String {
        val valueStart = markerStart + marker.length
        val rest = url.substring(valueStart)
        val nextAmp = rest.indexOf('&')
        return if (nextAmp >= 0) {
            // There are more params after this one.
            val prefix = url.substring(0, markerStart)
            val suffix = rest.substring(nextAmp + 1) // drop the leading '&'
            // If we removed the FIRST param (marker started with '?'), the next
            // param must become the new leading param after '?'.
            if (marker.startsWith("?")) {
                "$prefix?$suffix"
            } else {
                "$prefix&$suffix"
            }
        } else {
            // This was the last (or only) param — just drop it and any trailing
            // '?' or '&' it was attached to.
            url.substring(0, markerStart)
        }
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
            // CRITICAL: send a Range header so the CDN streams only the first
            // bytes. The previous code did a plain GET + body.string(), which
            // for a ~200 MB mp4 downloaded the ENTIRE file into RAM just to
            // sniff the content-type — that took many seconds on mobile data
            // (often hitting the 10 s read timeout) and risked OOM. A ranged
            // GET returns 206 in ~50–200 ms with the content-type header we
            // need, and for HLS it returns the small manifest body.
            builder.header("Range", "bytes=0-1023").get()
            verifier.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                Log.d(TAG, "VidLink verify: HTTP $code  ct=$ct")
                // Accept 200/206/416 (range not satisfiable but resource exists).
                if (code != 200 && code != 206 && code != 416) return false
                // Quick content-type sniff — a video/manifest host is fine.
                val isVideo = ct.contains("video") || ct.contains("mp4") ||
                    ct.contains("mpegurl") || ct.contains("dash") ||
                    ct.contains("octet-stream")
                if (isVideo) return true
                // Content-Type may be absent on some CDNs; fall back to a tiny
                // body read to detect an HLS manifest or an HTML error page.
                // Read only the first 1 KB (capped by the Range header above).
                val source = resp.body ?: return isVideo
                val buf = ByteArray(1024)
                val read = source.byteStream().read(buf)
                val prefix = if (read > 0) String(buf, 0, read, Charsets.UTF_8) else ""
                if (prefix.contains("#EXTM3U")) return true
                if (prefix.contains("<MPD")) return true
                // Reject obvious HTML/text error pages.
                if (ct.startsWith("text/html") || ct.startsWith("text/plain")) {
                    return false
                }
                // No clear signal either way — be optimistic (best-effort).
                true
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
