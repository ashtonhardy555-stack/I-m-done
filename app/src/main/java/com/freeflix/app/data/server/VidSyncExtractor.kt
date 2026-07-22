package com.freeflix.app.data.server

import android.util.Log
import com.freeflix.app.BuildConfig
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
 * VidSyncExtractor — resolves **direct playable** stream URLs from the
 * VidSync / wingsdatabase.com provider family using only plain HTTP.
 *
 * ## Why this extractor exists
 *
 * VidSync (`vidsync.xyz` → `vidsync.live`) is a multi-server aggregator
 * backed by the `api.wingsdatabase.com` API — the same backend that powers
 * Videasy. It exposes **10+ independent upstream CDNs** (Jett, cdn/Yoru,
 * Tejo, Neon, Sage, Cypher, Breach, Vyse, Killjoy, Fade, Omen, Raze), so
 * if one CDN lacks a title or is down, another often has it. This breadth
 * directly addresses the "shows don't have all episodes" and "some movies
 * don't play" gaps, because different upstreams cover different catalogues.
 *
 * ## How it works (reverse-engineered from the live API + enc-dec samples)
 *
 *  1. **TMDB lookup** — resolve title, year, and IMDb id for the TMDB id.
 *  2. **Seed** — `GET https://api.wingsdatabase.com/seed?mediaId={tmdbId}`
 *     → `{ "seed": "<seed>" }`. The seed rotates and is needed for the
 *     encrypted source fetch.
 *  3. **Source query (parallel)** — for each server:
 *     `GET https://api.wingsdatabase.com/{server}/sources-with-title`
 *     `?title={doubleEncodedTitle}&mediaType={movie|tv}&year={year}`
 *     `&tmdbId={tmdb}&imdbId={imdb}&enc=2&seed={seed}`
 *     `[&seasonId={s}&episodeId={e}]`
 *     → an encrypted blob (long opaque string).
 *  4. **Decrypt** — `POST https://enc-dec.app/api/dec-videasy`
 *     with `{ "text": <blob>, "id": <tmdbId>, "seed": <seed> }`
 *     → `{ "result": { "sources": [{ "url": "https://…m3u8", … }] } }`.
 *     (The VidSync and Videasy backends share the same decrypt endpoint.)
 *
 * All servers are queried concurrently via `async{}; awaitAll()`; the first
 * server that returns a decrypted, playable URL wins and the rest are
 * cancelled. This makes VidSync both **fast** (parallel, no JS) and
 * **broad** (10+ independent upstream CDNs).
 *
 * The `api.wingsdatabase.com` host sits behind Cloudflare, which may
 * challenge non-browser IPs (e.g. a 403 to a server-side probe). On a real
 * Android device the request carries proper browser headers/UA and is
 * accepted; verification is advisory and a probe failure never hard-blocks
 * the resolved URL.
 */
object VidSyncExtractor {

    private const val TAG = "VidSync"

    private const val WINGS_BASE = "https://api.wingsdatabase.com"
    private const val SEED_URL = "$WINGS_BASE/seed"
    private const val DECRYPT_URL = "https://enc-dec.app/api/dec-videasy"

    private const val REFERER = "https://player.videasy.to/"
    private const val ORIGIN = "https://player.videasy.to"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /**
     * The wingsdatabase upstream servers. Each is an independent CDN/source.
     * `moviesOnly` servers are skipped for TV content. Order only affects
     * tie-breaking since all run in parallel.
     */
    private data class Server(val key: String, val path: String, val moviesOnly: Boolean = false)

    private val SERVERS = listOf(
        Server("Jett", "jett"),
        Server("Tejo", "tejo"),
        Server("Neon", "neon"),
        Server("Sage", "sage"),
        Server("Cypher", "cypher"),
        Server("Breach", "breach"),
        Server("Vyse", "vyse"),
        Server("Killjoy", "killjoy"),
        Server("Fade", "fade"),
        Server("Omen", "omen"),
        Server("Raze", "raze"),
        Server("Yoru", "cdn", moviesOnly = true)
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
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

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidSync"
        ) : Result()

