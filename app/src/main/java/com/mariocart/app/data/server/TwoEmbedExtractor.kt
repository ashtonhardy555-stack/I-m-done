package com.mariocart.app.data.server

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TwoEmbedExtractor — resolves **direct playable** HLS stream URLs from the
 * 2embed.to provider, a large movie + TV + anime library indexed by TMDB id.
 *
 * ## Why this extractor exists
 *
 * 2embed.to is one of the broadest free embed libraries on the web — it
 * indexes an enormous catalogue of movies, TV shows, and anime by TMDB id
 * and serves them through a Vidcloud/rabbitstream backend. This gives the
 * race another large, independent upstream that frequently has titles the
 * vid-src-family providers don't.
 *
 * ## How it works (ported from the reference `parnexcodes/2embed-api`)
 *
 *  1. **Fetch embed page** —
 *     `GET https://www.2embed.to/embed/tmdb/{type}?id={tmdbId}[&s={season}&e={episode}]`
 *     returns an HTML page listing server buttons (`.item-server`).
 *
 *  2. **Find Vidcloud server id** — parse the HTML for the `.item-server`
 *     element whose text contains "Vidcloud" and read its `data-id`.
 *
 *  3. **Resolve rabbitstream URL** —
 *     `GET https://www.2embed.to/ajax/embed/play?id={sourceId}` returns
 *     `{ link: "https://rabbitstream.net/embed-4/…?…" }`.
 *
 *  4. **Fetch rabbitstream page** — `GET {rabbitstreamUrl}` (swapping
 *     `embed-5`→`embed-4`) returns HTML with `#vidcloud-player[data-id]`.
 *
 *  5. **Get sources** —
 *     `GET {rabbitstreamHost}/ajax/embed-4/getSources?id={playerDataId}`
 *     returns `{ sources: "[{…\"file\":\"https://…m3u8\"}]" | <encrypted>, … }`.
 *     If `sources` is already valid JSON, parse it directly. (If it is an
 *     AES-encrypted string we lack the live key for, we fall back to any
 *     `file` URL we can find, or return Error — the race continues to other
 *     providers.)
 *
 *  6. **Return** the first `file` (m3u8) URL to ExoPlayer with the
 *     rabbitstream Referer header.
 *
 * This extractor does NOT implement the Google reCAPTCHA token flow (it is
 * brittle and breaks frequently). Many 2embed.to titles resolve without a
 * token on the ajax endpoints; for the rest, the race falls through to the
 * other providers. This is the **unverified-fallback** pattern: we return the
 * resolved URL to ExoPlayer even if a bare OkHttp probe can't confirm it —
 * ExoPlayer is the real arbiter.
 */
object TwoEmbedExtractor {

    private const val TAG = "TwoEmbed"

