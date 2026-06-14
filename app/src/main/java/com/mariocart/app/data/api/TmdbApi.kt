package com.mariocart.app.data.api

import com.mariocart.app.data.model.TmdbResponse
import com.mariocart.app.data.model.TvSeasonsResponse
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {

    // Trending
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
    @GET("discover/{type}")
    suspend fun discover(
        @Path("type") type: String,
        @Query("api_key") apiKey: String,
        @Query("with_genres") genreId: String? = null,
        @Query("sort_by") sortBy: String = "popularity.desc",
        @Query("with_original_language") language: String = "en",
        @Query("page") page: Int = 1
    ): TmdbResponse

    // Search
    @GET("search/{type}")
    suspend fun search(
        @Path("type") type: String,
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String = "en-US",
        @Query("region") region: String = "US",
        @Query("year") year: String? = null,
        @Query("page") page: Int = 1
    ): TmdbResponse
}
