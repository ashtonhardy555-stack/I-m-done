package com.mariocart.app.data.api

import com.mariocart.app.data.model.ExternalIdsResponse
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.model.TmdbResponse
import com.mariocart.app.data.model.TvSeasonsResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    // Trending
    // include_adult=false — TMDB never sends adult/pornographic titles so
    // they can never appear in the trending feed.
    @GET("trending/all/week")
    suspend fun getTrending(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    // Movies
    @GET("movie/now_playing")
    suspend fun getNowPlaying(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("movie/top_rated")
    suspend fun getTopRatedMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    // TV Shows
    @GET("tv/popular")
    suspend fun getPopularTV(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("tv/airing_today")
    suspend fun getAiringToday(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    @GET("tv/top_rated")
    suspend fun getTopRatedTV(
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("page") page: Int = 1
    ): TmdbResponse

    // TV Season details
    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): TvSeasonsResponse

    // Discover (genre browsing)
    // include_adult=false keeps pornographic titles out of every genre browse
    // and quick-browse chip. The TMDB discover endpoint honours this param.
    @GET("discover/{type}")
    suspend fun discover(
        @Path("type") type: String,
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_original_language") language: String = "en",
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("page") page: Int = 1
    ): TmdbResponse

    // Search
    // include_adult=false is the critical guard for search: without it a user
    // typing an ambiguous query (or a title that happens to share a name with
    // an adult film) would see pornographic results. With it, the TMDB search
    // endpoint filters them server-side before they ever reach the app.
    @GET("search/{type}")
    suspend fun search(
        @Path("type") type: String,
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("include_adult") includeAdult: Boolean = false,
        @Query("year") year: String? = null,
        @Query("page") page: Int = 1
    ): TmdbResponse

    // Movie Details (includes runtime)
    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US"
    ): TmdbItem

    // ── External IDs ──────────────────────────────────────────────
    // Used by NoTorrentExtractor to map a TMDB id → IMDb id (ttXXXXXXX).
    // The NoTorrent Stremio addon accepts IMDb ids for its stream lookup.
    // We append external_ids to the standard movie/tv detail call so we
    // get the imdb_id in a single round-trip.
    @GET("movie/{movie_id}")
    suspend fun getMovieExternalIds(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "external_ids"
    ): ExternalIdsResponse

    @GET("tv/{tv_id}")
    suspend fun getTvExternalIds(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("append_to_response") append: String = "external_ids"
    ): ExternalIdsResponse
}
