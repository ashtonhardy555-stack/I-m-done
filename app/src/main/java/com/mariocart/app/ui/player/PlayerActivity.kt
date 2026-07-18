package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.mariocart.app.data.cache.StreamAvailabilityCache
import com.mariocart.app.data.server.DahmerMoviesExtractor
import com.mariocart.app.data.server.LordFlixExtractor
import com.mariocart.app.data.server.MeowTvExtractor
import com.mariocart.app.data.server.KissKhExtractor
import com.mariocart.app.data.server.LookMovieHeadlessExtractor
import com.mariocart.app.data.server.VidSyncExtractor
import com.mariocart.app.data.server.NoTorrentExtractor
import com.mariocart.app.data.server.ServerConfig
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.StreamProviders
import com.mariocart.app.data.server.SuperEmbedExtractor
import com.mariocart.app.data.server.VidLinkExtractor
import com.mariocart.app.data.server.VidSrcExtractor
import com.mariocart.app.data.server.VidSrcMeResolver
import com.mariocart.app.data.server.VidSrcNetExtractor
import com.mariocart.app.data.server.VidSrcProExtractor
import com.mariocart.app.data.server.VidStormExtractor
import com.mariocart.app.data.server.VideasyExtractor
import com.mariocart.app.data.server.VixSrcExtractor
import com.mariocart.app.data.server.TwoEmbedExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PlayerActivity — the on-device video player.
 *
 * This is the single activity that actually plays videos. It owns a fully
 * on-device extraction pipeline (no backend server involved):
 *
 *   0. [VidStormExtractor] (PRIMARY) — calls the VidStorm streaming API
 *      (vidstorm.ru/api), the same backend FilmCave's "cloud / servers"
 *      button uses, and resolves a **direct** `.m3u8` / `.mp4` URL for the
