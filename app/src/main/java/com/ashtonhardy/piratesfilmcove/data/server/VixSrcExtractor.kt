package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * VixSrcExtractor — resolves a **direct playable** HLS stream URL from the
 * VixSrc provider (`vixsrc.to`) using only plain HTTP (no WebView).
 *
 * ## How it works
 *
 * VixSrc exposes a clean 3-step API pipeline that returns a direct `.m3u8`
 * master playlist URL:
 *
 *  1. **API call** — get the embed page path:
 *     - Movie: `GET https://vixsrc.to/api/movie/{tmdbId}`
 *     - TV:    `GET https://vixsrc.to/api/tv/{tmdbId}/{season}/{episode}`
 *     → `{ "src": "/embed/…" }`
 *
 *  2. **Embed page** — fetch the HTML at `https://vixsrc.to{src}`. The HTML
 *     contains a JSON blob with `token`, `expires`, and `url` (the playlist
 *     base URL). We regex these out.
 *
 *  3. **Build & verify** the master HLS URL:
 *     `{playlist}?token={token}&expires={expires}&h=1`
 *     Verify it returns a real `#EXTM3U` manifest.
 *
 * This is a primary extractor (runs in the parallel race alongside VidStorm,
 * VidSrc, and VidLink) because it resolves a direct HLS URL in ~3 HTTP
 * round-trips with no JS execution — fast and reliable on real user devices.
 */
object VixSrcExtractor {

    private const val TAG = "VixSrc"

    private const val BASE_URL = "https://vixsrc.to"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** Separate short-timeout client for URL verification. */
    private val verifier by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Result type                                                          //
    // ───────────────────────────────────────────────────────────────────────//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "VixSrc"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Public API                                                           //
    // ───────────────────────────────────────────────────────────────────────//

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
        val type = if (contentType == "tv") "tv" else "movie"

        // Step 1: Call the VixSrc API to get the embed page path.
        val apiUrl = if (type == "tv") {
            "$BASE_URL/api/tv/$tmdbId/$season/$episode"
        } else {
            "$BASE_URL/api/movie/$tmdbId"
        }
        Log.d(TAG, "🔍 VixSrc API: $apiUrl")

        val apiBody = try {
            fetchJson(apiUrl)
        } catch (e: Exception) {
            Log.w(TAG, "VixSrc API fetch failed: ${e.message}")
            return@withContext Result.Error("VixSrc: API unreachable")
        }

        val srcPath = try {
            JSONObject(apiBody).optString("src")
        } catch (e: Exception) {
            Log.w(TAG, "VixSrc API JSON parse failed: ${e.message}")
            return@withContext Result.Error("VixSrc: invalid API response")
        }
        if (srcPath.isNullOrBlank()) {
            Log.w(TAG, "VixSrc: no src path in API response")
            return@withContext Result.Error("VixSrc: no embed path")
        }
        Log.d(TAG, "🔍 VixSrc src path: $srcPath")

        // Step 2: Fetch the embed page HTML and extract token/expires/playlist.
        val embedUrl = "$BASE_URL$srcPath"
        val embedHtml = try {
            fetchHtml(embedUrl)
        } catch (e: Exception) {
            Log.w(TAG, "VixSrc embed page fetch failed: ${e.message}")
            return@withContext Result.Error("VixSrc: embed unreachable")
        }

        val tokenData = extractTokenData(embedHtml)
        if (tokenData == null) {
            Log.w(TAG, "VixSrc: could not extract token/expires/playlist from embed HTML")
            return@withContext Result.Error("VixSrc: no stream data in embed")
        }

        // Step 3: Build the master HLS URL with token params.
        val (playlist, token, expires) = tokenData
        val sep = if (playlist.contains("?")) "&" else "?"
        val masterUrl = "$playlist${sep}token=$token&expires=$expires&h=1"
        Log.d(TAG, "🔍 VixSrc master URL: $masterUrl")

