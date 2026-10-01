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
 * PeachifyExtractor — resolves a **direct playable** stream URL from the
 * Peachify provider (`peachify.top` / `x.eat-peach.sbs`) using only plain
 * HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * Peachify is a TMDB-id-keyed embed provider that fronts **five** independent
 * upstream servers (Multi, Horizon, Spider, Wolf, Iron/MovieBox). It is a
 * *new* source — not part of the vid-src / 2embed / vidlink families the app
 * already races — so it adds independent coverage for titles the existing
 * extractors miss. It follows the same headless, no-WebView pattern as the
 * LookMovie / Cinejoy / VidUp extractors: pure OkHttp + enc-dec.app.
 *
 * ## The flow (a 1:1 port of the official enc-dec.app `peachify.py` sample)
 *
 *  1. **Per-server embed** —
 *     Movie: `GET {api}/{path}/movie/{tmdbId}`
 *     TV:    `GET {api}/{path}/tv/{tmdbId}/{season}/{episode}`
 *     returns `{ "data": "<encrypted>" }`.
 *
 *  2. **Decrypt** — `POST https://enc-dec.app/api/dec-peachify` with
 *     `{ "text": <encrypted> }` returns the decrypted stream info.
 *
 *  3. We recursively scan the decrypted JSON for the first playable
 *     `.m3u8` / `.mp4` URL and return it to ExoPlayer.
 *
 * The five servers are tried in order until one yields a playable URL; the
 * per-extractor timeout in the race bounds the whole attempt, and any failure
 * returns [Result.Error] so the race simply moves on to the next provider.
 */
object PeachifyExtractor {

    private const val TAG = "Peachify"

    private const val REFERER = "https://peachify.top/"
    private const val ORIGIN = "https://peachify.top"
    private const val DEC_URL = "https://enc-dec.app/api/dec-peachify"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    /** The five Peachify upstream servers (label → path), all on one API host. */
    private const val API_HOST = "https://x.eat-peach.sbs"
    private val SERVERS = listOf(
        "multi" to "Multi",
        "hr" to "Horizon",
        "holly" to "Spider",
        "air" to "Wolf",
        "moviebox" to "Iron"
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
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
            val providerName: String = "Peachify"
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
        try {
            resolve(tmdbId, contentType, season, episode)
        } catch (e: Exception) {
            Log.d(TAG, "extract failed: ${e.message}")
            Result.Error("Peachify: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Core resolve                                                         //
    // ────────────────────────────────────────────────────────────────────── //

    private fun resolve(tmdbId: Int, contentType: String, season: Int, episode: Int): Result {
        val isTv = contentType == "tv"
        for ((path, label) in SERVERS) {
            val url = if (isTv) {
                "$API_HOST/$path/tv/$tmdbId/$season/$episode"
            } else {
                "$API_HOST/$path/movie/$tmdbId"
            }
            val encData = getJson(url)?.optString("data").orEmpty()
            if (encData.isBlank()) continue
            val dec = decrypt(encData) ?: continue
            val playable = findPlayable(dec.opt("result")) ?: continue
            Log.i(TAG, "✅ resolved tmdb=$tmdbId via Peachify/$label: ${playable.take(90)}")
            return Result.Stream(playable, streamHeaders(), "Peachify·$label")
        }
        return Result.Error("Peachify: no stream")
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Helpers                                                              //
    // ────────────────────────────────────────────────────────────────────── //

    private fun decrypt(encrypted: String): JSONObject? {
        val payload = JSONObject().put("text", encrypted).toString()
        val body = postJson(DEC_URL, payload) ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optInt("status") != 200) null else json
        } catch (e: Exception) {
            null
        }
    }

    /** Recursively scan decrypted JSON for the first playable stream URL. */
    private fun findPlayable(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (k in listOf("url", "file", "link", "playlist", "src", "source", "stream")) {
                    val v = node.optString(k)
                    if (v.isNotBlank() && looksPlayable(v)) return v
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findPlayable(node.opt(keys.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findPlayable(node.opt(i))
                    if (found != null) return found
                }
            }
            is String -> if (looksPlayable(node)) return node
        }
        return null
    }

    private fun streamHeaders(): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to REFERER,
        "Origin" to ORIGIN
    )

    private fun getJson(url: String): JSONObject? {
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
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                JSONObject(body)
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url → ${e.message}")
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
}
