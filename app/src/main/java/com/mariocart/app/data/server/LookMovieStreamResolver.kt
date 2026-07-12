package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LookMovieStreamResolver
 *
 * A faithful Kotlin port of the LookMovie "lookmovietomb" stream-extraction
 * approach found in `advanced_resolver.py`.  It uses LookMovie2.to's
 * **security API flow** to retrieve a *direct* stream URL rather than relying
 * on a slow, brittle HTML scrape of an embed page.
 *
 * Flow (identical to the Python resolver):
 *   1.  GET  https://www.lookmovie2.to/movies/play/{tmdbId}   (movie)
 *       GET  https://www.lookmovie2.to/shows/play/{tmdbId}/{season}/{episode}  (tv)
 *   2.  Parse the `movie_storage` / `show_storage` JS object for `hash` and
 *       `id_movie` / `id_episode`.
 *   3.  GET  https://www.lookmovie2.to/api/v1/security/movie-access?id_movie=X&hash=Y
 *       GET  https://www.lookmovie2.to/api/v1/security/episode-access?id_episode=X&hash=Y
 *   4.  The JSON response contains a `streams` map; the first value is a
 *       direct playable URL (.m3u8 / .mp4).
 *
 * Because the result is a *direct* URL, the player can hand it straight to
 * ExoPlayer — no WebView, no ad overlays, no laggy page rendering.  This is
 * the single biggest win for "less lagging".
 *
 * The resolver also tries a couple of LookMovie mirrors so a single
 * dead domain does not break playback.
 */
object LookMovieStreamResolver {

    private const val TAG = "LookMovieResolver"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    /** LookMovie mirrors, ordered by preference.  Matches the Python resolver
     *  base plus common mirrors used by the Kodi plugin ecosystem. */
    private val mirrors = listOf(
        "https://www.lookmovie2.to",
        "https://lookmovie2.to",
        "https://lookmovie.foundation"
    )

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Result of a resolution attempt. */
    data class ResolveResult(
        val url: String,
        val isDirect: Boolean,
        val server: String = "LookMovie",
        val needsCaptcha: Boolean = false,
        val error: String? = null
    )

    /**
     * Resolve a direct stream URL for the given content.
     *
     * @param tmdbId      TMDB id of the movie / show.
     * @param contentType "movie" or "tv".
     * @param season      Season number (tv only).
     * @param episode     Episode number (tv only).
     * @return a [ResolveResult] whose [ResolveResult.url] is a direct playable
     *         URL when [ResolveResult.isDirect] is true.
     */
    suspend fun resolve(
        tmdbId: Int,
        contentType: String,
        season: Int = 1,
        episode: Int = 1
    ): ResolveResult = withContext(Dispatchers.IO) {
        val isTv = contentType.lowercase() == "tv"
        for (base in mirrors) {
            try {
                val result = resolveFromMirror(base, tmdbId, isTv, season, episode)
                if (result.isDirect && isDirectPlayable(result.url)) {
                    Log.i(TAG, "✅ LookMovie direct stream: ${result.url}")
                    return@withContext result
                }
                // If we hit a captcha wall on this mirror, the other mirrors
                // likely will too — bail out early to save time (less lag).
                if (result.needsCaptcha) {
                    Log.w(TAG, "LookMovie captcha challenge on $base")
                    return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mirror $base failed: ${e.message}")
            }
        }
        ResolveResult(url = "", isDirect = false, error = "All LookMovie mirrors failed")
    }

    private fun resolveFromMirror(
        base: String,
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int
    ): ResolveResult {
        val playUrl = if (isTv) {
            "$base/shows/play/$tmdbId/$season/$episode"
        } else {
            "$base/movies/play/$tmdbId"
        }

        val playReq = Request.Builder()
            .url(playUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$base/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(playReq).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            val lower = html.lowercase()

            // Detect anti-bot challenge (same guards as the Python resolver).
            if (lower.contains("thread defence") ||
                lower.contains("recaptcha") ||
                lower.contains("challenge")
            ) {
                return ResolveResult(
                    url = resp.request.url.toString(),
                    isDirect = false,
                    needsCaptcha = true
                )
            }

            // Extract the storage blob and the access API endpoint.
            val (storageRegex, apiUrl, idKey) = if (isTv) {
                Triple(
                    Regex("""show_storage"\]\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                    "$base/api/v1/security/episode-access",
                    "id_episode"
                )
            } else {
                Triple(
                    Regex("""movie_storage"\]\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL),
                    "$base/api/v1/security/movie-access",
                    "id_movie"
                )
            }

            val storageMatch = storageRegex.find(html) ?: return ResolveResult(
                url = playUrl, isDirect = false, error = "storage blob not found"
            )
            val data = storageMatch.groupValues[1]

            val hash = Regex("""hash\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val idValue = Regex("""$idKey\s*:\s*(\d+)""").find(data)?.groupValues?.get(1)
            if (hash == null || idValue == null) {
                return ResolveResult(url = playUrl, isDirect = false, error = "hash/id not found")
            }

            // Call the security access endpoint — this returns the direct streams.
            val accessUrl = HttpUrlBuilder.build(apiUrl, mapOf(idKey to idValue, "hash" to hash))
            val accessReq = Request.Builder()
                .url(accessUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", playUrl)
                .header("Accept", "application/json, text/plain, */*")
                .build()

            client.newCall(accessReq).execute().use { accessResp ->
                val body = accessResp.body?.string().orEmpty()
                val directUrl = parseStreams(body)
                if (directUrl != null) {
                    return ResolveResult(url = directUrl, isDirect = true)
                }
                return ResolveResult(url = playUrl, isDirect = false, error = "no streams in access response")
            }
        }
    }

    /**
     * Parse the `streams` object from the access API response and return the
     * best (highest quality) direct URL.  The Python resolver took
     * `list(streams.values())[0]`; here we prefer an .m3u8 (HLS) or .mp4 that
     * we know ExoPlayer can play directly.
     */
    private fun parseStreams(body: String): String? {
        return try {
            val json = JSONObject(body)
            val streams = json.optJSONObject("streams") ?: return null
            // Collect all stream values and prefer playable ones.
            val candidates = mutableListOf<String>()
            streams.keys().forEach { key ->
                val value = streams.opt(key)
                if (value is String && value.isNotBlank()) candidates += value
            }
            candidates.firstOrNull { isDirectPlayable(it) }
                ?: candidates.firstOrNull { it.startsWith("http") }
        } catch (e: Exception) {
            // Fall back to a regex scrape in case the response is not clean JSON.
            val regex = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""", RegexOption.IGNORE_CASE)
            regex.find(body)?.groupValues?.get(1)
                ?: Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1)
        }
    }

    /** A URL is "direct playable" if it points at a media file (not an embed page). */
    private fun isDirectPlayable(url: String): Boolean {
        val clean = url.lowercase()
        return clean.startsWith("http") &&
            (clean.contains(".m3u8") || clean.contains(".mp4")) &&
            !clean.contains("embed") && !clean.contains("/play/")
    }
}

/** Tiny URL builder so we avoid pulling in an extra dependency. */
private object HttpUrlBuilder {
    fun build(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$base?$query"
    }
}
