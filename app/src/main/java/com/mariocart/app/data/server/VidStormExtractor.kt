package com.mariocart.app.data.server

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * VidStormExtractor — resolves a **direct playable** stream URL from the
 * VidStorm API (vidstorm.ru), the exact same backend that powers FilmCave
 * (`fmcave.lovable.app`) and the AutoEmbed player.
 *
 * ## Why this is the primary extractor now
 * The VidStorm API returns *direct* stream URLs (`.m3u8` / `.mp4`) for a
 * given TMDB id — **no embed-page scraping, no WebView, and no Cloudflare
 * challenge to solve**. This is dramatically more reliable than the legacy
 * LookMovie WebView + embed-scraping pipeline, which depends on JS challenges
 * auto-solving and players auto-requesting their media inside an 18 s window.
 *
 * This is the single biggest reason "only The Rookie played": every other
 * title fell through the fragile WebView stages and hit dead ends, whereas
 * the VidStorm API resolves almost every popular movie/episode to a real
 * direct URL.
 *
 * ## How it works (reverse-engineered from the FilmCave JS bundle)
 *
 * The FilmCave / AutoEmbed player (`vidstorm.ru`) fetches its server list
 * from a small encrypted API:
 *
 *   GET https://vidstorm.ru/api/{movie|tv}/{encryptedId}
 *
 * where `encryptedId` is the TMDB id (movies) or `{tmdbId}_{season}_{episode}`
 * (TV), AES-256-CBC encrypted with a hardcoded key + IV, then base64url
 * encoded. The response is a JSON object keyed by element names (Lithium,
 * Hydrogen, Boron, Helium, …); each value is `{ url, language, flag, type }`.
 * Entries with a non-null `url` are live sources.
 *
 * Two source shapes exist:
 *  - **Direct** (`type` = "hls"):  `url` is already the `.m3u8` / `.mp4`.
 *  - **Playlist** (`type` = "mp4", host `hellstorm.lol`): `url` returns a
 *    JSON array `[{ resolution, url }]` of direct `.mp4` files at various
 *    qualities. We pick the highest resolution.
 *
 * The direct URLs are proxied through Cloudflare Workers and require a
 * `Referer` of the VidStorm origin, which we attach so ExoPlayer can play
 * them directly.
 */
object VidStormExtractor {

    private const val TAG = "VidStorm"

    /** The VidStorm streaming API base (extracted from the FilmCave bundle). */
    private const val API_BASE = "https://vidstorm.ru/api"

    /**
     * AES-256 key + IV used by the FilmCave player to encrypt the id segment.
     * Key is 32 bytes (AES-256); IV is the first 16 bytes of the key.
     * Reverse-engineered from `Zw="x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9"`.
     */
    private val AES_KEY = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9".toByteArray(Charsets.UTF_8)
    private val AES_IV = AES_KEY.copyOf(16)

    /** Origin to send as Referer on the proxied media URLs (CDN check). */
    private const val REFERER = "https://vidstorm.ru/"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  Result type                                                        //
    // ─────────────────────────────────────────────────────────────────── //

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VidStorm"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  Public API                                                         //
    // ─────────────────────────────────────────────────────────────────── //

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
        val kind = if (contentType == "tv") "tv" else "movie"
        val plain = if (contentType == "tv") "${tmdbId}_${season}_${episode}" else tmdbId.toString()

        val encrypted = try {
            encryptId(plain)
        } catch (e: Exception) {
            Log.e(TAG, "AES encryption failed: ${e.message}")
            return@withContext Result.Error("VidStorm: encryption failed")
        }

