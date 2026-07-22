package com.ashtonhardy.piratesfilmcove.data.server

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

    /**
     * VidStorm-compatible streaming API bases (extracted from the FilmCave
     * bundle). `autoembed.pro` is a public mirror of `vidstorm.ru` — same
     * `/api/{kind}/{encrypted}` path, same AES key, same element-named
     * sources (Lithium/Hydrogen/Boron/…). Trying both doubles extraction
     * redundancy: when one origin is rate-limited or down the other usually
     * still resolves the same direct URLs.
     */
    private val API_BASES = listOf(
        "https://vidstorm.ru/api",
        "https://autoembed.pro/api"
    )

    /**
     * AES-256 key + IV used by the FilmCave player to encrypt the id segment.
     * Key is 32 bytes (AES-256); IV is the first 16 bytes of the key.
     * Reverse-engineered from `Zw="x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9"`.
     */
    private val AES_KEY = "x7k9mPqT2rWvY8zA5bC3nF6hJ2lK4mN9".toByteArray(Charsets.UTF_8)
    private val AES_IV = AES_KEY.copyOf(16)

    /** Origin to send as Referer on the proxied media URLs (CDN check).
     *  Now derived dynamically per API base (vidstorm.ru / autoembed.pro). */
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
            // 4s/4s — tight enough that a dead CDN URL (the "Boron" CF-Worker
            // that 200s an HTML 404 for Interstellar/Green Mile) is rejected
            // in ~0.5-0.7s on a normal connection, and a hanging TCP
            // connection (no RST) fails in 4s instead of 6s. This shaves
            // ~0.2s off the VidStorm dead-end path so the parallel VidSrc
            // racer wins even sooner.
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
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
        //
        // Try every API base (vidstorm.ru -> autoembed.pro mirror). The first
        // base that returns a *verified* stream wins. We go sequentially
        // because the bases return identical URLs -- racing them would just
        // double the request load. If a base is unreachable or returns no
        // verified source, we fall through to the next.
        var lastError: Result.Error = Result.Error("VidStorm: no API base responded")
        for (base in API_BASES) {
            val apiUrl = "$base/$kind/$encrypted"
            Log.d(TAG, "VidStorm API: $apiUrl  (plain=$plain)")

            val body = try {
                fetchJson(apiUrl)
            } catch (e: Exception) {
                Log.w(TAG, "VidStorm API $base fetch failed: ${e.message}")
                lastError = Result.Error("VidStorm: $base unreachable")
                continue
            }

            val origin = base.substringBefore("/api")
            when (val res = resolveSources(body, origin)) {
                is Result.Stream -> return@withContext res
                is Result.Error -> {
                    Log.w(TAG, "VidStorm $base: ${res.message} -- trying next base")
                    lastError = res
                }
            }
        }
        lastError
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
    private fun resolveSources(body: String, origin: String = "https://vidstorm.ru"): Result {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "VidStorm response not JSON: ${body.take(120)}")
            return Result.Error("VidStorm: bad response")
        }

        // The proxied media URLs (Cloudflare Workers, hellstorm.lol) check the
        // Origin/Referer against the API origin that issued them. vidstorm.ru
        // and its autoembed.pro mirror share the same backing CDN, but to be
        // safe we send the origin that served this response.
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/",
            "Origin" to origin
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

        // Best unverified fallbacks (direct + playlist). We collect a
        // well-formed URL for each pass even when verifyUrl() says "no",
        // because the OkHttp side-channel probe is NOT the real arbiter of
        // playability. Many CDNs reply 403/401 to a bare OkHttp GET (no
        // browser session / different Range handling) while STILL serving
        // ExoPlayer perfectly once ExoPlayer sends the Referer/User-Agent/
        // Range headers it is configured with. Dropping such URLs here —
        // as v13 did — is the root cause of "only a handful of videos play":
        // the resolved stream is thrown away before ExoPlayer ever sees it.
        // We now prefer verified URLs, but ALWAYS fall back to a best
        // unverified one so ExoPlayer gets a chance to play it.
        var bestUnverifiedDirect: Pair<String, Map<String, String>>? = null
        var bestUnverifiedPlaylist: Pair<String, Map<String, String>>? = null

        // Cap how many direct sources we bother probing. Verification is now
        // only a PREFERENCE (we always fall back to an unverified URL), so
        // walking every source with a 4s-timeout probe would make shows with
        // many sources load slowly for no benefit. We probe at most the first
        // few (English-first, so the best candidates); if none verify we
        // immediately hand the best unverified URL to ExoPlayer. This is the
        // "shows load slowly" fix: a 12-source episode no longer waits
        // 12×4s before it starts playing.
        val MAX_DIRECT_PROBES = 3
        var directProbes = 0

        // First pass: direct sources (type=hls / .m3u8 / .mp4 directly).
        // Walk through them and return the first one that verifies.
        for ((name, src) in sortedSources) {
            val url = src.optString("url", "")
            val type = src.optString("type", "")
            if (type == "hls" || url.contains(".m3u8") ||
                (url.contains(".mp4") && !url.contains("hellstorm.lol"))
            ) {
                // Track a well-formed direct URL as the unverified fallback
                // (English sources are already first, so the first one we
                // see is the best English candidate).
                if (bestUnverifiedDirect == null && url.startsWith("http")) {
                    bestUnverifiedDirect = url to headers
                }
                // Stop probing after the cap — we have a fallback, no point
                // making the user wait through every dead source.
                if (directProbes >= MAX_DIRECT_PROBES) {
                    Log.d(TAG, "VidStorm: direct-probe cap reached ($MAX_DIRECT_PROBES) — keeping best unverified")
                    break
                }
                directProbes++
                if (verifyUrl(url, headers, name)) {
                    Log.i(TAG, "✅ VidStorm direct: $name → $url")
                    return Result.Stream(url, headers, providerName = "VidStorm·$name")
                }
                Log.w(TAG, "✗ VidStorm direct source $name failed verification, trying next")
            }
        }

        // Second pass: playlist sources (hellstorm.lol / type=mp4) — fetch the
        // playlist JSON and pick the highest-resolution direct file.
        // Capped at MAX_DIRECT_PROBES for the same slow-shows reason.
        var playlistProbes = 0
        for ((name, src) in sortedSources) {
            if (playlistProbes >= MAX_DIRECT_PROBES) break
            val url = src.optString("url", "")
            val direct = resolvePlaylist(url, name)
            if (direct != null && direct.startsWith("http") && bestUnverifiedPlaylist == null) {
                bestUnverifiedPlaylist = direct to headers
            }
            if (direct == null) continue
            playlistProbes++
            if (verifyUrl(direct, headers, name)) {
                Log.i(TAG, "✅ VidStorm playlist: $name → $direct")
                return Result.Stream(direct, headers, providerName = "VidStorm·$name")
            }
            Log.w(TAG, "✗ VidStorm playlist source $name failed verification, trying next")
        }

        // ── Unverified fallback ──
        // Every well-formed source failed the side-channel probe. Rather than
        // dropping the title (the v13 behaviour that left most videos
        // unplayable), hand the best-looking URL to ExoPlayer. ExoPlayer sends
        // its own Range/Referer/User-Agent headers and is the true test of
        // playability. If the CDN is genuinely dead, ExoPlayer errors out and
        // the player falls through to the next stage — exactly as intended.
        val fallback = bestUnverifiedDirect ?: bestUnverifiedPlaylist
        if (fallback != null) {
            val (fbUrl, fbHeaders) = fallback
            Log.w(TAG, "VidStorm: no source passed verification — returning best unverified URL to ExoPlayer: ${fbUrl.take(90)}")
            return Result.Stream(
                url = fbUrl,
                headers = fbHeaders,
                providerName = "VidStorm·unverified"
            )
        }

        Log.w(TAG, "VidStorm: no sources with a usable URL for this title.")
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
        // Derive the Origin/Referer from the URL host so requests to the
        // autoembed.pro mirror send autoembed.pro as Origin (matching what
        // the browser would send) rather than always vidstorm.ru.
        val origin = try {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://vidstorm.ru"
        }
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "$origin/")
            .header("Origin", origin)
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
