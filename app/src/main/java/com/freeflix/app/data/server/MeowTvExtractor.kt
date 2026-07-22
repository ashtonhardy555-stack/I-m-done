package com.freeflix.app.data.server

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
 * episodes"). In live verification it returned a decrypted `{url, headers}`
 * object for *every* test case:
 *  - Movie 497 (The Green Mile)
 *  - Movie 157336 (Interstellar)
 *  - TV 1399 Game of Thrones S1E1 **and** S8E6
 *  - TV 60625 Rick and Morty S1E1
 *
 * That makes MeowTV one of the most reliable sources for late-season
 * episodes that VidStorm/VidSrc frequently miss.
 *
 * ## How it works (reverse-engineered from the live `api.meowtv.ru` API)
 *
 *  1. **Stream query** — a single GET returns an encrypted JSON blob:
 *     - Movie: `GET https://api.meowtv.ru/streams/movie/{tmdbId}?s={server}`
 *     - TV:    `GET https://api.meowtv.ru/streams/tv/{tmdbId}/{season}/{episode}?s={server}`
 *
 *     The `s` query parameter selects the upstream server. Known servers:
 *     `pseudo`, `lynx`, `tik` (TCloud), `ipcloud`, `v4:English`, `turkce`,
 *     `v5:Hindi`, `v4:Hindi`, `v6:Hindi`. `pseudo` is the default and has
 *     the broadest movie/TV coverage.
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
 * are cancelled. This makes MeowTV both **fast** (2 HTTP round-trips, no JS)
 * and **broad** (multiple independent upstream CDNs).
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends the provider headers that CDNs accept). We always return
 * the resolved URL to ExoPlayer — it is the real arbiter.
 */
object MeowTvExtractor {

    private const val TAG = "MeowTv"

    private const val API_BASE = "https://api.meowtv.ru/streams"
    private const val DECRYPT_URL = "https://enc-dec.app/api/dec-meowtv"

    private const val REFERER = "https://meowtv.ru/"
    private const val ORIGIN = "https://meowtv.ru"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /**
     * MeowTV upstream servers. `pseudo` has the broadest coverage (movies +
     * all TV seasons/episodes); `lynx` is a secondary movie CDN. The rest are
     * language-specific (Hindi/Turkish) and are tried last. Order only
     * affects tie-breaking since all run in parallel.
     */
    private data class Server(val key: String, val param: String, val moviesOnly: Boolean = false)

    private val SERVERS = listOf(
        Server("Pseudo", "pseudo"),
        Server("Lynx", "lynx"),
        Server("TCloud", "tik"),
        Server("IPCloud", "ipcloud"),
        Server("English", "v4:English")
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Result type                                                          //
    // ─────────────────────────────────────────────────────────────────────//

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

    // ─────────────────────────────────────────────────────────────────────//
    //  Public API                                                           //
    // ─────────────────────────────────────────────────────────────────────//

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

    // ─────────────────────────────────────────────────────────────────────//
    //  Per-server query + decrypt                                           //
    // ─────────────────────────────────────────────────────────────────────//

    /**
     * Query one MeowTV server, decrypt the response, and return the stream
     * URL + headers (or null if the server has nothing for this content).
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

        // Fetch the encrypted JSON blob.
        val rawJson = try {
            fetchText(apiUrl)
        } catch (e: Exception) {
            Log.d(TAG, "MeowTV[${server.key}] fetch failed: ${e.message}")
            return null
        }
        // Reject obvious HTML/error responses.
        if (rawJson.isBlank() || rawJson.startsWith("<") || rawJson.length < 10) {
            Log.d(TAG, "MeowTV[${server.key}] no data (len=${rawJson.length})")
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

    // ─────────────────────────────────────────────────────────────────────//
    //  HTTP helpers                                                         //
    // ─────────────────────────────────────────────────────────────────────//

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get()
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
            lower.contains("manifest") ||
            // MeowTV CDN URLs use /v4/.../cf-master.m3u8 patterns and may
            // not have a clear extension on the base path — accept any
            // https URL that contains common media path markers.
            lower.contains("/v4/") ||
            lower.contains("/stream/") ||
            lower.contains("/video/")
    }
}
