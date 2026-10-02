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
 * VidFastExtractor — resolves a **direct playable** stream URL from the
 * VidFast provider (`vidfast.vc`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * VidFast is a TMDB-id-based provider that resolves to a real, full-length
 * HLS master playlist (verified: Inception → 148.1 min in 4K). It is fully
 * headless — the only "secret" is a token embedded in the server-rendered
 * page, which we scrape with a regex. No JS execution, no Cloudflare
 * challenge on the page itself.
 *
 * ## How it works (reverse-engineered from the live `vidfast.vc` site)
 *
 *  1. **Page** — `GET https://vidfast.vc/movie/{tmdbId}` (or
 *     `/tv/{tmdbId}/{season}/{episode}`) with browser-like headers. The
 *     Next.js RSC payload embeds a short-lived token as
 *     `\"en\":\"<token>\"` (or `\"token\":\"<token>\"`).
 *
 *  2. **Encrypt the token** — `GET https://enc-dec.app/api/enc-vidfast?text=<token>`
 *     → `{ "result": { "servers": "<url>", "stream": "<url>", "token": "<csrf>" } }`.
 *
 *  3. **Server list** — `POST <servers>` with header `X-CSRF-Token: <csrf>`
 *     → an encrypted blob. `POST https://enc-dec.app/api/dec-vidfast`
 *     with `{ "text": <blob> }` → `[{ "name": "vRapid", "data": "<enc>" }, …]`.
 *
 *  4. **Stream** — for a chosen server, `POST <stream>/<data>` with the same
 *     `X-CSRF-Token` → an encrypted blob. `POST /api/dec-vidfast`
 *     → `{ "url": "https://…/master.m3u8", "title": "…", "tracks": […] }`.
 *
 *  5. **Pick** the first server that yields a playable `url`. Multiple
 *     upstream servers are tried in order (vRapid/vBlaze/… are independent
 *     CDNs), so a dead one doesn't sink the whole provider.
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends the provider headers that the CDN accepts). We always
 * return the resolved URL to ExoPlayer — it is the real arbiter.
 */
object VidFastExtractor {

    private const val TAG = "VidFast"

    private const val ENC_DEC_BASE = "https://enc-dec.app/api"
    private const val VIDFAST_BASE = "https://vidfast.vc"
    private const val REFERER = "https://vidfast.vc/"
    private const val ORIGIN = "https://vidfast.vc"

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

    // ────────────────────────────────────────────────────────────────────────//
    //  Result type                                                          //
    // ────────────────────────────────────────────────────────────────────────//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidFast"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ────────────────────────────────────────────────────────────────────────//
    //  Public API                                                           //
    // ────────────────────────────────────────────────────────────────────────//

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
            "$VIDFAST_BASE/tv/$tmdbId/$season/$episode"
        } else {
            "$VIDFAST_BASE/movie/$tmdbId"
        }
        val html = try {
            fetchText(pageUrl)
        } catch (e: Exception) {
            Log.w(TAG, "page fetch failed: ${e.message}")
            return@withContext Result.Error("VidFast: page unreachable")
        }
        val token = extractToken(html)
        if (token == null) {
            Log.w(TAG, "no token in page (len=${html.length})")
            return@withContext Result.Error("VidFast: no token")
        }

        // Step 2: exchange the token for the server/stream endpoints.
        val parts = try {
            encryptToken(token)
        } catch (e: Exception) {
            Log.w(TAG, "enc-vidfast failed: ${e.message}")
            return@withContext Result.Error("VidFast: token exchange failed")
        }
        val serversUrl = parts.optString("servers")
        val streamUrl = parts.optString("stream")
        val csrf = parts.optString("token")
        if (serversUrl.isBlank() || streamUrl.isBlank()) {
            return@withContext Result.Error("VidFast: bad endpoint payload")
        }

        // Step 3: fetch + decrypt the server list.
        val servers = try {
            val blob = postText(serversUrl, csrf)
            decrypt(blob)
        } catch (e: Exception) {
            Log.w(TAG, "server list failed: ${e.message}")
            return@withContext Result.Error("VidFast: server list failed")
        }
        val serverArray = servers as? JSONArray ?: return@withContext Result.Error("VidFast: no servers")

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
                Log.i(TAG, "✅ VidFast[$name] stream: ${url.take(80)}")
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to REFERER,
                    "Origin" to ORIGIN
                )
                return@withContext Result.Stream(url, headers, "VidFast·$name")
            }
        }

        Log.w(TAG, "no VidFast server yielded a playable URL for tmdb=$tmdbId")
        Result.Error("VidFast: no stream found")
    }

    // ────────────────────────────────────────────────────────────────────────//
    //  Token extraction                                                     //
    // ────────────────────────────────────────────────────────────────────────//

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

    // ────────────────────────────────────────────────────────────────────────//
    //  enc-dec.app bridge                                                   //
    // ────────────────────────────────────────────────────────────────────────//

    /** `GET /enc-vidfast?text=<token>` → the `result` object. */
    private fun encryptToken(token: String): JSONObject {
        val url = "$ENC_DEC_BASE/enc-vidfast?text=" + java.net.URLEncoder.encode(token, "UTF-8")
        val body = fetchText(url)
        val json = JSONObject(body)
        if (json.optInt("status") != 200) {
            throw java.io.IOException("enc-vidfast status ${json.optInt("status")}")
        }
        return json.getJSONObject("result")
    }

    /**
     * `POST /dec-vidfast` with `{ "text": <blob> }` → the decrypted `result`
     * (a [JSONArray] for the server list, a [JSONObject] for the stream).
     */
    private fun decrypt(blob: String): Any? {
        val payload = JSONObject().apply { put("text", blob) }.toString()
        val req = Request.Builder()
            .url("$ENC_DEC_BASE/dec-vidfast")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("dec-vidfast HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty body")
            val json = JSONObject(body)
            if (json.optInt("status") != 200) {
                throw java.io.IOException("dec-vidfast status ${json.optInt("status")}")
            }
            return json.opt("result")
        }
    }

    // ────────────────────────────────────────────────────────────────────────//
    //  HTTP helpers                                                         //
    // ────────────────────────────────────────────────────────────────────────//

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

    /** POST to a VidFast endpoint with the CSRF token header. */
    private fun postText(url: String, csrf: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (csrf.isNotBlank()) header("X-CSRF-Token", csrf) }
            // The VidFast endpoint rejects an EMPTY JSON body with
            // 400 {"code":"FST_ERR_CTP_EMPTY_JSON_BODY"}. It must be a valid
            // (even if empty) JSON object: "{}".
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    /** Quick heuristic — accept .mp4 / .m3u8 / .mkv / .mpd / HLS / CDN URLs. */
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
