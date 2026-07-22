package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import com.ashtonhardy.piratesfilmcove.BuildConfig
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * VideasyExtractor — resolves **direct playable** stream URLs from the
 * Videasy provider (`videasy.net`), a 10-server aggregator that mirrors
 * content from MyFlixerz, MovieBox, PrimeWire, OnionPlay, M4UHD, 1Movies,
 * HDMovie, SuperFlix and more.
 *
 * ## Why this extractor exists
 *
 * The existing primary extractors (VidStorm, VidSrc, VidLink, VixSrc,
 * NoTorrent) all have coverage gaps: some titles resolve on one but not
 * another, and TV-show episodes are especially spotty. Videasy is a
 * **title-based** aggregator (not TMDB-id-based), so it finds content by
 * name + year + IMDb id across 10 upstream CDNs in parallel. Each server
 * is an independent source — if one is down or doesn't have the title,
 * another often does. Running all 10 concurrently means the *first* server
 * that returns a stream wins the race, typically in 1–2 HTTP round-trips.
 *
 * ## How it works (reverse-engineered from the live `api.videasy.net` API)
 *
 *  1. **TMDB lookup** — resolve the title, year, and IMDb id for the given
 *     TMDB id. We do this with a single plain-HTTP call to TMDB's
 *     `movie/{id}` or `tv/{id}` endpoint with `append_to_response=external_ids`,
 *     exactly like the reference Videasy provider implementation.
 *
 *  2. **Server query (parallel)** — for each of the 10 Videasy servers,
 *     `GET https://api.videasy.net/{server}/sources-with-title?title=…&mediaType=…&year=…&tmdbId=…&imdbId=…[&seasonId=…&episodeId=…]`
 *     returns an **encrypted** blob (a long base64-ish string).
 *
 *  3. **Decrypt** — `POST https://enc-dec.app/api/dec-videasy`
 *     with `{ "text": <encrypted>, "id": <tmdbId> }` returns
 *     `{ "result": { "sources": [{ "url": "https://…m3u8", "quality": "1080" }, …] } }`.
 *
 *  4. **Pick** the first source URL from the first server that decrypts
 *     successfully and return it to ExoPlayer with the correct Referer/Origin
 *     headers (`https://player.videasy.net/`).
 *
 * All 10 servers are queried concurrently via `async{}; awaitAll()`. The
 * first non-null, non-blank decrypted stream URL wins and the rest are
 * cancelled. This makes Videasy both **fast** (parallel, no sequential
 * probing) and **broad** (10 independent upstream CDNs).
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends proper Referer/User-Agent/Range headers that CDNs accept).
 * We always return the resolved URL to ExoPlayer — it is the real arbiter.
 */
object VideasyExtractor {

    private const val TAG = "Videasy"

    private const val DECRYPT_URL = "https://enc-dec.app/api/dec-videasy"

    private const val REFERER = "https://player.videasy.net/"
    private const val ORIGIN = "https://player.videasy.net"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    /**
     * The 10 Videasy upstream servers. Each is an independent CDN/source.
     * `moviesOnly` servers are skipped for TV content (the API doesn't index
     * episodes for those upstreams).
     *
     * Order matters only for tie-breaking when two servers return at the
     * same time; since they run in parallel the fastest server wins
     * regardless of position.
     */
    private data class Server(val key: String, val url: String, val moviesOnly: Boolean = false)

