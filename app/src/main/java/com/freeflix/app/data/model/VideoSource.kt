package com.freeflix.app.data.model

/**
 * Represents a video source from an API/server
 */
data class VideoSource(
    val url: String,
    val title: String? = null,
    val quality: String = "auto", // 1080p, 720p, 480p, etc.
    val mimeType: String = "video/mp4", // video/mp4, application/x-mpegurl, etc.
    val headers: Map<String, String> = emptyMap(), // Custom headers if needed
    val isHls: Boolean = false,
    val isDirect: Boolean = true // true = direct file, false = m3u8/playlist
)

/**
 * Response from video API
 */
data class VideoApiResponse(
    val sources: List<VideoSource>,
    val subtitles: List<Subtitle>? = null,
    val duration: Long = 0L,
    val serverName: String = ""
)

/**
 * Subtitle data
 */
data class Subtitle(
    val url: String,
    val language: String,
    val label: String
)
