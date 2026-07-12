package com.mariocart.app.data.server

/**
 * StreamProviders — the verified list of embed providers used by
 * [EmbedExtractor] to obtain a direct, in-app playable video URL.
 *
 * Each provider exposes an embed HTML page that runs a JavaScript video
 * player (hls.js / video.js). [EmbedExtractor] loads that page in an
 * off-screen WebView and intercepts the direct `.m3u8` / `.mp4` URL the
 * player requests, then hands that URL to ExoPlayer so the video plays
 * natively inside the app — no WebView for playback.
 *
 * URLs are built from the TMDB id, the standard way these providers index
 * content. Providers are ordered by reliability — providers that serve a
 * clean direct stream with NO human-verification challenge come first, so
 * the user gets a video immediately. Cloudflare-protected providers come
 * last as fallbacks (the app surfaces their captcha to the user via
 * [com.mariocart.app.ui.player.VerificationActivity] only if every clean
 * provider fails).
 *
 * Every provider below was individually probed and confirmed alive as of
 * the latest verification pass. Dead providers were removed so the app
 * never wastes time timing out on them.
 */
data class StreamProvider(
    val id: String,
    val name: String,
    /** Builds the embed URL for a movie from its TMDB id. */
    val movieUrl: (tmdbId: Int) -> String,
    /** Builds the embed URL for a TV episode from its TMDB id + season/episode. */
    val tvUrl: (tmdbId: Int, season: Int, episode: Int) -> String
)

object StreamProviders {

    // ── Tier 1: Clean players, direct MP4/HLS, no challenge ── //
    // VidLink — verified working: serves a direct MP4 that ExoPlayer plays
    // natively. No Cloudflare, no captcha. Best provider, tried first.
    private val VIDLINK = StreamProvider(
        id = "vidlink",
        name = "VidLink",
        movieUrl = { id -> "https://vidlink.pro/movie/$id" },
        tvUrl = { id, s, e -> "https://vidlink.pro/tv/$id/$s/$e" }
    )

    // VidSrc.su — React/Vite SPA with a real player-vendor module. Backend
    // API at themoviedb.vidsrc.su. Clean, modern player.
    private val VIDSRC_SU = StreamProvider(
        id = "vidsrc_su",
        name = "VidSrc.su",
        movieUrl = { id -> "https://vidsrc.su/embed/movie/$id" },
        tvUrl = { id, s, e -> "https://vidsrc.su/embed/tv/$id/$s/$e" }
    )

    // ── Tier 2: Working embeds with iframe chains (app intercepts nested iframes) ── //
    // 2Embed — confirmed serving a real player. The embed page loads nested
    // iframes to backend servers; EmbedExtractor's shouldInterceptRequest
    // captures the video URL from those nested frames.
    private val TWO_EMBED_CC = StreamProvider(
        id = "2embed_cc",
        name = "2Embed.cc",
        movieUrl = { id -> "https://www.2embed.cc/embed/$id" },
        tvUrl = { id, s, e -> "https://www.2embed.cc/embed/tv/$id/$s/$e" }
    )

    private val TWO_EMBED_SKIN = StreamProvider(
        id = "2embed_skin",
        name = "2Embed.skin",
        movieUrl = { id -> "https://www.2embed.skin/embed/$id" },
        tvUrl = { id, s, e -> "https://www.2embed.skin/embed/tv/$id/$s/$e" }
    )

    // VidSrc.to — Cloudflare "Just a moment" managed challenge. The WebView
    // runs the challenge JS which often auto-solves; if not, the user solves
    // it via VerificationActivity. Same /embed/movie/ & /embed/tv/ pattern.
    private val VIDSRC_TO = StreamProvider(
        id = "vidsrc_to",
        name = "VidSrc.to",
        movieUrl = { id -> "https://vidsrc.to/embed/movie/$id" },
        tvUrl = { id, s, e -> "https://vidsrc.to/embed/tv/$id/$s/$e" }
    )

    // ── Tier 3: Cloudflare-protected fallbacks (user solves captcha if needed) ── //
    // Videasy — domain moved from player.videasy.net to player.videasy.to.
    // Cloudflare-protected; viable with user captcha via VerificationActivity.
    private val VIDEASY = StreamProvider(
        id = "videasy",
        name = "Videasy",
        movieUrl = { id -> "https://player.videasy.to/movie/$id" },
        tvUrl = { id, s, e -> "https://player.videasy.to/tv/$id/$s/$e" }
    )

    // VidSrc.in / VidSrc.fyi — Cloudflare 403 challenge. Same embed pattern.
    // Viable with user captcha; kept as last-resort fallbacks.
    private val VIDSRC_IN = StreamProvider(
        id = "vidsrc_in",
        name = "VidSrc.in",
        movieUrl = { id -> "https://vidsrc.in/embed/movie/$id" },
        tvUrl = { id, s, e -> "https://vidsrc.in/embed/tv/$id/$s/$e" }
    )

    private val VIDSRC_FYI = StreamProvider(
        id = "vidsrc_fyi",
        name = "VidSrc.fyi",
        movieUrl = { id -> "https://vidsrc.fyi/embed/movie/$id" },
        tvUrl = { id, s, e -> "https://vidsrc.fyi/embed/tv/$id/$s/$e" }
    )

    /**
     * The ordered list of providers tried by [EmbedExtractor].
     *
     * Ordering principle: no-challenge providers first (so the user gets
     * video instantly), then iframe-chain providers, then Cloudflare-
     * protected providers last (captcha only surfaced if all clean
     * providers fail for that title).
     *
     * Removed (dead as of latest probe): VidSrc.io, VidSrc.me, VidSrc.pm,
     * VidSrc.dev, VidSrc.nl, Embed.su, SmashyStream, AutoEmbed, CineHub,
     * VidHub, Embedrise, EmbedStream.
     */
    val ALL: List<StreamProvider> = listOf(
        // Tier 1 — clean, no challenge
        VIDLINK,
        VIDSRC_SU,
        // Tier 2 — working, iframe chains / CF auto-solve
        TWO_EMBED_CC,
        TWO_EMBED_SKIN,
        VIDSRC_TO,
        // Tier 3 — Cloudflare, user captcha fallback
        VIDEASY,
        VIDSRC_IN,
        VIDSRC_FYI
    )

    /** Build the embed URL for the given content from a provider. */
    fun urlFor(
        provider: StreamProvider,
        contentType: String,
        tmdbId: Int,
        season: Int,
        episode: Int
    ): String = if (contentType == "tv")
        provider.tvUrl(tmdbId, season, episode)
    else
        provider.movieUrl(tmdbId)
}
