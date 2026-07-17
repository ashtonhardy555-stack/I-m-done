package com.mariocart.app.data.server

import android.util.Log
import com.mariocart.app.BuildConfig
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
 * LordFlixExtractor — resolves **direct playable** HLS stream URLs from the
 * LordFlix provider (`lordflix.org`), a 10-server aggregator that mirrors
 * content across ten independent upstream CDNs.
 *
 * ## Why this extractor exists
 *
 * Like Videasy, LordFlix is a **title + IMDb-id based** aggregator (not
 * TMDB-id-only). It queries ten independent servers in parallel, each of
 * which is a separate upstream source. If one server is down or doesn't have
 * a title, another often does. Running all ten concurrently means the first
 * server that returns a stream wins the race — typically in 2–3 HTTP
 * round-trips. This gives broad coverage for movies **and** TV (all ten
 * servers support series via `&season=&episode=` params).
 *
 * ## How it works (reverse-engineered from the reference LordFlix provider)
 *
 *  1. **TMDB lookup** — resolve the title, year, and IMDb id for the given
 *     TMDB id, using `append_to_response=external_ids`. LordFlix requires
 *     BOTH a title AND an IMDb id; if either is missing we bail early (the
 *     upstream API returns nothing without them).
 *
 *  2. **Server query (parallel)** — for each of the 10 LordFlix servers,
 *     build a URL to `https://snowhouse.lordflix.club/?title=…&type={movie|series}
 *     &year=…&imdb=…&tmdb=…&server={name}[&season=…&episode=…]`.
 *
 *  3. **Sign** — `GET https://enc-dec.app/api/enc-lordflix?url={encodedServerUrl}`
 *     returns `{ status:200, result:{ url: <proxyEncUrl>, sign: <signature> } }`.
 *
 *  4. **Fetch encrypted** — `GET {proxyEncUrl}` with LordFlix headers
 *     (Origin/Referer `https://lordflix.org`) returns an encrypted blob.
 *
 *  5. **Decrypt** — `POST https://enc-dec.app/api/dec-lordflix` with
 *     `{ text: <encrypted>, sign: <signature> }` returns
 *     `{ status:200, result:{ stream: [{ type:"hls", playlist:"<url>" }, …] } }`.
 *
 *  6. **Pick** the first `hls` playlist URL from the first server that
 *     decrypts successfully and return it to ExoPlayer with the correct
 *     Referer/Origin headers (`https://lordflix.org`).
 *
 * All 10 servers are queried concurrently via `async{}; awaitAll()`. The
 * first non-null decrypted playlist URL wins and the rest are cancelled.
 *
 * Verification is advisory: a 403/401 OkHttp probe does NOT drop the URL
 * (ExoPlayer sends proper Referer/User-Agent/Range headers that CDNs accept).
 * We always return the resolved URL to ExoPlayer — it is the real arbiter.
 */
object LordFlixExtractor {

    private const val TAG = "LordFlix"

    private const val LORDFLIX_API = "https://snowhouse.lordflix.club"
    private const val ENC_BRIDGE = "https://enc-dec.app/api/enc-lordflix"
    private const val DEC_BRIDGE = "https://enc-dec.app/api/dec-lordflix"

    private const val REFERER = "https://lordflix.org/"
    private const val ORIGIN = "https://lordflix.org"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /**
     * The 10 LordFlix upstream servers. Each is an independent CDN/source.
     * All ten support both movies and TV (series).
     */
    private val SERVERS = listOf(
        "Berlin", "Marseille", "Backrooms", "Phoenix", "Oslo",
        "Luna", "Sakura", "Rio", "Ativa", "Moscow"
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

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Result type                                                                                          //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "LordFlix"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Public API                                                                                           //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

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
            return@withContext Result.Error("LordFlix: TMDB lookup failed")
        }
        if (info.title.isBlank() || info.imdbId.isNullOrBlank()) {
            Log.w(TAG, "Missing title or IMDb id from TMDB for $tmdbId")
            return@withContext Result.Error("LordFlix: no title/imdb")
        }
        Log.d(TAG, "🔎 LordFlix TMDB info: \"${info.title}\" (${info.year}) imdb=${info.imdbId}")

        // Step 2: Query all 10 servers in parallel, sign+fetch+decrypt, and
        // return the first HLS playlist URL found.
        val firstStream = coroutineScope {
            SERVERS.map { server ->
                async {
                    try {
                        queryServer(server, info, tmdbId, type, season, episode)
                    } catch (e: Exception) {
                        Log.d(TAG, "LordFlix[$server] error: ${e.message}")
                        null
                    }
                }
            }.awaitAll()
        }.firstOrNull { it != null }

        if (firstStream == null) {
            Log.w(TAG, "No LordFlix server returned a stream for \"${info.title}\"")
            return@withContext Result.Error("LordFlix: no stream found")
        }

