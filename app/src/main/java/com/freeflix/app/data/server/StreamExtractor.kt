package com.freeflix.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * StreamExtractor — mirrors the LookMovie Kodi addon
 * (plugin.video.lookmovietomb) extraction logic exactly.
 *
 * Flow (identical to the Kodi addon's main.py):
 *
 *  1. SEARCH  — LookMovie does NOT use TMDB ids. We search the site by the
 *     content title to obtain LookMovie's own internal id.
 *        Movies: https://www.lookmovie2.to/movies/search/page/1?q=<title>
 *        Shows : https://www.lookmovie2.to/shows/search/page/1?q=<title>
 *
 *  2. STORAGE — Fetch the /play/ page and pull the `movie_storage` (movies) or
 *     `show_storage` (shows) JS object out of the HTML. From it we read
 *     hash / id_movie|id_episode / expires and (for shows) the seasons array.
 *     If the page contains a `g-recaptcha` challenge we return a [Result.Challenge]
 *     so the app can hand the URL to the user for human verification
 *     (the addon calls resolveCaptcha(); we delegate to the user instead).
 *
 *  3. SECURITY API — Call LookMovie's security endpoint with those params to
 *     receive the real HLS manifest:
 *        Movies: /api/v1/security/movie-access?id_movie=&hash=&expires=
 *        Shows : /api/v1/security/episode-access?id_episode=&hash=&expires=
 *     Both require Referer + X-Requested-With: XMLHttpRequest headers and the
 *     session cookies, exactly like the addon.
 *
 *  4. PLAY — The first non-empty value in the returned `streams` object is the
 *     direct .m3u8 URL. The addon's serverHTTP.py proxies segment requests with
 *     a `t_hash` cookie (== the storage hash); we expose that hash so ExoPlayer
 *     can send it directly.
 */
object StreamExtractor {
    private const val TAG = "StreamExtractor"

    // Same User-Agent the addon uses.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

    // LookMovie mirrors — the addon hard-codes lookmovie2.to; we try mirrors too.
    private val LOOKMOVIE_BASES = listOf(
        "https://www.lookmovie2.to",
        "https://lookmovie2.to"
    )

    // Shared cookie jar so cookies from a verification round-trip persist
    // across the search → play → security-api calls.
    private val cookieJar = SimpleCookieJar()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    // ─────────────────────────────────────────────────────────────────────── //
    //  Result type                                                            //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Outcome of an extraction attempt.
     *
     * - [Stream]   → a direct playable URL + the t_hash cookie value to send
     *                with segment requests (matches serverHTTP.py).
     * - [Challenge]→ LookMovie is showing a reCAPTCHA / human-verification
     *                page. The app should open [challengeUrl] in a WebView for
     *                the user to solve, then retry with the resulting cookies.
     * - [Error]    → extraction failed with a human-readable message.
     */
    sealed class Result {
        data class Stream(val url: String, val tHash: String?, val headers: Map<String, String>) : Result()
        data class Challenge(val challengeUrl: String, val referer: String) : Result()
        data class Error(val message: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Public API                                                             //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Extract a direct playable stream using the LookMovie Kodi addon logic.
     *
     * @param title       display title of the movie/show (from TMDB)
     * @param year        release year (used to disambiguate search results)
     * @param contentType "movie" or "tv"
     * @param season      season number (tv only)
     * @param episode     episode number (tv only)
     */
    suspend fun extract(
        title: String,
        year: String? = null,
        contentType: String,
        season: Int? = null,
        episode: Int? = null
    ): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔎 LookMovie extraction: \"$title\" ($year) $contentType S${season}E${episode}")

        for (base in LOOKMOVIE_BASES) {
            try {
                // 1. Prime cookies (the addon calls CreateCookies() first).
                primeCookies(base)

                // 2. Search the site by title to get LookMovie's internal id.
                val lmId = searchLookMovie(base, title, contentType, year)
                if (lmId == null) {
                    Log.w(TAG, "No LookMovie id found for \"$title\" on $base")
                    continue
                }
                Log.d(TAG, "Found LookMovie id $lmId for \"$title\"")

                // 3. Pull the storage object + resolve via the security API.
                val resolved = when (contentType) {
                    "tv" -> resolveEpisode(base, lmId, season ?: 1, episode ?: 1)
                    else -> resolveMovie(base, lmId)
                }

                when (resolved) {
                    is Resolved.Stream -> {
                        Log.i(TAG, "✅ LookMovie stream: ${resolved.url}")
                        return@withContext Result.Stream(
                            url = resolved.url,
                            tHash = resolved.tHash,
                            headers = mapOf(
                                "Referer" to "$base/",
                                "User-Agent" to USER_AGENT
                            ).let { m ->
                                // The addon's proxy sends the t_hash cookie on
                                // segment requests; we surface it as a Cookie
                                // header so ExoPlayer can send it directly.
                                if (resolved.tHash != null)
                                    m + ("Cookie" to "t_hash=${resolved.tHash}")
                                else m
                            }
                        )
                    }
                    is Resolved.Challenge ->
                        return@withContext Result.Challenge(resolved.url, "$base/")
                    is Resolved.Failed -> { /* try next base */ }
                }
            } catch (e: Exception) {
                Log.w(TAG, "LookMovie $base failed: ${e.message}")
            }
        }

        Log.e(TAG, "❌ No stream found for \"$title\"")
        Result.Error("No stream found for \"$title\". Try another title or check your connection.")
    }

    // Internal resolved types from the storage/security step.
    private sealed class Resolved {
        data class Stream(val url: String, val tHash: String?) : Resolved()
        data class Challenge(val url: String) : Resolved()
        object Failed : Resolved()
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Cookies                                                                //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun primeCookies(base: String) {
        runCatching {
            val req = Request.Builder().url(base).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { /* body intentionally not read; just warming cookies */ }
        }
    }

    /**
     * Inject cookies obtained from a successful human-verification round-trip
     * (VerificationActivity → WebView → CookieManager) into our OkHttp jar so
     * the next extraction attempt is already authenticated.
     */
    fun injectCookies(cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        // Parse "name=value; name2=value2" into Cookie objects. Save them for
        // both the www. and bare-domain hosts so they apply regardless of
        // which mirror the retry lands on.
        val cookies = cookieHeader.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val name = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            if (name.isEmpty()) return@mapNotNull null
            Cookie.Builder().name(name).value(value).build()
        }
        if (cookies.isEmpty()) return

        for (base in LOOKMOVIE_BASES) {
            val uri = java.net.URI(base)
            val host = uri.host ?: continue
            val url = HttpUrl.Builder().scheme("https").host(host).build()
            // Rebuild each cookie with the correct domain for this host.
            val domainCookies = cookies.map { c ->
                Cookie.Builder()
                    .name(c.name)
                    .value(c.value)
                    .domain(url.host)
                    .path("/")
                    .build()
            }
            cookieJar.saveFromResponse(url, domainCookies)
        }
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Search  (mirrors ListMovies / ListSerial search in the addon)          //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun searchLookMovie(
        base: String, title: String, contentType: String, year: String?
    ): Int? {
        val searchPath = if (contentType == "tv") "/shows/search/page/1?q=" else "/movies/search/page/1?q="
        val url = "$base$searchPath${java.net.URLEncoder.encode(title, "UTF-8")}"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", base)
            .build()

        val html = runCatching { client.newCall(req).execute().use { it.body?.string() } }
            .getOrNull() ?: return null

        // The addon finds all <div class="movie-item positions and slices the HTML
        // between them. Each slice contains an <a href="/movies/view/123"> and a year.
        // We replicate that approach because a single-regex with a lookahead fails on
        // the last (or only) result when there is no following movie-item div.
        val marker = "<div class=\"movie-item"
        val positions = ArrayList<Int>()
        var searchFrom = 0
        while (true) {
            val idx = html.indexOf(marker, searchFrom)
            if (idx < 0) break
            positions.add(idx)
            searchFrom = idx + 1
        }
        if (positions.isEmpty()) return null
        positions.add(html.length) // sentinel for slicing the last block

        val hrefRegex = Regex("""href="(/(?:movies|shows)/view/(\d+))"""")
        val yearRegex = Regex("""year">(\d{4})<"""")

        var bestId: Int? = null
        for (i in positions.indices) {
            if (i == positions.size - 1) break // skip sentinel
            val block = html.substring(positions[i], positions[i + 1])
            val href = hrefRegex.find(block) ?: continue
            val id = href.groupValues[2].toIntOrNull() ?: continue

            // Prefer a result whose year matches; otherwise take the first hit
            // (the addon sorts by "newest first" so the top result is usually right).
            if (year.isNullOrBlank()) {
                bestId = id
                break
            }
            val resultYear = yearRegex.find(block)?.groupValues?.getOrNull(1)
            if (resultYear == year.take(4)) {
                bestId = id
                break
            }
            if (bestId == null) bestId = id
        }
        return bestId
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Movie extraction  (mirrors ListLinks → movie_storage → movie-access)   //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun resolveMovie(base: String, lmId: Int): Resolved {
        val playUrl = "$base/movies/play/$lmId"

        // Fetch the /view/ page first (the addon does this for the plot and to
        // warm cookies), then the /play/ page for the storage JSON.
        fetchHtml("$base/movies/view/$lmId", base)

        val resp = fetchResponse(playUrl, base) ?: return Resolved.Failed
        val html = resp.body ?: return Resolved.Failed

        // ── Human verification check (mirrors the addon's g-recaptcha guard) ──
        if (isCaptchaPage(html)) {
            // The challenge is on the play URL (after redirects).
            val challengeUrl = resp.url
            Log.w(TAG, "🤖 reCAPTCHA challenge detected at $challengeUrl")
            return Resolved.Challenge(challengeUrl)
        }

        val normalized = normalizeQuotes(html)
        val storageRegex = Regex("""movie_storage"\]\s*=\s*(\{[\s\S]*?\})""")
        val storage = storageRegex.find(normalized)?.groupValues?.getOrNull(1) ?: run {
            Log.w(TAG, "No movie_storage in $playUrl")
            return Resolved.Failed
        }

        val hash = extractField(storage, "hash") ?: return Resolved.Failed
        val idMovie = extractNumber(storage, "id_movie") ?: return Resolved.Failed
        val expires = extractNumber(storage, "expires") ?: return Resolved.Failed

        val streamUrl = callSecurityApi(
            base,
            "$base/api/v1/security/movie-access",
            mapOf("id_movie" to idMovie, "hash" to hash, "expires" to expires),
            playUrl
        ) ?: return Resolved.Failed

        return Resolved.Stream(streamUrl, hash)
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  TV extraction  (mirrors ListSerial → show_storage → episode-access)    //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun resolveEpisode(base: String, lmId: Int, season: Int, episode: Int): Resolved {
        val playUrl = "$base/shows/play/$lmId"

        fetchHtml("$base/shows/view/$lmId", base)

        val resp = fetchResponse(playUrl, base) ?: return Resolved.Failed
        val html = resp.body ?: return Resolved.Failed

        if (isCaptchaPage(html)) {
            val challengeUrl = resp.url
            Log.w(TAG, "🤖 reCAPTCHA challenge detected at $challengeUrl")
            return Resolved.Challenge(challengeUrl)
        }

        val normalized = normalizeQuotes(html)
        val storageRegex = Regex("""show_storage"\]\s*=\s*(\{[\s\S]*?\}\s*;)""")
        val storage = storageRegex.find(normalized)?.groupValues?.getOrNull(1) ?: run {
            Log.w(TAG, "No show_storage in $playUrl")
            return Resolved.Failed
        }

        val hash = extractField(storage, "hash") ?: return Resolved.Failed
        val expires = extractNumber(storage, "expires") ?: return Resolved.Failed
        val idEpisode = findEpisodeId(storage, season, episode) ?: run {
            Log.w(TAG, "No episode S${season}E${episode} found in show_storage")
            return Resolved.Failed
        }

        val streamUrl = callSecurityApi(
            base,
            "$base/api/v1/security/episode-access",
            mapOf("id_episode" to idEpisode, "hash" to hash, "expires" to expires),
            playUrl
        ) ?: return Resolved.Failed

        return Resolved.Stream(streamUrl, hash)
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Security API call  (mirrors the addon's GET with Referer + XHR)        //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun callSecurityApi(
        base: String, apiUrl: String, params: Map<String, String>, referer: String
    ): String? {
        val fullUrl = run {
            val parsed = java.net.URI(apiUrl)
            val builder = HttpUrl.Builder()
                .scheme(if (parsed.scheme == "http") "http" else "https")
                .host(parsed.host ?: return null)
            val port = parsed.port
            if (port > 0) builder.port(port)
            if (!parsed.path.isNullOrBlank()) builder.addPathSegments(parsed.path.trimStart('/'))
            val query = parsed.rawQuery
            if (!query.isNullOrBlank()) {
                for (pair in query.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) builder.addQueryParameter(pair.substring(0, eq), pair.substring(eq + 1))
                    else builder.addQueryParameter(pair, "")
                }
            }
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            builder.build()
        }

        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()

        val body = runCatching { client.newCall(req).execute().use { it.body?.string() } }
            .getOrNull() ?: return null

        // Try to parse as JSON first (the addon does .json()).
        return runCatching {
            val json = JSONObject(body)
            val streams = json.optJSONObject("streams") ?: return null
            // [x for x in list(streams.values()) if x][0] — first non-empty stream.
            val keys = streams.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val streamUrl = streams.optString(key, "")
                if (streamUrl.isNotBlank() && isPlayableManifest(streamUrl)) {
                    return if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl"
                }
            }
            null
        }.getOrElse {
            // Fallback: regex extraction (in case the JSON isn't valid).
            val streamsRegex = Regex(""""streams"\s*:\s*\{([^}]*)\}""")
            val streamsBlock = streamsRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
            val urlRegex = Regex(""":\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""")
            val first = urlRegex.find(streamsBlock)?.groupValues?.getOrNull(1)
            first?.let { if (it.startsWith("http")) it else "$base$it" }
        }
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Human-verification detection                                           //
    // ─────────────────────────────────────────────────────────────────────── //

    /**
     * Detects whether the page is a human-verification / captcha challenge.
     * Mirrors the addon's `if 'g-recaptcha' in html` check, plus common
     * Cloudflare / access-denied signatures.
     */
    private fun isCaptchaPage(html: String): Boolean {
        if (html.isBlank()) return false
        val lower = html.lowercase()
        return lower.contains("g-recaptcha") ||
            lower.contains("data-sitekey") ||
            lower.contains("recaptcha/api.js") ||
            lower.contains("cf-challenge") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("just a moment") ||           // Cloudflare interstitial
            lower.contains("attention required! cloudflare") ||
            lower.contains("access denied")
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Helpers                                                                //
    // ─────────────────────────────────────────────────────────────────────── //

    /** A simple wrapper around the HTTP response body + final (redirected) URL. */
    private data class HtmlResponse(val body: String?, val url: String)

    private fun fetchResponse(url: String, referer: String): HtmlResponse? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HtmlResponse(resp.body?.string(), resp.request.url.toString())
                } else {
                    Log.w(TAG, "HTTP ${resp.code} for $url")
                    HtmlResponse(null, resp.request.url.toString())
                }
            }
        }.getOrNull()
    }

    private fun fetchHtml(url: String, referer: String): String? =
        fetchResponse(url, referer)?.body

    /** Normalises the JS object quotes so regex extraction is reliable
     *  (the addon does html.replace('\\"', "'").replace("'", '"')). */
    private fun normalizeQuotes(html: String): String =
        html.replace("\\\"", "'").replace("'", "\"")

    /** Extracts a string field value: hash: "abc" → "abc". */
    private fun extractField(text: String, key: String): String? {
        val r = Regex("""$key\s*:\s*"([^"]+)"""")
        return r.find(text)?.groupValues?.getOrNull(1)
    }

    /** Extracts a numeric field value: id_movie: 123 → "123". */
    private fun extractNumber(text: String, key: String): String? {
        val r = Regex("""$key\s*:\s*(\d+)""")
        return r.find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * Walks the seasons array inside the show_storage block and returns the
     * id_episode for the requested season/episode. Mirrors the addon's loop
     * over seasons[0] episode objects.
     */
    private fun findEpisodeId(storage: String, season: Int, episode: Int): String? {
        val seasonsRegex = Regex("""seasons\s*:\s*(\[[\s\S]*?\])""")
        val seasonsBlock = seasonsRegex.find(storage)?.groupValues?.getOrNull(1) ?: return null

        val episodeObjRegex = Regex("""\{([^{}]*)\}""")
        for (m in episodeObjRegex.findAll(seasonsBlock)) {
            val obj = m.groupValues[1]
            val s = extractField(obj, "season")?.toIntOrNull() ?: continue
            val e = extractField(obj, "episode")?.toIntOrNull() ?: continue
            if (s == season && e == episode) {
                return extractNumber(obj, "id_episode")
            }
        }
        return null
    }

    private fun isPlayableManifest(url: String): Boolean {
        val c = url.lowercase()
        return c.startsWith("http") && (c.contains(".m3u8") || c.contains(".mp4"))
    }
}

// ────────────────────────────────────────────────────────────────────────── //
//  In-memory cookie jar so the session cookies (set on /view/ and search)   //
//  are replayed on the security API, exactly like the addon's `sess`.       //
//  Cookies injected from a human-verification round-trip also land here.    //
// ────────────────────────────────────────────────────────────────────────── //
private class SimpleCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val key = url.host
        val list = store.getOrPut(key) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            list.add(c)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store[url.host]?.filter { it.expiresAt > now } ?: emptyList()
    }

    /** Expose all cookies for a host as a "name=value; name2=value2" header. */
    fun cookieHeader(host: String): String =
        (store[host] ?: emptyList())
            .filter { it.expiresAt > System.currentTimeMillis() }
            .joinToString("; ") { "${it.name}=${it.value}" }
}
