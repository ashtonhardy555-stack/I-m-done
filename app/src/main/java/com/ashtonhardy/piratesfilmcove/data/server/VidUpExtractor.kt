package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VidUpExtractor - resolves a **direct playable** stream URL from the
 * VidUp provider (`vidup.to`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * VidUp is a TMDB-id-based streaming provider that resolves to a real,
 * full-length HLS playlist. It was verified end-to-end (through the
 * enc-dec.app bridge) to serve HTTP 200 `application/vnd.apple.mpegurl`
 * for mainstream titles:
 *
 *   - Game of Thrones S1E1 (1399)      -> HTTP 200 HLS
 *   - Breaking Bad S1E1 (1396)         -> HTTP 200 HLS
 *   - Stranger Things S1E1 (66732)     -> HTTP 200 HLS
 *   - The Last of Us S1E1 (100088)     -> HTTP 200 HLS
 *
 * It is fully headless - the only "secret" is a short-lived token embedded
 * in the server-rendered page, which we scrape with a regex. No JS
 * execution, no Cloudflare challenge on the page itself.
 *
 * ## How it works (reverse-engineered from the live `vidup.to` site)
 *
 *  1. **Page** - `GET https://vidup.to/movie/{tmdbId}/` (or
 *     `/tv/{tmdbId}/{season}/{episode}/`) with browser-like headers. The
 *     Next.js RSC payload embeds a short-lived token as
 *     `\"en\":\"<token>\"` (or `\"token\":\"<token>\"`).
 *
 *  2. **Encrypt the token** - `GET https://enc-dec.app/api/enc-vidup?text=<token>`
 *     -> `{ "result": { "servers": "<url>", "stream": "<url>", "token": "<csrf>" } }`.
 *
 *  3. **Server list** - `POST <servers>` with header `X-CSRF-Token: <csrf>`
 *     -> an encrypted blob. `POST https://enc-dec.app/api/dec-vidup`
 *     with `{ "text": <blob> }` -> `[{ "name": "CineX", "data": "<enc>" }, ...]`.
 *
 *  4. **Stream** - for a chosen server, `POST <stream>/<data>` with the same
 *     `X-CSRF-Token` -> an encrypted blob. `POST /api/dec-vidup`
 *     -> `{ "url": "https://.../playlist.m3u8", "title": "...", "tracks": [...] }`.
 *
 *  5. **Pick** the first server that yields a playable `url`. Multiple
 *     upstream servers are tried in order (CineX/Euro/... are independent
 *     CDNs), so a dead one doesn't sink the whole provider.
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends the provider headers that the CDN accepts). We always
 * return the resolved URL to ExoPlayer - it is the real arbiter.
 */
object VidUpExtractor {

    private const val TAG = "VidUp"

    private const val ENC_DEC_BASE = "https://enc-dec.app/api"
    private const val VIDUP_BASE = "https://vidup.to"
    private const val REFERER = "https://vidup.to/"
    private const val ORIGIN = "https://vidup.to"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // --------------------------------------------------------------------- //
    //  Result type                                                          //
    // --------------------------------------------------------------------- //

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidUp"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // --------------------------------------------------------------------- //
    //  Public API                                                           //
    // --------------------------------------------------------------------- //

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

        // Step 1: fetch the page and scrape the embedded token.
        val pageUrl = if (isTv) {
            "$VIDUP_BASE/tv/$tmdbId/$season/$episode/"
        } else {
            "$VIDUP_BASE/movie/$tmdbId/"
        }
        val html = try {
            fetchText(pageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "page fetch failed: ${e.message}")
            return@withContext Result.Error("VidUp: page unreachable")
        }
        val token = extractToken(html)
        if (token == null) {
            Log.w(TAG, "no token in page (len=${html.length})")
            return@withContext Result.Error("VidUp: no token")
        }

        // Step 2: exchange the token for the server/stream endpoints.
        val parts = try {
            encryptToken(token)
        } catch (e: Exception) {
            Log.w(TAG, "enc-vidup failed: ${e.message}")
            return@withContext Result.Error("VidUp: token exchange failed")
        }
        val serversUrl = parts.optString("servers")
        val streamUrl = parts.optString("stream")
        val csrf = parts.optString("token")
        if (serversUrl.isBlank() || streamUrl.isBlank()) {
            return@withContext Result.Error("VidUp: bad endpoint payload")
        }

