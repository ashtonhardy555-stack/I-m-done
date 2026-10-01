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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * VidUpExtractor — resolves a **direct playable** stream URL from the VidUp
 * provider (`vidup.to`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * VidUp is a TMDB-id-keyed embed provider (same family/UX as VidCore) that
 * serves a broad movie + TV catalogue through a set of upstream streaming
 * servers. It is a *new* source — it is not one of the vid-src / 2embed /
 * vidlink families the app already races — so it adds independent coverage
 * for titles the existing extractors miss. It follows the exact same headless,
 * no-WebView pattern as the LookMovie / Cinejoy extractors: a pure OkHttp
 * request sequence plus enc-dec.app for the provider's custom cipher.
 *
 * ## The flow (a 1:1 port of the official enc-dec.app `vidup.py` sample)
 *
 *  1. **Embed page** —
 *     Movie: `GET https://vidup.to/movie/{tmdbId}/`
 *     TV:    `GET https://vidup.to/tv/{tmdbId}/{season}/{episode}/`
 *     returns HTML with an escaped JSON blob containing the seed text
 *     (`\"en\":\"…\"` or `\"token\":\"…\"`).
 *
 *  2. **Encrypt/derive** — `GET https://enc-dec.app/api/enc-vidup?text={seed}`
 *     returns `{ servers, stream, token }` — the encrypted server-list URL, the
 *     stream base URL, and the CSRF token.
 *
 *  3. **Server list** — `POST {servers}` with header `X-CSRF-Token: {token}`
 *     returns an encrypted blob; `POST https://enc-dec.app/api/dec-vidup`
 *     with `{ text }` decrypts it into a list of servers, each carrying a
 *     `data` field.
 *
 *  4. **Stream** — for each server, `POST {stream}/{data}` (same CSRF header)
 *     returns an encrypted blob; `POST …/dec-vidup` decrypts it into the
 *     stream info. We recursively scan the decrypted JSON for the first
 *     playable `.m3u8` / `.mp4` URL and return it to ExoPlayer.
 *
 * The whole pipeline is 4–6 round-trips; the per-extractor timeout in the
 * race bounds it, and any failure returns [Result.Error] so the race simply
 * moves on to the next provider.
 */
object VidUpExtractor {

    private const val TAG = "VidUp"

    private const val SITE = "https://vidup.to"
    private const val REFERER = "https://vidup.to/"
    private const val ENC_URL = "https://enc-dec.app/api/enc-vidup"
    private const val DEC_URL = "https://enc-dec.app/api/dec-vidup"

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

    // ────────────────────────────────────────────────────────────────────── //
    //  Result type                                                          //
    // ────────────────────────────────────────────────────────────────────── //

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
            Result.Error("VidUp: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Core resolve                                                         //
    // ────────────────────────────────────────────────────────────────────── //

    private fun resolve(tmdbId: Int, contentType: String, season: Int, episode: Int): Result {
        val isTv = contentType == "tv"
        val path = if (isTv) "/tv/$tmdbId/$season/$episode/" else "/movie/$tmdbId/"

        val page = getText("$SITE$path") ?: return Result.Error("VidUp: no page")
        val seed = extractSeed(page) ?: return Result.Error("VidUp: no seed")

        val encJson = getJson("$ENC_URL?text=${enc(seed)}")
            ?: return Result.Error("VidUp: enc failed")
        if (encJson.optInt("status", 200) != 200) return Result.Error("VidUp: enc status")
        val enc = encJson.optJSONObject("result") ?: return Result.Error("VidUp: no enc result")

        val serversUrl = enc.optString("servers")
        val streamBase = enc.optString("stream")
        val csrf = enc.optString("token")
        if (serversUrl.isBlank() || streamBase.isBlank()) return Result.Error("VidUp: no servers")

        val hdrs = mapOf(
            "X-CSRF-Token" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to REFERER,
            "Origin" to SITE
        )

        // 3. Server list → decrypt.
        val serversEnc = postRaw(serversUrl, "", hdrs)
            ?: return Result.Error("VidUp: servers fetch failed")
        val serversDec = decrypt(serversEnc) ?: return Result.Error("VidUp: dec servers failed")
        val arr = serversDec.optJSONArray("result") ?: return Result.Error("VidUp: no server list")

        // 4. Try each server until one yields a playable URL.
        for (i in 0 until arr.length()) {
            val data = arr.optJSONObject(i)?.optString("data") ?: continue
            if (data.isBlank()) continue
            val streamEnc = postRaw("$streamBase/$data", "", hdrs) ?: continue
            val streamDec = decrypt(streamEnc) ?: continue
            val url = findPlayable(streamDec.opt("result")) ?: continue
            Log.i(TAG, "✅ resolved tmdb=$tmdbId via VidUp: ${url.take(90)}")
            return Result.Stream(url, streamHeaders(), "VidUp")
        }
        return Result.Error("VidUp: no stream")
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Helpers                                                              //
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * Pull the seed text out of the embed page. The page embeds escaped JSON
     * (`\"en\":\"…\"`) but some builds emit plain JSON, so we try both.
     */
    private fun extractSeed(html: String): String? {
        val patterns = listOf(
            Regex("\\\\\"(?:en|token)\\\\\":\\\\\"(.*?)\\\\\""),
            Regex("\"(?:en|token)\":\"(.*?)\""),
            Regex("\\\\\"(?:en|token)\\\\\":\\\\\"(.*?)\\\"")
        )
        for (p in patterns) {
            val m = p.find(html)
            if (m != null && m.groupValues[1].isNotBlank()) return m.groupValues[1]
        }
        return null
    }

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
        "Origin" to SITE
    )

    private fun getText(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            .header("Referer", REFERER)
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

    private fun getJson(url: String): JSONObject? {
        val body = getText(url) ?: return null
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun postRaw(url: String, body: String, headers: Map<String, String>): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
        for ((k, v) in headers) builder.header(k, v)
        builder.post(body.toRequestBody("text/plain;charset=UTF-8".toMediaType()))
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "POST $url → ${e.message}")
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

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