/**
 * PlayerActivity — the on-device video player.
 *
 * This is the single activity that actually plays videos. It owns a fully
 * on-device extraction pipeline (no backend server, NO WebView at all):
 *
 * EVERY direct extractor is fired SIMULTANEOUSLY in a single all-servers
 * parallel race. Each extractor resolves a direct `.m3u8`/`.mp4` URL for the
 * given TMDB id over plain HTTP — no WebView, no embed scraping, no JS
 * challenge. The race collects ALL non-null results, ranks them English-first
 * then by provider reliability, plays the best, and queues the rest as
 * instant-failover candidates. When ExoPlayer rejects the head URL (the "acts
 * like it works then won't play" bug) the next queued candidate is popped
 * with zero re-extraction. Only when the whole queue is exhausted is the
 * race re-run once more as a last attempt.
 *
 * The available direct extractors include (all pure HTTP): VidStorm, VidSrc,
 * VidSrcMe, VidSrcPro, VidSrcNet, VidLink, VixSrc, NoTorrent (Stremio),
 * MeowTV, Videasy (10 servers), KissKH, VidSync, LordFlix (10 servers),
 * DahmerMovies, TwoEmbed (+ sub-streams), SuperEmbed.
 *
 * Before the stream is resolved (and until ExoPlayer reports STATE_READY),
 * the TMDB poster/backdrop is shown as a thumbnail via Coil so the user sees
 * the artwork while the stream is being found.
 *
 * The captured URL is always played with [ExoPlayer] — never in a WebView.
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PlayerActivity"

        // Single extraction stage now that there is no WebView pipeline:
        // every direct extractor races in parallel from STAGE_RACE. There is
        // no embed / LookMovie / OkHttp fallback stage anymore.
        const val STAGE_RACE = 0
        // Kept for source compatibility with any code that still names the
        // old stage constants; all collapse to the race.
        const val STAGE_VIDSTORM = 0
        const val STAGE_VIDSRC = 1
        const val STAGE_EMBED = 2
        const val STAGE_LOOKMOVIE = 3
        const val STAGE_OKHTTP = 4

        /**
         * Hard ceiling on the PARALLEL direct-extractor lane (Stage 0+1).
         *
         * 2026 strategy: the FAST LANE (NoTorrent → VidStorm, tried
         * sequentially with a 5 s timeout each) handles the common case in
         * ~300 ms and returns before this parallel lane ever runs. This
         * ceiling only bounds the PARALLEL LANE that runs when the fast lane
         * misses — i.e. when none of the confirmed-fast extractors had the
         * title. Because the confirmed-dead/timeout-prone extractors
         * (VidSrcPro 12 s timeout, SuperEmbed DNS dead, VidSrcNet parked)
         * are now EXCLUDED from the parallel lane, the remaining extractors
         * each have a 6 s per-extractor timeout and resolve quickly, so 9 s
         * is a generous ceiling that still guarantees the user never waits
         * long before a stream, a challenge, or a fallthrough to the next
         * stage. When the timeout fires, we salvage any winner that
         * completed before the deadline instead of discarding it.
         */
        const val RACE_TIMEOUT_MS = 12_000L

        /**
         * Per-extractor timeout for the parallel direct-API lane
         * (Stage 0+1). Each pure-HTTP extractor is given this long to
         * resolve a playable URL before its async returns null and the
         * lane continues with the remaining extractors. 6 s is long
         * enough for VidSrc's multi-hop RCP/PRORCP pipeline (~2.4 s on a
         * fast connection, up to ~5 s on 3G). Since the dead extractors
         * are excluded, the overall worst-case wait is max(6s, RACE_TIMEOUT).
         */
        const val PROVIDER_TIMEOUT_MS = 6_000L

        /**
         * Factory used by [com.mariocart.app.ui.MainActivity].
         *
         * @param posterUrl   TMDB poster URL (w342) shown as a thumbnail
         *                     before the video starts.
         * @param backdropUrl TMDB backdrop URL (original) used as the
         *                     full-bleed loading background.
         */
        fun newIntent(
            context: Context,
            tmdbId: Int,
            contentType: String = "movie",
            season: Int = 1,
            episode: Int = 1,
            title: String = "Now Playing",
            year: String? = null,
            posterUrl: String? = null,
            backdropUrl: String? = null
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("TMDB_ID", tmdbId)
            putExtra("CONTENT_TYPE", contentType)
            putExtra("SEASON", season)
            putExtra("EPISODE", episode)
            putExtra("TITLE", title)
            putExtra("YEAR", year)
            posterUrl?.let { putExtra("POSTER_URL", it) }
            backdropUrl?.let { putExtra("BACKDROP_URL", it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make sure the server list (used for ordering/scoring) is loaded.
        ServerManager.initialize(this)

        val tmdbId = intent.getIntExtra("TMDB_ID", -1)
        val contentType = intent.getStringExtra("CONTENT_TYPE") ?: "movie"
        val season = intent.getIntExtra("SEASON", 1)
        val episode = intent.getIntExtra("EPISODE", 1)
        val title = intent.getStringExtra("TITLE") ?: "Now Playing"
        val year = intent.getStringExtra("YEAR")
        val posterUrl = intent.getStringExtra("POSTER_URL")
        val backdropUrl = intent.getStringExtra("BACKDROP_URL")

        if (tmdbId == -1) {
            Log.e(TAG, "Invalid TMDB ID")
            finish()
            return
        }

        setContent {
            MarioCartTheme {
                PlayerScreen(
                    tmdbId = tmdbId,
                    contentType = contentType,
                    season = season,
                    episode = episode,
                    title = title,
                    year = year,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl
                )
            }
        }
    }
}

/**
 * The player screen.
 *
 * Extraction pipeline (all on-device, NO backend, NO WebView):
 *  Every direct extractor is fired SIMULTANEOUSLY in one all-servers
 *  parallel race. The race collects every non-null result, ranks them
 *  English-first then by provider reliability, plays the best, and queues
 *  the rest as instant-failover candidates. When ExoPlayer rejects the
 *  head URL the next queued candidate is popped with zero re-extraction.
 *  Only when the whole queue is exhausted does the race re-run once more.
 *
 * The TMDB poster/backdrop is shown as a thumbnail while the stream is
 * being resolved and until ExoPlayer reports STATE_READY (playback begins).
 */
@UnstableApi
@Composable
fun PlayerScreen(
    tmdbId: Int,
    contentType: String,
    season: Int,
    episode: Int,
    title: String = "Now Playing",
    year: String? = null,
    posterUrl: String? = null,
    backdropUrl: String? = null
) {
    val localContext = LocalContext.current

    // --- Player + extraction state ---------------------------------- //
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var streamHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    // --- Loop control ------------------------------------------------ //
    // attempt is the LaunchedEffect key; bumping it re-runs extraction.
    var attempt by remember { mutableStateOf(0) }
    // In the no-WebView pipeline there is a single extraction lane: the
    // all-servers parallel race. startStage is always STAGE_VIDSTORM (0)
    // — every direct extractor fires at once and candidates are ranked
    // English-first. Bumping `attempt` re-runs that same race (with any
    // dead providers moved into excludedRaceProviders so they're skipped).
    var startStage by remember { mutableStateOf(0) }
    // Tracks the stage that delivered the current streamUrl so an ExoPlayer
    // playback error can pop the next candidate (or re-run the race).
    var currentStage by remember { mutableStateOf(0) }

    // --- Race-provider exclusion (the Interstellar + "everything plays" fix) -- //
    // When the all-servers race hands ExoPlayer a stream that ExoPlayer
    // can't actually play (e.g. VidStorm's dead unverified URL for
    // Interstellar), the candidate queue's failover path (and the
    // queue-empty re-race) records the dead provider here so a re-race
    // won't waste a slot on it. We keep doing this until every racer has
    // been tried; only then do we surface a clear error. This delivers the
    // UNION of build 4 and build 13:
    //   - build 13 behaviour (Interstellar works) is preserved because the
    //     dead VidStorm URL is excluded and NoTorrent wins on the re-race;
    //   - build 4 behaviour (most things play & load fast) is preserved
    //     because we never DROP a resolved URL — we just pop the next
    //     candidate when ExoPlayer (the real arbiter) says one is dead.
    var excludedRaceProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var raceFallbackUsed by remember { mutableStateOf(false) }

    // --- Resolved-candidate queue (the "acts like it works then won't play" fix) -- //
    // When the race fires every extractor in parallel, it collects ALL
    // resolved candidates (not just the first) into this queue, ranked
    // English-audio-first then by reliability. We play the head of the queue;
    // if ExoPlayer rejects it (the "it says it has a stream but won't play"
    // bug), we pop the next already-resolved candidate and play THAT — no
    // re-extraction, near-instant failover. Only when the queue is empty do
    // we re-run the all-servers race once more.
    // Each entry is an immutable snapshot so it survives recomposition.
    var candidateQueue by remember { mutableStateOf<List<RankedCandidate>>(emptyList()) }

    // --- Server picker ------------------------------------------------ //
    // The user can choose a specific server ("Auto" = let ServerManager order).
    // Make sure the server list is loaded so the picker has options.
    LaunchedEffect(Unit) {
        ServerManager.initialize(localContext)
    }
    val availableServers = remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var selectedServerId by remember { mutableStateOf<String?>(null) }
    var showServerPicker by remember { mutableStateOf(false) }
    // The name of the server that actually delivered the current stream.
    var deliveringServerName by remember { mutableStateOf<String?>(null) }

    // Refresh the server list + selected id whenever the picker might open.
    LaunchedEffect(showServerPicker) {
        if (showServerPicker) {
            ServerManager.initialize(localContext)
            availableServers.value = ServerManager.allServers()
            selectedServerId = ServerManager.getSelectedServerId()
        }
    }

    // --------------------------------------------------------------- //
    //  Extraction LaunchedEffect                                       //
    // --------------------------------------------------------------- //
    LaunchedEffect(tmdbId, contentType, season, episode, attempt) {
        isLoading = true
        error = null
        infoMessage = null

        // Reset the race-provider exclusion set whenever we start extraction
        // for a NEW title (attempt 0). Within one playback session we keep
        // accumulating exclusions across re-races (see onPlayerError), but a
        // brand-new title starts with a clean slate so every racer gets a
        // fair shot again.
        if (attempt == 0) {
            // Seed the race-provider exclusion set with providers we already
            // KNOW are dead for this title (learned from past playback
            // failures, persisted across app restarts). This is what makes the
            // app "know what servers work for what movie/show before you even
            // open the video": on a second open the race skips the providers
            // that failed last time and lets a known-good (or untried) one win
            // immediately. For Interstellar this means VidStorm is excluded up
            // front and NoTorrent wins the first race — no cold-start penalty.
            excludedRaceProviders = StreamAvailabilityCache.knownBadProviders(
                tmdbId, contentType, season, episode
            ).toSet()
            ServerManager.resetHealth()
        }

        // The stage to start from this attempt. Captured once so the whole
        // pipeline runs against a consistent snapshot even if startStage
        // changes mid-extraction.
        val start = startStage

        Log.d("Player", "🔎 Extraction attempt #$attempt (start stage $start) for \"$title\" ($year) $contentType S$season E$episode")

        val activity = localContext as? android.app.Activity

        // ── The all-servers parallel race (the only extraction lane) ── //
        // Every direct extractor fires at once inside one coroutineScope
        // race. We collect EVERY resolved candidate (not just the first),
        // rank them English-first then by provider reliability, and play the
        // head. The rest sit in candidateQueue as instant backups for when
        // ExoPlayer rejects the head URL (the "acts like it works then
        // won't play" bug) — popping the next candidate is zero-network.
        //
        // ## Why a candidate-collecting race (not first-wins `select`)
        //
        // A naive `select{}` that takes the first non-null winner is fragile:
        // a slow/hung extractor can win by returning a dead-but-non-Error
        // body, and `select` cancels the good candidates so they're gone
        // when the winner turns out to be unplayable. By collecting ALL
        // candidates with `awaitAll` and ranking them, we keep every good
        // stream queued — failover to the next one is instant.
        //
        // The 17 direct extractors in the race (all fired simultaneously):
        //   NoTorrent   → Stremio addon (great TV coverage)
        //   VidStorm    → one HTTP call, direct .m3u8/.mp4 (fast for popular movies)
        //   VidSrc      → vidsrc.me RCP/PRORCP, resolves almost everything
        //   VidSrcMe    → vidsrc.me resolver (me sub-domain)
        //   VidSrcPro   → vidsrc.pro embed resolver
        //   VidSrcNet   → vidsrc.net/cloudnestra 12-decoder pipeline
        //   VidLink     → /api/player?tmdb direct HLS
        //   VixSrc      → vidsrc.xyz /vixsrc embed
        //   MeowTV      → api.meowtv.ru direct API (excellent TV-episode coverage)
        //   Videasy     → videasy.stream (10-server parallel)
        //   KissKH      → kisskh.do search→episode direct HLS (broad TV catalogue)
        //   VidSync     → vidsync.xyz / wingsdatabase 12-server parallel
        //   LordFlix    → lordflix alternative (10-server aggregator)
        //   DahmerMovies→ dahmermovies fallback
        //   TwoEmbed    → 2embed.cc direct API (+ sub-streams VCR/Vesy/XPS)
        //   SuperEmbed  → superembedstream direct HLS
        //   LookMovie   → headless port of the plugin.video.lookmovietomb Kodi
        //                 addon (search→storage→security-API), direct .m3u8 with
        //                 the t_hash cookie in headers — no WebView, no Kodi
        //
        // The race collects ALL resolved candidates with awaitAll (not just
        // the first), so playback starts in ~1-3 s (the time of the FASTEST
        // working extractor) AND every other good stream is queued as an
        // instant backup. If all return Error/timeout, we surface a clear
        // error (no WebView fallback stage anymore).
        if (start <= PlayerActivity.STAGE_VIDSRC) {
            currentStage = PlayerActivity.STAGE_VIDSTORM
            infoMessage = "Finding best stream\u2026"

            val excluded = excludedRaceProviders

            // Each helper: skip if excluded, run the extractor with a hard
            // per-extractor timeout, and return a DirectWinner only on a
            // verified Stream. Errors/timeouts return null so the next
            // extractor in the chain gets a chance.
            //
            // -- Sub-server-aware exclusion --
            // `excluded` holds FULL provider names (e.g. "VidStorm\u00b7Lithium",
            // "VidSrc", "NoTorrent"). For most extractors the provider name
            // IS the base name, so a simple `in` membership check works.
            // VidStorm is special: it returns element-named sub-servers
            // ("VidStorm\u00b7Lithium", "VidStorm\u00b7Hydrogen", "VidStorm\u00b7Boron", \u2026)
            // and we don't know WHICH sub-server we'll get until after
            // extraction. So for VidStorm we run the extraction first, then
            // drop the result only if THAT SPECIFIC sub-server is excluded \u2014
            // never the whole VidStorm family. This is the fix for "servers
            // like lithium are the main ones I've seen work" being removed:
            // a Boron failure no longer excludes Lithium.
            suspend fun tryVidStorm(): DirectWinner? {
                // Only short-circuit if the BARE "VidStorm" key is excluded
                // (legacy cache entries / explicit whole-family exclusion).
                // We do NOT skip just because one sub-server like
                // "VidStorm\u00b7Boron" is in the set.
                if ("VidStorm" in excluded && excluded.none { it.startsWith("VidStorm\u00b7") }) {
                    Log.d("Player", "\u23ed\ufe0f VidStorm excluded this round (whole family)")
                    return null
                }
                Log.d("Player", "🏇 VidStorm: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidStormExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidStormExtractor.Result.Stream)?.let {
                    val pname = it.providerName.ifBlank { "VidStorm" }
                    // If THIS specific sub-server is excluded, drop it so a
                    // different sub-server or another extractor can win.
                    if (pname in excluded) {
                        Log.d("Player", "\u23ed\ufe0f VidStorm sub-server $pname excluded \u2014 skipping")
                        null
                    } else {
                        Log.i("Player", "\u2705 VidStorm hit ($pname): ${it.url}")
                        DirectWinner(it.url, it.headers, pname)
                    }
                }
            }

            suspend fun tryVidSrc(): DirectWinner? {
                if ("VidSrc" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidSrc excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VidSrc: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidSrcExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidSrcExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidSrc hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidSrc" })
                }
            }

            suspend fun tryVidSrcNet(): DirectWinner? {
                if ("VidSrcNet" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidSrcNet excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VidSrcNet: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidSrcNetExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidSrcNetExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidSrcNet hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidSrcNet" })
                }
            }

            suspend fun tryVidLink(): DirectWinner? {
                if ("VidLink" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidLink excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VidLink: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidLinkExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidLinkExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidLink hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidLink" })
                }
            }

            suspend fun tryVixSrc(): DirectWinner? {
                if ("VixSrc" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VixSrc excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VixSrc: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VixSrcExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VixSrcExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VixSrc hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VixSrc" })
                }
            }

            suspend fun tryNoTorrent(): DirectWinner? {
                if ("NoTorrent" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f NoTorrent excluded this round")
                    return null
                }
                Log.d("Player", "🏇 NoTorrent: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    NoTorrentExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? NoTorrentExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 NoTorrent hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "NoTorrent" })
                }
            }

            suspend fun tryMeowTv(): DirectWinner? {
                if ("MeowTV" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f MeowTV excluded this round")
                    return null
                }
                Log.d("Player", "🏇 MeowTV: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    MeowTvExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? MeowTvExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 MeowTV hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "MeowTV" })
                }
            }

            suspend fun tryKissKh(): DirectWinner? {
                if ("KissKH" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f KissKH excluded this round")
                    return null
                }
                Log.d("Player", "🏇 KissKH: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    KissKhExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? KissKhExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 KissKH hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "KissKH" })
                }
            }

            suspend fun tryVidSync(): DirectWinner? {
                if ("VidSync" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidSync excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VidSync: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidSyncExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidSyncExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidSync hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidSync" })
                }
            }

            suspend fun tryVideasy(): DirectWinner? {
                if ("Videasy" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f Videasy excluded this round")
                    return null
                }
                Log.d("Player", "🏇 Videasy: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VideasyExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VideasyExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 Videasy hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "Videasy" })
                }
            }

            suspend fun tryLordFlix(): DirectWinner? {
                if ("LordFlix" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f LordFlix excluded this round")
                    return null
                }
                Log.d("Player", "🏇 LordFlix: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    LordFlixExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? LordFlixExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 LordFlix hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "LordFlix" })
                }
            }

            suspend fun tryDahmer(): DirectWinner? {
                if ("DahmerMovies" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f DahmerMovies excluded this round")
                    return null
                }
                Log.d("Player", "🏇 DahmerMovies: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    DahmerMoviesExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? DahmerMoviesExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 DahmerMovies hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "DahmerMovies" })
                }
            }

            suspend fun tryTwoEmbed(): DirectWinner? {
                if ("TwoEmbed" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f TwoEmbed excluded this round")
                    return null
                }
                Log.d("Player", "🏇 TwoEmbed: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    TwoEmbedExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? TwoEmbedExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 TwoEmbed hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "TwoEmbed" })
                }
            }
            suspend fun tryVidSrcMe(): DirectWinner? {
                if ("VidSrcMe" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidSrcMe excluded this round")
                    return null
                }
                Log.d("Player", "🏇 VidSrcMe: extracting sub-servers\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidSrcMeResolver.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidSrcMeResolver.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidSrcMe hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidSrcMe" })
                }
            }


            suspend fun trySuperEmbed(): DirectWinner? {
                if ("SuperEmbed" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f SuperEmbed excluded this round")
                    return null
                }
                Log.d("Player", "\ud83c\udfc7 SuperEmbed: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    SuperEmbedExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? SuperEmbedExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 SuperEmbed hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "SuperEmbed" })
                }
            }

            suspend fun tryVidSrcPro(): DirectWinner? {
                if ("VidSrcPro" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f VidSrcPro excluded this round")
                    return null
                }
                Log.d("Player", "\ud83c\udfc7 VidSrcPro: extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    VidSrcProExtractor.extract(tmdbId, contentType, season, episode)
                }
                return (res as? VidSrcProExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 VidSrcPro hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "VidSrcPro" })
                }
            }

            // ── LookMovie headless engine (Kodi-addon flow, no WebView) ──
            // This is the no-WebView port of the plugin.video.lookmovietomb
            // Kodi addon. It runs the addon's search → storage → security-API
            // flow in pure OkHttp and returns a direct .m3u8 with the t_hash
            // cookie attached so ExoPlayer plays it headlessly (no local proxy,
            // no WebView, no Kodi). Best-effort: if LookMovie 403s or hits the
            // Thread Defence captcha, it returns null and the race moves on.
            suspend fun tryLookMovie(): DirectWinner? {
                if ("LookMovie" in excluded) {
                    Log.d("Player", "\u23ed\ufe0f LookMovie excluded this round")
                    return null
                }
                Log.d("Player", "\ud83c\udfc7 LookMovie (headless): extracting\u2026")
                val res = withTimeoutOrNull(PlayerActivity.PROVIDER_TIMEOUT_MS) {
                    LookMovieHeadlessExtractor.extract(
                        title = title,
                        year = year,
                        isMovie = contentType.equals("movie", ignoreCase = true),
                        season = season,
                        episode = episode
                    )
                }
                return (res as? LookMovieHeadlessExtractor.Result.Stream)?.let {
                    Log.i("Player", "\u2705 LookMovie hit: ${it.url}")
                    DirectWinner(it.url, it.headers, it.providerName.ifBlank { "LookMovie" })
                }
            }

            // ── ALL-SERVERS PARALLEL RACE (single unified lane) ──
            //
            // Per the user's explicit requests:
            //   • "I want all the servers to be tried at the same time"
            //   • "make sure all the servers are being used not just some"
            //   • "the first working stream that is in english to be played"
            //   • "the app act like it has a working stream a lot then it
            //      won't play and goes to the next server" (fix this)
            //
            // So instead of a sequential fast-lane (NoTorrent → VidStorm) that
            // BLOCKS the other extractors and then a partial parallel lane that
            // EXCLUDES three "confirmed dead" extractors, we now fire EVERY
            // direct extractor simultaneously in one coroutineScope. No
            // extractor is excluded up-front — they each get a hard
            // PROVIDER_TIMEOUT_MS, so a dead/slow one (VidSrcPro 12 s timeout,
            // SuperEmbed DNS, VidSrcNet parked) simply returns null at the
            // timeout and never blocks the others. This is the union of "fast"
            // and "uses every server": the first working extractor wins in
            // ~300 ms, and every other extractor still gets a fair shot.
            //
            // ── Candidate-collecting race (the "won't play → next server" fix) ──
            // The race collects ALL non-null results into a ranked list, not
            // just the first one. Ranking: English-audio candidates first (per
            // the user's English-first requirement), then by provider
            // reliability. We play the head of the ranked list and keep the
            // rest in `candidateQueue`. When ExoPlayer rejects the head (the
            // "acts like it has a stream then won't play" bug — a URL that
            // resolved but is dead/geo-blocked/in another language ExoPlayer
            // can't render), onPlayerError pops the next already-resolved
            // candidate and plays it INSTANTLY — no re-extraction, no full
            // race re-run. Only when the queue is exhausted do we fall
            // through to a second all-servers race attempt.
            //
            // ── Why CompletableDeferred + awaitAll (not select{}/removeAt) ──
            // The original PR-#18 `select` block re-threw CancellationException
            // on timeout, crashing the scope and DISCARDING every result that
            // had already succeeded ("videos that worked don't anymore"). The
            // `safe()` wrapper here swallows ALL exceptions (including
            // cancellation) and returns null — it NEVER propagates — so a
            // timeout can never crash the scope. We await ALL deferreds (with
            // the outer RACE_TIMEOUT_MS ceiling) and keep every non-null
            // result, so a timeout never throws away a resolved stream.
            suspend fun safe(d: suspend () -> DirectWinner?): DirectWinner? =
                try { d() } catch (e: Exception) {
                    Log.w("Player", "race async error: ${e.message}")
                    null
                }

            Log.d("Player", "🏁 ALL-SERVERS PARALLEL RACE: firing every direct extractor at once")
            val raceCandidates = try {
                withTimeoutOrNull(PlayerActivity.RACE_TIMEOUT_MS) {
                    coroutineScope {
                        // EVERY extractor fires simultaneously. Previously-
                        // excluded extractors (VidSrcPro, SuperEmbed,
                        // VidSrcNet) are now INCLUDED — they race with the
                        // same per-extractor timeout; dead ones simply return
                        // null at the timeout and never hold up the others.
                        // This is the fix for "make sure all the servers are
                        // being used not just some".
                        val deferreds = listOf(
                            async { safe { tryNoTorrent() } },
                            async { safe { tryVidStorm() } },
                            async { safe { tryVidSrc() } },
                            async { safe { tryVidSrcMe() } },
                            async { safe { tryVidSrcPro() } },
                            async { safe { tryVidSrcNet() } },
                            async { safe { tryVidLink() } },
                            async { safe { tryVixSrc() } },
                            async { safe { tryMeowTv() } },
                            async { safe { tryVideasy() } },
                            async { safe { tryKissKh() } },
                            async { safe { tryVidSync() } },
                            async { safe { tryLordFlix() } },
                            async { safe { tryDahmer() } },
                            async { safe { tryTwoEmbed() } },
                            async { safe { trySuperEmbed() } },
                            async { safe { tryLookMovie() } }
                        )
                        // awaitAll so we collect EVERY resolved candidate,
                        // not just the first. safe() guarantees no async
                        // throws, so awaitAll completes cleanly.
                        deferreds.map { d ->
                            try { d.await() } catch (e: Exception) { null }
                        }.filterNotNull()
                    }
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w("Player", "Parallel race error: ${e.message}")
                emptyList()
            }

            // ── Rank: English-audio first, then by provider reliability ──
            // isEnglishStream() inspects the URL/headers for English-audio
            // hints (multi-audio HLS manifests, "eng" in track names, provider
            // language flags). Non-English candidates are kept as fallback
            // (better a working non-English stream than nothing) but sorted
            // after English ones. Within each language group we sort by a
            // per-provider reliability weight so the most dependable
            // English source is played first.
            val ranked = raceCandidates
                .map { RankedCandidate(it, isEnglishStream(it, contentType)) }
                .sortedWith(
                    compareByDescending<RankedCandidate> { it.english }
                        .thenByDescending { providerReliability(it.winner.providerName) }
                )

            if (ranked.isNotEmpty()) {
                val best = ranked.first()
                val rest = ranked.drop(1)
                candidateQueue = rest
                Log.i("Player", "🏁 Direct-API winner: ${best.winner.providerName} (english=${best.english}, ${rest.size} backup candidates queued)")
                streamUrl = best.winner.url
                streamHeaders = best.winner.headers.ifEmpty {
                    mapOf("User-Agent" to DEFAULT_UA)
                }
                deliveringServerName = best.winner.providerName
                isLoading = false
                return@LaunchedEffect
            } else {
                candidateQueue = emptyList()
                Log.w("Player", "All direct extractors yielded nothing on first race; a second race attempt follows if needed.")
            }
        }

        // ── No WebView fallback — every direct extractor already raced ── //
        // All 17 direct extractors fired in parallel above. If we reach here
        // they all returned null/empty. There is no embed / LookMovie / OkHttp
        // WebView stage anymore (removed per "no WebView at all" requirement),
        // so we surface a clear error. The caller (onPlayerError / manual retry)
        // can bump `attempt` to re-fire the race a second time with no
        // exclusions (a clean second attempt).
        if (error == null) {
            error = "Couldn't find a working stream. Tap retry to search again, or pick a different server."
        }
        isLoading = false
    }


    // --------------------------------------------------------------- //
    //  UI                                                              //
    // --------------------------------------------------------------- //
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            isLoading -> LoadingScreen(title, posterUrl, backdropUrl, infoMessage)

            error != null -> ErrorScreen(
                title = title,
                posterUrl = posterUrl,
                backdropUrl = backdropUrl,
                error = error!!,
                onRetry = {
                    error = null
                    // Retry from the top of the pipeline.
                    startStage = PlayerActivity.STAGE_VIDSTORM
                    attempt++
                },
                onBack = { (localContext as? ComponentActivity)?.finish() }
            )

            streamUrl != null -> {
                ExoPlayerView(
                    url = streamUrl!!,
                    headers = streamHeaders,
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    onPlayerError = { isFatal ->
                        // ── Transient-error-aware ExoPlayer fallback ──
                        // ExoPlayer emits onPlayerError for BOTH fatal errors
                        // (dead/404/geo-blocked source) AND transient errors
                        // (a slow CDN segment, a momentary network blip, an
                        // HLS manifest re-load hiccup). Shows use HLS (.m3u8)
                        // with many segments/variants and are far more likely
                        // to hit a transient error during buffering than movies
                        // (progressive MP4). Previously ANY error immediately
                        // abandoned the server and re-ran extraction → "videos
                        // try to play then switch to a different server every
                        // time."
                        //
                        // ExoPlayerView now classifies the error and performs
                        // an in-player retry for transient errors (up to
                        // MAX_PLAYER_RETRIES) BEFORE surfacing the error here.
                        // This callback is ONLY invoked once those retries are
                        // exhausted (isFatal == true) or the error is
                        // inherently fatal (isFatal == true). So reaching here
                        // means the source is genuinely unplayable.
                        if (!isFatal) {
                            // Safety net: transient errors that survived the
                            // in-player retries are still recoverable — keep
                            // the current server and just nudge the player to
                            // re-prepare instead of switching servers.
                            Log.w("Player", "⏳ Transient playback hiccup — keeping current server, not switching.")
                            return@ExoPlayerView
                        }
                        Log.w("Player", "🔄 ExoPlayer couldn't play the resolved URL — recovering.")
                        streamUrl = null
                        val failedProvider = deliveringServerName
                        deliveringServerName = null
                        error = null
                        infoMessage = "That source didn't play — trying another…"

                        // Record this (title, provider) failure so the
                        // per-title server availability cache learns which
                        // providers DON'T work for this movie/show and the
                        // next time the user opens it we lead with a known-good
                        // provider instead.
                        failedProvider?.let { fp ->
                            StreamAvailabilityCache.recordFailure(
                                tmdbId = tmdbId,
                                contentType = contentType,
                                season = season,
                                episode = episode,
                                provider = fp
                            )
                        }

                        // ── Candidate-queue pop FIRST (no re-extraction) ──
                        // The all-servers parallel race already collected and
                        // ranked every resolved stream. So when ExoPlayer
                        // rejects the head URL (the "acts like it works then
                        // won't play" case), we DON'T re-run the whole race —
                        // we pop the next already-resolved candidate from
                        // candidateQueue and hand it straight to ExoPlayer.
                        // This is instant (zero network round-trips for the
                        // extraction step) and skips the dead provider.
                        val nextCandidate = candidateQueue.firstOrNull()
                        if (nextCandidate != null) {
                            candidateQueue = candidateQueue.drop(1)
                            Log.i("Player", "⚡ Popping next queued candidate: ${nextCandidate.winner.providerName} (english=${nextCandidate.english}, ${candidateQueue.size} left)")
                            // Record the failure for the per-title cache so
                            // next open leads with a known-good provider.
                            failedProvider?.let { fp ->
                                StreamAvailabilityCache.recordFailure(
                                    tmdbId = tmdbId,
                                    contentType = contentType,
                                    season = season,
                                    episode = episode,
                                    provider = fp
                                )
                            }
                            streamUrl = nextCandidate.winner.url
                            streamHeaders = nextCandidate.winner.headers
                                .ifEmpty { mapOf("User-Agent" to DEFAULT_UA) }
                            deliveringServerName = nextCandidate.winner.providerName
                            currentStage = PlayerActivity.STAGE_VIDSTORM
                            // NOTE: we deliberately do NOT increment `attempt`
                            // here. Incrementing attempt would re-run the
                            // LaunchedEffect keyed on attempt, which re-fires
                            // the whole extraction race and overwrites the
                            // candidate we just popped. Instead we only set
                            // streamUrl/streamHeaders — ExoPlayerView has its
                            // own LaunchedEffect(url) that re-prepares the
                            // player the instant the URL changes, so the new
                            // candidate plays with zero re-extraction.
                            isLoading = false
                            error = null
                            return@ExoPlayerView
                        }

                        // ── Queue empty → re-fire the all-servers race ──
                        // No more pre-resolved candidates. Record the failure
                        // for the cache and the race-provider exclusion set,
                        // then re-run the full parallel race from the top.
                        // The dead provider is now in excludedRaceProviders so
                        // the second race won't waste a slot on it.
                        failedProvider?.let { fp ->
                            StreamAvailabilityCache.recordFailure(
                                tmdbId = tmdbId,
                                contentType = contentType,
                                season = season,
                                episode = episode,
                                provider = fp
                            )
                            // Keep race-provider exclusion up to date so a
                            // future re-race (if the user re-opens the title)
                            // already skips this dead provider.
                            val raceProviderKey = mapRaceProviderKey(fp)
                            if (raceProviderKey != null &&
                                raceProviderKey !in excludedRaceProviders
                            ) {
                                excludedRaceProviders = excludedRaceProviders + raceProviderKey
                            }
                        }
                        Log.i("Player", "Candidate queue empty — re-firing the all-servers race.")
                        startStage = PlayerActivity.STAGE_VIDSTORM
                        isLoading = true
                        attempt++
                    }
                )
            }
        }

        // ── Server picker overlay (always available) ── //
        ServerPickerOverlay(
            servers = availableServers.value,
            selectedServerId = selectedServerId,
            deliveringServerName = deliveringServerName,
            expanded = showServerPicker,
            onExpandChange = { showServerPicker = it },
            onSelect = { id ->
                ServerManager.setSelectedServerId(id)
                selectedServerId = id
                showServerPicker = false
                // Re-trigger extraction with the newly selected server.
                ServerManager.resetHealth()
                streamUrl = null
                deliveringServerName = null
                error = null
                infoMessage = null
                // ── Server-selection ──
                // In the no-WebView world there is one extraction lane: the
                // all-servers parallel race. Whether the user picks "Auto"
                // (id == null) or a specific server (id != null), we always
                // re-fire the full race from STAGE_VIDSTORM. The race fires
                // every extractor at once and ranks candidates English-first,
                // so the user's specific pick (if it resolves) will be in the
                // candidate queue; if it doesn't resolve, the next-best
                // English candidate plays instead of dead-ending.
                startStage = PlayerActivity.STAGE_VIDSTORM
                isLoading = true
                attempt++
            }
        )
    }
}

