package com.mariocart.app.data.model

import com.google.gson.annotations.SerializedName

data class TmdbResponse(
    val page: Int = 1,
    val results: List<TmdbItem> = emptyList(),
    @SerializedName("total_pages") val totalPages: Int = 1,
    @SerializedName("total_results") val totalResults: Int = 0
)

data class TmdbItem(
    val id: Int,
    val title: String? = null,           // movies
    val name: String? = null,            // tv shows
    val overview: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("original_language") val originalLanguage: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList()
) {
    val displayTitle: String get() = title ?: name ?: "Unknown"
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val isMovie: Boolean get() = title != null || mediaType == "movie"
    val contentType: String get() = if (isMovie) "movie" else "tv"

    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }

    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }

    val ratingText: String
        get() = if (voteAverage > 0) String.format("%.1f", voteAverage) else ""
}

data class TvSeasonsResponse(
    val id: Int,
    val seasons: List<TvSeason> = emptyList(),
    @SerializedName("number_of_seasons") val numberOfSeasons: Int = 0
)

data class TvSeason(
    @SerializedName("season_number") val seasonNumber: Int,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    val name: String? = null
)

data class StreamingServer(
    val name: String,
    val baseUrl: String
) {
    fun movieUrl(tmdbId: Int): String = "$baseUrl/movie/$tmdbId"
    fun tvUrl(tmdbId: Int, season: Int, episode: Int): String =
        "$baseUrl/tv/$tmdbId/$season/$episode"
}
