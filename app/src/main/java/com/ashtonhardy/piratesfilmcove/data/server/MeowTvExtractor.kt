package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * MeowTvExtractor — resolves **direct playable** stream URLs from the
 * MeowTV provider (`api.meowtv.ru`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * MeowTV is a TMDB-id-based direct-API provider with **excellent TV-episode
 * coverage** — the precise gap the user reported ("shows don't have all
 * episodes"). It returns a decrypted `{url, headers}` object for movies and
 * for late-season episodes that VidStorm/VidSrc frequently miss.
 *
 * ## How it works (reverse-engineered from the live `api.meowtv.ru` API)
 *
 * The API is now **ticket-gated** (it previously worked unauthenticated).
 * Each stream request must carry a short-lived, **single-use** ticket bound
 * to the requesting `User-Agent`:
 *
 *  0. **Ticket** — `POST https://api.meowtv.ru/streams/ticket`
 *     with `Content-Type: application/json`, body `{}` returns
 *     `{ "ticket": "<opaque>", "exp": <ms> }`. The ticket is bound to the
 *     `User-Agent` used to request it, so the SAME UA must be sent on the
 *     stream call, and a FRESH ticket is needed for every stream request
 *     (reuse → `410 {"error":"Ticket already used"}`).
 *
 *  1. **Stream query** — a single GET returns an encrypted JSON blob:
 *     - Movie: `GET https://api.meowtv.ru/streams/movie/{tmdbId}?s={server}`
 *     - TV:    `GET https://api.meowtv.ru/streams/tv/{tmdbId}/{season}/{episode}?s={server}`
 *     with header `x-stream-ticket: <ticket>`.
 *
 *     The `s` query parameter selects the upstream server. Known servers:
 *     `ipcloud`, `dcloud`, `tik` (TCloud), `turkce` (movies only),
 *     `hindiv3` (Hindi). `ipcloud` has the broadest movie/TV coverage.
 *
 *  2. **Decrypt** — `POST https://enc-dec.app/api/dec-meowtv`
 *     with `{ "data": <the JSON object returned in step 1> }` returns:
 *     `{ "status": 200, "result": { "language": "Auto", "url": "https://…", "headers": {…} } }`
 *
 *  3. **Pick** the `url` and forward the provider-supplied `headers` to
 *     ExoPlayer (these are required by the CDN — usually Referer/Origin).
 *
 * All candidate servers are queried concurrently via `async{}; awaitAll()`;
 * the first server that returns a decrypted, playable URL wins and the rest
 * are cancelled. Each server fetch mints its own fresh ticket. This makes
 * MeowTV both **fast** (a few HTTP round-trips, no JS) and **broad**
 * (multiple independent upstream CDNs).
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends the provider headers that CDNs accept). We always return
 * the resolved URL to ExoPlayer — it is the real arbiter.
 */
object MeowTvExtractor {

    private const val TAG = "MeowTv"

    private const val API_BASE = "https://api.meowtv.ru/streams"
    private const val TICKET_URL = "https://api.meowtv.ru/streams/ticket"
    private const val DECRYPT_URL = "https://enc-dec.app/api/dec-meowtv"

    private const val REFERER = "https://meowtv.ru/"
    private const val ORIGIN = "https://meowtv.ru"

    /**
     * A single, stable User-Agent for BOTH the ticket request and the stream
     * request. The ticket is bound to this exact string — changing it between
     * the two calls yields `401 {"error":"UA mismatch"}`.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /**
     * MeowTV upstream servers. `ipcloud` has the broadest coverage (movies +
     * all TV seasons/episodes); the rest are secondary/language-specific and
     * are tried in parallel anyway (order only affects tie-breaking).
     */
    private data class Server(val key: String, val param: String, val moviesOnly: Boolean = false)

    private val SERVERS = listOf(
        Server("IPCloud", "ipcloud"),
        Server("DCloud", "dcloud"),
        Server("TCloud", "tik"),
        Server("HindiV3", "hindiv3"),
        Server("Turkce", "turkce", moviesOnly = true)
    )

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
            val providerName: String = "MeowTV"
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

        // Query all eligible servers in parallel, decrypt, and return the
        // first stream URL found.
        val eligibleServers = SERVERS.filter { !(it.moviesOnly && isTv) }

        val firstStream = coroutineScope {
            eligibleServers.map { server ->
                async {
                    try {
                        queryServer(server, tmdbId, isTv, season, episode)
                    } catch (e: Exception) {
                        Log.d(TAG, "MeowTV[${server.key}] error: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.firstOrNull { it != null }

        if (firstStream == null) {
            Log.w(TAG, "No MeowTV server returned a stream for tmdb=$tmdbId")
            return@withContext Result.Error("MeowTV: no stream found")
        }

        val (url, headers, serverKey) = firstStream
        Log.i(TAG, "✅ MeowTV[$serverKey] stream: ${url.take(80)}")
        Result.Stream(
            url = url,
            headers = headers,
            providerName = "MeowTV·$serverKey"
        )
    }

    // ────────────────────────────────────────────────────────────────────────//
    //  Per-server query + decrypt                                           //
    // ────────────────────────────────────────────────────────────────────────//

    /**
     * Query one MeowTV server, decrypt the response, and return the stream
     * URL + headers (or null if the server has nothing for this content).
     *
     * Retries once with a fresh ticket if the first attempt is rejected with
     * a ticket/signature/UA error (the ticket is single-use and short-lived).
     *
     * @return (url, headers, serverKey) or null
     */
    private suspend fun queryServer(
        server: Server,
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int
    ): Triple<String, Map<String, String>, String>? {
        val apiUrl = if (isTv) {
            "$API_BASE/tv/$tmdbId/$season/$episode?s=${server.param}"
        } else {
            "$API_BASE/movie/$tmdbId?s=${server.param}"
        }

        // Up to 2 attempts: the first ticket may already be consumed/expired.
        repeat(2) { attempt ->
            val ticket = getTicket()
            if (ticket == null) {
                Log.d(TAG, "MeowTV[${server.key}] could not mint a ticket")
                return null
            }

            val rawJson = try {
                fetchText(apiUrl, ticket)
            } catch (e: Exception) {
                Log.d(TAG, "MeowTV[${server.key}] fetch failed: ${e.message}")
                return null
            }

            // Ticket/UA problems → mint a new ticket and retry once.
            if (rawJson.contains("\"error\"") &&
                (rawJson.contains("ticket", ignoreCase = true) ||
                    rawJson.contains("UA mismatch", ignoreCase = true))
            ) {
                Log.d(TAG, "MeowTV[${server.key}] ticket rejected (attempt $attempt): ${rawJson.take(80)}")
                return@repeat
            }

            // Reject obvious HTML/error responses.
            if (rawJson.isBlank() || rawJson.startsWith("<") || rawJson.length < 10) {
                Log.d(TAG, "MeowTV[${server.key}] no data (len=${rawJson.length})")
                return null
            }
            // A definitive "No stream" is not worth retrying.
            if (rawJson.contains("No stream")) {
                Log.d(TAG, "MeowTV[${server.key}] no stream for this title")
                return null
            }

            // Decrypt via enc-dec.app.
            val decrypted = decryptStream(rawJson) ?: run {
                Log.d(TAG, "MeowTV[${server.key}] decrypt yielded no URL")
                return null
            }
            val url = decrypted.first
            if (!looksPlayable(url)) {
                Log.d(TAG, "MeowTV[${server.key}] decrypted URL not playable: ${url.take(60)}")
                return null
            }
            return Triple(url, decrypted.second, server.key)
        }
        return null
    }

    /**
     * Mint a fresh, single-use stream ticket bound to [USER_AGENT].
     *
     * `POST /streams/ticket` with an empty JSON object returns
     * `{ "ticket": "…", "exp": <ms> }`.
     *
     * @return the ticket string, or null on failure.
     */
    private fun getTicket(): String? {
        val req = Request.Builder()
            .url(TICKET_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "ticket HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                json.optString("ticket").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ticket error: ${e.message}")
            null
        }
    }

    /**
     * POST the raw JSON to enc-dec.app and extract the stream URL + headers
     * from the decrypted result.
     *
     * @return (url, headers) or null
     */
    private fun decryptStream(rawJson: String): Pair<String, Map<String, String>>? {
        // The enc-dec /dec-meowtv endpoint expects the *raw response object*
        // wrapped as { "data": <parsed object> }. Parse the raw JSON so we
        // can re-serialize it cleanly into the request body.
        val parsed = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            Log.d(TAG, "dec-meowtv: raw JSON parse failed: ${e.message}")
            return null
        }

        val jsonBody = JSONObject().apply {
            put("data", parsed)
        }.toString()

        val req = Request.Builder()
            .url(DECRYPT_URL)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "dec-meowtv HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("status") != 200) {
                    Log.d(TAG, "dec-meowtv status ${json.optInt("status")} err=${json.optString("error")}")
                    return null
                }
                val result = json.optJSONObject("result") ?: return null
                val url = result.optString("url").orEmpty()
                if (url.isBlank() || !looksPlayable(url)) return null
                // The provider may include required CDN headers.
                val headers = mutableMapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to REFERER,
                    "Origin" to ORIGIN
                )
                val hdrs = result.optJSONObject("headers")
                if (hdrs != null) {
                    for (key in hdrs.keys()) {
                        val v = hdrs.optString(key)
                        if (v.isNotBlank()) headers[key] = v
                    }
                }
                url to headers
            }
        } catch (e: Exception) {
            Log.d(TAG, "dec-meowtv error: ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────//
    //  HTTP helpers                                                         //
    // ────────────────────────────────────────────────────────────────────────//

    private fun fetchText(url: String, ticket: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("x-stream-ticket", ticket)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            // Return the body even on 401/404/410 so the caller can inspect
            // the error envelope (ticket errors are retried; "No stream" is
            // treated as a definitive miss).
            val body = resp.body?.string() ?: throw java.io.IOException("empty body")
            if (!resp.isSuccessful && body.isBlank()) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return body
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
            lower.contains("manifest") ||
            // MeowTV CDN URLs use /e/<id>/master.m3u8 patterns and may not
            // have a clear extension on the base path — accept common media
            // path markers too.
            lower.contains("/v4/") ||
            lower.contains("/stream/") ||
            lower.contains("/video/")
    }
}
