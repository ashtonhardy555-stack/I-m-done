package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.toHttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * StreamExtractor — mirrors the LookMovie Kodi addon
 * (plugin.video.lookmovietomb) extraction logic exactly.
 *
 * Flow (identical to the Kodi addon's default.py / main.py):
 *
 *  1. SEARCH  — LookMovie does NOT use TMDB ids. We search the site by the
 *     content title to obtain LookMovie's own internal id.
 *        Movies: https://www.lookmovie2.to/movies/search/page/1?q=<title>
 *        Shows : https://www.lookmovie2.to/shows/search/page/1?q=<title>
 *
 *  2. STORAGE — Fetch the /view/ page (cookies) then the /play/ page and pull
 *     the `movie_storage` (movies) or `show_storage` (shows) JS object out of
 *     the HTML. From it we read hash / id_movie|id_episode / expires and (for
 *     shows) the seasons array.
 *
 *  3. SECURITY API — Call LookMovie's security endpoint with those params to
 *     receive the real HLS manifest:
 *        Movies: /api/v1/security/movie-access?id_movie=&hash=&expires=
 *        Shows : /api/v1/security/episode-access?id_episode=&hash=&expires=
 *     Both require Referer + X-Requested-With: XMLHttpRequest headers and the
 *     session cookies, exactly like the addon.
 *
 *  4. PLAY — The first non-empty value in the returned `streams` object is the
 *     direct .m3u8 URL.
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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .cookieJar(SimpleCookieJar())
        .build()

    /**
     * Extract a direct playable stream URL using the LookMovie Kodi addon logic.
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
    ): String? = withContext(Dispatchers.IO) {
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
                val streamUrl = when (contentType) {
                    "tv" -> resolveEpisode(base, lmId, season ?: 1, episode ?: 1)
                    else -> resolveMovie(base, lmId)
                }

                if (streamUrl != null && isPlayableManifest(streamUrl)) {
                    Log.i(TAG, "✅ LookMovie stream: $streamUrl")
                    return@withContext streamUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "LookMovie $base failed: ${e.message}")
            }
        }

        Log.e(TAG, "❌ No stream found for \"$title\"")
        null
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Cookies                                                                //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun primeCookies(base: String) {
        runCatching {
            val req = Request.Builder().url(base).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().close()
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

        // The addon parses <div class="movie-item…"> blocks.
        // Each contains an <a href="/movies/view/123"> (or /shows/view/) and a year.
        val itemRegex = Regex("""<div\s+class="movie-item[^>]*>([\s\S]*?)(?=<div\s+class="movie-item|</div>\s*$)""")
        val hrefRegex = Regex("""href="(/(?:movies|shows)/view/(\d+))"""")
        val yearRegex = Regex("""year">(\d{4})<""")

        var bestId: Int? = null
        for (match in itemRegex.findAll(html)) {
            val block = match.value
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

    private fun resolveMovie(base: String, lmId: Int): String? {
        // The addon hits /view/ first (for plot) then /play/ for the storage JSON.
        val playUrl = "$base/movies/play/$lmId"

        val html = fetchHtml(playUrl, base) ?: return null
        val normalized = normalizeQuotes(html)

        // movie_storage"] = ({...})
        val storageRegex = Regex("""movie_storage"\]\s*=\s*(\{[\s\S]*?\})""")
        val storage = storageRegex.find(normalized)?.groupValues?.getOrNull(1) ?: run {
            Log.w(TAG, "No movie_storage in $playUrl")
            return null
        }

        val hash = extractField(storage, "hash") ?: return null
        val idMovie = extractNumber(storage, "id_movie") ?: return null
        val expires = extractNumber(storage, "expires") ?: return null

        return callSecurityApi(
            base,
            "$base/api/v1/security/movie-access",
            mapOf("id_movie" to idMovie.toString(), "hash" to hash, "expires" to expires.toString()),
            playUrl
        )
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  TV extraction  (mirrors ListSerial → show_storage → episode-access)    //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun resolveEpisode(base: String, lmId: Int, season: Int, episode: Int): String? {
        val playUrl = "$base/shows/play/$lmId"

        val html = fetchHtml(playUrl, base) ?: return null
        val normalized = normalizeQuotes(html)

        // show_storage"] = ({...};  — capture up to the closing };
        val storageRegex = Regex("""show_storage"\]\s*=\s*(\{[\s\S]*?\}\s*;)""")
        val storage = storageRegex.find(normalized)?.groupValues?.getOrNull(1) ?: run {
            Log.w(TAG, "No show_storage in $playUrl")
            return null
        }

        val hash = extractField(storage, "hash") ?: return null
        val expires = extractNumber(storage, "expires") ?: return null

        // Find the id_episode matching the requested season/episode.
        // The seasons array holds objects like {season:"1", episode:"2", id_episode:123, ...}
        val idEpisode = findEpisodeId(storage, season, episode) ?: run {
            Log.w(TAG, "No episode S${season}E${episode} found in show_storage")
            return null
        }

        return callSecurityApi(
            base,
            "$base/api/v1/security/episode-access",
            mapOf("id_episode" to idEpisode.toString(), "hash" to hash, "expires" to expires.toString()),
            playUrl
        )
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Security API call  (mirrors the addon's GET with Referer + XHR)        //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun callSecurityApi(
        base: String, apiUrl: String, params: Map<String, String>, referer: String
    ): String? {
        val fullUrl = run {
            val b = apiUrl.toHttpUrl().newBuilder()
            params.forEach { (k, v) -> b.addQueryParameter(k, v) }
            b.build()
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

        // Response JSON: {"streams": {"1080":"https://.../master.m3u8", ...}, "subtitles": [...]}
        // The addon does: [x for x in list(streams.values()) if x][0]
        val streamsRegex = Regex(""""streams"\s*:\s*\{([^}]*)\}""")
        val streamsBlock = streamsRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
        val urlRegex = Regex(""":\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)"""")
        val first = urlRegex.find(streamsBlock)?.groupValues?.getOrNull(1)
        return first?.let { if (it.startsWith("http")) it else "$base$it" }
    }

    // ─────────────────────────────────────────────────────────────────────── //
    //  Helpers                                                                //
    // ─────────────────────────────────────────────────────────────────────── //

    private fun fetchHtml(url: String, referer: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }.getOrNull()
    }

    /** Normalises the JS object quotes so regex extraction is reliable
     *  (the addon does html.replace("\\\"", "'").replace("'", '"')). */
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
        // Grab everything inside seasons: [ ... ]
        val seasonsRegex = Regex("""seasons\s*:\s*(\[[\s\S]*?\])""")
        val seasonsBlock = seasonsRegex.find(storage)?.groupValues?.getOrNull(1) ?: return null

        // Each episode is an object without nested braces: { ... }
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

// ───────────────────────────────────────────────────────────────────────── //
//  In-memory cookie jar so the session cookies (set on /view/ and search)   //
//  are replayed on the security API, exactly like the addon's `sess`.       //
// ───────────────────────────────────────────────────────────────────────── //
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
}
