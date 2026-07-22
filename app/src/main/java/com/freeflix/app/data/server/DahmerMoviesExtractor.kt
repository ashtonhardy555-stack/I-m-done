package com.freeflix.app.data.server

import android.util.Log
import com.freeflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * DahmerMoviesExtractor — resolves **direct playable** file URLs from the
 * DahmerMovies open directory (`a.111477.xyz`), routed through a bulk proxy
 * worker (`p.111477.xyz/bulk?u=`).
 *
 * ## Why this extractor exists
 *
 * DahmerMovies is an **open-directory** host: movies live at
 * `/movies/{Title} ({Year})/` and TV episodes at
 * `/tvs/{Title}/Season {NN}/`. Each directory is a plain Apache/Nginx-style
 * file listing (HTML `<table>` of `<a href>` links to `.mkv`/`.mp4` files).
 * Because the files are **direct, progressive-download** URLs (not HLS
 * manifests and not behind a JavaScript embed), ExoPlayer can start playback
 * almost instantly with a simple `Range: bytes=0-` request — this is the
 * fastest possible playback path and is excellent for the "loads fast"
 * requirement.
 *
 * It covers movies AND TV. For TV we filter the parsed file list to the
 * specific episode (`SxxExx` or `Exx` pattern) when possible.
 *
 * ## How it works (reverse-engineered from the reference DahmerMovies provider)
 *
 *  1. **TMDB lookup** — resolve the title and year for the given TMDB id.
 *
 *  2. **Locate directory** — try candidate directory paths:
 *     - Movies: `/movies/{Title} ({Year})/`
 *     - TV: `/tvs/{Title}/Season 0{N}/` and `/tvs/{Title}/Season {N}/`
 *     The first path that returns an HTML listing wins.
 *
 *  3. **Parse links** — regex-scan the HTML for `<a href>` entries whose
 *     text matches a video extension (`.mkv|.mp4|.avi|.webm|.m3u8`). Extract
 *     href + size (from the adjacent `<td>`).
 *
 *  4. **Filter + sort** — for TV, filter to the requested episode. Sort so
 *     4K/2160p files come first (best quality).
 *
 *  5. **Build direct URL** — resolve the relative `href` to an absolute
 *     `https://a.111477.xyz/…` URL, then wrap it with the bulk proxy worker:
 *     `https://p.111477.xyz/bulk?u={directUrl}`. The proxy serves the file
 *     with permissive CORS/Range so ExoPlayer can stream it directly.
 *
 *  6. **Return** the first usable file URL to ExoPlayer with the correct
 *     headers (`Referer: https://a.111477.xyz/`, `Range: bytes=0-`).
 *
 * Verification is advisory: we always return the resolved URL to ExoPlayer —
 * it is the real arbiter. The bulk proxy is designed to be directly
 * streamable, so a bare OkHttp probe that fails does NOT drop the URL.
 */
object DahmerMoviesExtractor {

    private const val TAG = "DahmerMovies"

    private const val DAHMER_API = "https://a.111477.xyz"
    private const val DAHMER_WORKER = "https://p.111477.xyz/bulk?u="

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val tmdbClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Result type                                                                                          //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "DahmerMovies"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Public API                                                                                           //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /**
     * Resolve a direct playable file URL for the given TMDB content.
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

        // Step 1: Resolve title and year from TMDB.
        val info = try {
            resolveTmdbInfo(tmdbId, isTv)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB lookup failed: ${e.message}")
            return@withContext Result.Error("DahmerMovies: TMDB lookup failed")
        }
        if (info.title.isBlank()) {
            Log.w(TAG, "No title from TMDB for $tmdbId")
            return@withContext Result.Error("DahmerMovies: no title")
        }
        Log.d(TAG, "🔎 DahmerMovies TMDB info: \"${info.title}\" (${info.year})")

        // Step 2: Locate the directory listing.
        val dir = try {
            fetchDirectory(info.title, info.year, if (isTv) season else null)
        } catch (e: Exception) {
            Log.w(TAG, "Directory fetch failed: ${e.message}")
            return@withContext Result.Error("DahmerMovies: directory not found")
        }
        if (dir == null) {
            Log.w(TAG, "No directory for \"${info.title}\"")
            return@withContext Result.Error("DahmerMovies: directory not found")
        }

        // Step 3: Parse the file links from the HTML listing.
        var paths = parseLinks(dir.html)
        if (paths.isEmpty()) {
            Log.w(TAG, "No video files in directory ${dir.dirUrl}")
            return@withContext Result.Error("DahmerMovies: no files")
        }

        // Step 4: For TV, filter to the specific episode when possible.
        if (isTv) {
            val epStr = episode.toString().padStart(2, '0')
            val seStr = season.toString().padStart(2, '0')
            val epFiltered = paths.filter { p ->
                val name = p.text.lowercase()
                name.contains("s${seStr}e${epStr}") || name.contains("e${epStr}")
            }
            if (epFiltered.isNotEmpty()) paths = epFiltered
        }

        // Sort 4K/2160p first.
        paths = paths.sortedByDescending { p ->
            if (Regex("2160p|4k", RegexOption.IGNORE_CASE).containsMatchIn(p.text)) 1 else 0
        }

