package com.mariocart.app.data.server

import com.google.gson.annotations.SerializedName

/**
 * ServerConfig — a single stream provider as described by the auto-updating
 * `working-servers.json` (both the bundled asset and the remote copy fetched
 * at runtime from the GitHub raw URL).
 *
 * Unlike the legacy [com.mariocart.app.data.model.StreamingServer], each
 * ServerConfig carries its own URL **templates** so new providers can be added
 * to the remote JSON without an app update — the app builds the correct embed
 * URL for any template shape.
 *
 * Templates use the placeholders:
 *   `{base}`     — replaced with [baseUrl]
 *   `{id}`       — replaced with the TMDB id
 *   `{season}`   — replaced with the season number (TV)
 *   `{episode}`  — replaced with the episode number (TV)
 */
data class ServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    @SerializedName("movieUrlTemplate")
    val movieUrlTemplate: String,
    @SerializedName("tvUrlTemplate")
    val tvUrlTemplate: String,
    /** 1 = clean / no challenge (tried first), 3 = Cloudflare captcha fallback. */
    val tier: Int = 2,
    /** 0-100 reliability score; higher = tried earlier within a tier. */
    val reliability: Int = 50,
    /** Whether this provider sits behind a Cloudflare JS challenge. */
    @SerializedName("cloudflare")
    val cloudflare: Boolean = false,
    /** Master kill-switch from the remote list. */
    val enabled: Boolean = true
) {

    /** Builds the movie embed URL for a TMDB id. */
    fun movieUrl(tmdbId: Int): String = buildUrl(movieUrlTemplate, tmdbId, 1, 1)

    /** Builds the TV embed URL for a TMDB id + season/episode. */
    fun tvUrl(tmdbId: Int, season: Int, episode: Int): String =
        buildUrl(tvUrlTemplate, tmdbId, season, episode)

    /** Builds a URL for the given content type. */
    fun urlFor(contentType: String, tmdbId: Int, season: Int, episode: Int): String =
        if (contentType == "tv") tvUrl(tmdbId, season, episode) else movieUrl(tmdbId)

    private fun buildUrl(
        template: String,
        tmdbId: Int,
        season: Int,
        episode: Int
    ): String = template
        .replace("{base}", baseUrl)
        .replace("{id}", tmdbId.toString())
        .replace("{season}", season.toString())
        .replace("{episode}", episode.toString())

    /** A short label for the server picker UI, e.g. "VidLink (95%)". */
    val pickerLabel: String
        get() = if (reliability > 0) "$name ($reliability%)" else name
}