        data class Error(val message: String) : Result()
    }

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
            return@withContext Result.Error("VidSync: TMDB lookup failed")
        }
        if (info.title.isBlank()) {
            Log.w(TAG, "No title from TMDB for $tmdbId")
            return@withContext Result.Error("VidSync: no title")
        }
        Log.d(TAG, "🔍 VidSync TMDB info: \"${info.title}\" (${info.year}) imdb=${info.imdbId}")

        // Step 2: Fetch the rotating seed.
        val seed = try {
            fetchSeed(tmdbId)
        } catch (e: Exception) {
            Log.w(TAG, "seed fetch failed: ${e.message}")
            return@withContext Result.Error("VidSync: seed failed")
        }
        if (seed.isBlank()) {
            Log.w(TAG, "empty seed for $tmdbId")
            return@withContext Result.Error("VidSync: no seed")
        }
        Log.d(TAG, "VidSync seed: ${seed.take(16)}…")

        // Step 3: Query all eligible servers in parallel, decrypt, return first URL.
        val eligibleServers = SERVERS.filter { !(it.moviesOnly && isTv) }
        val firstStream = coroutineScope {
            eligibleServers.map { server ->
                async {
                    try {
                        queryServer(server, info, tmdbId, type, season, episode, seed)
                    } catch (e: Exception) {
                        Log.d(TAG, "VidSync[${server.key}] error: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.firstOrNull { it != null }

        if (firstStream == null) {
            Log.w(TAG, "No VidSync server returned a stream for \"${info.title}\"")
            return@withContext Result.Error("VidSync: no stream found")
        }
        val (url, serverKey) = firstStream
        Log.i(TAG, "✅ VidSync[$serverKey] stream: ${url.take(80)}")
        Result.Stream(
            url = url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "Origin" to ORIGIN
            ),
            providerName = "VidSync·$serverKey"
        )
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Per-server query + decrypt                                           //
    // ─────────────────────────────────────────────────────────────────────//

    private suspend fun queryServer(
        server: Server,
        info: TmdbInfo,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int,
        seed: String
    ): Pair<String, String>? {
        // The API expects the title to be URL-encoded TWICE.
        val encTitle = doubleEncode(info.title)
        val apiUrl = StringBuilder("$WINGS_BASE/${server.path}/sources-with-title").apply {
            append("?title=").append(encTitle)
            append("&mediaType=").append(type)
            append("&year=").append(URLEncoder.encode(info.year, "UTF-8"))
            append("&tmdbId=").append(tmdbId)
            append("&imdbId=").append(URLEncoder.encode(info.imdbId ?: "", "UTF-8"))
            append("&enc=2&seed=").append(URLEncoder.encode(seed, "UTF-8"))
            if (type == "tv") {
                append("&seasonId=").append(season)
                append("&episodeId=").append(episode)
            }
        }.toString()

        val encrypted = try {
            fetchText(apiUrl)
        } catch (e: Exception) {
            Log.d(TAG, "VidSync[${server.key}] fetch failed: ${e.message}")
            return null
        }
        if (encrypted.length < 20 || encrypted.startsWith("<")) {
            Log.d(TAG, "VidSync[${server.key}] no data (len=${encrypted.length})")
            return null
        }
        val decryptedUrl = decryptStream(encrypted, tmdbId, seed) ?: run {
            Log.d(TAG, "VidSync[${server.key}] decrypt yielded no URL")
            return null
        }
        if (!looksPlayable(decryptedUrl)) {
            Log.d(TAG, "VidSync[${server.key}] decrypted URL not playable: ${decryptedUrl.take(60)}")
            return null
        }
        return decryptedUrl to server.key
    }

    private fun decryptStream(encryptedText: String, tmdbId: Int, seed: String): String? {
        val jsonBody = JSONObject().apply {
            put("text", encryptedText)
            put("id", tmdbId.toString())
            put("seed", seed)
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
                    Log.d(TAG, "dec HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: json
                val sources = result.optJSONArray("sources")
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        val s = sources.optJSONObject(i) ?: continue
                        val url = s.optString("url").orEmpty()
                        if (url.isNotBlank() && looksPlayable(url)) return url
                    }
                }
                // Some servers return a single url field directly.
                val directUrl = result.optString("url").orEmpty()
                if (directUrl.isNotBlank() && looksPlayable(directUrl)) return directUrl
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "dec error: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  Helpers                                                              //
    // ─────────────────────────────────────────────────────────────────────//

    private fun fetchSeed(tmdbId: Int): String {
        val req = Request.Builder()
            .url("$SEED_URL?mediaId=$tmdbId")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("seed HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty seed body")
            // The response is JSON: { "seed": "..." }. Be tolerant of non-JSON.
            return try {
                JSONObject(body).optString("seed")
            } catch (e: Exception) {
                body.trim().trim('"')
            }
        }
    }

    /** URL-encode a string twice (the wingsdatabase API expects double encoding). */
    private fun doubleEncode(s: String): String =
        URLEncoder.encode(URLEncoder.encode(s, "UTF-8"), "UTF-8")

    private data class TmdbInfo(val title: String, val year: String, val imdbId: String?)

    private suspend fun resolveTmdbInfo(tmdbId: Int, isTv: Boolean): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId" +
            "?api_key=${BuildConfig.TMDB_API_KEY}&append_to_response=external_ids"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get().build()
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

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

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