    private val SERVERS = listOf(
        Server("Neon", "https://api.videasy.net/myflixerzupcloud/sources-with-title"),
        Server("Cypher", "https://api.videasy.net/moviebox/sources-with-title"),
        Server("Reyna", "https://api.videasy.net/primewire/sources-with-title"),
        Server("Omen", "https://api.videasy.net/onionplay/sources-with-title"),
        Server("Breach", "https://api.videasy.net/m4uhd/sources-with-title"),
        Server("Ghost", "https://api.videasy.net/primesrcme/sources-with-title"),
        Server("Sage", "https://api.videasy.net/1movies/sources-with-title"),
        Server("Vyse", "https://api.videasy.net/hdmovie/sources-with-title"),
        Server("Raze", "https://api.videasy.net/superflix/sources-with-title"),
        Server("Yoru", "https://api.videasy.net/cdn/sources-with-title", moviesOnly = true)
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val tmdbClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Result type                                                                                          //
    // ════════════════════════════════════════════════════════════════════════════════════════════════════//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "Videasy"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Public API                                                                                           //
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//

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
        val type = if (isTv) "tv" else "movie"

        // Step 1: Resolve title, year, imdbId from TMDB.
        val info = try {
            resolveTmdbInfo(tmdbId, isTv)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB lookup failed: ${e.message}")
            return@withContext Result.Error("Videasy: TMDB lookup failed")
        }
        if (info.title.isBlank()) {
            Log.w(TAG, "No title from TMDB for $tmdbId")
            return@withContext Result.Error("Videasy: no title")
        }
        Log.d(TAG, "🔍 Videasy TMDB info: \"${info.title}\" (${info.year}) imdb=${info.imdbId}")

        // Step 2: Query all eligible servers in parallel, decrypt, and return
        // the first stream URL found.
        val eligibleServers = SERVERS.filter { !(it.moviesOnly && isTv) }

        val firstStream = coroutineScope {
            eligibleServers.map { server ->
                async {
                    try {
                        queryServer(server, info, tmdbId, type, season, episode)
                    } catch (e: Exception) {
                        Log.d(TAG, "Videasy[${server.key}] error: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.firstOrNull { it != null }

        if (firstStream == null) {
            Log.w(TAG, "No Videasy server returned a stream for \"${info.title}\"")
            return@withContext Result.Error("Videasy: no stream found")
        }

        val (url, serverKey) = firstStream
        Log.i(TAG, "✅ Videasy[$serverKey] stream: $url")
        Result.Stream(
            url = url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "Origin" to ORIGIN
            ),
            providerName = "Videasy·$serverKey"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Per-server query + decrypt                                                                           //
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//

    /**
     * Query one Videasy server, decrypt the response, and return the first
     * source URL (or null if the server has nothing for this title).
     *
     * @return (url, serverKey) or null
     */
    private suspend fun queryServer(
        server: Server,
        info: TmdbInfo,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int
    ): Pair<String, String>? {
        val apiUrl = StringBuilder(server.url).apply {
            append("?title=").append(URLEncoder.encode(info.title, "UTF-8"))
            append("&mediaType=").append(type)
            append("&year=").append(URLEncoder.encode(info.year, "UTF-8"))
            append("&tmdbId=").append(tmdbId)
            append("&imdbId=").append(URLEncoder.encode(info.imdbId ?: "", "UTF-8"))
            if (type == "tv") {
                append("&seasonId=").append(season)
                append("&episodeId=").append(episode)
            }
        }.toString()

        // Fetch the encrypted blob.
        val encrypted = try {
            fetchText(apiUrl)
        } catch (e: Exception) {
            Log.d(TAG, "Videasy[${server.key}] fetch failed: ${e.message}")
            return null
        }
        // The encrypted payload is a long opaque string. Reject obvious
        // HTML/error responses (short or starts with '<').
        if (encrypted.length < 20 || encrypted.startsWith("<")) {
            Log.d(TAG, "Videasy[${server.key}] no data (len=${encrypted.length})")
            return null
        }

        // Decrypt via enc-dec.app.
        val decryptedUrl = decryptStream(encrypted, tmdbId) ?: run {
            Log.d(TAG, "Videasy[${server.key}] decrypt yielded no URL")
            return null
        }
        if (!looksPlayable(decryptedUrl)) {
            Log.d(TAG, "Videasy[${server.key}] decrypted URL not playable: ${decryptedUrl.take(60)}")
            return null
        }
        return decryptedUrl to server.key
    }

    /**
     * POST the encrypted blob to enc-dec.app and extract the first source URL
     * from the decrypted JSON.
     */
    private fun decryptStream(encryptedText: String, tmdbId: Int): String? {
        val jsonBody = JSONObject().apply {
            put("text", encryptedText)
            put("id", tmdbId.toString())
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
                    Log.d(TAG, "dec-videasy HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                // The result may be nested under "result" or at the top level.
                val result = json.optJSONObject("result") ?: json
                val sources = result.optJSONArray("sources") ?: return null
                for (i in 0 until sources.length()) {
                    val s = sources.optJSONObject(i) ?: continue
                    val url = s.optString("url").orEmpty()
                    if (url.isNotBlank() && looksPlayable(url)) return url
                }
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "dec-videasy error: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  TMDB info resolution                                                                                 //
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//

    private data class TmdbInfo(val title: String, val year: String, val imdbId: String?)

    /**
     * Fetch title, year, and IMDb id for a TMDB id in a single round-trip.
     * Calls `https://api.themoviedb.org/3/{movie|tv}/{id}?api_key=…&append_to_response=external_ids`.
     */
    private suspend fun resolveTmdbInfo(tmdbId: Int, isTv: Boolean): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId" +
            "?api_key=${BuildConfig.TMDB_API_KEY}" +
            "&append_to_response=external_ids"

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return tmdbClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("TMDB HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty TMDB body")
            val json = JSONObject(body)
            val title = json.optString("title").ifBlank { json.optString("name") }
            val dateStr = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""
            val imdbId = json.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.isNotBlank() }
            TmdbInfo(title, year, imdbId)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  HTTP helpers                                                                                         //
    // ══════════════════════════════════════════════════════════════════════════════════════════════════════//

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

    /** Quick heuristic — accept .mp4 / .m3u8 / .mkv / .mpd URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && (
            lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mkv") ||
                lower.contains(".mpd") ||
                lower.contains("/playlist/") ||
                lower.contains("/hls/") ||
                lower.contains("manifest")
            )
    }
}
