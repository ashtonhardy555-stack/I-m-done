package com.freeflix.app.data.model

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
     * Used by [com.freeflix.app.data.repository.ContentRepository] to
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

// ──────────────────────────────────────────────────────────────────────────────
//  TV season + episode detail models                                          //
//  TMDB's `tv/{id}/season/{season_number}` endpoint returns a full episode     //
//  list with per-episode stills (thumbnails), overviews, names, runtimes,     //
//  and air dates — exactly what Netflix's episode list shows.                  //
// ──────────────────────────────────────────────────────────────────────────────

/**
 * A single episode within a season, as returned by TMDB's
 * `tv/{id}/season/{n}` endpoint. `stillPath` is the episode screenshot
 * (thumbnail) that Netflix shows on each episode card; `overview` is the
 * episode synopsis shown beneath it.
 */
data class TvEpisode(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("episode_number") val episodeNumber: Int = 1,
    @SerializedName("season_number") val seasonNumber: Int = 1,
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("runtime") val runtime: Int? = null,
    @SerializedName("air_date") val airDate: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0
) {
    /** Episode screenshot URL — w300 is a small, fast thumbnail size. */
    val stillUrl: String?
        get() = stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }

    /** Formatted runtime like "48m" or empty. */
    val runtimeText: String
        get() = if (runtime != null && runtime > 0) "${runtime}m" else ""
}

/**
 * Full season detail from `tv/{id}/season/{n}` — the episode list with
 * per-episode metadata (stills + overviews) for the Netflix-style episode
 * list.
 */
data class TvSeasonDetail(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("season_number") val seasonNumber: Int = 1,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("episodes") val episodes: List<TvEpisode> = emptyList(),
    @SerializedName("air_date") val airDate: String? = null
)

/**
 * Full TV show detail from `tv/{id}` — used by the detail screen to show
 * the show overview, genres, number of seasons, and episode runtime.
 * Mirrors [TmdbItem] but with the richer fields TMDB returns from the
 * detail endpoint (genres as objects, episode_run_time array, etc.).
 */
data class TvShowDetail(
    val id: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("number_of_seasons") val numberOfSeasons: Int = 0,
    @SerializedName("number_of_episodes") val numberOfEpisodes: Int = 0,
    @SerializedName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    val genres: List<TmdbGenre> = emptyList(),
    val seasons: List<TvSeason> = emptyList(),
    @SerializedName("original_language") val originalLanguage: String? = null,
    val status: String? = null,
    @SerializedName("media_type") val mediaType: String? = "tv"
) {
    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }

    val year: String get() = (firstAirDate ?: "").take(4)

    val ratingText: String
        get() = if (voteAverage > 0) String.format("%.1f", voteAverage) else ""

    /** Typical episode runtime in minutes (first non-zero value). */
    val runtimeText: String
        get() = (episodeRunTime.firstOrNull { it > 0 }?.let { "${it}m" }) ?: ""

    val genreIds: List<Int> get() = genres.map { it.id }
}

/** A genre object as returned by TMDB detail endpoints (id + name). */
data class TmdbGenre(
    val id: Int = 0,
    val name: String? = null
)

/**
 * Full movie detail from `movie/{id}` — richer than the list [TmdbItem]:
 * includes genres (as objects), runtime, tagline, and status. We already
 * have [TmdbItem] with runtime/overview but this gives us the genre objects
 * for the "More Like This" row without an extra call.
 */
data class MovieDetail(
    val id: Int = 0,
    val title: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("vote_average") val voteAverage: Double = 0.0,
    @SerializedName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerializedName("original_language") val originalLanguage: String? = null,
    val status: String? = null,
    @SerializedName("media_type") val mediaType: String? = "movie"
) {
    val backdropUrl: String?
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" }

    val posterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" }

    val year: String get() = (releaseDate ?: "").take(4)

    val ratingText: String
        get() = if (voteAverage > 0) String.format("%.1f", voteAverage) else ""

    val runtimeText: String
        get() = if (runtime != null && runtime > 0) "${runtime}m" else ""

    val genreIds: List<Int> get() = genres.map { it.id }
}
