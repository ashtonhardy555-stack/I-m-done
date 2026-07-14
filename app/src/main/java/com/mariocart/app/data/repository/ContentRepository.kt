package com.mariocart.app.data.repository

import com.mariocart.app.BuildConfig
import com.mariocart.app.data.api.ApiClient
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.model.TmdbResponse
import com.mariocart.app.data.model.TvSeasonsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

class ContentRepository {

    private val api = ApiClient.tmdbApi
    private val key = BuildConfig.TMDB_API_KEY

    /**
     * The legacy streaming-server list kept for compatibility with
     * [com.mariocart.app.ui.player.VideoDownloadService].
     *
     * The actual playback path uses the auto-updating server list from
     * [com.mariocart.app.data.server.RemoteServerListFetcher] (exposed via
     * [com.mariocart.app.data.server.StreamProviders] and ordered by
     * [com.mariocart.app.data.server.ServerManager]). This list is a static
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
        // Tier 3 — Cloudflare, user captcha fallback
        StreamingServer("Videasy",          "https://player.videasy.to"),
        StreamingServer("VidSrc.in",        "https://vidsrc.in"),
        StreamingServer("VidSrc.fyi",       "https://vidsrc.fyi")
    )

    // ── TMDB Content ───────────────────────────────────────────────────────────

    private fun filterEnglish(items: List<TmdbItem>): List<TmdbItem> =
        items.filter {
            (it.originalLanguage == null || it.originalLanguage == "en") && it.posterPath != null
        }

    private fun filterValidMovies(items: List<TmdbItem>): List<TmdbItem> =
        items.filter { it.isValidMovie }

    suspend fun getTrending(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getTrending(key, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())

    private suspend fun enrichWithRuntime(items: List<TmdbItem>): List<TmdbItem> = withContext(Dispatchers.IO) {
        items.map { item ->
            async {
                if (item.isMovie && item.runtime == null) {
                    runCatching { api.getMovieDetails(item.id, key) }.getOrNull() ?: item
                } else {
                    item
                }
            }
        }.awaitAll()
    }

    suspend fun getNowPlaying(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getNowPlaying(key, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())

    suspend fun getPopularMovies(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getPopularMovies(key, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())

    suspend fun getTopRatedMovies(page: Int = 1): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.getTopRatedMovies(key, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())

    suspend fun getPopularTV(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getPopularTV(key, page = page).results) }
            .getOrDefault(emptyList())

    suspend fun getAiringToday(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getAiringToday(key, page = page).results) }
            .getOrDefault(emptyList())

    suspend fun getTopRatedTV(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getTopRatedTV(key, page = page).results) }
            .getOrDefault(emptyList())

    suspend fun getTvDetails(tvId: Int): TvSeasonsResponse? =
        runCatching { api.getTvDetails(tvId, key) }.getOrNull()

    suspend fun discover(
        type: String,
        genreId: String? = null,
        sortBy: String = "popularity.desc",
        page: Int = 1
    ): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.discover(type, key, genreId, sortBy = sortBy, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())

    suspend fun search(
        query: String,
        type: String = "multi",
        year: String? = null,
        page: Int = 1
    ): List<TmdbItem> =
        runCatching {
            val items = filterEnglish(api.search(type, key, query, year = year, page = page).results)
            val enriched = enrichWithRuntime(items)
            filterValidMovies(enriched)
        }.getOrDefault(emptyList())
}
