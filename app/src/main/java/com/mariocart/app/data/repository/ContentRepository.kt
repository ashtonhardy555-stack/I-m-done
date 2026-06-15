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

    // ---- Streaming Servers ----
    val streamingServers: List<StreamingServer> = listOf(

        // ── Tier 1: VidSrc family (most reliable) ────────────────────────────
        StreamingServer("VidSrc",           "https://vidsrc.to/embed"),
        StreamingServer("VidSrc.me",        "https://vidsrc.me/embed"),
        StreamingServer("VidSrc Pro",       "https://vidsrc.pro/embed"),
        StreamingServer("VidSrc.dev",       "https://vidsrc.dev/embed"),
        StreamingServer("VidSrc.in",        "https://vidsrc.in/embed"),
        StreamingServer("VidSrc.xyz",       "https://vidsrc.xyz/embed"),
        StreamingServer("VidSrc.cc",        "https://vidsrc.cc/v2/embed"),
        StreamingServer("VidSrc.net",       "https://vidsrc.net/embed"),
        StreamingServer("VidSrc.icu",       "https://vidsrc.icu/embed"),
        StreamingServer("VidSrc.pm",        "https://vidsrc.pm/embed"),
        StreamingServer("VidSrc.nl",        "https://vidsrc.nl/embed"),
        StreamingServer("VidSrc2",          "https://vidsrc2.to/embed"),
        StreamingServer("VidSrc.wtf",       "https://vidsrc.wtf/embed"),
        StreamingServer("VidSrc.lol",       "https://vidsrc.lol/embed"),

        // ── Tier 2: Major embed providers ────────────────────────────────────
        StreamingServer("AutoEmbed",        "https://autoembed.cc/embed"),
        StreamingServer("AutoEmbed.co",     "https://autoembed.co/embed"),
        StreamingServer("2Embed",           "https://2embed.to/embed"),
        StreamingServer("2Embed.org",       "https://2embed.org/embed"),
        StreamingServer("Embed.su",         "https://embed.su/embed"),
        StreamingServer("SuperEmbed",       "https://superembed.stream/embed"),
        StreamingServer("MultiEmbed",       "https://multiembed.mov/embed"),
        StreamingServer("EmbedSito",        "https://embedsito.com/embed"),
        StreamingServer("FlixEmbed",        "https://flixembed.net/embed"),
        StreamingServer("EmbedMe",          "https://embedme.top/embed"),
        StreamingServer("EmbedHub",         "https://embedhub.xyz/embed"),
        StreamingServer("EmbedStream",      "https://embedstream.net/embed"),

        // ── Tier 3: Stream sites ──────────────────────────────────────────────
        StreamingServer("SmashyStream",     "https://smashystream.com/embed"),
        StreamingServer("MoviesAPI",        "https://moviesapi.club/embed"),
        StreamingServer("RiveStream",       "https://rivestream.live/embed"),
        StreamingServer("RiveStream.xyz",   "https://rivestream.xyz/embed"),
        StreamingServer("VidLink",          "https://vidlink.pro/embed"),
        StreamingServer("VidBinge",         "https://vidbinge.dev/embed"),
        StreamingServer("NontonGo",         "https://www.nontongo.win/embed"),
        StreamingServer("StreamWish",       "https://streamwish.to/embed"),
        StreamingServer("FileMoon",         "https://filemoon.sx/embed"),
        StreamingServer("MixDrop",          "https://mixdrop.ag/embed"),
        StreamingServer("DropLoad",         "https://dropload.io/embed"),
        StreamingServer("DoodStream",       "https://dood.to/embed"),
        StreamingServer("StreamTape",       "https://streamtape.com/embed"),

        // ── Tier 4: Movie/TV sites with embed support ─────────────────────────
        StreamingServer("GoMovies",         "https://gomovies.sx/embed"),
        StreamingServer("HiMovies",         "https://himovies.sx/embed"),
        StreamingServer("CineZone",         "https://cinezone.to/embed"),
        StreamingServer("Zoechip",          "https://zoechip.cc/embed"),
        StreamingServer("SFlix",            "https://sflix.to/embed"),
        StreamingServer("LookMovie",        "https://lookmovie2.to/embed"),
        StreamingServer("BFlixz",           "https://bflixz.to/embed"),
        StreamingServer("Flix2Day",         "https://flix2day.to/embed"),
        StreamingServer("123Movies",        "https://w4.123moviesfree.net/embed"),
        StreamingServer("MovieOrca",        "https://movieorca.com/embed"),
        StreamingServer("FlixHQ",           "https://flixhq.click/embed"),
        StreamingServer("AniWatch",         "https://aniwatch.to/embed"),
        StreamingServer("FMovies",          "https://fmovies.ps/embed"),
        StreamingServer("YesMovies",        "https://yesmovies.mn/embed"),
        StreamingServer("SolarMovie",       "https://solarmovie.pe/embed"),
        StreamingServer("PrimeWire",        "https://www.primewire.tf/embed"),

        // ── Tier 5: Alternative / fallback ───────────────────────────────────
        StreamingServer("Phisher",          "https://phisher.app/embed"),
        StreamingServer("2Embed.skin",      "https://www.2embed.skin/embed"),
        StreamingServer("NovaCinema",       "https://novacinema.app/embed"),
        StreamingServer("CineHD",           "https://cinehd.xyz/embed"),
        StreamingServer("TheFlixer",        "https://theflixer.tv/embed"),
        StreamingServer("FlixHive",         "https://flixhive.net/embed"),
        StreamingServer("MovieKex",         "https://moviekex.online/embed"),
        StreamingServer("WatchSeries",      "https://watchseries.im/embed"),
        StreamingServer("StreamM4u",        "https://streamm4u.app/embed"),
        StreamingServer("NetStream",        "https://netstream.me/embed"),
        StreamingServer("WatchMoviesFree",  "https://watchmoviesfree.ac/embed"),
        StreamingServer("EmbedRapo",        "https://embedrapo.com/embed"),
        StreamingServer("Player.vip",       "https://player.vip/embed"),
        StreamingServer("MovEmbed",         "https://movembed.cc/embed"),
        StreamingServer("CineWorld",        "https://cineworld.cc/embed"),
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

    suspend fun discover(
        type: String,
        genreId: String? = null,
        sortBy: String = "popularity.desc",
        page: Int = 1
    ): List<TmdbItem> =
        runCatching {
            filterEnglish(api.discover(type, key, genreId, sortBy = sortBy, page = page).results)
        }.getOrDefault(emptyList())

    suspend fun search(query: String, type: String = "multi", year: String? = null, page: Int = 1): List<TmdbItem> =
        runCatching { filterEnglish(api.search(type, key, query, year = year, page = page).results) }.getOrDefault(emptyList())
}
