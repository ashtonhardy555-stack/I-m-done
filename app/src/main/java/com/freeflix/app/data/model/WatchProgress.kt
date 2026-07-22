package com.freeflix.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Persistent record of how far the user has watched a title.
 *
 * Stored by [com.freeflix.app.data.repository.WatchProgressStore] in
 * SharedPreferences as a JSON list, keyed by `tmdbId + contentType + season +
 * episode` (so each episode of a TV show is tracked independently, just like
 * Netflix's "Continue Watching" row).
 *
 * Lifecycle:
 *  - Created/updated every few seconds while a title plays (position grows).
 *  - Updated one last time when the player Activity is destroyed (final flush).
 *  - Flipped to `completed = true` when ExoPlayer reports STATE_ENDED. A
 *    completed record is the signal that removes the title from "Continue
 *    Watching" — the user finished it, so it no longer belongs in the row.
 */
data class WatchProgress(
    @SerializedName("tmdbId") val tmdbId: Int,
    @SerializedName("contentType") val contentType: String,        // "movie" | "tv"
    @SerializedName("positionMs") val positionMs: Long,
    @SerializedName("durationMs") val durationMs: Long,
    @SerializedName("season") val season: Int = 1,                  // TV only (1 for movies)
    @SerializedName("episode") val episode: Int = 1,                // TV only (1 for movies)
    @SerializedName("title") val title: String = "",
    @SerializedName("year") val year: String? = null,
    @SerializedName("posterPath") val posterPath: String? = null,
    @SerializedName("backdropPath") val backdropPath: String? = null,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("completed") val completed: Boolean = false
) {
    /**
     * Stable key that uniquely identifies a single watchable unit: a movie is
     * one unit; a TV episode is one unit (season + episode disambiguate). Used
     * by [WatchProgressStore] to upsert without creating duplicates.
     */
    val key: String get() = "${contentType}_$tmdbId" +
        if (contentType.equals("tv", ignoreCase = true)) "_S${season}_E${episode}" else ""

    /**
     * Fraction watched, 0f..1f. Clamped so a malformed duration never yields
     * NaN or a value > 1. Used by the continue-watching card to render the
     * red progress bar across the bottom of the poster.
     */
    val progressFraction: Float
        get() = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

    /**
     * Whether this record should still appear in "Continue Watching".
     * A title is dropped from the row once it is finished (completed) OR once
     * the user has watched ~97% of it (the last few seconds of credits rarely
     * reach STATE_ENDED on some streams, so we treat near-the-end as finished
     * too — matching Netflix, which removes a title once you're basically at
     * the end).
     */
    val isActive: Boolean
        get() = !completed && progressFraction < 0.97f

    /**
     * Human-readable subtitle for the continue-watching card, e.g.
     * "S2 · E5" for a TV episode or the year for a movie.
     */
    val resumeLabel: String
        get() = if (contentType.equals("tv", ignoreCase = true)) {
            "S$season · E$episode"
        } else {
            year ?: ""
        }
}