        // Step 3: fetch + decrypt the server list.
        val servers = try {
            val blob = postText(serversUrl, csrf)
            decrypt(blob)
        } catch (e: Exception) {
            Log.w(TAG, "server list failed: ${e.message}")
            return@withContext Result.Error("VidUp: server list failed")
        }
        val serverArray = servers as? JSONArray ?: return@withContext Result.Error("VidUp: no servers")

        // Step 4: try each server until one yields a playable URL.
        for (i in 0 until serverArray.length()) {
            val server = serverArray.optJSONObject(i) ?: continue
            val name = server.optString("name").ifBlank { "server$i" }
            val data = server.optString("data")
            if (data.isBlank()) continue
            val resolved = try {
                val blob = postText("$streamUrl/$data", csrf)
                decrypt(blob)
            } catch (e: Exception) {
                Log.d(TAG, "server $name failed: ${e.message}")
                null
            }
            val url = (resolved as? JSONObject)?.optString("url").orEmpty()
            if (url.isNotBlank() && looksPlayable(url)) {
                Log.i(TAG, "\u2705 VidUp[$name] stream: ${url.take(80)}")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to REFERER,
                    "Origin" to ORIGIN
                )
                return@withContext Result.Stream(url, headers, "VidUp\u00b7$name")
            }
        }

        Log.w(TAG, "no VidUp server yielded a playable URL for tmdb=$tmdbId")
        Result.Error("VidUp: no stream found")
    }

    // --------------------------------------------------------------------- //
    //  Token extraction                                                     //
    // --------------------------------------------------------------------- //

    /**
     * Scrape the short-lived token from the server-rendered page.
     *
     * The Next.js RSC payload escapes JSON, so the marker appears as the
     * literal byte sequence `\"en\":\"<token>\"` (or `\"token\":\"<token>\"`).
     */
    private fun extractToken(html: String): String? {
        for (key in listOf("en", "token")) {
            val marker = "\\\"$key\\\":\\\""
            val i = html.indexOf(marker)
            if (i < 0) continue
            val start = i + marker.length
            val end = html.indexOf("\\\"", start)
            if (end > start) {
                val v = html.substring(start, end)
                if (v.length in 20..512) return v
            }
        }
        return null
    }

    // --------------------------------------------------------------------- //
    //  enc-dec.app bridge                                                   //
    // --------------------------------------------------------------------- //

    /** `GET /enc-vidup?text=<token>` -> the `result` object. */
    private fun encryptToken(token: String): JSONObject {
        val url = "$ENC_DEC_BASE/enc-vidup?text=" + java.net.URLEncoder.encode(token, "UTF-8")
        val body = fetchText(url)
        val json = JSONObject(body)
        if (json.optInt("status") != 200) {
            throw java.io.IOException("enc-vidup status ${json.optInt("status")}")
        }
        return json.getJSONObject("result")
    }

    /**
     * `POST /dec-vidup` with `{ "text": <blob> }` -> the decrypted `result`
     * (a [JSONArray] for the server list, a [JSONObject] for the stream).
     */
    private fun decrypt(blob: String): Any? {
        val payload = JSONObject().apply { put("text", blob) }.toString()
        val req = Request.Builder()
            .url("$ENC_DEC_BASE/dec-vidup")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("dec-vidup HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty body")
            val json = JSONObject(body)
            if (json.optInt("status") != 200) {
                throw java.io.IOException("dec-vidup status ${json.optInt("status")}")
            }
            return json.opt("result")
        }
    }

    // --------------------------------------------------------------------- //
    //  HTTP helpers                                                         //
    // --------------------------------------------------------------------- //

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", REFERER)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    /** POST to a VidUp endpoint with the CSRF token header. */
    private fun postText(url: String, csrf: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (csrf.isNotBlank()) header("X-CSRF-Token", csrf) }
            .post("".toRequestBody())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    /** Quick heuristic - accept .mp4 / .m3u8 / .mkv / .mpd / HLS / CDN URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".mpd") ||
            lower.contains("/playlist/") ||
            lower.contains("/hls/") ||
            lower.contains("manifest")
    }
}