        // Step 5: Build the direct (proxied) URL for the first usable file.
        val path = paths.first()
        val directUrl = buildDirectUrl(path.href, dir.dirUrl)
        if (!looksPlayable(directUrl)) {
            Log.w(TAG, "Resolved URL not playable: ${directUrl.take(80)}")
            return@withContext Result.Error("DahmerMovies: URL not playable")
        }

        val finalUrl = DAHMER_WORKER + encodeURI(directUrl)
        Log.i(TAG, "✅ DahmerMovies stream: $finalUrl")
        Result.Stream(
            url = finalUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$DAHMER_API/",
                "Accept" to "*/*",
                "Range" to "bytes=0-"
            ),
            providerName = "DahmerMovies"
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Directory fetch + link parsing                                                                      //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    private data class Directory(val html: String, val dirUrl: String)

    private data class FileLink(val text: String, val href: String, val size: String)

    /**
     * Try candidate directory paths and return the first that returns an HTML
     * listing.
     */
    private fun fetchDirectory(title: String, year: String, season: Int?): Directory? {
        val cleanTitle = title.replace(":", "")
        val variants: List<String> = if (season != null) {
            val sPadded = if (season < 10) "0$season" else season.toString()
            listOf(
                "/tvs/${URLEncoder.encode(cleanTitle, "UTF-8")}/Season%20$sPadded/",
                "/tvs/${URLEncoder.encode(cleanTitle, "UTF-8")}/Season%20$season/"
            )
        } else {
            listOf("/movies/${URLEncoder.encode("$cleanTitle ($year)", "UTF-8")}/")
        }

        for (variant in variants) {
            val url = DAHMER_API + variant
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$DAHMER_API/")
                    .header("Accept", "text/html, */*")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use null
                        if (body.isNotBlank()) return Directory(body, url)
                    }
                    null
                }
            } catch (e: Exception) {
                Log.d(TAG, "fetchDirectory variant $variant failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Regex-scan the HTML listing for `<tr>` rows containing an `<a href>` to
     * a video file. Returns a list of (text, href, size).
     */
    private fun parseLinks(html: String): List<FileLink> {
        val links = mutableListOf<FileLink>()
        val rowRegex = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
        val videoExt = Regex("\\.(mkv|mp4|avi|webm|m3u8)$", RegexOption.IGNORE_CASE)
        for (rowMatch in rowRegex.findAll(html)) {
            val rowContent = rowMatch.groupValues[1]
            val linkMatch = Regex("<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>([^<]*)</a>", RegexOption.IGNORE_CASE)
                .find(rowContent) ?: continue
            val href = linkMatch.groupValues[1]
            val text = linkMatch.groupValues[2].trim()
            if (text.isBlank() || href == "../") continue
            if (!videoExt.containsMatchIn(text)) continue
            val sizeMatch = Regex("<td[^>]*>(\\d+(?:\\.\\d+)?\\s?[KMGT]B)</td>", RegexOption.IGNORE_CASE)
                .find(rowContent)
            val size = sizeMatch?.groupValues?.get(1)?.trim() ?: "N/A"
            links.add(FileLink(text, href, size))
        }
        return links
    }

    /**
     * Resolve a (possibly relative) href to an absolute `https://a.111477.xyz/…`
     * URL, collapsing duplicate slashes (but preserving the `://`).
     */
    private fun buildDirectUrl(href: String, dirUrl: String): String {
        val absolute = when {
            href.startsWith("http") -> href
            href.contains("/movies/") || href.contains("/tvs/") -> {
                DAHMER_API + (if (href.startsWith("/")) "" else "/") + href
            }
            else -> dirUrl + href
        }
        // Collapse duplicate slashes (but keep the scheme's //).
        return decodeURI(absolute.replace(Regex("([^:])/\\/+/"), "$1/"))
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  TMDB info resolution                                                                                 //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    private data class TmdbInfo(val title: String, val year: String)

    private suspend fun resolveTmdbInfo(tmdbId: Int, isTv: Boolean): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId" +
            "?api_key=${BuildConfig.TMDB_API_KEY}"

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return tmdbClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("TMDB HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty TMDB body")
            val json = JSONObject(body)
            val title = json.optString("title").ifBlank { json.optString("name") }
            val dateStr = json.optString("release_date").ifBlank { json.optString("first_air_date") }
            val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""
            TmdbInfo(title, year)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Helpers                                                                                              //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /** encodeURI equivalent — percent-encode but preserve reserved chars. */
    private fun encodeURI(str: String): String =
        URLEncoder.encode(str, "UTF-8")
            .replace("%3A", ":")
            .replace("%2F", "/")
            .replace("%3F", "?")
            .replace("%3D", "=")
            .replace("%26", "&")
            .replace("%23", "#")
            .replace("+", "%20")

    /** decodeURI equivalent — best-effort URL-decode. */
    private fun decodeURI(str: String): String =
        try { java.net.URLDecoder.decode(str, "UTF-8") } catch (e: Exception) { str }

    /** Quick heuristic — accept .mp4 / .m3u8 / .mkv / .mpd / .avi / .webm URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && (
            lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mkv") ||
                lower.contains(".mpd") ||
                lower.contains(".avi") ||
                lower.contains(".webm")
            )
    }
}
