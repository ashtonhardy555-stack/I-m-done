package com.freeflix.app.data.api

import retrofit2.http.GET
import retrofit2.http.Query
import com.google.gson.annotations.SerializedName

/**
 * Streaming Backend API Client
 * Communicates with the I'm Done Streaming backend for video delivery
 */

data class StreamServer(
    val id: String,
    val name: String,
    val type: String, // "embed" or "direct"
)

data class ServersResponse(
    val success: Boolean,
    val servers: List<StreamServer>,
    val total: Int,
)

data class EmbedResponse(
    val success: Boolean,
    val embedUrl: String? = null,
    val serverId: String? = null,
    val headers: Map<String, String>? = null,
    val error: String? = null,
)

data class StreamResponse(
    val success: Boolean,
    val url: String? = null,
    val serverId: String? = null,
    val contentType: String? = null,
    val isDirect: Boolean? = null,
    val headers: Map<String, String>? = null,
    val challengeUrl: String? = null,
    val error: String? = null,
)

data class HealthResponse(
    val status: String,
    val timestamp: String,
)

interface StreamingBackendApi {
    /**
     * Health check endpoint
     */
    @GET("health")
    suspend fun getHealth(): HealthResponse

    /**
     * List all available streaming servers
     */
    @GET("api/servers")
    suspend fun getServers(): ServersResponse

    /**
     * Get embed URL for a movie or TV show
     * @param serverId Server ID (e.g., "vidsrc_to", "lookmovie")
     * @param tmdbId TMDB ID of the content
     * @param type Content type: "movie" or "tv"
     * @param season Season number (required for TV)
     * @param episode Episode number (required for TV)
     */
    @GET("api/embed")
    suspend fun getEmbed(
        @Query("serverId") serverId: String,
        @Query("tmdbId") tmdbId: Int,
        @Query("type") type: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
    ): EmbedResponse

    /**
     * Extract direct stream URL from a server
     * @param serverId Server ID (e.g., "vidlink", "vidsrc_pro")
     * @param tmdbId TMDB ID of the content
     * @param type Content type: "movie" or "tv"
     * @param season Season number (required for TV)
     * @param episode Episode number (required for TV)
     */
    @GET("api/stream")
    suspend fun getStream(
        @Query("tmdbId") tmdbId: Int,
        @Query("type") type: String,
        @Query("season") season: Int? = null,
        @Query("episode") episode: Int? = null,
        @Query("serverId") serverId: String? = null,
    ): StreamResponse
}
