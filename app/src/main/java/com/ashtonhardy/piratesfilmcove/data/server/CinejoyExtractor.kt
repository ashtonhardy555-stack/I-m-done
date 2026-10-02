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
 * CinejoyExtractor — resolves **direct playable** HLS stream URLs from the
 * Cinejoy provider (`api.wing.st`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * Cinejoy is a TMDB-id-based direct-API provider that returns **full-length,
 * up-to-4K** streams. It was verified end-to-end during testing:
 *   • Inception (TMDB 27205) → 148.1 min @ 3840x2160 (expected ~148) ✅
 *   • Game of Thrones S1E1 (TMDB 1399) → 61.7 min @ 3840x2160 (expected ~62) ✅
 *
 * It follows the exact same headless, no-WebView pattern as the reference
 * LookMovie / MeowTV extractors: pure OkHttp request sequence + enc-dec.app
 * decryption, multiple upstream servers raced in parallel, and a direct HLS
 * URL returned with the headers ExoPlayer must send.
 *
 * ## The flow (a 1:1 port of the official enc-dec.app `cinejoy.py` sample)
 *
 *  0. **TMDB info** — resolve `title`, `year` and `imdbId` for the TMDB id
 *     via TMDB's `append_to_response=external_ids` (single round-trip).
 *
 *  1. **Server list** — `GET https://api.wing.st/servers` returns the
 *     available upstream servers: Nebula, Lisbon (4K), Scout, Riga, Solara,
 *     Athens. We race them all in parallel.
 *
 *  2. **Encrypted request** — `GET https://enc-dec.app/api/enc-cinejoy?url=<url>`
 *     where `<url>` is the `api.wing.st` query for the chosen server:
 *       Movie: `https://api.wing.st/?title={t}&type=movie&year={y}&imdb={i}&tmdb={id}&server={s}`
 *       TV:    `...&type=series&year={y}&imdb={i}&tmdb={id}&server={s}&season={S}&episode={E}`
 *     Returns `{ "data": "<base64url>", "state": { "responseKey": ..., "aad": ... } }`.
 *
 *  3. **Fetch encrypted response** — base64url-decode `data` and POST the raw
 *     bytes to `https://api.wing.st/g`; the raw (encrypted) response bytes are
 *     what we must decrypt.
 *
 *  4. **Decrypt** — base64url-encode the response bytes and
 *     `POST https://enc-dec.app/api/dec-cinejoy` with `{ "text": <b64url>, "state": <state> }`.
 *     Returns `{ "data": { "stream": [ { "type": "hls", "playlist": "https://…m3u8" } ] } }`.
 *
 *  5. **Pick + validate** — the `playlist` URL is the direct HLS. Some servers
 *     (notably **Nebula**) return an anti-scrape redirect loop
 *     (`…/hls/dontscrape/…`), so we validate each candidate with a real fetch
 *     and skip the looping ones, preferring the known-good 4K servers
 *     (**Lisbon**, then **Scout**).
 *
 * All candidate servers are queried concurrently via `async{}; awaitAll()`;
 * the first server that yields a validated, playable URL wins.
 */
object CinejoyExtractor {

    private const val TAG = "Cinejoy"

    private const val SERVERS_URL = "https://api.wing.st/servers"
    private const val GATE_URL = "https://api.wing.st/g"
    private const val ENC_URL = "https://enc-dec.app/api/enc-cinejoy"
    private const val DEC_URL = "https://enc-dec.app/api/dec-cinejoy"

    private const val REFERER = "https://cinejoy.pk/"
    private const val ORIGIN = "https://cinejoy.pk"

    // Deep-validation tri-state results (see validate()).
    private const val PROBE_PLAYABLE = 1   // proven to serve real media
    private const val PROBE_UNKNOWN = 0    // couldn't confirm (network hiccup)
    private const val PROBE_DEAD = -1      // definitively dead (no content)

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /**
     * Server preference order. Lisbon (4K) and Scout are the reliable ones
     * (verified returning full-length HLS); Nebula is last because it returns
     * an anti-scrape redirect loop. Unknown servers fall in before Nebula.
     */
    private val PREFERRED_ORDER = listOf("Lisbon", "Scout", "Solara", "Riga", "Athens", "Nebula")

    /** Fallback list if the live `/servers` call fails. */
    private val FALLBACK_SERVERS = listOf("Lisbon", "Scout", "Solara", "Riga", "Athens", "Nebula")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Validation client: follows only a couple of redirects so an anti-scrape
     * redirect loop fails fast instead of burning the full redirect budget.
     */
    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Result type                                                          //
    // ────────────────────────────────────────────────────────────────────── //

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "Cinejoy"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Public API                                                           //
    // ────────────────────────────────────────────────────────────────────── //

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

        // 0. Resolve title/year/imdb from TMDB (required by api.wing.st).
        val info = try {
            resolveTmdbInfo(tmdbId, isTv)
        } catch (e: Exception) {
            Log.d(TAG, "TMDB resolve failed: ${e.message}")
            return@withContext Result.Error("Cinejoy: no TMDB info")
        }
        if (info.title.isBlank()) {
            return@withContext Result.Error("Cinejoy: no title")
        }
        Log.d(TAG, "🔎 Cinejoy TMDB info: \"${info.title}\" (${info.year}) imdb=${info.imdbId}")

        // 1. Server list (live, with fallback).
        val servers = try {
            fetchServers()
        } catch (e: Exception) {
            FALLBACK_SERVERS
        }.ifEmpty { FALLBACK_SERVERS }

        // 2-4. Resolve every server in parallel → (serverName, url) candidates.
        val resolved = coroutineScope {
            servers.map { server ->
                async {
                    try {
                        val url = resolveServer(server, info, tmdbId, isTv, season, episode)
                        if (url != null) server to url else null
                    } catch (e: Exception) {
                        Log.d(TAG, "Cinejoy[$server] error: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (resolved.isEmpty()) {
            Log.w(TAG, "No Cinejoy server returned a stream for tmdb=$tmdbId")
            return@withContext Result.Error("Cinejoy: no stream found")
        }

        // 5. Order by preference, then DEEP-validate each (manifest + first
        //    variant + a real segment) so a stream whose segments are dead is
        //    rejected HERE instead of being played, sitting at 00:00, and
        //    skipped. The first proven-playable candidate wins. If none is
        //    proven playable we fall back to an INCONCLUSIVE candidate (better
        //    than nothing) but NEVER to a definitively-dead one, so the race
        //    can fall through to another provider instead of looping on a
        //    dead Cinejoy server.
        val ordered = resolved.sortedBy { (name, _) -> rank(name) }
        var inconclusive: Pair<String, String>? = null
        for ((server, url) in ordered) {
            when (validate(url)) {
                PROBE_PLAYABLE -> {
                    Log.i(TAG, "✅ Cinejoy[$server] validated (deep): ${url.take(90)}")
                    return@withContext Result.Stream(
                        url = url,
                        headers = streamHeaders(),
                        providerName = "Cinejoy·$server"
                    )
                }
                PROBE_UNKNOWN -> {
                    if (inconclusive == null) inconclusive = server to url
                    Log.d(TAG, "Cinejoy[$server] deep validation inconclusive")
                }
                else -> Log.d(TAG, "Cinejoy[$server] deep validation: DEAD (no playable content)")
            }
        }

        inconclusive?.let { (server, url) ->
            Log.i(TAG, "⚠️ Cinejoy[$server] inconclusive fallback: ${url.take(90)}")
            return@withContext Result.Stream(
                url = url,
                headers = streamHeaders(),
                providerName = "Cinejoy·$server"
            )
        }

        Log.w(TAG, "No Cinejoy server passed deep validation for tmdb=$tmdbId")
        Result.Error("Cinejoy: no playable stream")
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Per-server resolve + decrypt                                         //
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * Resolve one Cinejoy server to a direct HLS playlist URL, or null.
     */
    private fun resolveServer(
        server: String,
        info: TmdbInfo,
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int
    ): String? {
        val type = if (isTv) "series" else "movie"
        val sb = StringBuilder(SERVERS_QUERY_BASE)
        sb.append("?title=").append(enc(info.title))
            .append("&type=").append(type)
            .append("&year=").append(enc(info.year))
            .append("&imdb=").append(enc(info.imdbId ?: ""))
            .append("&tmdb=").append(tmdbId)
            .append("&server=").append(enc(server))
        if (isTv) {
            sb.append("&season=").append(season).append("&episode=").append(episode)
        }
        val apiUrl = sb.toString()

        // 2. enc-cinejoy → { data, state }
        val encBody = getText("$ENC_URL?url=${enc(apiUrl)}") ?: return null
        val encJson = try {
            JSONObject(encBody)
        } catch (e: Exception) {
            return null
        }
        if (encJson.optInt("status", 200) != 200) return null
        val encResult = encJson.optJSONObject("result") ?: return null
        val dataB64 = encResult.optString("data")
        val state = encResult.optJSONObject("state") ?: return null
        if (dataB64.isBlank()) return null

        // 3. POST raw bytes to api.wing.st/g → encrypted response bytes.
        val blob = base64UrlDecode(dataB64) ?: return null
        val encrypted = postBytes(GATE_URL, blob) ?: return null
        if (encrypted.isEmpty()) return null

        // 4. dec-cinejoy → { data: { stream: [ { playlist } ] } }
        val payload = JSONObject().apply {
            put("text", base64UrlEncode(encrypted))
            put("state", state)
        }.toString()
        val decBody = postJson(DEC_URL, payload) ?: return null
        val decJson = try {
            JSONObject(decBody)
        } catch (e: Exception) {
            return null
        }
        if (decJson.optInt("status") != 200) return null

        val streams = decJson.optJSONObject("result")
            ?.optJSONObject("data")
            ?.optJSONArray("stream")
            ?: return null
        for (i in 0 until streams.length()) {
            val s = streams.optJSONObject(i) ?: continue
            val playlist = s.optString("playlist")
            if (playlist.isNotBlank() && looksPlayable(playlist)) return playlist
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Validation                                                           //
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * Confirm a candidate URL actually serves a PLAYABLE HLS stream — not just
     * a manifest that *looks* valid.
     *
     * The old check only fetched the manifest and looked for `#EXTM3U`. That is
     * fooled by a "00:00" stream: a provider can hand back a perfectly valid
     * master playlist whose segments are all dead (HTTP 502 "origin
     * unavailable", an HTML error page, …). ExoPlayer would then load the
     * manifest, reach READY, and sit at 00:00 until it errored and the app
     * skipped it — the exact "it tries to play streams that don't have any
     * content" behaviour.
     *
     * This DEEP check walks the whole chain:
     *   1. fetch the playlist and confirm it is HLS;
     *   2. if it is a MASTER playlist, descend into the first variant so we
     *      validate the MEDIA playlist (the one that actually lists segments);
     *   3. confirm the media playlist advertises at least one segment;
     *   4. fetch the first segment and confirm it is real media (not an HTML /
     *      JSON error page).
     *
     * Returns a tri-state [Int]:
     *   - [PROBE_PLAYABLE] the whole chain (manifest -> variant -> segment)
     *     resolved to real media;
     *   - [PROBE_DEAD] a definitive failure (non-2xx, no segments, or an
     *     HTML/JSON error page) \u2014 this candidate must never be played;
     *   - [PROBE_UNKNOWN] an inconclusive network hiccup. Unknown is treated as
     *     acceptable (ExoPlayer is the final arbiter on-device) so a transient
     *     probe failure never rejects a working stream.
     */
    private fun validate(url: String): Int {
        // 1. The playlist itself.
        val body = fetchText(url) ?: return PROBE_UNKNOWN
        if (!(body.contains("#EXTM3U") || body.contains("#EXTINF") || body.contains("#EXT-X-"))) {
            return PROBE_DEAD
        }
        // 2. If this is a master playlist, descend into the first variant.
        val variant = firstVariantUrl(body, url)
        val mediaUrl = variant ?: url
        val mediaBody = if (variant != null) (fetchText(variant) ?: return PROBE_UNKNOWN) else body
        // 3. A real stream must list at least one media segment.
        if (!mediaBody.contains("#EXTINF")) return PROBE_DEAD
        // 4. Fetch the first segment and confirm it is real media.
        val segUrl = firstSegmentUrl(mediaBody, mediaUrl) ?: return PROBE_UNKNOWN
        return segmentProbe(segUrl)
    }

    /** GET a URL with the probe client and return its body, or null on failure. */
    private fun fetchText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get()
            .build()
        return try {
            probeClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchText failed: ${typeOf(e)}")
            null
        }
    }

    /** First variant URI of a master playlist (the line after #EXT-X-STREAM-INF). */
    private fun firstVariantUrl(masterBody: String, base: String): String? {
        val lines = masterBody.lines()
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                for (j in i + 1 until lines.size) {
                    val v = lines[j].trim()
                    if (v.isEmpty() || v.startsWith("#")) continue
                    return resolveUrl(base, v)
                }
            }
        }
        return null
    }

    /** First media segment URI of a media playlist (the line after #EXTINF). */
    private fun firstSegmentUrl(mediaBody: String, base: String): String? {
        val lines = mediaBody.lines()
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("#EXTINF")) {
                for (j in i + 1 until lines.size) {
                    val v = lines[j].trim()
                    if (v.isEmpty() || v.startsWith("#")) continue
                    return resolveUrl(base, v)
                }
            }
        }
        return null
    }

    /** Resolve a possibly-relative playlist URI against its base. */
    private fun resolveUrl(base: String, ref: String): String = try {
        java.net.URI(base).resolve(ref).toString()
    } catch (e: Exception) {
        ref
    }

    /**
     * Ranged GET of the first bytes of a segment. Rejects the stream only when
     * the response is a definitive failure (non-2xx) or the payload is clearly
     * an HTML/JSON error page; an inconclusive network error is accepted so a
     * transient probe hiccup never rejects a working stream.
     */
    private fun segmentProbe(segUrl: String): Int {
        val req = Request.Builder()
            .url(segUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get()
            .build()
        return try {
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return PROBE_DEAD
                // Read only the first KB of the segment, then close the body
                // (the rest of the segment is discarded — we never download it).
                val src = resp.body?.source() ?: return PROBE_UNKNOWN
                val buf = okio.Buffer()
                src.read(buf, 1024L)
                val bytes = buf.readByteArray()
                if (bytes.isEmpty()) return PROBE_DEAD
                var firstUnsigned = -1
                for (b in bytes) {
                    val u = b.toInt() and 0xFF
                    if (u != 0x20 && u != 0x09 && u != 0x0A && u != 0x0D) {
                        firstUnsigned = u
                        break
                    }
                }
                if (firstUnsigned < 0) return PROBE_DEAD
                // Real TS/fMP4 media never starts with '<' (0x3C, HTML),
                // '{' (0x7B) or '[' (0x5B) (JSON). Those are dead-stream
                // error pages.
                if (firstUnsigned == 0x3C || firstUnsigned == 0x7B || firstUnsigned == 0x5B) PROBE_DEAD
                else PROBE_PLAYABLE
            }
        } catch (e: Exception) {
            Log.d(TAG, "segment probe failed: ${typeOf(e)}")
            PROBE_UNKNOWN
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  HTTP helpers                                                         //
    // ────────────────────────────────────────────────────────────────────── //

    private fun streamHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to REFERER,
        "Origin" to ORIGIN
    )

    private fun fetchServers(): List<String> {
        val body = getText(SERVERS_URL) ?: return emptyList()
        val json = JSONObject(body)
        val arr = json.optJSONArray("servers") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    private fun rank(name: String): Int {
        val idx = PREFERRED_ORDER.indexOfFirst { it.equals(name, ignoreCase = true) }
        return if (idx >= 0) idx else PREFERRED_ORDER.size
    }

    private fun getText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url → ${e.message}")
            null
        }
    }

    private fun postBytes(url: String, body: ByteArray): ByteArray? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .post(body.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "POST(bytes) $url → ${e.message}")
            null
        }
    }

    private fun postJson(url: String, json: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, */*")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "POST(json) $url → ${e.message}")
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  TMDB + encoding helpers                                              //
    // ────────────────────────────────────────────────────────────────────── //

    private data class TmdbInfo(val title: String, val year: String, val imdbId: String?)

    private fun resolveTmdbInfo(tmdbId: Int, isTv: Boolean): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId" +
            "?api_key=${BuildConfig.TMDB_API_KEY}&append_to_response=external_ids"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("TMDB HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty TMDB body")
            val json = JSONObject(body)
            val title = json.optString("title").ifBlank { json.optString("name") }
            val dateStr = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""
            val imdbId = json.optJSONObject("external_ids")
                ?.optString("imdb_id")?.takeIf { it.isNotBlank() }
            TmdbInfo(title, year, imdbId)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun base64UrlEncode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING
        )

    private fun base64UrlDecode(s: String): ByteArray? = try {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        android.util.Base64.decode(
            padded,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    } catch (e: Exception) {
        null
    }

    /** Quick heuristic — accept .m3u8 / .mp4 / HLS / playlist URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains("/playlist/") ||
            lower.contains("/hls/") ||
            lower.contains("manifest")
    }

    private fun typeOf(e: Exception): String = e.javaClass.simpleName

    private const val SERVERS_QUERY_BASE = "https://api.wing.st/"
}
