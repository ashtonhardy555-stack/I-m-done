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
 *
 * ## Dead-source handling
 *
 * Not every source the API returns is actually alive — some URLs are stale
 * or return a 403/404. Previously we returned the *first* source we found and
 * handed it straight to ExoPlayer; if it was dead the player would fail with
 * a black screen and **no fallback** to the next extraction stage (this is
 * why titles like "Interstellar" didn't play). Now every candidate URL is
 * lightweight-verified (a 2-byte ranged GET) before it is returned, and we
 * walk through *all* live sources until one verifies. Only if none verify do
 * we yield nothing and let the caller fall through to the embed pipeline.
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

    /** A tiny client used to verify a candidate media URL is alive. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Result type                                                        //
    // ────────────────────────────────────────────────────────────────────── //

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

    // ────────────────────────────────────────────────────────────────────── //
    //  Public API                                                         //
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
        Log.d(TAG, "🔍 VidStorm API: $apiUrl  (plain=$plain)")

        val body = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "VidStorm API fetch failed: ${e.message}")
            return@withContext Result.Error("VidStorm: API unreachable")
        }

        resolveSources(body)
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  Source resolution                                                  //
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * Parses the VidStorm API JSON object and returns the first **verified**
     * direct URL. Tries every source that carries a `url`; for playlist
     * sources (hellstorm.lol) it fetches the playlist JSON and picks the
     * highest-resolution direct file. Each candidate is lightweight-verified
     * before being returned so we never hand ExoPlayer a dead URL (which was
     * the root cause of "some titles like Interstellar don't play").
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

        // Sort sources so ENGLISH ones are tried first: a source whose
        // language/flag indicates English (lang contains "eng"/"english", or
        // flag is us/gb/en/au/ca) gets priority. Among same-language groups
        // the original API order is preserved (stable sort). This directly
        // implements the user's request: "pick the best quality ENGLISH video".
        val isEnglish = { src: JSONObject ->
            val lang = src.optString("language", "").lowercase()
            val flag = src.optString("flag", "").lowercase()
            lang.contains("eng") || lang.contains("english") ||
                flag in setOf("us", "gb", "uk", "en", "au", "ca", "ie")
        }
        val sortedSources = liveSources.sortedWith(
            compareByDescending<Pair<String, JSONObject>> { isEnglish(it.second) }
        )

        Log.d(TAG, "VidStorm live sources (English-first): ${sortedSources.joinToString { "${it.first}${if (isEnglish(it.second)) "(en)" else ""}" }}")

        // First pass: direct sources (type=hls / .m3u8 / .mp4 directly).
        // Walk through ALL of them and return the first one that verifies.
        for ((name, src) in sortedSources) {
            val url = src.optString("url", "")
            val type = src.optString("type", "")
            if (type == "hls" || url.contains(".m3u8") ||
                (url.contains(".mp4") && !url.contains("hellstorm.lol"))
            ) {
                if (verifyUrl(url, headers, name)) {
                    Log.i(TAG, "✅ VidStorm direct: $name → $url")
                    return Result.Stream(url, headers, providerName = "VidStorm·$name")
                }
                Log.w(TAG, "✗ VidStorm direct source $name failed verification, trying next")
            }
        }

        // Second pass: playlist sources (hellstorm.lol / type=mp4) — fetch the
        // playlist JSON and pick the highest-resolution direct file.
        for ((name, src) in sortedSources) {
            val url = src.optString("url", "")
            val direct = resolvePlaylist(url, name) ?: continue
            if (verifyUrl(direct, headers, name)) {
                Log.i(TAG, "✅ VidStorm playlist: $name → $direct")
                return Result.Stream(direct, headers, providerName = "VidStorm·$name")
            }
            Log.w(TAG, "✗ VidStorm playlist source $name failed verification, trying next")
        }

        Log.w(TAG, "VidStorm: all sources failed verification for this title.")
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

    private fun verifyUrl(
        url: String,
        headers: Map<String, String>,
        name: String
    ): Boolean {
        val lowerUrl = url.lowercase()
        val isHls = lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/") ||
            lowerUrl.contains("/master") || lowerUrl.contains("manifest")

        // ── HLS / .m3u8: validate the manifest content ──
        if (isHls) {
            return try {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                // Limit how much we read — a master playlist is a few KB.
                // We don't send a Range header here on purpose: a ranged GET
                // returns 206 for the dead-`.` bodies we want to reject.
                builder.get()
                verifier.newCall(builder.build()).execute().use { resp ->
                    val code = resp.code
                    if (code !in 200..299) {
                        Log.d(TAG, "VidStorm verify $name: HLS HTTP $code -> reject")
                        return false
                    }
                    val body = resp.body?.string().orEmpty()
                    val ok = body.contains("#EXTM3U")
                    Log.d(TAG, "VidStorm verify $name: HLS body ${body.length} chars, EXTM3U=$ok")
                    ok
                }
            } catch (e: Exception) {
                Log.d(TAG, "VidStorm verify $name: HLS connection failed (${e.message})")
                false
            }
        }

        // ── Progressive (.mp4 etc.): ranged status + content-type sniff ──
        //
        // CRITICAL: a 2-byte ranged GET returning 206 is NOT enough proof a
        // media URL is playable. VidStorm's hellstorm.lol playlist sources
        // resolve to CDN `.mp4` URLs that gate on a browser session and reply
        // with **HTTP 403 + an HTML "ERROR: ACCESS DENIED" body** to any
        // OkHttp request (no session cookie/token exists outside vidstorm.ru
        // itself). ExoPlayer cannot play an HTML error page, so returning
        // such a URL produces a black screen and — worse — blocks the
        // fallthrough to the VidSrc stage, which *does* resolve TV shows to
        // real #EXTM3U streams.
        //
        // This was the root cause of "shows don't load": VidStorm returned
        // a 403 hellstorm mp4 as "the stream" for every TV episode, the
        // player died on it, and VidSrc was never reached. Now we:
        //  - Reject 403/401/405 outright for progressive URLs. A real media
        //    host answers 200/206/416 to a ranged GET; a CDN that 403s an
        //    unauthenticated OkHttp client will 403 ExoPlayer too.
        //  - Additionally sniff the response: if the body/Content-Type looks
        //    like HTML or text (an error page), reject it even on a 200/206.
        //    Some CDNs wrap "ACCESS DENIED" inside a 200 with text/html.
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            // Range request: read only the first bytes to minimise data.
            builder.header("Range", "bytes=0-3").get()
            verifier.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                // A real media host: 200/206/416. 416 = range not satisfiable
                // but the resource exists (host alive, file may be empty/tiny).
                // 403/401/405/404/5xx = not playable by ExoPlayer over plain
                // OkHttp -> reject so we move on / fall through to VidSrc.
                if (code != 200 && code != 206 && code != 416) {
                    Log.d(TAG, "VidStorm verify $name: progressive HTTP $code -> reject (not 200/206/416)")
                    return false
                }
                // Content-type sniff: an HTML/text body is an error page, not
                // media. Real mp4/m3u8-progressive responses are video/* or
                // application/octet-stream (or have no content-type but binary
                // bytes). A few media CDNs omit Content-Type, so only reject
                // when it *explicitly* declares text/html or text/plain.
                val ct = resp.header("Content-Type")?.lowercase().orEmpty()
                if (ct.startsWith("text/html") || ct.startsWith("text/plain")) {
                    // Peek the body to be sure it's an error page and not a
                    // mislabeled media file (some hosts serve video as
                    // text/plain). If the first bytes are "<" (HTML) or the
                    // classic "ERROR:" sentinel, it's an error page.
                    val peek = resp.peekBody(64L).string()
                    val looksLikeErrorPage =
                        peek.contains("<html", ignoreCase = true) ||
                            peek.contains("<!doctype", ignoreCase = true) ||
                            peek.contains("ERROR:", ignoreCase = true) ||
                            peek.contains("access denied", ignoreCase = true)
                    if (looksLikeErrorPage) {
                        Log.d(TAG, "VidStorm verify $name: progressive HTTP $code but body is an HTML/error page (ct=$ct) -> reject")
                        return false
                    }
                }
                Log.d(TAG, "VidStorm verify $name: progressive HTTP $code OK (ct=$ct)")
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "VidStorm verify $name: progressive connection failed (${e.message})")
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    //  AES encryption (mirrors the FilmCave `mZ` function)                //
    // ────────────────────────────────────────────────────────────────────── //

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

    // ────────────────────────────────────────────────────────────────────── //
    //  HTTP helper                                                        //
    // ────────────────────────────────────────────────────────────────────── //

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