    private const val TWOEMBED_URL = "https://www.2embed.to"

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

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Result type                                                                                          //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "TwoEmbed"
        ) : Result()

        /** Extraction found nothing usable. */
        data class Error(val message: String) : Result()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Public API                                                                                           //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /**
     * Resolve a direct playable HLS stream for the given TMDB content.
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
        val type = if (isTv) "tv" else "movie"

        // Step 1: Fetch the embed page.
        val embedPage = try {
            fetchEmbedPage(type, tmdbId, if (isTv) season else null, if (isTv) episode else null)
        } catch (e: Exception) {
            Log.w(TAG, "Embed page fetch failed: ${e.message}")
            return@withContext Result.Error("TwoEmbed: embed page fetch failed")
        }
        if (embedPage.isBlank() || embedPage.contains("404 Page Not Found")) {
            Log.w(TAG, "No embed page for $type/$tmdbId")
            return@withContext Result.Error("TwoEmbed: not available")
        }

        // Step 2: Find the Vidcloud server data-id.
        val sourceId = findVidcloudSourceId(embedPage)
        if (sourceId.isNullOrBlank()) {
            Log.w(TAG, "No Vidcloud server in embed page for $type/$tmdbId")
            return@withContext Result.Error("TwoEmbed: no Vidcloud server")
        }
        Log.d(TAG, "🔎 TwoEmbed Vidcloud source id: $sourceId")

        // Step 3: Resolve the rabbitstream URL via the ajax play endpoint.
        val rabbitUrl = resolveRabbitstreamUrl(sourceId)
        if (rabbitUrl.isNullOrBlank()) {
            Log.w(TAG, "ajax/play returned no link for source $sourceId")
            return@withContext Result.Error("TwoEmbed: no rabbitstream link")
        }
        Log.d(TAG, "🔎 TwoEmbed rabbitstream url: $rabbitUrl")

        // Step 4: Fetch the rabbitstream page and get the player data-id.
        val playerDataId = fetchRabbitstreamPlayerId(rabbitUrl)
        if (playerDataId.isNullOrBlank()) {
            Log.w(TAG, "No player data-id on rabbitstream page")
            return@withContext Result.Error("TwoEmbed: no player id")
        }

        // Step 5: Get sources from rabbitstream's ajax endpoint.
        val hlsUrl = getRabbitstreamSources(rabbitUrl, playerDataId)
        if (hlsUrl.isNullOrBlank() || !looksPlayable(hlsUrl)) {
            Log.w(TAG, "No playable HLS source from rabbitstream")
            return@withContext Result.Error("TwoEmbed: no playable source")
        }

        Log.i(TAG, "✅ TwoEmbed stream: $hlsUrl")
        Result.Stream(
            url = hlsUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to rabbitUrl,
                "Origin" to hostOf(rabbitUrl)
            ),
            providerName = "TwoEmbed"
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Step implementations                                                                                //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /** Step 1: fetch the 2embed.to embed page HTML. */
    private fun fetchEmbedPage(type: String, tmdbId: Int, season: Int?, episode: Int?): String {
        val url = StringBuilder("$TWOEMBED_URL/embed/tmdb/$type?id=$tmdbId").apply {
            if (season != null) append("&s=").append(season)
            if (episode != null) append("&e=").append(episode)
        }.toString()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$TWOEMBED_URL/")
            .header("Accept", "text/html, */*")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw java.io.IOException("empty body")
        }
    }

    /**
     * Step 2: find the `.item-server` element whose text contains "Vidcloud"
     * and return its `data-id`.
     */
    private fun findVidcloudSourceId(html: String): String? {
        // The server buttons look like:
        //   <div class="item-server" data-id="12345" ...>Vidcloud</div>
        // We scan for "Vidcloud" occurrences and grab the nearest preceding
        // data-id attribute.
        val itemRegex = Regex(
            "<div[^>]*class=\"[^\"]*item-server[^\"]*\"[^>]*data-id=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</div>",
            RegexOption.IGNORE_CASE
        )
        for (m in itemRegex.findAll(html)) {
            val id = m.groupValues[1]
            val label = m.groupValues[2]
            if (label.contains("Vidcloud", ignoreCase = true) && id.isNotBlank()) return id
        }
        // Fallback: try a looser pattern that allows attributes in any order.
        val looseRegex = Regex(
            "data-id=\"([^\"]+)\"[^>]*>([\\s\\S]{0,60}?)</\\w+>",
            RegexOption.IGNORE_CASE
        )
        for (m in looseRegex.findAll(html)) {
            val id = m.groupValues[1]
            val label = m.groupValues[2]
            if (label.contains("Vidcloud", ignoreCase = true) && id.isNotBlank()) return id
        }
        return null
    }

    /**
     * Step 3: `GET {TWOEMBED_URL}/ajax/embed/play?id={sourceId}` →
     * `{ link: "https://rabbitstream.net/embed-4/…?…" }`.
     */
    private fun resolveRabbitstreamUrl(sourceId: String): String? {
        val url = "$TWOEMBED_URL/ajax/embed/play?id=$sourceId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$TWOEMBED_URL/")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, */*")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "ajax/play HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                json.optString("link").orEmpty().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ajax/play error: ${e.message}")
            null
        }
    }

    /**
     * Step 4: fetch the rabbitstream page (swapping `embed-5`→`embed-4`),
     * parse `#vidcloud-player[data-id]`.
     */
    private fun fetchRabbitstreamPlayerId(rabbitUrl: String): String? {
        val fetchUrl = rabbitUrl.replace("embed-5", "embed-4")
        val req = Request.Builder()
            .url(fetchUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", hostOf(fetchUrl))
            .header("Accept", "text/html, */*")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "rabbitstream page HTTP ${resp.code}")
                    return null
                }
                val html = resp.body?.string() ?: return null
                // Look for id="vidcloud-player" ... data-id="..."
                val idRegex = Regex(
                    "id=\"vidcloud-player\"[^>]*data-id=\"([^\"]+)\"",
                    RegexOption.IGNORE_CASE
                )
                idRegex.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                    // Fallback: any data-id on a player element.
                    ?: Regex("data-id=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "rabbitstream page error: ${e.message}")
            null
        }
    }

    /**
     * Step 5: `GET {rabbitHost}/ajax/embed-4/getSources?id={playerDataId}` →
     * `{ sources: <json-string | encrypted>, … }`. If `sources` is valid JSON
     * we parse it and return the first `file` URL.
     */
    private fun getRabbitstreamSources(rabbitUrl: String, playerDataId: String): String? {
        val host = hostOf(rabbitUrl)
        val ajaxUrl = "$host/ajax/embed-4/getSources?id=$playerDataId"
        val req = Request.Builder()
            .url(ajaxUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", rabbitUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, */*")
            .get()
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "getSources HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val sourcesRaw = json.opt("sources") ?: return null
                // sources can be a JSON array string, an encrypted string, or
                // already a JSON array (rare). Try to parse as a string first.
                val sourcesStr = sourcesRaw.toString()
                extractFileUrl(sourcesStr)
            }
        } catch (e: Exception) {
            Log.d(TAG, "getSources error: ${e.message}")
            null
        }
    }

    /**
     * Given the `sources` value (a JSON-array string of `[{file, …}]` or an
     * encrypted blob), try to extract the first playable `file` URL.
     * If it's encrypted (not parseable JSON), we attempt a last-ditch regex
     * scan for an http(s) m3u8/mp4 URL inside the blob.
     */
    private fun extractFileUrl(sourcesStr: String): String? {
        // Case 1: valid JSON array string.
        if (sourcesStr.trimStart().startsWith("[")) {
            try {
                val arr = org.json.JSONArray(sourcesStr)
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val file = s.optString("file").orEmpty()
                    if (file.isNotBlank() && looksPlayable(file)) return file
                }
            } catch (e: Exception) {
                Log.d(TAG, "sources not a JSON array: ${e.message}")
            }
        }
        // Case 2: encrypted blob — last-ditch regex for a playable URL.
        val urlRegex = Regex("https?://[^\"'\\s]+\\.(?:m3u8|mp4|mkv|mpd)[^\"'\\s]*", RegexOption.IGNORE_CASE)
        return urlRegex.find(sourcesStr)?.value?.takeIf { looksPlayable(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//
    //  Helpers                                                                                              //
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════//

    /** Extract the scheme://host from a URL. */
    private fun hostOf(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return ""
        val hostStart = schemeEnd + 3
        val pathStart = url.indexOf('/', hostStart)
        return if (pathStart < 0) url else url.substring(0, pathStart)
    }

    /** Quick heuristic — accept .m3u8 / .mp4 / .mkv / .mpd URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http") && (
            lower.contains(".m3u8") ||
                lower.contains(".mp4") ||
                lower.contains(".mkv") ||
                lower.contains(".mpd") ||
                lower.contains("/playlist/") ||
                lower.contains("/hls/") ||
                lower.contains("manifest")
            )
    }
}
