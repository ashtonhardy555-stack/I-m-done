package com.freeflix.app.data.server

import android.util.Log
import com.freeflix.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * KissKhExtractor — resolves **direct playable** stream URLs from the
 * KissKH provider (`kisskh.do`) using only plain HTTP (no WebView).
 *
 * ## Why this extractor exists
 *
 * KissKH is a **title-based** Asian-drama / TV aggregator with very broad
 * TV-episode coverage — a strong complement to MeowTV for the "shows don't
 * have all episodes" gap. In live verification the full pipeline returned a
 * direct, byte-range-capable HLS URL (`HTTP 206`, `application/vnd.apple.mpegurl`):
 *
 *  1. `GET https://kisskh.do/api/DramaList/Search?q=Game+of+Thrones` → list of dramas
 *  2. `GET https://enc-dec.app/api/enc-kisskh?text={dramaId}&type=vid` → `{ result: "<kkey>" }`
 *  3. `GET https://kisskh.do/api/DramaList/Episode/{dramaId}.png?err=false&ts=&time=&kkey={kkey}`
 *     → `[ { "id":…, "number":1, "Video":"https://hls…/…m3u8" }, … ]`
 *
 * The `Video` field of each episode object is **already a direct playable
 * HLS URL** — no further decryption is needed for the video itself. (The
 * `dec-kisskh` endpoint is only used for subtitle URLs, which we don't need
 * for playback.)
 *
 * ## How it works
 *
 *  1. **TMDB lookup** — resolve the title + year for the given TMDB id
 *     (single HTTP call to TMDB's `movie/{id}` or `tv/{id}`).
 *  2. **Search KissKH** by title, pick the best match (same name, closest
 *     year, or first result).
 *  3. **Encrypt the drama id** via `enc-dec.app/api/enc-kisskh?text={id}&type=vid`
 *     to obtain the `kkey`.
 *  4. **Fetch episodes** for the drama; for the requested season/episode,
 *     pick the matching episode object and return its `Video` URL.
 *
 * Verification is advisory: the CDN accepts byte-range requests (confirmed
 * 206 in testing), so we trust the URL and let ExoPlayer be the arbiter.
 *
 * For movies, KissKH lists episodes too (often a single "episode 1"); we
 * simply take the first/last episode as the movie source.
 */
object KissKhExtractor {

    private const val TAG = "KissKH"

    private const val KISSKH_SEARCH = "https://kisskh.do/api/DramaList/Search"
    private const val KISSKH_EPISODE = "https://kisskh.do/api/DramaList/Episode"
    private const val ENC_DEC = "https://enc-dec.app/api/enc-kisskh"

    private const val REFERER = "https://kisskh.do/"
    private const val ORIGIN = "https://kisskh.do"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

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

    sealed class Result {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String = "KissKH"
        ) : Result()

        data class Error(val message: String) : Result()
    }

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
        val isTv = contentType == "tv"

        // Step 1: Resolve title (+ year for matching) from TMDB.
        val info = try {
            resolveTmdbInfo(tmdbId, isTv)
        } catch (e: Exception) {
            Log.w(TAG, "TMDB lookup failed: ${e.message}")
            return@withContext Result.Error("KissKH: TMDB lookup failed")
        }
        if (info.title.isBlank()) {
            Log.w(TAG, "No title from TMDB for $tmdbId")
            return@withContext Result.Error("KissKH: no title")
        }
        Log.d(TAG, "🔍 KissKH TMDB info: \"${info.title}\" (${info.year})")

        // Step 2: Search KissKH by title.
        val dramas = try {
            searchDramas(info.title)
        } catch (e: Exception) {
            Log.w(TAG, "KissKH search failed: ${e.message}")
            return@withContext Result.Error("KissKH: search failed")
        }
        if (dramas.length() == 0) {
            Log.w(TAG, "KissKH: no results for \"${info.title}\"")
            return@withContext Result.Error("KissKH: no results")
        }
        // Pick best match: prefer an exact title match, else first result.
        val drama = pickBestDrama(dramas, info) ?: dramas.optJSONObject(0)
            ?: return@withContext Result.Error("KissKH: no drama object")
        val dramaId = drama.optInt("id")
        Log.d(TAG, "KissKH picked drama id=$dramaId title=\"${drama.optString("title").take(40)}\"")

        // Step 3: Encrypt the drama id → kkey.
        val kkey = try {
            encryptId(dramaId)
        } catch (e: Exception) {
            Log.w(TAG, "KissKH enc failed: ${e.message}")
            return@withContext Result.Error("KissKH: encrypt failed")
        } ?: return@withContext Result.Error("KissKH: no kkey")

        // Step 4: Fetch episodes and pick the matching one.
        val episodes = try {
            fetchEpisodes(dramaId, kkey)
        } catch (e: Exception) {
            Log.w(TAG, "KissKH episode fetch failed: ${e.message}")
            return@withContext Result.Error("KissKH: episode fetch failed")
        }
        if (episodes.length() == 0) {
            Log.w(TAG, "KissKH: no episodes for drama $dramaId")
            return@withContext Result.Error("KissKH: no episodes")
        }

        val target = pickEpisode(episodes, isTv, season, episode)
            ?: return@withContext Result.Error("KissKH: no matching episode")
        val videoUrl = target.optString("Video").orEmpty()
        if (videoUrl.isBlank() || !looksPlayable(videoUrl)) {
            Log.w(TAG, "KissKH: no playable Video for s${season}e${episode}")
            return@withContext Result.Error("KissKH: no video url")
        }
        Log.i(TAG, "✅ KissKH stream: ${videoUrl.take(80)}")
        Result.Stream(
            url = videoUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to REFERER,
                "Origin" to ORIGIN
            ),
            providerName = "KissKH"
        )
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  KissKH API helpers                                                   //
    // ─────────────────────────────────────────────────────────────────────//

    /** `GET /api/DramaList/Search?q={title}` → JSONArray of dramas. */
    private fun searchDramas(title: String): JSONArray {
        val url = "$KISSKH_SEARCH?q=${URLEncoder.encode(title, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", REFERER)
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("search HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty search body")
            return JSONArray(body)
        }
    }

    /**
     * Pick the best-matching drama: an exact (case-insensitive) title match,
     * else the drama whose release year is closest to the TMDB year, else the
     * first result.
     */
    private fun pickBestDrama(dramas: JSONArray, info: TmdbInfo): JSONObject? {
        val targetTitle = info.title.trim().lowercase()
        // Exact title match.
        for (i in 0 until dramas.length()) {
            val d = dramas.optJSONObject(i) ?: continue
            if (d.optString("title").trim().lowercase() == targetTitle) return d
        }
        // Year match (KissKH dramas carry a "releaseDate" / "startDate").
        if (info.year.isNotBlank()) {
            for (i in 0 until dramas.length()) {
                val d = dramas.optJSONObject(i) ?: continue
                val rd = d.optString("releaseDate").ifBlank { d.optString("startDate") }
                if (rd.startsWith(info.year)) return d
            }
        }
        return null
    }

    /** `GET enc-kisskh?text={id}&type=vid` → the kkey string. */
    private fun encryptId(dramaId: Int): String? {
        val req = Request.Builder()
            .url("$ENC_DEC?text=$dramaId&type=vid")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("enc HTTP ${resp.code}")
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            if (json.optInt("status") != 200) {
                Log.d(TAG, "enc-kisskh err: ${json.optString("error")}")
                return null
            }
            // result is the kkey string.
            val r = json.opt("result") ?: return null
            return when (r) {
                is String -> r
                else -> r.toString()
            }
        }
    }

    /** `GET /api/DramaList/Episode/{id}.png?err=false&ts=&time=&kkey={kkey}` → JSONArray. */
    private fun fetchEpisodes(dramaId: Int, kkey: String): JSONArray {
        val url = "$KISSKH_EPISODE/$dramaId.png?err=false&ts=&time=&kkey=${URLEncoder.encode(kkey, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("episode HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw java.io.IOException("empty episode body")
            return JSONArray(body)
        }
    }

    /**
     * Pick the episode object matching the requested season/episode.
     *
     * KissKH lists episodes flat (by episode number within a season) and may
     * not always carry a season field. We first look for an exact
     * `season`+`number` match; failing that, for a flat series we map the
     * requested (season, episode) onto a cumulative episode index; for
     * movies we take the last episode (typically the full film).
     */
    private fun pickEpisode(
        episodes: JSONArray,
        isTv: Boolean,
        season: Int,
        episode: Int
    ): JSONObject? {
        // Exact season+number match.
        for (i in 0 until episodes.length()) {
            val ep = episodes.optJSONObject(i) ?: continue
            val epSeason = ep.optInt("season", ep.optInt("Season", 1))
            val epNumber = ep.optInt("number", ep.optInt("Number", 0))
            if (epSeason == season && epNumber == episode) return ep
        }
        // Flat series — cumulative episode index. Sum episode counts of prior
        // seasons (assume 24/season as a common heuristic if no season info).
        if (isTv && season > 1) {
            val perSeason = 24
            val targetIndex = (season - 1) * perSeason + episode - 1
            if (targetIndex in 0 until episodes.length()) {
                return episodes.optJSONObject(targetIndex)
            }
        }
        // Movies or season 1: direct episode-number match.
        for (i in 0 until episodes.length()) {
            val ep = episodes.optJSONObject(i) ?: continue
            val epNumber = ep.optInt("number", ep.optInt("Number", 0))
            if (epNumber == episode) return ep
        }
        // Fallback: last episode (movie = full film).
        return episodes.optJSONObject(episodes.length() - 1)
    }

    // ─────────────────────────────────────────────────────────────────────//
    //  TMDB info resolution                                                 //
    // ─────────────────────────────────────────────────────────────────────//

    private data class TmdbInfo(val title: String, val year: String)

    private suspend fun resolveTmdbInfo(tmdbId: Int, isTv: Boolean): TmdbInfo {
        val type = if (isTv) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId" +
            "?api_key=${BuildConfig.TMDB_API_KEY}"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get().build()
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

    /** Quick heuristic — accept .m3u8 / .mp4 / .mkv / HLS / CDN URLs. */
    private fun looksPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mkv") ||
            lower.contains(".mpd") ||
            lower.contains("/hls") ||
            lower.contains("manifest")
    }
}
