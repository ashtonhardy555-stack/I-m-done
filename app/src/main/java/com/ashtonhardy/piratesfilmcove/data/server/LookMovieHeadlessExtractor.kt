package com.ashtonhardy.piratesfilmcove.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * LookMovieHeadlessExtractor — a pure-OkHttp, **no-WebView, no-Kodi** port of
 * the `plugin.video.lookmovietomb` Kodi addon's extraction flow.
 *
 * ## Why this exists (the user's "Kodi-like headless engine" request)
 *
 * The user asked for "a Kodi-like app that can run the LookMovie tomb addon in
 * the background to pull from" — i.e. get LookMovie working **without a
 * WebView**. LookMovie's nginx 403s plain OkHttp from many client networks, so
 * the old `LookMovieWebExtractor` (WebView) and `StreamExtractor` (OkHttp that
 * 403'd) were both removed from the pipeline in the no-WebView cleanup.
 *
 * This extractor re-implements the addon's *extraction logic* directly in
 * Kotlin/OkHttp (the "headless engine" — no Kodi runtime, no Python, no
 * WebView). It reproduces the addon's exact request sequence, headers, and
 * regex parsing, so it benefits from whatever request shape the addon relies
 * on to reach the server.
 *
 * ## Multi-mirror support (reliability)
 *
 * LookMovie operates a network of official mirror/proxy domains (published at
 * `proxymirrorlookmovie.github.io`). All mirrors use the **identical** URL
 * structure (`/movies/search/...`, `/api/v1/security/...`), so a single
 * extraction flow works against any of them. When one mirror is down, 403s,
 * or shows a reCAPTCHA interstitial, we automatically fall through to the
 * next mirror. This dramatically improves reliability — a single blocked
 * domain no longer kills extraction.
 *
 * ## The flow (a 1:1 port of main.py)
 *
 * 1. **SEARCH** — `GET /movies/search/page/1?q=<title>` (TV: `/shows/search/...`).
 *    Parse HTML for the first `<a href="/movies/view/{id-slug}">` (TV:
 *    `/shows/view/{id-slug}`). We pick the result whose slug best matches the
 *    requested title (and year, when available) to avoid grabbing the wrong
 *    entry on ambiguous searches.
 *
 * 2. **STORAGE** — `GET /movies/play/{id-slug}` (TV: `/shows/play/{id-slug}`).
 *    Regex out the `movie_storage` / `show_storage` JS object to get:
 *      Movie → `hash`, `id_movie`, `expires`
 *      TV    → `hash`, `expires`, and the `seasons` array; we then scan the
 *              seasons array for the episode matching (season, episode) and
 *              read its `id_episode`.
 *
 * 3. **SECURITY API** — `GET /api/v1/security/movie-access?id_movie=&hash=&expires=`
 *    (TV: `/api/v1/security/episode-access?id_episode=&hash=&expires=`) with
 *    `Referer: <play page>` and `X-Requested-With: XMLHttpRequest`.
 *    Returns JSON `{ "streams": {"1080p": "https://...m3u8", ...},
 *                     "subtitles": [...] }`. We take the first stream value
 *    (highest quality, same as the addon's `[x for x in streams.values() if x][0]`).
 *
 * 4. **PLAY** — The addon runs a LOCAL HTTP proxy (`serverHTTP.py`) that injects
 *    the `t_hash={hash}` cookie on every `.m3u8`/segment request. We DON'T need
 *    a proxy: ExoPlayer's `DefaultHttpDataSource.Factory` sends our `headers`
 *    map on **every** segment/playlist fetch, so we attach
 *    `Cookie: t_hash={hash}` to the returned headers and ExoPlayer carries it
 *    automatically — a true headless play with zero extra server process.
 *
 * ## Notes / known limitations
 *
 *  - LookMovie intermittently shows a reCAPTCHA v2 interstitial (the addon's
 *    v0.8 changed the marker from ">Thread Defence" to "g-recaptcha"). Solving
 *    reCAPTCHA headlessly on-device is out of scope here; if the play page
 *    returns the reCAPTCHA interstitial we return `Result.Error` and let the
 *    rest of the parallel race cover the title (the other direct providers).
 *  - If a mirror 403s outright, we fall through to the next mirror. Only when
 *    ALL mirrors fail do we return `Result.Error` and let the race continue.
 */
object LookMovieHeadlessExtractor {

    private const val TAG = "LookMovieHeadless"

    /**
     * Official LookMovie mirror domains (from proxymirrorlookmovie.github.io).
     * All share the identical URL structure, so the extraction flow works
     * against any of them. We try them in order — the first that yields a
     * stream wins. If one is down / 403s / shows reCAPTCHA, we fall through.
     */
    private val MIRRORS = listOf(
        "https://www.lookmovie2.to",
        "https://lookmovie2.to",
        "https://lookmovie.ag",
        "https://lookmovie.site",
        "https://lookmovie2.la",
        "https://lookmovie.buzz",
        "https://lookmovie.click",
        "https://lookmovie.digital",
        "https://lookmovie.clinic",
        "https://lookmovie.foundation",
        "https://lookmovie.fun",
        "https://lookmovie.fyi",
        "https://lookmovie.guru",
        "https://lookmovie.media",
        "https://lookmovie.mobi",
        "https://lookmovie.download"
    )

    // The addon's exact User-Agent (Firefox 115 on Win64). Reusing it maximises
    // the chance the request shape matches what the server expects.
    private const val UA =
        "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    sealed class Result {
        /** A resolved direct HLS stream. [headers] MUST be sent on every request. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String
        ) : Result()

        data class Error(val message: String) : Result()
    }

    /**
     * Resolve a direct playable stream for the given title.
     *
     * Tries each official mirror domain in sequence. The first mirror that
     * completes the full extraction flow (search → storage → security → play)
     * wins. If a mirror fails (down, 403, reCAPTCHA, no results), we fall
     * through to the next one automatically.
     *
     * @param title     the movie/show title to search for
     * @param year      release year (used to disambiguate search results; may be null)
     * @param isMovie   true for movies, false for TV
     * @param season    1-indexed season (TV only)
     * @param episode   1-indexed episode (TV only)
     */
    suspend fun extract(
        title: String,
        year: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): Result = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext Result.Error("LookMovie: no title")

        var lastError = "LookMovie: all mirrors failed"
        for (base in MIRRORS) {
            val result = tryExtractFromMirror(base, title, year, isMovie, season, episode)
            when (result) {
                is Result.Stream -> return@withContext result
                is Result.Error -> {
                    lastError = result.message
                    Log.d(TAG, "mirror $base failed: ${result.message} — trying next")
                }
            }
        }
        Log.w(TAG, "all ${MIRRORS.size} mirrors failed for '$title': $lastError")
        Result.Error(lastError)
    }

    /**
     * Run the full extraction flow against a single mirror domain.
     * Returns a [Result] — Stream on success, Error if this mirror fails
     * (so the caller can try the next mirror).
     */
    private fun tryExtractFromMirror(
        base: String,
        title: String,
        year: String?,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): Result {
        val root = if (isMovie) "movies" else "shows"
        try {
            // —— 1. SEARCH ——
            val query = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "$base/$root/search/page/1?q=$query"
            val searchHtml = get(searchUrl, baseHeaders(referer = "$base/")) ?: run {
                return Result.Error("search failed/403")
            }
            // v0.8 of the addon changed the captcha marker from ">Thread Defence"
            // to "g-recaptcha" — LookMovie now uses Google reCAPTCHA v2 interstitial.
            if (searchHtml.contains("g-recaptcha")) {
                return Result.Error("reCAPTCHA (captcha)")
            }

            val slugRegex = if (isMovie)
                Regex("""/movies/view/([^"'<>]+)""")
            else
                Regex("""/shows/view/([^"'<>]+)""")

            val candidates = slugRegex.findAll(searchHtml)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            if (candidates.isEmpty()) {
                return Result.Error("no results")
            }

            val slug = pickBestSlug(candidates, title, year)
            Log.d(TAG, "[$base] search '$title' → slug '$slug' (of ${candidates.size})")

            // —— 2. STORAGE ——
            val playUrl = "$base/$root/play/$slug"
            val playHtml = get(playUrl, baseHeaders(referer = "$base/$root/search/page/1?q=$query"))
                ?: run {
                    return Result.Error("play fetch failed/403")
                }
            if (playHtml.contains("g-recaptcha")) {
                return Result.Error("reCAPTCHA (captcha)")
            }

            // Normalise quote styles the same way the addon does before regexing.
            val norm = playHtml.replace("\\\"", "'").replace("\'", "\"")

            val hash: String
            val expires: String
            val securityPath: String
            val idParam: Pair<String, String>  // (paramName, paramValue)

            if (isMovie) {
                val storageMatch = Regex("""movie_storage"\]\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)
                    .find(norm)
                    ?: run {
                        return Result.Error("no movie_storage")
                    }
                val storage = storageMatch.groupValues[1]
                hash = extractField(storage, "hash")
                    ?: return Result.Error("no hash")
                val idMovie = extractNum(storage, "id_movie")
                    ?: return Result.Error("no id_movie")
                expires = extractNum(storage, "expires")
                    ?: return Result.Error("no expires")
                securityPath = "/api/v1/security/movie-access"
                idParam = "id_movie" to idMovie
            } else {
                val storageMatch = Regex("""show_storage"\]\s*=\s*(\{.*?\};)""", RegexOption.DOT_MATCHES_ALL)
                    .find(norm)
                    ?: run {
                        return Result.Error("no show_storage")
                    }
                val storage = storageMatch.groupValues[1]
                hash = extractField(storage, "hash")
                    ?: return Result.Error("no hash")
                expires = extractNum(storage, "expires")
                    ?: return Result.Error("no expires")

                // —— Normalise the matched storage block the way the v0.8 addon
                //    does (ListSerial in main.py): collapse escaped quotes,
                //    strip newlines and triple-spaces so the seasons array
                //    parses as one contiguous run.
                val storageClean = storage
                    .replace("\\\"", "'")
                    .replace("\n", "")
                    .replace("   ", "")

                val seasonsMatch = Regex(""""?seasons"?\s*:\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL)
                    .find(storageClean)
                    ?: run {
                        return Result.Error("no seasons")
                    }
                val seasons = seasonsMatch.groupValues[1]

                // —— Find the id_episode for the requested (season, episode) ——
                val episodeObjectRegex = Regex("""(\{.*?\})(?:,|\])""", RegexOption.DOT_MATCHES_ALL)
                val seasonRe = Regex("""(?<![A-Za-z_])["']?season["']?\s*:\s*"?(\d+)?""")
                val episodeRe = Regex("""(?<![A-Za-z_])["']?episode["']?\s*:\s*"?(\d+)?""")
                val idEpisodeRe = Regex("""(?<![A-Za-z_])["']?id_episode["']?\s*:\s*(\d+)""")
                val ep = episodeObjectRegex.findAll(seasons).firstOrNull { obj ->
                    val s = seasonRe.find(obj.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()
                    val e = episodeRe.find(obj.groupValues[1])?.groupValues?.get(1)?.toIntOrNull()
                    s == season && e == episode
                } ?: run {
                    return Result.Error("episode not found")
                }
                val idEpisode = idEpisodeRe.find(ep.groupValues[1])?.groupValues?.get(1)
                    ?: run {
                        return Result.Error("episode not found")
                    }
                securityPath = "/api/v1/security/episode-access"
                idParam = "id_episode" to idEpisode
            }

            // —— 3. SECURITY API ——
            val securityUrl = "$base$securityPath?${idParam.first}=${idParam.second}&hash=$hash&expires=$expires"
            val secBody = get(
                securityUrl,
                baseHeaders(referer = playUrl).plus("X-Requested-With" to "XMLHttpRequest")
            ) ?: run {
                return Result.Error("security API failed/403")
            }

            val json = try { JSONObject(secBody) } catch (e: Exception) {
                return Result.Error("security not JSON")
            }
            val streams = json.optJSONObject("streams")
                ?: run {
                    return Result.Error("no streams")
                }
            val firstKey = streams.keys().asSequence()
                .firstOrNull { key ->
                    streams.optString(key, "").takeIf { it.isNotBlank() }?.startsWith("http") == true
                }
                ?: run {
                    return Result.Error("no valid stream")
                }
            val m3u8 = streams.optString(firstKey, "")
            if (m3u8.isBlank() || !m3u8.startsWith("http")) {
                return Result.Error("invalid stream url")
            }

            Log.i(TAG, "✅ LookMovie [$base] resolved ($slug) [$firstKey]: $m3u8")

            // —— 4. PLAY ——
            // ExoPlayer sends these headers on every playlist + segment fetch,
            // so attaching the t_hash cookie here replaces the addon's local
            // proxy (serverHTTP.py) entirely — a true headless play.
            val playHeaders = baseHeaders(referer = playUrl).plus(
                "Cookie" to "t_hash=$hash"
            )
            Result.Stream(m3u8, playHeaders, "LookMovie")
        } catch (e: Exception) {
            Result.Error(e.message ?: "error")
        }
    }

    // —— helpers ——

    private fun baseHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to referer
    )

    /** Execute a GET, returning the body string or null on non-2xx / error. */
    private fun get(url: String, headers: Map<String, String>): String? {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code in 200..299) resp.body?.string()
                else {
                    Log.d(TAG, "GET $url → ${resp.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url threw ${e.message}")
            null
        }
    }

    /**
     * Extract a quoted string field from a JS object. Handles both unquoted
     * keys (`hash:"abc"`) and quoted keys (`"hash":"abc"`) — the `"?` around
     * [name] makes the regex work either way.
     */
    private fun extractField(js: String, name: String): String? {
        val r = Regex(""""?$name"?\s*:\s*"([^"]+)"""")
        return r.find(js)?.groupValues?.get(1)
    }

    /**
     * Extract a numeric field from a JS object. Handles both unquoted keys
     * (`expires:1700000000`) and quoted keys (`"expires":1700000000`).
     */
    private fun extractNum(js: String, name: String): String? {
        val r = Regex(""""?$name"?\s*:\s*(\d+)""")
        return r.find(js)?.groupValues?.get(1)
    }

    /**
     * Pick the search-result slug that best matches the requested title (and
     * year, if known). LookMovie slugs look like `12345-the-dark-knight-2008`,
     * so a substring match on the normalised title + year is a strong signal.
     */
    private fun pickBestSlug(slugs: List<String>, title: String, year: String?): String {
        val normTitle = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        val yearStr = year?.trim()?.takeIf { it.isNotBlank() }

        fun score(slug: String): Int {
            val s = slug.lowercase()
            var sc = 0
            if (yearStr != null && s.contains(yearStr)) sc += 100
            // Whole-title slug match is the strongest non-year signal.
            if (s.contains(normTitle)) sc += 50
            else {
                // Partial: reward each title word present in the slug.
                val words = normTitle.split('-').filter { it.length > 2 }
                sc += words.count { s.contains(it) } * 5
            }
            // Earlier results are generally more relevant; small tiebreaker.
            sc -= slugs.indexOf(slug).coerceAtMost(10)
            return sc
        }
        return slugs.maxByOrNull { score(it) } ?: slugs.first()
    }
}
