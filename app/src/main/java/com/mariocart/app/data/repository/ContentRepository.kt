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

    val streamingServers: List<StreamingServer> = listOf(

        // Tier 0: LookMovie — resolved to a DIRECT stream via the lookmovietomb
        // security-API flow (LookMovieStreamResolver), so no embed WebView is
        // needed.  Fastest possible start-to-play and no ad overlays.
        StreamingServer("LookMovie",        "https://lookmovie2.to/embed"),

        // Tier 1: Direct REST JSON API — fastest, zero ads, always works
        StreamingServer("VidLink",          "https://vidlink.pro"),
        StreamingServer("Videasy",          "https://player.videasy.net"),
        StreamingServer("AutoEmbed",        "https://autoembed.cc/embed"),
        StreamingServer("AutoEmbed.co",     "https://autoembed.co/embed"),
        StreamingServer("VidBinge",         "https://vidbinge.dev/embed"),
        StreamingServer("MoviesAPI",        "https://moviesapi.club/embed"),

        // Tier 2: VidSrc family — all mirrors share same AJAX extractor
        StreamingServer("VidSrc.me",        "https://vidsrc.me/embed"),
        StreamingServer("VidSrc.io",        "https://vidsrc.io/embed"),
        StreamingServer("VidSrc.pm",        "https://vidsrc.pm/embed"),
        StreamingServer("VidSrc.to",        "https://vidsrc.to/embed"),
        StreamingServer("VidSrc.xyz",       "https://vidsrc.xyz/embed"),
        StreamingServer("VidSrc.dev",       "https://vidsrc.dev/embed"),
        StreamingServer("VidSrc.in",        "https://vidsrc.in/embed"),
        StreamingServer("VidSrc.nl",        "https://vidsrc.nl/embed"),
        StreamingServer("VidSrc.su",        "https://vidsrc.su/embed"),
        StreamingServer("VidSrc.lol",       "https://vidsrc.lol/embed"),
        StreamingServer("VidSrc2",          "https://vidsrc2.to/embed"),

        // Tier 3: Generic API probe + HTML scrape fallback
        StreamingServer("Embed.su",         "https://embed.su/embed"),
        StreamingServer("2Embed.cc",        "https://www.2embed.cc/embed"),
        StreamingServer("2Embed.skin",      "https://www.2embed.skin/embed"),
        StreamingServer("2Embed.org",       "https://2embed.org/embed"),
        StreamingServer("EmbedStream",      "https://embedstream.me/embed"),
        StreamingServer("SuperEmbed",       "https://superembed.stream/embed"),
        StreamingServer("RiveStream",       "https://rivestream.live/embed"),
        StreamingServer("Embedrise",        "https://embedrise.com/embed"),
        StreamingServer("VidHub",           "https://vidhub.vip/embed"),
        StreamingServer("PlayEmbed",        "https://playembed.online/embed"),
        StreamingServer("CineHub",          "https://cinehub.pro/embed"),
        StreamingServer("EmbedMe",          "https://embedme.top/embed"),
        StreamingServer("FlixEmbed",        "https://flixembed.net/embed"),
        StreamingServer("VidCloud",         "https://vidcloud.co/embed"),
        StreamingServer("MultiEmbed",       "https://multiembed.mov/embed"),
        StreamingServer("EmbedSito",        "https://embedsito.com/embed"),
        StreamingServer("SmashyStream",     "https://smashystream.com/embed"),
        StreamingServer("Flicky",           "https://flicky.host/embed"),
        StreamingServer("NontonGo",         "https://www.nontongo.win/embed"),
        StreamingServer("Embedder",         "https://embedder.net/e"),
        StreamingServer("EmbedHub",         "https://embedhub.xyz/embed"),
        StreamingServer("VidBinge.me",      "https://vidbinge.me/embed"),
        StreamingServer("StreamWish",       "https://streamwish.to/embed"),
        StreamingServer("FileMoon",         "https://filemoon.sx/embed"),
        StreamingServer("Warezcdn",         "https://embed.warezcdn.net/filme"),
        StreamingServer("Frembed",          "https://frembed.live/api/film.php?id="),
        StreamingServer("EmbedRapo",        "https://embedrapo.com/embed"),
        StreamingServer("NetStream",        "https://netstream.me/embed"),
        StreamingServer("StreamM4u",        "https://streamm4u.app/embed"),
        StreamingServer("CineWorld",        "https://cineworld.cc/embed"),
        StreamingServer("MovEmbed",         "https://movembed.cc/embed"),
        StreamingServer("Player.vip",       "https://player.vip/embed"),
        StreamingServer("NovaCinema",       "https://novacinema.app/embed"),
        StreamingServer("CineHD",           "https://cinehd.xyz/embed"),
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