        // Step 4: Verify the HLS manifest is actually live.
        //
        // The OkHttp side-channel probe is advisory only. VixSrc's CDN is
        // frequently Cloudflare-403 to a bare OkHttp GET while ExoPlayer —
        // which sends a full browser User-Agent + the Referer header we
        // attach — can still play the manifest. In v13 a failed probe here
        // dropped the whole stream before ExoPlayer saw it, which is why so
        // many videos were "unplayable". Now we still PREFER a verified
        // manifest, but if the probe fails we hand the URL to ExoPlayer
        // anyway; ExoPlayer is the real arbiter and will error out (and the
        // player will move to the next stage) only if the stream is truly dead.
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to apiUrl
        )
        if (!verifyHls(masterUrl, headers)) {
            Log.w(TAG, "VixSrc: playlist verification failed (probe inconclusive) — returning URL to ExoPlayer anyway")
        }

        Log.i(TAG, "✅ VixSrc stream: $masterUrl")
        Result.Stream(
            url = masterUrl,
            headers = headers,
            providerName = "VixSrc"
        )
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Token extraction from embed HTML                                     //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * Extracts the `token`, `expires`, and `url` (playlist) values from the
     * embed page HTML. These appear as JSON keys in a script blob:
     *   `token":"abc123","expires":"1700000000","url":"https://…/playlist"`
     *
     * Returns null if any of the three are missing or the token is expired.
     */
    private fun extractTokenData(html: String): Triple<String, String, String>? {
        val token = TOKEN_PATTERN.matcher(html).let { m ->
            if (m.find()) m.group(1) else null
        } ?: return null

        val expires = EXPIRES_PATTERN.matcher(html).let { m ->
            if (m.find()) m.group(1) else null
        } ?: return null

        val playlist = PLAYLIST_PATTERN.matcher(html).let { m ->
            if (m.find()) m.group(1) else null
        } ?: return null

        // Reject expired tokens (with 60s grace period).
        val expiresMs = expires.toLongOrNull()?.times(1000) ?: return null
        if (expiresMs - 60_000 < System.currentTimeMillis()) {
            Log.d(TAG, "VixSrc: token is expired (expires=$expires)")
            return null
        }

        return Triple(playlist, token, expires)
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Verification                                                         //
    // ───────────────────────────────────────────────────────────────────────//

    /**
     * Lightweight HLS verification: a small full GET that must return 2xx
     * and contain `#EXTM3U` in the body.
     */
    private fun verifyHls(url: String, headers: Map<String, String>): Boolean {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.get()
            verifier.newCall(builder.build()).execute().use { resp ->
                if (resp.code !in 200..299) {
                    Log.d(TAG, "VixSrc verify: HTTP ${resp.code}")
                    return false
                }
                val body = resp.body?.string().orEmpty()
                val ok = body.contains("#EXTM3U")
                Log.d(TAG, "VixSrc verify: ${body.length} chars, EXTM3U=$ok")
                ok
            }
        } catch (e: Exception) {
            Log.d(TAG, "VixSrc verify: connection failed (${e.message})")
            false
        }
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  HTTP helpers                                                         //
    // ───────────────────────────────────────────────────────────────────────//

    private fun fetchJson(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", BASE_URL)
            .header("Origin", BASE_URL)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    private fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", BASE_URL)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}")
            }
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    // ───────────────────────────────────────────────────────────────────────//
    //  Compiled regex patterns                                              //
    // ───────────────────────────────────────────────────────────────────────//

    // Matches: "token":"value" or "token": "value" or token:"value"
    private val TOKEN_PATTERN =
        Pattern.compile("token[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']")

    private val EXPIRES_PATTERN =
        Pattern.compile("expires[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']")

    // The playlist URL — match a quoted string that looks like an HLS URL.
    // VixSrc embeds it as `"url":"https://…/playlist.m3u8"`.
    private val PLAYLIST_PATTERN =
        Pattern.compile("url[\"']?\\s*[:=]\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']")
}
