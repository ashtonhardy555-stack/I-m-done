package com.freeflix.app.data.repository

import com.freeflix.app.BuildConfig
import com.freeflix.app.data.api.ApiClient
import com.freeflix.app.data.model.MovieDetail
import com.freeflix.app.data.model.StreamingServer
import com.freeflix.app.data.model.TmdbItem
import com.freeflix.app.data.model.TmdbResponse
import com.freeflix.app.data.model.TvSeasonDetail
import com.freeflix.app.data.model.TvSeasonsResponse
import com.freeflix.app.data.model.TvShowDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContentRepository {

    private val api = ApiClient.tmdbApi
    private val key = BuildConfig.TMDB_API_KEY

    /**
     * The legacy streaming-server list kept for compatibility with
     * [com.freeflix.app.ui.player.VideoDownloadService].
     *
     * The actual playback path uses the auto-updating server list from
     * [com.freeflix.app.data.server.RemoteServerListFetcher] (exposed via
     * [com.freeflix.app.data.server.StreamProviders] and ordered by
     * [com.freeflix.app.data.server.ServerManager]). This list is a static
     * fallback and is NOT used for playback ordering.
     */
    val streamingServers: List<StreamingServer> = listOf(
        // Tier 1 — clean, no challenge
        StreamingServer("VidLink",          "https://vidlink.pro"),
        StreamingServer("VidSrc.su",        "https://vidsrc.su"),
        // Tier 2 — working, iframe chains / CF auto-solve
        StreamingServer("2Embed.cc",        "https://www.2embed.cc"),
        StreamingServer("2Embed.skin",      "https://www.2embed.skin"),
        StreamingServer("VidSrc.to",        "https://vidsrc.to"),
        StreamingServer("MultiEmbed",       "https://multiembed.mov"),
        StreamingServer("VidSrc.cc",        "https://vidsrc.cc"),
        StreamingServer("Videasy.net",      "https://player.videasy.net"),
        // Tier 3 — Cloudflare, user captcha fallback
        StreamingServer("Videasy",          "https://player.videasy.to"),
        StreamingServer("VidSrc.in",        "https://vidsrc.in"),
        StreamingServer("VidSrc.fyi",       "https://vidsrc.fyi")
    )

    // ── TMDB Content ─────────────────────────────────────────────────────────

    private fun filterEnglish(items: List<TmdbItem>): List<TmdbItem> =
        items.filter {
            (it.originalLanguage == null || it.originalLanguage == "en") && it.posterPath != null
        }

    /**
     * Drops items that are not valid *movies* — but only from movie content.
     *
     * TMDB's tv endpoints and discover/tv return `name` (not `title`) and do
     * NOT include a `media_type` field, so every TV item correctly has
     * [TmdbItem.isMovie] == false and [TmdbItem.isValidMovie] == false.
     * Naively filtering with `isValidMovie` would therefore discard *all* TV
     * shows — which is exactly the "app only loads movies, not shows" bug.
     *
     * To stay correct across every caller (movie endpoints, discover/tv, and
     * search/multi which mixes both), this filter keeps an item when EITHER it
     * is a valid movie OR it is a TV show. In other words it only rejects
     * malformed movie rows (e.g. a movie with no title) and never rejects a
     * TV show.
     */
    private fun filterValidMovies(items: List<TmdbItem>): List<TmdbItem> =
        items.filter { it.isValidMovie || !it.isMovie }

    /**
     * Drops any title flagged as adult / pornographic by TMDB.
     *
     * This is the **second layer of defense** against adult content. The first
     * layer is the `include_adult=false` parameter sent on every TMDB API call
     * (see [com.freeflix.app.data.api.TmdbApi]), which asks the TMDB server to
     * exclude adult titles from its response. This filter catches anything
     * that slips through — for example if a future endpoint is added without
     * the parameter, or if TMDB's server-side filtering has a gap. Every
     * content-fetch method in this repository pipes its results through this.
     */
    private fun filterAdult(items: List<TmdbItem>): List<TmdbItem> =
        items.filter { !it.isAdult }

    /**
     * Drops items whose release / first-air date is in the future so the user
     * never sees a title they can't actually watch yet. Items with no date
     * are kept (treated as released). Date strings are ISO (YYYY-MM-DD) which
     * sort lexicographically, so a simple string comparison is correct.
     */
    private fun filterReleased(items: List<TmdbItem>): List<TmdbItem> =
        items.filter { it.isReleased }

    suspend fun getTrending(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getTrending(key, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())

    suspend fun getNowPlaying(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getNowPlaying(key, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())

    suspend fun getPopularMovies(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getPopularMovies(key, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())

    suspend fun getTopRatedMovies(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getTopRatedMovies(key, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())

    suspend fun getPopularTV(page: Int = 1): List<TmdbItem> =
        runCatching {
            filterAdult(filterReleased(filterEnglish(api.getPopularTV(key, page = page).results)))
        }.getOrDefault(emptyList())

    suspend fun getAiringToday(page: Int = 1): List<TmdbItem> =
        runCatching {
            filterAdult(filterReleased(filterEnglish(api.getAiringToday(key, page = page).results)))
        }.getOrDefault(emptyList())

    suspend fun getTopRatedTV(page: Int = 1): List<TmdbItem> =
        runCatching {
            filterAdult(filterReleased(filterEnglish(api.getTopRatedTV(key, page = page).results)))
        }.getOrDefault(emptyList())

    suspend fun getTvDetails(tvId: Int): TvSeasonsResponse? =
        runCatching { api.getTvDetails(tvId, key) }.getOrNull()

    /**
     * Fetches full details for a single movie (runtime, full overview, genres,
     * tagline) from TMDB's `movie/{id}` endpoint. Used by the Netflix-style
     * movie detail screen. Returns null on any network/parse failure so the
     * caller can fall back to the lightweight [TmdbItem] it already has.
     */
    suspend fun getMovieDetail(movieId: Int): MovieDetail? =
        runCatching { api.getMovieDetail(movieId, key) }.getOrNull()

    /**
     * Fetches full TV show details (overview, genres, seasons, episode
     * runtime, number of seasons/episodes) from TMDB's `tv/{id}` endpoint.
     * Used by the Netflix-style TV detail screen. Returns null on failure.
     */
    suspend fun getTvShowDetail(tvId: Int): TvShowDetail? =
        runCatching { api.getTvShowDetail(tvId, key) }.getOrNull()

    /**
     * Fetches the full episode list for a season — each episode has its
     * thumbnail (still_path), overview, name, and runtime. This is what
     * powers the Netflix-style episode list where each episode button is
     * its thumbnail with the description below it. Returns null on failure.
     */
    suspend fun getSeasonDetail(tvId: Int, seasonNumber: Int): TvSeasonDetail? =
        runCatching { api.getSeasonDetail(tvId, seasonNumber, key) }.getOrNull()

    /**
     * Fetches a "More Like This" row for a movie using TMDB's discover endpoint
     * sorted by popularity, scoped to the same genres. This mirrors Netflix's
     * "More Like This" / "More Details" rail on the title detail page.
     */
    suspend fun getSimilarMovies(genreIds: List<Int>, excludeId: Int, page: Int = 1): List<TmdbItem> =
        runCatching {
            val genreQuery = genreIds.take(2).joinToString(",")
            val items = filterEnglish(
                api.discover("movie", key, genreId = genreQuery, sortBy = "popularity.desc", page = page).results
            )
            filterValidMovies(filterReleased(filterAdult(items)))
                .filter { it.id != excludeId }
                .take(18)
        }.getOrDefault(emptyList())

    /**
     * Fetches a "More Like This" row for a TV show — discover/tv scoped to
     * the show's genres, sorted by popularity. Mirrors the movie version.
     */
    suspend fun getSimilarTV(genreIds: List<Int>, excludeId: Int, page: Int = 1): List<TmdbItem> =
        runCatching {
            val genreQuery = genreIds.take(2).joinToString(",")
            val items = filterEnglish(
                api.discover("tv", key, genreId = genreQuery, sortBy = "popularity.desc", page = page).results
            )
            filterReleased(filterAdult(items))
                .filter { it.id != excludeId }
                .take(18)
        }.getOrDefault(emptyList())

    suspend fun discover(
        type: String,
        genreId: String? = null,
        sortBy: String = "popularity.desc",
        page: Int = 1
    ): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.discover(type, key, genreId, sortBy = sortBy, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())

    suspend fun search(
        query: String,
        type: String = "multi",
        year: String? = null,
        page: Int = 1
    ): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.search(type, key, query, year = year, page = page).results)
            filterValidMovies(filterReleased(filterAdult(items)))
        }.getOrDefault(emptyList())
}
