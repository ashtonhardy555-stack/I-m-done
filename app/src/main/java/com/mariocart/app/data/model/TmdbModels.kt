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
    @SerializedName("genre_ids") val genreIds: List<Int> = emptyList(),
    /**
     * TMDB's "adult" flag — true for pornographic / adult-only titles.
     * The API is asked for include_adult=false so this should always be
     * false/absent, but we parse it so [ContentRepository] can filter any
     * that slip through as a second layer of defense.
     */
    val adult: Boolean? = null,
    val runtime: Int? = null             // movie runtime in minutes (unused by UI)
) {
    val displayTitle: String get() = title ?: name ?: "Unknown"
    val year: String get() = (releaseDate ?: firstAirDate ?: "").take(4)
    val isMovie: Boolean get() = title != null || mediaType == "movie"
    val contentType: String get() = if (isMovie) "movie" else "tv"

    /**
     * Poster URL — w185 is a small, fast-loading size that still looks crisp
     * at the 140dp card width we display. Using w185 (≈50 KB per image)
     * instead of w342 (≈120 KB) cuts the home screen's image payload by
     * more than half, which is the single biggest win for scroll smoothness.
     */
    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }

    /**
     * Backdrop URL — w780 is large enough for the hero banner (420dp) and the
     * player loading screen, but far smaller than "original" (which can be
     * 3-5 MB per image and caused jank while the hero banner loaded).
     */
    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

    val ratingText: String
        get() = if (voteAverage > 0) String.format("%.1f", voteAverage) else ""

    val isValidMovie: Boolean
        get() = isMovie // All videos identified as movies are valid

    /**
     * Whether this title is flagged as adult/pornographic by TMDB.
     * Used by [com.mariocart.app.data.repository.ContentRepository] to
     * guarantee no adult content ever surfaces in browse/search/home.
     */
    val isAdult: Boolean
        get() = adult == true

    /**
     * Whether the title has actually been released yet, based on its
     * release / first-air date. Items with no date are treated as released.
     *
     * ISO date strings (YYYY-MM-DD) sort lexicographically, so a plain
     * string comparison against today's date is correct.
     */
    val isReleased: Boolean
        get() {
            val date = releaseDate ?: firstAirDate ?: return true
            if (date.length < 7) return true
            val today = run {
                val c = java.util.Calendar.getInstance()
                String.format(
                    "%04d-%02d-%02d",
                    c.get(java.util.Calendar.YEAR),
                    c.get(java.util.Calendar.MONTH) + 1,
                    c.get(java.util.Calendar.DAY_OF_MONTH)
                )
            }
            return date <= today
        }
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
    fun movieUrl(tmdbId: Int): String {
        // Updated for 2026: Use consistent path structure verified in testing
        return when {
            baseUrl.contains("vidlink.pro") -> "$baseUrl/movie/$tmdbId"
            baseUrl.contains("frembed") -> "$baseUrl$tmdbId"
            else -> "$baseUrl/movie/$tmdbId"
        }
    }

    fun tvUrl(tmdbId: Int, season: Int, episode: Int): String {
        // Updated for 2026: Use consistent path structure verified in testing
        return when {
            baseUrl.contains("vidlink.pro") -> "$baseUrl/tv/$tmdbId/$season/$episode"
            else -> "$baseUrl/tv/$tmdbId/$season/$episode"
        }
    }
}

/**
 * Response shape for TMDB's `movie/{id}` / `tv/{id}` endpoint when called with
 * `append_to_response=external_ids`. We only need the [imdbId] field, which is
 * nested under the `external_ids` object. Used by NoTorrentExtractor to map a
 * TMDB id → IMDb id (the NoTorrent Stremio addon keys off IMDb ids).
 */
data class ExternalIdsResponse(
    val id: Int = 0,
    @SerializedName("external_ids") val externalIds: ExternalIds? = null
) {
    /** The IMDb id (e.g. "tt0816692") or null if unavailable. */
    val imdbId: String? get() = externalIds?.imdbId
}

data class ExternalIds(
    @SerializedName("imdb_id") val imdbId: String? = null
)