        // encryptId() already returns base64url (A-Z a-z 0-9 '-' '_'),
        // which is URL-safe, so no further URLEncoder pass is needed.
        val apiUrl = "$API_BASE/$kind/$encrypted"
        Log.d(TAG, "🔎 VidStorm API: $apiUrl  (plain=$plain)")

        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "VidStorm API fetch failed: ${e.message}")
            return@withContext Result.Error("VidStorm: API unreachable")
        }

        resolveSources(body)
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  Source resolution                                                  //
    // ─────────────────────────────────────────────────────────────────── //

    /**
     * Parses the VidStorm API JSON object and returns the first playable
     * direct URL. Tries every source that carries a `url`; for playlist
     * sources (hellstorm.lol) it fetches the playlist JSON and picks the
     * highest-resolution direct file.
     */
    private fun resolveSources(body: String): Result {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "VidStorm response not JSON: ${body.take(120)}")
            return Result.Error("VidStorm: bad response")
        }

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Origin" to "https://vidstorm.ru"
        )

        val keys = root.keys()
        // Collect sources that actually have a URL, preserving order.
        val liveSources = mutableListOf<Pair<String, JSONObject>>()
        while (keys.hasNext()) {
            val name = keys.next()
            val src = root.optJSONObject(name) ?: continue
            val url = src.optString("url", "")
            if (url.isNotBlank() && url != "null") {
                liveSources += name to src
            }
        }

        if (liveSources.isEmpty()) {
            Log.w(TAG, "VidStorm: no sources with a URL for this title.")
            return Result.Error("VidStorm: no streams available for this title")
        }

        Log.d(TAG, "VidStorm live sources: ${liveSources.joinToString { it.first }}")

        // First pass: direct sources (type=hls / .m3u8 / .mp4 directly).
        for ((name, src) in liveSources) {
            val url = src.optString("url", "")
            val type = src.optString("type", "")
            if (type == "hls" || url.contains(".m3u8") ||
                (url.contains(".mp4") && !url.contains("hellstorm.lol"))
            ) {
                Log.i(TAG, "✅ VidStorm direct: $name → $url")
                return Result.Stream(url, headers, providerName = "VidStorm·$name")
            }
        }

        // Second pass: playlist sources (hellstorm.lol / type=mp4) — fetch the
        // playlist JSON and pick the highest-resolution direct file.
        for ((name, src) in liveSources) {
            val url = src.optString("url", "")
            val direct = resolvePlaylist(url, name) ?: continue
            Log.i(TAG, "✅ VidStorm playlist: $name → $direct")
            return Result.Stream(direct, headers, providerName = "VidStorm·$name")
        }

        Log.w(TAG, "VidStorm: all sources were null/empty for this title.")
        return Result.Error("VidStorm: streams not yet available for this title")
    }

    /**
     * Fetches a playlist URL (e.g. hellstorm.lol) and returns the highest
     * resolution direct `.mp4` URL from its JSON array
     * `[{ "resolution": N, "url": "..." }]`.
     */
    private fun resolvePlaylist(playlistUrl: String, name: String): String? {
        return try {
            val text = fetchJson(playlistUrl)
            val arr = org.json.JSONArray(text)
            var best: String? = null
            var bestRes = -1
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val res = item.optInt("resolution", 0)
                val u = item.optString("url", "")
                if (u.isNotBlank() && u.startsWith("http") && res > bestRes) {
                    bestRes = res
                    best = u
                }
            }
            if (best == null) Log.w(TAG, "VidStorm playlist $name had no usable url")
            best
        } catch (e: Exception) {
            Log.w(TAG, "VidStorm playlist $name fetch failed: ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  AES encryption (mirrors the FilmCave `mZ` function)                //
    // ─────────────────────────────────────────────────────────────────── //

    /**
     * AES-256-CBC encrypts [plaintext] and returns a base64url string with
     * padding stripped — exactly like the JS:
     *   ct = AES.encrypt(s, Utf8(key), { iv: Utf8(key.substring(0,16)) })
     *   ct = ct.ciphertext.toString(Base64)
     *   ct = ct.replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'')
     */
    private fun encryptId(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // CryptoJS's `.ciphertext.toString(Base64)` is the raw ciphertext
        // base64-encoded (no IV prefix). doFinal() returns raw ciphertext.
        // Android Base64 with NO_WRAP avoids line breaks.
        val b64 = Base64.encodeToString(ct, Base64.NO_WRAP)
        // base64url: + -> -, / -> _, strip trailing '=' (mirrors the JS).
        return b64.replace("+", "-").replace("/", "_").replace("=+$".toRegex(), "")
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  HTTP helper                                                        //
    // ─────────────────────────────────────────────────────────────────── //

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", REFERER)
            .header("Origin", "https://vidstorm.ru")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }
}
