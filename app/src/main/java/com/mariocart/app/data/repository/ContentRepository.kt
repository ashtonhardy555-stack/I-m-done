package com.mariocart.app.data.repository

import com.mariocart.app.BuildConfig
import com.mariocart.app.data.api.ApiClient
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.model.TmdbResponse
import com.mariocart.app.data.model.TvSeasonsResponse

class ContentRepository {

    private val api = ApiClient.tmdbApi
    private val key = BuildConfig.TMDB_API_KEY

    // ---- Streaming Servers — expanded list for maximum coverage ----
    val streamingServers: List<StreamingServer> = listOf(
        // Tier 1 — most reliable
        StreamingServer("VidSrc", "https://vidsrc.to/embed"),
        StreamingServer("VidSrc Pro", "https://vidsrc.pro/embed"),
        StreamingServer("VidSrc.me", "https://vidsrc.me/embed"),
        StreamingServer("VidLink", "https://vidlink.pro/embed"),
        StreamingServer("AutoEmbed", "https://autoembed.cc/embed"),
        // Tier 2 — solid alternates
        StreamingServer("2Embed", "https://2embed.to/embed"),
        StreamingServer("Embed.su", "https://embed.su/embed"),
        StreamingServer("SmashyStream", "https://smashystream.com/embed"),
        StreamingServer("MoviesAPI", "https://moviesapi.club/embed"),
        StreamingServer("SuperEmbed", "https://superembed.stream/embed"),
        // Tier 3 — extra fallbacks
        StreamingServer("EmbedSito", "https://embedsito.com/embed"),
        StreamingServer("MultiEmbed", "https://multiembed.mov/embed"),
        StreamingServer("NontonGo", "https://www.nontongo.win/embed"),
        StreamingServer("FlixHQ", "https://flixhq.to/embed"),
        StreamingServer("RiveStream", "https://rivestream.live/embed"),
        // Tier 4 — additional sources
        StreamingServer("VidSrc.xyz", "https://vidsrc.xyz/embed"),
        StreamingServer("VidSrc.in", "https://vidsrc.in/embed"),
        StreamingServer("VidSrc.cc", "https://vidsrc.cc/v2/embed"),
        StreamingServer("VidSrc.net", "https://vidsrc.net/embed"),
        StreamingServer("VidBinge", "https://vidbinge.dev/embed"),
        StreamingServer("Flix2Day", "https://flix2day.to/embed"),
        StreamingServer("Movie-Web", "https://movie-web.app/embed"),
        StreamingServer("PrimeWire", "https://primewire.mx/embed"),
        StreamingServer("GoMovies", "https://gomovies.sx/embed"),
        StreamingServer("YesMovies", "https://yesmovies.ag/embed"),
        StreamingServer("FMovies", "https://fmovies.ps/embed"),
        StreamingServer("BFlixz", "https://bflixz.to/embed"),
        StreamingServer("HiMovies", "https://himovies.sx/embed"),
        StreamingServer("CineZone", "https://cinezone.to/embed"),
        StreamingServer("Zoechip", "https://zoechip.cc/embed"),
        StreamingServer("SFlix", "https://sflix.to/embed"),
        StreamingServer("Showbox", "https://showbox.media/embed"),
        StreamingServer("LookMovie", "https://lookmovie2.to/embed"),
        StreamingServer("AniWave", "https://aniwave.to/embed"),
        StreamingServer("123Movies", "https://w4.123moviesfree.net/embed"),
    )

    // ---- TMDB Content ----

    private fun filterEnglish(items: List<TmdbItem>): List<TmdbItem> =
        items.filter { (it.originalLanguage == null || it.originalLanguage == "en") && it.posterPath != null }

    suspend fun getTrending(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getTrending(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getNowPlaying(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getNowPlaying(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getPopularMovies(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getPopularMovies(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getTopRatedMovies(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getTopRatedMovies(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getPopularTV(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getPopularTV(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getAiringToday(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getAiringToday(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getTopRatedTV(page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.getTopRatedTV(key, page = page).results) }.getOrDefault(emptyList())

    suspend fun getTvDetails(tvId: Int): TvSeasonsResponse? =
        runCatching { api.getTvDetails(tvId, key) }.getOrNull()

    suspend fun discover(type: String, genreId: String? = null, page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.discover(type, key, genreId, page = page).results) }.getOrDefault(emptyList())

    suspend fun search(query: String, type: String = "multi", year: String? = null, page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.search(type, key, query, year = year, page = page).results) }.getOrDefault(emptyList())
}