        val (url, serverKey) = firstStream
        Log.i(TAG, "✅ LordFlix[$serverKey] stream: $url")
        Result.Stream(
            url = url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "Origin" to ORIGIN,
                "Accept" to "*/*"
            ),
            providerName = "LordFlix·$serverKey"
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Per-server query: sign → fetch → decrypt                                                            //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /**
     * Query one LordFlix server: build the server URL, get a signed proxy URL
     * from enc-dec.app, fetch the encrypted blob, decrypt it, and return the
     * first HLS playlist URL (or null).
     *
     * @return (playlistUrl, serverKey) or null
     */
    private suspend fun queryServer(
        server: String,
        info: TmdbInfo,
        tmdbId: Int,
        type: String,
        season: Int,
        episode: Int
    ): Pair<String, String>? {
        val typeParam = if (type == "tv") "series" else "movie"
        val titleEnc = encodeQuote(info.title)

        val serverUrl = StringBuilder("$LORDFLIX_API/?").apply {
            append("title=").append(titleEnc)
            append("&type=").append(typeParam)
            append("&year=").append(URLEncoder.encode(info.year, "UTF-8"))
            append("&imdb=").append(URLEncoder.encode(info.imdbId, "UTF-8"))
            append("&tmdb=").append(tmdbId)
            append("&server=").append(URLEncoder.encode(server, "UTF-8"))
            if (type == "tv") {
                append("&season=").append(season)
                append("&episode=").append(episode)
            }
        }.toString()

        // Step 3: Get signed proxy URL + signature from enc-dec.app.
        val bridge = getSignedProxy(serverUrl) ?: run {
            Log.d(TAG, "LordFlix[$server] enc-bridge returned no proxy/sign")
            return null
        }

        // Step 4: Fetch the encrypted blob from the proxy URL (LordFlix headers).
        val encrypted = try {
            fetchTextWithLordFlixHeaders(bridge.proxyUrl)
        } catch (e: Exception) {
            Log.d(TAG, "LordFlix[$server] fetch encrypted failed: ${e.message}")
            return null
        }
        if (encrypted.length < 20 || encrypted.startsWith("<")) {
            Log.d(TAG, "LordFlix[$server] no encrypted data (len=${encrypted.length})")
            return null
        }

        // Step 5: Decrypt via enc-dec.app.
        val playlist = decryptStream(encrypted, bridge.signature) ?: run {
            Log.d(TAG, "LordFlix[$server] decrypt yielded no playlist")
            return null
        }
        if (!looksPlayable(playlist)) {
            Log.d(TAG, "LordFlix[$server] playlist not playable: ${playlist.take(60)}")
            return null
        }
        return playlist to server
    }

    /** Holds the signed proxy URL + signature returned by enc-lordflix. */
    private data class SignedProxy(val proxyUrl: String, val signature: String)

    /**
     * `GET https://enc-dec.app/api/enc-lordflix?url={encodedServerUrl}` →
     * `{ status:200, result:{ url, sign } }`.
     */
    private fun getSignedProxy(serverUrl: String): SignedProxy? {
        val encUrl = "$ENC_BRIDGE?url=${encodeQuote(serverUrl)}"
        val req = Request.Builder()
            .url(encUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, */*")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "enc-lordflix HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("status") != 200) return null
                val result = json.optJSONObject("result") ?: return null
                val proxyUrl = result.optString("url").orEmpty()
                val sign = result.optString("sign").orEmpty()
                if (proxyUrl.isBlank() || sign.isBlank()) return null
                SignedProxy(proxyUrl, sign)
            }
        } catch (e: Exception) {
            Log.d(TAG, "enc-lordflix error: ${e.message}")
            null
        }
    }

    /**
     * `POST https://enc-dec.app/api/dec-lordflix` with
     * `{ text: <encrypted>, sign: <signature> }` →
     * `{ status:200, result:{ stream: [{ type:"hls", playlist:"<url>" }] } }`.
     */
    private fun decryptStream(encryptedText: String, signature: String): String? {
        val jsonBody = JSONObject().apply {
            put("text", encryptedText)
            put("sign", signature)
        }.toString()

        val req = Request.Builder()
            .url(DEC_BRIDGE)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "dec-lordflix HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (json.optInt("status") != 200) return null
                val result = json.optJSONObject("result") ?: return null
                // result.error indicates the server had nothing for this title.
                if (result.has("error") && result.optString("error").isNotBlank()) return null
                val streamList = result.optJSONArray("stream") ?: return null
                for (i in 0 until streamList.length()) {
                    val s = streamList.optJSONObject(i) ?: continue
                    if (s.optString("type") == "hls") {
                        val playlist = s.optString("playlist").orEmpty()
                        if (playlist.isNotBlank() && looksPlayable(playlist)) return playlist
                    }
                    // Some servers return a direct "url" field instead of playlist.
                    val url = s.optString("url").orEmpty()
                    if (url.isNotBlank() && looksPlayable(url)) return url
                }
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "dec-lordflix error: ${e.message}")
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  TMDB info resolution                                                                                 //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    private data class TmdbInfo(val title: String, val year: String, val imdbId: String?)

    /**
     * Fetch title, year, and IMDb id for a TMDB id in a single round-trip.
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

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  HTTP helpers                                                                                         //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /** Fetch text from the encrypted-proxy URL using LordFlix headers. */
    private fun fetchTextWithLordFlixHeaders(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    /**
     * Matches the reference project's `encodeQuote`: encodeURIComponent, then
     * replace `%20` with `+`, then replace `+` back to `%20`. The net effect is
     * standard percent-encoding with spaces as `%20`, which is what the
     * enc-dec.app bridge expects.
     */
    private fun encodeQuote(str: String): String =
        URLEncoder.encode(str, "UTF-8").replace("%20", "+").replace("+", "%20")

    /** Quick heuristic — accept .m3u8 / .mp4 / .mkv / .mpd URLs. */
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