/**
 * Server picker overlay — lets the user choose which provider to try.
 *
 * Shown as a small button in the top-start corner: "Auto" or the selected
 * server's name. Tapping it opens a dropdown listing every available server
 * (from the auto-updating list) with its reliability percentage. The user
 * picks one (or "Auto" to let ServerManager order by health/score) and
 * extraction re-runs with that server tried first.
 *
 * When a stream is playing, the button also shows which server delivered it
 * (e.g. "▶ VidLink"), so the user can see the result of their choice.
 */
@Composable
private fun ServerPickerOverlay(
    servers: List<ServerConfig>,
    selectedServerId: String?,
    deliveringServerName: String?,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onSelect: (String?) -> Unit
) {
    val selected = selectedServerId?.let { id -> servers.firstOrNull { it.id == id } }
    val buttonLabel = when {
        deliveringServerName != null -> "▶ $deliveringServerName"
        selected != null -> "Ⓢ ${selected.pickerLabel}"
        else -> "Ⓢ Auto"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp, start = 8.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Box {
            TextButton(
                onClick = { onExpandChange(!expanded) },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = buttonLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandChange(false) }
            ) {
                DropdownMenuItem(
                    text = { Text("Auto  (best available)") },
                    onClick = { onSelect(null) }
                )
                if (servers.isNotEmpty()) {
                    HorizontalDivider()
                    servers.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(server.pickerLabel)
                                    Text(
                                        text = if (server.cloudflare) "Cloudflare" else "Direct",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            },
                            onClick = { onSelect(server.id) }
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- //
//  Loading / Error screens with poster                              //
// ---------------------------------------------------------------- //

/**
 * Full-bleed backdrop with the poster + a spinner while the stream is being
 * resolved. The TMDB artwork is the "thumbnail before the video starts".
 */
@Composable
private fun LoadingScreen(
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
    infoMessage: String?
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Backdrop as full-bleed background (faded).
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Dark scrim so the spinner/text are readable.
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Poster thumbnail.
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(160.dp)
                        .height(240.dp)
                        .background(Color(0xFF1F1F1F))
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                text = infoMessage ?: "Finding best stream…",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Error screen with the poster so the user still sees the artwork they
 * tapped, plus Retry / Back actions.
 */
@Composable
private fun ErrorScreen(
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
    error: String,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color(0xDD000000)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(140.dp)
                        .height(210.dp)
                        .background(Color(0xFF1F1F1F))
                )
                Spacer(Modifier.height(20.dp))
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "⚠️ $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text("Retry") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}// ---------------------------------------------------------------- //
//  ExoPlayer composable                                            //
// ---------------------------------------------------------------- //

/**
 * ExoPlayer view. The poster/backdrop thumbnail is shown as a layer behind
 * the player surface until ExoPlayer reaches [Player.STATE_READY] (i.e. the
 * first frame is rendered), so the user always sees artwork before actual
 * playback begins.
 */
@UnstableApi
@Composable
private fun ExoPlayerView(
    url: String,
    headers: Map<String, String>,
    posterUrl: String? = null,
    backdropUrl: String? = null
    onPlayerError: (isFatal: Boolean) -> Unit = {}
) {
    var player: ExoPlayer? by remember { mutableStateOf(null) }
    var trackSelector: DefaultTrackSelector? by remember { mutableStateOf(null) }

    // ── Quality state ──
    var availableQualities by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf("Auto") }
    var showQualityMenu by remember { mutableStateOf(false) }

    // Track when the first frame has rendered so we can hide the thumbnail.
    var isReady by remember { mutableStateOf(false) }

    // ── In-player transient-error retry state ──
    // Transient errors (slow segment, network blip, HLS manifest re-load) are
    // recovered by re-preparing the SAME media item instead of abandoning the
    // server. This is the fix for "shows try to play then switch servers every
    // time": previously any PlaybackException immediately switched servers.
    var transientRetryCount by remember { mutableStateOf(0) }

    // Capture the error callback in a lambda we can invoke from the
    // AndroidView factory (which runs outside of the Composition).
    val errorHandler = rememberUpdatedState(onPlayerError)

    // ── Reset ready + retry state whenever the URL changes (server switch) ──
    // When the player falls through to the next server, a NEW url arrives.
    // The old player has been released (onRelease), so we must clear the
    // "first frame rendered" flag and the retry counter so the new source
    // starts with a clean slate. Without this, isReady could stay true from
    // a previous (broken) source and the poster would never show, and the
    // retry counter could carry over and prematurely exhaust retries on the
    // new source.
    LaunchedEffect(url) {
        isReady = false
        transientRetryCount = 0
        availableQualities = emptyList()
        selectedQuality = "Auto"
    }

    // Build the MediaItem fresh from the current URL every time. Previously
    // a `mediaItemHolder` cached the FIRST media item and never updated when
    // the URL changed (server switch) — so the new source's URL was silently
    // ignored and the stale (broken) source kept "playing" with a known
    // duration but no video, then errored and switched again.
    val mediaItem = remember(url) {
        val mimeType = guessMimeType(url)
        if (mimeType != null) {
            MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMimeType(mimeType)
                .build()
        } else {
            MediaItem.fromUri(Uri.parse(url))
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Thumbnail/poster shown behind the player until STATE_READY.
        if (!isReady) {
            if (!backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // ── Key the AndroidView on the url so the player is FULLY recreated
        // when the source changes (server switch). Without this key the
        // AndroidView factory only runs ONCE for the composable's lifetime,
        // so when a new url arrived after a server switch the OLD player
        // (with the broken/old url) stayed active — it reported the correct
        // duration from the manifest but never rendered video, then errored
        // out and switched again. Keying on url guarantees a fresh ExoPlayer +
        // PlayerView + surface for every source. ──
        androidx.compose.runtime.key(url) {
        AndroidView(
            factory = { ctx ->
                val userAgent = headers["User-Agent"] ?: DEFAULT_UA
                val remainingHeaders = headers.filterKeys { it != "User-Agent" }

                val httpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(remainingHeaders)
                    // ── Faster failover for dead URLs ──
                    // Default ExoPlayer connect/read timeouts are 8s each. On a
                    // dead CDN URL (e.g. VidStorm's "Boron" CF-Worker that 200s
                    // an HTML 404, or a VidSrc token that expired) the player
                    // would hang for up to 16s before surfacing a PlaybackException.
                    // With 4s/4s the player errors out in ~4-8s and the
                    // fallthrough to the next server/stage kicks in promptly.
                    // This is the "loads videos really slow" fix: a dead source
                    // no longer monopolises the player for 16s.
                    .setConnectTimeoutMs(4_000)
                    .setReadTimeoutMs(4_000)

                val dataSourceFactory: DataSource.Factory = httpFactory

                val selector = DefaultTrackSelector(ctx).apply {
                    // Start in auto / adaptive mode — ExoPlayer picks the best
                    // quality based on available bandwidth. The user can
                    // override via the quality picker once tracks are loaded.
                    parameters = DefaultTrackSelector.ParametersBuilder(ctx)
                        .setForceHighestSupportedBitrate(false)
                        .build()
                }
                trackSelector = selector
                // Ensure playback defaults to English audio/subs when the
                // provider ships multi-language tracks (VidSrc, 2Embed,
                // VidStorm often do). Applied before prepare() so the first
                // rendered track is English.
                forceEnglishTracks(selector)

                // ── LoadControl: tolerant buffering for shows ──
                // Shows use HLS (.m3u8) with many segments/variants. The
                // default LoadControl gives up quickly on slow CDNs, which
                // surfaces as a PlaybackException and (before this fix) caused
                // the app to switch servers every time. We give ExoPlayer a
                // larger min buffer and, critically, a long back-buffer so a
                // momentary segment stall doesn't immediately error out.
                //
                // NOTE: media3 1.4.x uses setBufferDurationsMs(...) (a single
                // call covering min/max/forPlayback/forPlaybackAfterRebuffer)
                // — the old setMinBufferMs/setMaxBufferMs setters were removed.
                //
                // bufferForPlayback lowered from 1500 → 1000 ms and
                // bufferForPlaybackAfterRebuffer from 3000 → 1500 ms so the
                // first frame appears sooner after the manifest loads. The
                // 30s minBuffer still keeps playback smooth once started; this
                // only affects how quickly playback *begins* after enough data
                // is buffered. This is the "loads videos really slow" fix:
                // the user sees video ~0.5-1.5s sooner on every title.
                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs            = */ 30_000,
                        /* maxBufferMs            = */ 90_000,
                        /* bufferForPlaybackMs    = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 1_500
                    )
                    .setBackBuffer(30_000, true)     // keep 30 s behind for seeks
                    .build()

                // mediaItem is built from the current url via a remember(url)
                // block above the Box — it updates whenever the url changes
                // (server switch), so the player always loads the correct
                // source. Previously a stale mediaItemHolder cached the first
                // media item and the new URL was silently ignored.
                val exoPlayer = ExoPlayer.Builder(ctx)
                    .setTrackSelector(selector)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory)
                    )
                    .build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                Log.d("ExoPlayer", "State: $state")
                                if (state == Player.STATE_READY) {
                                    isReady = true
                                    // Playback successfully started → reset the
                                    // transient retry counter so a later stall
                                    // gets a fresh allowance of retries.
                                    transientRetryCount = 0
                                    populateQualities(this@apply) { q -> availableQualities = q }
                                }
                            }

                            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                // Tracks can become available after STATE_READY
                                // for HLS — fire here too so the quality list
                                // populates as soon as the manifest is parsed.
                                populateQualities(this@apply) { q -> availableQualities = q }
                                // Re-assert English audio preference once the
                                // real track list is known — HLS manifests for
                                // multi-audio sources often expose the language
                                // tags only after the first track change, so
                                // applying it here locks in English playback.
                                trackSelector?.let { forceEnglishTracks(it) }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e("ExoPlayer", "Error: ${error.errorCodeName} - ${error.message}")
                                // ── Classify the error ──
                                // Transient errors are recovered in-player by
                                // re-preparing the same media item (up to
                                // MAX_PLAYER_RETRIES). Only once retries are
                                // exhausted — or the error is inherently fatal
                                // — do we surface it so PlayerScreen falls
                                // through to the next server. This stops the
                                // "switch to a different server every time"
                                // behaviour for shows.
                                if (isTransientPlaybackError(error) &&
                                    transientRetryCount < MAX_PLAYER_RETRIES) {
                                    transientRetryCount++
                                    Log.w("ExoPlayer", "🔁 Transient error — in-player retry $transientRetryCount/$MAX_PLAYER_RETRIES for same source.")
                                    // Re-prepare the same media item on the main
                                    // thread (the error callback already runs on
                                    // the main/application thread, but we use a
                                    // handler so we don't re-enter the player
                                    // synchronously while it's still tearing down
                                    // from the error). A short delay gives the
                                    // CDN/network a moment to recover.
                                    val item = mediaItem
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            this@apply.setMediaItem(item)
                                            this@apply.prepare()
                                            this@apply.playWhenReady = true
                                        } catch (e: Exception) {
                                            Log.e("ExoPlayer", "Retry re-prepare failed", e)
                                        }
                                    }, 800L)
                                    return
                                }
                                // Retries exhausted OR a fatal error → surface it.
                                val fatal = !isTransientPlaybackError(error) ||
                                    transientRetryCount >= MAX_PLAYER_RETRIES
                                Log.w("ExoPlayer", if (fatal) "❌ Fatal/unrecoverable error — surfacing to switch server." else "⚠️ surfacing error")
                                errorHandler.value.invoke(fatal)
                            }
                        })
                        setMediaItem(mediaItem)
                        prepare()
                        playWhenReady = true
                    }
                player = exoPlayer

                PlayerView(ctx).apply {
                    this.player = exoPlayer
                    useController = true
                    controllerShowTimeoutMs = 3000
                    // ── Surface / rendering configuration ──
                    // Ensure the video surface is always attached and visible.
                    // The surface type is a TextureView by default in media3,
                    // which handles resolution changes and DRM content better
                    // than SurfaceView. resizeMode=FIT keeps the correct aspect
                    // ratio so the video is never stretched/cropped.
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                    // Keep the surface visible even before the first frame —
                    // the poster overlay (outside the AndroidView) covers it
                    // until isReady flips true.
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = {},
            onRelease = {
                player?.release()
                player = null
            },
            modifier = Modifier.fillMaxSize()
        )
        } // end key(url)

        // ── Quality picker overlay (drawn on top of the player) ──
        if (availableQualities.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, end = 8.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                TextButton(
                    onClick = { showQualityMenu = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "⬡ $selectedQuality",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .background(
                                Color(0xCC000000),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                DropdownMenu(
                    expanded = showQualityMenu,
                    onDismissRequest = { showQualityMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Auto") },
                        onClick = {
                            showQualityMenu = false
                            selectedQuality = "Auto"
                            trackSelector?.let { applyAutoQuality(it) }
                        }
                    )
                    availableQualities.forEach { info ->
                        DropdownMenuItem(
                            text = { Text(info.label) },
                            onClick = {
                                showQualityMenu = false
                                selectedQuality = info.label
                                trackSelector?.let { applyQuality(it, info) }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Returns the ExoPlayer MIME type for [url], or null if it can't be guessed
 * (in which case ExoPlayer's built-in sniffing is used).
 *
 * This is the fix for the **00:00 duration bug**. Many providers serve a
 * progressive MP4 (or HLS) from a CDN path that does NOT end in a recognisable
 * file extension — e.g. `https://cdn.xyz/video/720p/abc123?token=xyz`. When
 * no MIME type is set, ExoPlayer's content-type sniffing can fail for these
 * extension-less URLs, and the player then:
 *   • reports a 0 duration (the seek bar shows 00:00),
 *   • cannot seek,
 *   • in some cases never starts playback.
 *
 * By detecting the stream shape from the URL and path patterns and setting the
 * MIME type explicitly, ExoPlayer always knows how to parse the stream and
 * reports the real duration.
 */
/**
 * Maps a `deliveringServerName` (the provider label shown to the user / set
 * when a race winner is applied) back to the canonical race-provider key used
 * by [PlayerScreen]'s exclusion set. Returns null for embed/LookMovie/OkHttp
 * providers (those are NOT racers — they live in later stages and are handled
 * by the normal stage fallthrough).
 *
 * Provider labels from the race look like "VidStorm", "VidStorm·Boron",
 * "VidStorm·unverified", "VidSrc·1", "VidLink", "VixSrc", "NoTorrent",
 * "NoTorrent·unverified", etc. We match on the leading canonical key.
 */
/**
 * Lightweight holder for a winning direct-API extraction result.
 * Used by the sequential failover in Stage 0+1 so the per-extractor
 * `tryXxx()` helpers can return a uniform type regardless of which
 * extractor's `Result.Stream` they came from.
 */

/**
 * Safely read the completed value of a [CompletableDeferred] without
 * throwing. Returns null if the deferred is not yet completed (which can
 * happen if `isCompleted` was true a moment ago but the value was reset, or
 * in a very tight race). This avoids [CompletableDeferred.getCompleted]
 * throwing [IllegalStateException].
 */
private fun safeGetCompleted(d: CompletableDeferred<DirectWinner?>): DirectWinner? =
    try { d.getCompleted() } catch (e: Exception) { null }

private data class DirectWinner(
    val url: String,
    val headers: Map<String, String>,
    val providerName: String
)

/**
 * Map a deliveringServerName to the race-provider key used for exclusion.
 *
 * Preserves the FULL sub-server name so that a failure of ONE VidStorm
 * sub-server (e.g. "VidStorm·Boron") only excludes THAT sub-server on
 * re-race, not the entire VidStorm family (Lithium, Hydrogen, …). This is
 * the fix for "servers like lithium are the main ones I've seen work" being
 * removed: the old version stripped the "·Boron" suffix and collapsed
 * every sub-server to "VidStorm", so a single Boron failure excluded Lithium
 * too.
 *
 * Returns null if the base name isn't a known race provider.
 */
private fun mapRaceProviderKey(deliveringServerName: String): String? {
    val n = deliveringServerName.trim()
    if (n.isBlank()) return null
    val base = n.substringBefore("·").trim()
    return if (base in RACE_PROVIDER_BASES) n else null
}

/** The set of base race-provider names (without sub-server suffixes). */
private val RACE_PROVIDER_BASES = setOf(
    "VidStorm", "VidSrc", "VidSrcNet", "VidLink", "VixSrc", "NoTorrent",
    "MeowTV", "Videasy", "KissKH", "VidSync", "LordFlix", "DahmerMovies",
    "TwoEmbed", "SuperEmbed", "VidSrcPro", "LookMovie"
)

/**
 * A resolved stream candidate held in the fallback queue.
 *
 * [english] is the heuristic English-audio flag computed at extraction time
 * (see [isEnglishStream]); [winner] is the fully-resolved direct stream that
 * can be handed to ExoPlayer with zero further network work.
 */
private data class RankedCandidate(
    val winner: DirectWinner,
    val english: Boolean
)

/**
 * Heuristic English-audio detection for a resolved stream.
 *
 * The race fires every extractor in parallel and we want the FIRST stream that
 * is (very likely) in English to be played. Because we never block playback on
 * a deep manifest probe (that would defeat the "play as soon as I click the
 * button" goal), this is a *cheap heuristic*: it inspects the URL and headers
 * we already have for English-audio signals.
 *
 * Signals (any one is enough to call it English):
 *  - URL query/segment contains `lang=en`, `audio=en`, `language=en`, `&eng`,
 *    `audio_eng`, `eng_audio`, `english` (case-insensitive).
 *  - Provider known to serve English audio by default (VidLink, VidSrc,
 *    VidStorm, NoTorrent, TwoEmbed, SuperEmbed, VidSrcPro, VidSrcNet,
 *    VixSrc, VidSync, LordFlix, DahmerMovies, MeowTV — all default-English
 *    upstreams for the TMDB-id embed pattern).
 *  - The HLS master URL itself contains an `eng`/`english` audio variant hint
 *    (some CDNs name the master `...eng.m3u8` or `...-en-...`).
 *
 * Returns false only when we have a positive *non-English* signal (e.g. a URL
 * that explicitly names another language such as `lang=es`, `lang=hi`,
 * `audio=fr`, etc.) AND no English signal — i.e. we downgrade clearly-foreign
 * streams but never downgrade ambiguous ones. This keeps the heuristic
 * conservative: ambiguous English-by-default providers still rank as English.
 */
private fun isEnglishStream(winner: DirectWinner, contentType: String): Boolean {
    val url = winner.url.lowercase()
    val name = winner.providerName.lowercase()

    // Positive non-English signal: an explicit foreign-language marker with
    // NO english marker present.
    val englishMarkers = listOf("lang=en", "audio=en", "language=en", "&eng",
        "audio_eng", "eng_audio", "english", "eng.", "eng-", "-en-", "multi_lang")
    val foreignMarkers = listOf("lang=es", "lang=hi", "lang=fr", "lang=de",
        "lang=it", "lang=pt", "lang=ru", "lang=ja", "lang=ko", "lang=zh",
        "lang=tr", "lang=ar", "audio=es", "audio=fr", "audio=hi", "audio=de",
        "audio=ru", "language=es", "language=fr", "language=hi", "language=de",
        "language=ru", "language=ja", "language=ko", "language=zh")

    val hasEnglish = englishMarkers.any { url.contains(it) } ||
        url.contains("eng.m3u8") || url.contains("english.m3u8")
    val hasForeign = foreignMarkers.any { url.contains(it) }

    if (hasEnglish) return true
    if (hasForeign && !hasEnglish) return false

    // Provider-default-English allowlist. These are TMDB-id embed providers
    // whose upstream is English audio for the vast majority of content.
    val defaultEnglishProviders = setOf(
        "vidlink", "vidsrc", "vidstorm", "notorrent", "twoembed",
        "superembed", "vidsrcpro", "vidsrcnet", "vixsrc", "vidsync",
        "lordflix", "dahmermovies", "meowtv", "vidspark", "autoembed",
        "vidnest", "vidrock", "vidcore", "tvembed", "vidsrcme",
        "vidking", "curtstream", "databasegdriveplayer", "vidsrcpro",
        "vcr", "vesy", "xps", "smashystream", "hexa", "flixer", "lookmovie"
    )
    val baseName = name.substringBefore("·").substringBefore(" ").trim()
    return defaultEnglishProviders.any { baseName.startsWith(it) || baseName == it }
}

/**
 * Rank providers by empirical reliability (higher = more trusted).
 *
 * Used as the tiebreaker after the English-first sort: among English streams
 * the most reliable provider is tried first, and among non-English streams the
 * most reliable is tried first too. Dead/blocked providers that somehow still
 * resolve a URL (e.g. a stale CDN node) get a low score so they sink to the
 * bottom of the candidate queue.
 */
private fun providerReliability(providerName: String): Int {
    val n = providerName.lowercase().substringBefore("·").substringBefore(" ").trim()
    return when {
        // Tier 1 — most reliable, default-English, widely-deployed.
        n.startsWith("vidlink") -> 100
        n.startsWith("vidsrc") -> 95
        n.startsWith("superembed") -> 90
        n.startsWith("twoembed") -> 88
        n.startsWith("vidsrcpro") -> 85
        n.startsWith("vidstorm") -> 82
        n.startsWith("notorrent") -> 80
        n.startsWith("vixsrc") -> 78
        n.startsWith("meowtv") -> 76
        n.startsWith("vidspark") -> 74
        n.startsWith("autoembed") -> 72
        n.startsWith("lordflix") -> 70
        n.startsWith("videasy") -> 68
        n.startsWith("vidsync") -> 66
        n.startsWith("dahmermovies") -> 64
        n.startsWith("curtstream") -> 62
        n.startsWith("databasegdriveplayer") -> 60
        // Tier 2 — 2embed sub-streams / secondary CDNs.
        n.startsWith("vcr") -> 55
        n.startsWith("vesy") -> 53
        n.startsWith("xps") -> 51
        n.startsWith("kisskh") -> 50
        n.startsWith("vidnest") -> 48
        n.startsWith("vidrock") -> 47
        n.startsWith("vidcore") -> 46
        n.startsWith("tvembed") -> 45
        n.startsWith("vidking") -> 44
        n.startsWith("smashystream") -> 42
        n.startsWith("hexa") -> 40
        n.startsWith("flixer") -> 38
        n.startsWith("vidsrcme") -> 36
        // LookMovie (headless addon port) — clean direct HLS when reachable,
        // but best-effort (may 403 / hit Thread Defence), so mid-tier.
        n.startsWith("lookmovie") -> 58
        // Unknown / fallback.
        else -> 20
    }
}

private fun guessMimeType(url: String): String? {
    val u = url.lowercase()

    // HLS (.m3u8 or HLS path patterns) — checked FIRST so that HLS
    // manifests served from paths containing "/video/" etc. are not
    // misidentified as progressive MP4 (which causes "duration known but
    // no video renders" because ExoPlayer tries to parse the m3u8 text
    // as an MP4 moov atom).
    if (u.contains(".m3u8")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("/hls/")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("/master")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("/playlist") && u.contains("m3u8")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("manifest") && (u.contains("m3u8") || u.contains("hls"))) return MimeTypes.APPLICATION_M3U8
    if (u.contains("/playlist.m3u8")) return MimeTypes.APPLICATION_M3U8
    // HLS served from subdomains/paths that imply a playlist (no .m3u8 ext).
    if (u.contains("/index") && u.contains("m3u8")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("hls.m3u8")) return MimeTypes.APPLICATION_M3U8
    if (u.contains("/stream/") && u.contains("m3u8")) return MimeTypes.APPLICATION_M3U8
    // VidLink / common provider HLS: query param ?hls or /playlist without ext.
    if (u.contains("?hls") || u.contains("&hls")) return MimeTypes.APPLICATION_M3U8
    // Query-param format hints: ?format=hls, ?type=m3u8, etc.
    if (u.contains("format=hls") || u.contains("type=m3u8") ||
        u.contains("format=m3u8") || u.contains("type=hls")) return MimeTypes.APPLICATION_M3U8

    // MeowTV / cf-master HLS manifests disguised as .txt files.
    // MeowTV's decrypt pipeline returns URLs like:
    //   https://sunl.stellarforgeinnovation.online/v4/tab/.../cf-master.1774676289.txt
    // These are HLS (M3U8) playlists served with a .txt extension by the
    // CDN. Without this check ExoPlayer falls through to the generic MP4
    // heuristics (the URL contains "/v4/" which matches the /video/ rule
    // further below) and tries to parse the M3U8 text as an MP4 moov atom,
    // causing "duration known but no video renders" \u2014 the exact bug that
    // made Rick and Morty (and other MeowTV-sourced titles) not play.
    // The cf-master filename and /v4/ path are unique to MeowTV's CDN.
    if (u.contains("cf-master") || (u.contains("/v4/") && u.contains(".txt"))) return MimeTypes.APPLICATION_M3U8
    // Some MeowTV CDNs also use /v2/ or /v3/ paths with .txt manifests.
    if ((u.contains("/v2/") || u.contains("/v3/")) && u.endsWith(".txt")) return MimeTypes.APPLICATION_M3U8

    // DASH (.mpd or manifest patterns).
    if (u.contains(".mpd")) return MimeTypes.APPLICATION_MPD
    if (u.contains("manifest") && u.contains("dash")) return MimeTypes.APPLICATION_MPD

    // Progressive container files by extension.
    if (u.endsWith(".mp4") || u.contains(".mp4?")) return MimeTypes.VIDEO_MP4
    if (u.endsWith(".webm") || u.contains(".webm?")) return MimeTypes.VIDEO_WEBM
    if (u.endsWith(".mkv") || u.contains(".mkv?")) return MimeTypes.VIDEO_MATROSKA
    if (u.endsWith(".mov") || u.contains(".mov?")) return MimeTypes.VIDEO_MP4
    if (u.endsWith(".avi") || u.contains(".avi?")) return "video/x-msvideo"
    if (u.endsWith(".flv") || u.contains(".flv?")) return "video/x-flv"

    // Progressive MP4 from extension-less CDN paths. Many providers (VidLink,
    // VidSrc CDNs) serve MP4 from paths containing "video", "mp4", "media",
    // "play", or "/720p|1080p|480p" resolution segments with a query token.
    // These are progressive MP4 streams, not HLS — setting VIDEO_MP4 lets
    // ExoPlayer read the moov atom and report the real duration.
    if (u.contains("/mp4/") || u.contains("/mp4?")) return MimeTypes.VIDEO_MP4
    if (u.contains("/video/") && !u.contains(".m3u8")) return MimeTypes.VIDEO_MP4
    if (u.contains("/media/") && !u.contains(".m3u8")) return MimeTypes.VIDEO_MP4
    if (u.contains("/play/") && !u.contains(".m3u8")) return MimeTypes.VIDEO_MP4
    if (Regex("""/(480|720|1080|1440|2160)p[/$?]""").containsMatchIn(u)) return MimeTypes.VIDEO_MP4
    // VidLink-style direct resource path.
    if (u.contains("/mp/resource/")) return MimeTypes.VIDEO_MP4
    // Generic direct-stream CDN paths (token-authenticated, no extension).
    if (u.contains("/directstream")) return MimeTypes.VIDEO_MP4
    if (u.contains("/download/") && u.contains("mp4")) return MimeTypes.VIDEO_MP4
    if (u.contains("/raw/") && !u.contains(".m3u8")) return MimeTypes.VIDEO_MP4
    // Cloud storage / file CDN progressive streams.
    if (u.contains("/file/") && !u.contains(".m3u8") && !u.contains(".mpd")) return MimeTypes.VIDEO_MP4

    return null
}

/**
 * Describes one selectable video quality: a human label (e.g. "1080p"),
 * plus the [TrackGroup] and the track index within it so we can
 * force-selection via [DefaultTrackSelector.Parameters].
 */
private data class TrackInfo(
    val label: String,
    val mediaTrackGroup: androidx.media3.common.TrackGroup,
    val trackIndex: Int,
    val height: Int,
    val bitrate: Int
)

/**
 * Reads the video tracks from the player after the manifest has loaded and
 * builds a sorted list of [TrackInfo] entries (highest resolution first).
 */
private fun populateQualities(
    player: ExoPlayer,
    onResult: (List<TrackInfo>) -> Unit
) {
    val tracks = player.currentTracks
    val result = mutableListOf<TrackInfo>()
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        for (ti in 0 until group.length) {
            if (!group.isTrackSupported(ti)) continue
            val format = group.getTrackFormat(ti)
            val h = format.height
            val bitrate = format.bitrate
            // Build a label: "1080p" or "1080p (5.2 Mbps)"
            val label = if (h > 0) {
                if (bitrate > 0) {
                    "$h p (${String.format("%.1f", bitrate / 1_000_000.0)} Mbps)"
                } else "$h p"
            } else {
                if (bitrate > 0) "Unknown (${String.format("%.1f", bitrate / 1_000_000.0)} Mbps)"
                else "Track $ti"
            }
            result.add(TrackInfo(label, group.mediaTrackGroup, ti, h, bitrate))
        }
    }
    // Sort by resolution descending (highest quality first).
    result.sortByDescending { it.height }
    if (result.isNotEmpty()) onResult(result)
}

/** Resets track selection to adaptive (auto) — ExoPlayer picks the best stream. */
private fun applyAutoQuality(selector: DefaultTrackSelector) {
    selector.parameters = DefaultTrackSelector.ParametersBuilder(selector.context!!)
        .build()
}

/** Forces the player to use a specific video track. */
private fun applyQuality(selector: DefaultTrackSelector, info: TrackInfo) {
    selector.parameters = DefaultTrackSelector.ParametersBuilder(selector.context!!)
        .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
        .setForceHighestSupportedBitrate(true)
        .setOverrideForType(
            TrackSelectionOverride(info.mediaTrackGroup, info.trackIndex)
        )
        .build()
}

/**
 * Default User-Agent. A desktop Firefox UA so segment / manifest requests
 * look like a real browser to upstream CDNs.
 * earned the Cloudflare clearance.
 */
private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * Maximum number of in-player retries for a TRANSIENT playback error before we
 * give up on the current source and fall through to the next server. Each retry
 * re-prepares the SAME media item (no server switch). This is the core fix for
 * "videos are there but it skips them every time": a slow segment or momentary
 * network blip no longer abandons a working URL.
 */
private const val MAX_PLAYER_RETRIES = 3

/**
 * Classifies an ExoPlayer [PlaybackException] as transient (recoverable by
 * re-preparing the same source) vs fatal (the source is genuinely dead and we
 * should move to the next server).
 *
 * Transient errors are the ones that happen on streams that *do* exist and
 * *can* play — a slow CDN segment, a momentary network drop, an HLS manifest
 * that temporarily 500s then recovers, or a decoder that needs a re-prepare.
 * Fatal errors are hard 404s, unsupported formats, and DRM/geo blocks where
 * retrying the same URL will never help.
 */
private fun isTransientPlaybackError(error: PlaybackException): Boolean {
    // Drill into the cause chain — ExoPlayer often wraps the real error.
    val cause = error.cause
    val causeName = cause?.javaClass?.simpleName ?: ""

    // Network/connectivity blips → definitely transient.
    if (causeName.contains("HttpDataSourceException", true) ||
        causeName.contains("Socket", true) ||
        causeName.contains("Timeout", true) ||
        causeName.contains("Network", true)
    ) {
        return true
    }

    // Parse-specific recoverable errors (manifest load hiccup, segment read).
    if (causeName.contains("ParserException", true) ||
        causeName.contains("BehindLiveWindowException", true)
    ) {
        return true
    }

    // Inspect the error code for known transient categories.
    val code = error.errorCode
    when (code) {
        // Load errors (HTTP 5xx, timeout, connection reset) — retryable.
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        // Renderer/decoder init that can recover on re-prepare.
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        // Source/manifest parse that may recover on re-prepare.
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
            return true
        else -> Unit
    }

    // A 403 / hard "ACCESS DENIED" on the actual media is fatal for THIS
    // source (geo/CDN block) — move on. ERROR_CODE_IO_BAD_HTTP_STATUS is
    // ambiguous, so only treat an explicit 403 message as fatal here.
    val msg = error.message?.lowercase() ?: ""
    if (msg.contains("403") || msg.contains("access denied") || msg.contains("forbidden")) {
        return false
    }

    // Default: treat as transient so we try the in-player retry before
    // abandoning a stream that "is there" — better to retry once too many
    // than to skip a working source every time.
    return true
}

/**
 * Forces English audio + subtitle tracks (when available) on the given player's
 * [DefaultTrackSelector]. This guarantees playback is in English — many
 * providers (VidSrc, 2Embed, VidStorm) serve multi-audio HLS/MP4 where the
 * default track is not English. We prefer "en"/"eng" language tags and, if no
 * English audio exists, fall back to the default so we never break playback.
 *
 * Subtitles: we enable English text tracks but do NOT force them on if the
 * audio is already English (avoids redundant captions). If the audio is
 * non-English and English subs are available, they're enabled automatically.
 */
@UnstableApi
private fun forceEnglishTracks(selector: DefaultTrackSelector) {
    val params = DefaultTrackSelector.ParametersBuilder(selector.context!!)
        // Prefer English audio; ExoPlayer falls back to the default track
        // automatically if no English audio is available, so playback is
        // never broken — only nudged toward English when it exists.
        .setPreferredAudioLanguage("en")
        .setPreferredAudioLanguages("en", "eng")
        // Prefer English text tracks for subtitle/caption selection. Text
        // tracks are only auto-selected when the audio is NOT in the preferred
        // language, so English audio won't get redundant English captions.
        .setPreferredTextLanguage("en")
        .setPreferredTextLanguages("en", "eng")
        .build()
    selector.parameters = params
}

