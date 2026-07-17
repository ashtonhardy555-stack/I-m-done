package com.mariocart.app.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mariocart.app.data.server.EmbedExtractor
import com.mariocart.app.data.server.LookMovieWebExtractor
import com.mariocart.app.data.server.LordFlixExtractor
import com.mariocart.app.data.server.MeowTvExtractor
import com.mariocart.app.data.server.KissKhExtractor
import com.mariocart.app.data.server.VidSyncExtractor
import com.mariocart.app.data.server.NoTorrentExtractor
import com.mariocart.app.data.server.ServerConfig
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.StreamExtractor
import com.mariocart.app.data.server.StreamProviders
import com.mariocart.app.data.server.SuperEmbedExtractor
import com.mariocart.app.data.server.VidLinkExtractor
import com.mariocart.app.data.server.VidSrcExtractor
import com.mariocart.app.data.server.VidSrcNetExtractor
import com.mariocart.app.data.server.VidSrcProExtractor
import com.mariocart.app.data.server.VidStormExtractor
import com.mariocart.app.data.server.VideasyExtractor
import com.mariocart.app.data.server.VixSrcExtractor
import com.mariocart.app.data.server.TwoEmbedExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
 *      given TMDB id in a single HTTP call — no WebView, no embed scraping,
 *      no Cloudflare challenge. This is by far the most reliable stage and
 *      is the root-cause fix for "only The Rookie played": virtually every
 *      other title fell through the fragile WebView stages below because
 *      LookMovie is Cloudflare-blocked and embed players don't always
 *      auto-request their media inside the extraction timeout, whereas the
 *      VidStorm API returns a real direct URL for almost everything.
 *   1. [VidSrcExtractor] (FALLBACK after VidStorm) — when VidStorm is broken
 *      for a title (Interstellar, The Green Mile, … get only a dead "Boron"
 *      URL), this walks the VidSrc embed pipeline
 *      (vidsrc.me → cloudorchestranova.com RCP/PRORCP → master_urls →
 *      generate.php token) over plain OkHttp and resolves live `#EXTM3U`
 *      manifests at 360p/720p/1080p. No WebView, no JS challenge. This is
 *      the "hidden servers in the exoplayer" aggregator sites keep behind
 *      their RCP indirection, and it consistently plays the titles VidStorm
 *      cannot. Fixes the long-wait-then-black-screen bug.
 *
 *      Stages 0 + 1 are raced in **parallel** with two additional direct-API
 *      extractors so the first one to return a verified Stream wins:
 *        - [VidLinkExtractor] — encrypts the TMDB id via enc-dec.app, then
 *          fetches a direct `.m3u8` playlist from the vidlink.pro JSON API.
 *        - [VixSrcExtractor] — calls the vixsrc.to API, fetches the embed
 *          HTML, extracts the token/expires/playlist, and builds a direct
 *          master HLS URL. No WebView, no JS challenge.
 *        - [NoTorrentExtractor] — queries the NoTorrent Stremio addon
 *          (`addon-osvh.onrender.com`) after resolving TMDB→IMDb. Returns
 *          8–18 direct HLS/MP4 streams and reliably resolves the stubborn
 *          titles VidStorm/VidSrc miss (Interstellar 157336, LOTR:ROTK 122).
 *   2. [EmbedExtractor] (FALLBACK) — tries every embed provider
 *      (VidLink, 2Embed, VidSrc, …) inside an off-screen WebView and
 *      captures the direct `.m3u8`/`.mp4` URL the provider's JS player
 *      requests at runtime.
 *   3. [LookMovieWebExtractor] (FALLBACK) — runs the exact Kodi-addon flow
 *      (search -> play page -> storage object -> security API -> .m3u8)
 *      inside an off-screen WebView so the Cloudflare JS challenge is solved
 *      automatically and the `cf_clearance` cookie is carried into the
 *      security-API `fetch()`. This mirrors the way the LookMovie Kodi addon
 *      works.
 *   4. [StreamExtractor] (LAST RESORT) — the original OkHttp-based LookMovie
 *      extractor. Kept as a final fallback; it returns a direct HLS URL when
 *      LookMovie is reachable without a Cloudflare challenge.
 *
 * Whenever any stage hits a human-verification challenge (captcha /
 * Cloudflare interstitial), it is forwarded to the user in-app via
 * [VerificationActivity]. The user solves it in a real WebView, the resulting
 * cookies are injected back into the extractors, and extraction is retried.
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
        // Guard so we only escalate to the user N times per playback session.
        const val MAX_VERIFICATION_ROUNDS = 2

        // Extraction stage indices — used by PlayerScreen to skip stages
        // (e.g. when the user picks a specific server we jump straight to
        // the embed stage) and to resume from the right point when ExoPlayer
        // reports a playback error on an already-resolved URL.
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
        const val RACE_TIMEOUT_MS = 9_000L

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

    /**
     * Outcome of a human-verification round-trip.
     *
     * [signal] is a monotonically increasing counter so that the Compose tree
     * can reliably detect each new result even if two successive verifications
     * return identical cookies (MutableStateFlow conflates equal values, so
     * we can't rely on the cookie string alone to trigger recomposition).
     */
    data class VerificationOutcome(
        val success: Boolean,
        val signal: Int
    )

    /** Updated by the ActivityResult callback; observed by the Compose tree. */
    private val verificationOutcome = MutableStateFlow(VerificationOutcome(false, 0))

    private lateinit var verificationLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Make sure the server list (used for ordering/scoring) is loaded.
        ServerManager.initialize(this)

        // Register the launcher for the VerificationActivity round-trip.
        // When the user solves the challenge, cookies come back in the result
        // intent and we feed them into the extraction retry via
        // [verificationOutcome].
        verificationLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                val prev = verificationOutcome.value
                if (result.resultCode == RESULT_OK) {
                    val cookies = result.data?.getStringExtra(VerificationActivity.EXTRA_COOKIES)
                    val finalUrl = result.data?.getStringExtra(VerificationActivity.EXTRA_FINAL_URL)
                    Log.i(TAG, "✅ Verification solved. cookies=${cookies?.length ?: 0} chars; finalUrl=$finalUrl")
                    if (!cookies.isNullOrBlank()) {
                        // Push cookies into OkHttp's jar for the OkHttp retry
                        // path (StreamExtractor) and into the WebView cookie
                        // jar for LookMovieWebExtractor / EmbedExtractor.
                        StreamExtractor.injectCookies(cookies)
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                    }
                    verificationOutcome.value = VerificationOutcome(success = true, signal = prev.signal + 1)
                } else {
                    Log.w(TAG, "Verification cancelled by user.")
                    verificationOutcome.value = VerificationOutcome(success = false, signal = prev.signal + 1)
                }
            }

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
                    backdropUrl = backdropUrl,
                    verificationOutcome = verificationOutcome.asStateFlow().collectAsState().value,
                    onLaunchVerification = { challengeUrl, referer ->
                        launchVerificationActivity(challengeUrl, referer)
                    }
                )
            }
        }
    }

    private fun launchVerificationActivity(challengeUrl: String, referer: String) {
        Log.i(TAG, "🤖 Launching human verification for $challengeUrl (referer=$referer)")
        verificationLauncher.launch(
            VerificationActivity.newIntent(this, challengeUrl, referer)
        )
    }
}

/**
 * The player screen.
 *
 * Extraction pipeline (in order, all on-device — no backend):
 *  0. [VidStormExtractor] — direct VidStorm API call (the FilmCave "cloud
 *     servers" backend). Resolves a direct .m3u8/.mp4 URL for a TMDB id
 *     with no WebView and no Cloudflare challenge. PRIMARY stage.
 *  1. [VidSrcExtractor] — VidSrc RCP pipeline (vidsrc.me →
 *     cloudorchestranova.com) over OkHttp. Resolves live #EXTM3U manifests
 *     for the titles VidStorm cannot (Interstellar, The Green Mile, …).
 *     No WebView, no JS challenge. Falls back here when VidStorm returns
 *     only dead/null sources.
 *  2. [EmbedExtractor] — off-screen WebView over every embed provider,
 *     capturing a direct media URL.
 *  3. [LookMovieWebExtractor] — the Kodi-addon flow inside a WebView
 *     (search -> play page -> storage object -> security API -> .m3u8).
 *     Bypasses Cloudflare because the WebView executes the JS challenge.
 *  4. [StreamExtractor] — OkHttp LookMovie fallback (last resort).
 *
 * A human-verification challenge from ANY stage is surfaced to the user via
 * [VerificationActivity]; cookies are injected and extraction is retried.
 *
 * The TMDB poster/backdrop is shown as a thumbnail while the stream is being
 * resolved and until ExoPlayer reports STATE_READY (actual playback begins).
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
    backdropUrl: String? = null,
    verificationOutcome: PlayerActivity.VerificationOutcome,
    onLaunchVerification: (challengeUrl: String, referer: String) -> Unit
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
    // Which stage extraction should START from on this attempt.
    //  - STAGE_VIDSTORM (0): full auto pipeline (default).
    //  - STAGE_EMBED   (2): user picked a specific server — skip VidStorm /
    //    VidSrc and go straight to the embed stage so ServerManager's
    //    ordered list (with their pick first) is honoured. This is the fix
    //    for "if I interrupt the stream auto finder to choose my own it
    //    stops working": previously the select just bumped `attempt`, which
    //    re-ran the whole pipeline from VidStorm and ignored the pick.
    //    NOTE: embed is now stage 2 (after VidStorm/VidSrc, before LookMovie)
    //    so when VidStorm/VidSrc fail the parallel embed racing stage runs
    //    next, giving faster and more reliable fallback to working providers.
    var startStage by remember { mutableStateOf(0) }
    // Tracks the stage that actually delivered the current streamUrl so that
    // an ExoPlayer playback error can resume extraction from the NEXT stage
    // instead of giving up (fix for "some titles like Interstellar don't
    // play" — the URL resolves but ExoPlayer can't render it).
    var currentStage by remember { mutableStateOf(0) }

    // --- Race-provider exclusion (the Interstellar + "everything plays" fix) -- //
    // When the parallel Stage-0 race hands ExoPlayer a stream that ExoPlayer
    // can't actually play (e.g. VidStorm's dead unverified URL for
    // Interstellar), we must NOT just fall through to Stage 1 (VidSrc) — that
    // would SKIP the other racers (notably NoTorrent, which is the only one
    // that resolves Interstellar). Instead we re-run the Stage-0 race with the
    // failed provider excluded, so a DIFFERENT racer wins. We keep doing this
    // until every racer has been tried; only then do we fall through to the
    // later stages. This delivers the UNION of build 4 and build 13:
    //   - build 13 behaviour (Interstellar works) is preserved because the
    //     dead VidStorm URL is excluded and NoTorrent wins on the re-race;
    //   - build 4 behaviour (most things play & load fast) is preserved
    //     because we never DROP a resolved URL — we just try the next racer
    //     when ExoPlayer (the real arbiter) says one is dead.
    var excludedRaceProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var raceFallbackUsed by remember { mutableStateOf(false) }
    // How many times we've escalated to the user for human verification.
    var verificationRounds by remember { mutableStateOf(0) }
    // Track the pending challenge so we only launch the activity once.
    var pendingChallengeUrl by remember { mutableStateOf<String?>(null) }
    var pendingReferer by remember { mutableStateOf("https://www.lookmovie2.to/") }

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

        // ── Stage 0+1: direct-API sequential failover (auto mode) ── //
        // Skipped when the user manually picked a server (start == STAGE_EMBED):
        // the direct extractors ignore ServerManager ordering, so running them
        // would defeat the user's explicit choice (the embed stage below
        // honours it).
        //
        // ## Why SEQUENTIAL failover (not a parallel race)
        //
        // Build 4 (the last working build) used a simple sequential pipeline:
        // try VidStorm, and if it errored, try VidSrc. Movies played in ~3-4 s.
        //
        // The regression that broke everything was a commit that turned this
        // into a 10-way `select{}` coroutine race (5 direct extractors + a
        // WebView embed + more added over time) wrapped in a single 12 s
        // withTimeout. That race was fragile:
        //   - A single slow/hung extractor (e.g. a Cloudflare-blocked VixSrc,
        //     or a VidSrc token round-trip that stalls) could win the race by
        //     returning a dead-but-non-Error body, starving the fast winners.
        //   - The `select` cancelled losers, so when the "winner" turned out
        //     to be a dead URL ExoPlayer later rejected, the good candidates
        //     were already gone and we fell all the way through to the slow
        //     WebView stages.
        //   - The 12 s wall-clock meant a few extractors simply never got a
        //     fair shot.
        //
        // This restores the build-4 philosophy — simple, fast, predictable —
        // but with MORE direct APIs and a per-extractor timeout so one slow
        // server can never block the others for more than PROVIDER_TIMEOUT_MS.
        //
        // UPDATE: all 13 direct-API extractors now run IN PARALLEL via a
        // coroutineScope race (see below). The first verified Stream wins
        // and every other in-flight extractor is cancelled immediately.
        // This means playback starts in ~1-3 s (the time of the FASTEST
        // working extractor) instead of up to 91 s in the worst case.
        // Order no longer matters for speed \u2014 all fire at once \u2014 but the
        // list below documents each extractor's role:
        //   VidStorm  → one HTTP call, direct .m3u8/.mp4 (fast for popular movies)
        //   VidSrc    → vidsrc.me RCP/PRORCP, resolves almost everything (~2.4 s)
        //   VidSrcNet → vidsrc.net/cloudnestra with full 12-decoder pipeline
        //   VidLink   → /api/player?tmdb direct HLS
        //   VixSrc    → vidsrc.xyz /vixsrc embed
        //   NoTorrent → Stremio addon (great TV coverage)
        //   MeowTV    → api.meowtv.ru direct API (EXCELLENT TV-episode coverage)
        //   Videasy   → videasy.stream (10-server parallel)
        //   KissKH    → kisskh.do search→episode direct HLS (broad TV catalogue)
        //   VidSync   → vidsync.xyz / wingsdatabase 12-server parallel
        //   LordFlix  → lordflix alternative
        //   DahmerMovies → dahmermovies fallback
        //   TwoEmbed  → 2embed.cc WebView-style direct
        //
        // The FIRST extractor to return a verified Stream wins and we stop
        // immediately. If all return Error/timeout, we fall through to
        // Stage 2 (WebView embed) which is now re-enabled in auto mode.
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

            // ── FAST SEQUENTIAL FAILOVER → then BOUNDED PARALLEL RACE ──
            //
            // 2026 provider testing (see test_results_v2.json / embed_test_results.json)
            // empirically confirmed which extractors actually resolve a real
            // playable stream RIGHT NOW and how fast:
            //
            //   • NoTorrent  ≈ 280–400 ms  → direct .mp4/.m3u8 (works movie + TV) ✅ BEST
            //   • VidStorm   ≈ 300 ms      → m3u8 (reliable for TV; flaky for some movies)
            //   • VidSrc.pro → 12 s TIMEOUT (dead/slow)              ❌
            //   • SuperEmbed  → seapi.link DNS DEAD                    ❌
            //   • VidSrcNet   → vidsrc.net embed is a PARKED lander    ❌
            //   • VidLink API → enc-dec.app proxy returns EMPTY body   ❌ (WebView only)
            //
            // The old 15-way parallel race waited up to RACE_TIMEOUT_MS (12 s)
            // because the dead/slow extractors (VidSrc.pro, SuperEmbed DNS,
            // VidLink empty-body) held the race open even though NoTorrent had
            // already won in ~300 ms. That 12 s ceiling — not the extraction
            // itself — was the dominant cause of "videos play really slowly".
            //
            // New strategy (per the user's request: "play as soon as I click,
            // don't necessarily need to be raced"):
            //
            //   1. FAST LANE (sequential, short timeouts) — try the confirmed
            //      fast+reliable extractors ONE AT A TIME with a tight
            //      per-extractor timeout. NoTorrent first (~300 ms typical),
            //      then VidStorm. If either wins we return IMMEDIATELY —
            //      playback starts in well under a second for the common case.
            //   2. If the fast lane misses, fall into a BOUNDED parallel race
            //      of the REMAINING alive extractors (with the dead ones
            //      REMOVED so they can no longer inflate the wait). First
            //      verified Stream wins; the rest are cancelled.
            //
            // Dead/confirmed-broken extractors (VidSrcPro, SuperEmbed,
            // VidSrcNet) are excluded from BOTH lanes — they were the sources
            // of the 12 s timeouts that made everything feel slow.
            //
            // ── Why a CompletableDeferred winner (not select{}/removeAt) ──
            // The original PR-#18 implementation used a `select` block with a
            // `removeAt(idx)` loop. That had a critical regression: the
            // `safe()` wrapper re-threw `CancellationException`, so when the
            // outer timeout fired (cancelling all asyncs) the cancellation
            // propagated through `onAwait` → crashed the `select` → the
            // `coroutineScope` threw → the outer `catch` returned `null`,
            // DISCARDING every extractor result that had already succeeded
            // ("videos that worked don't anymore").
            //
            // This version uses a single shared `CompletableDeferred` as the
            // "winner" signal. Each async, on getting a non-null result, tries
            // to complete it (only the first succeeds). `safe()` swallows ALL
            // exceptions (including cancellation) and returns null — it NEVER
            // propagates, so a timeout can never crash the scope. If the
            // timeout fires we salvage whatever `winner` already holds.
            suspend fun safe(d: suspend () -> DirectWinner?): DirectWinner? =
                try { d() } catch (e: Exception) {
                    Log.w("Player", "race async error: ${e.message}")
                    null
                }

            // ── FAST LANE: confirmed-fast extractors, tried sequentially ──
            // NoTorrent is the single fastest+most-reliable 2026 source, so it
            // goes first with its own tight timeout. VidStorm is next. If
            // either resolves a verified stream we play it immediately and
            // never touch the (slower) parallel lane.
            val FAST_LANE_TIMEOUT_MS = 5_000L
            var winner: DirectWinner? = null
            // Declared in the outer scope so the belt-and-suspenders salvage
            // after the parallel lane can reference it even if the fast lane
            // already resolved `winner` (in which case the deferred stays
            // incomplete and safeGetCompleted returns null — harmless).
            val winnerDeferred = CompletableDeferred<DirectWinner?>()
            Log.d("Player", "🚦 FAST LANE: NoTorrent → VidStorm (sequential, ${FAST_LANE_TIMEOUT_MS}ms each)")
            winner = withTimeoutOrNull(FAST_LANE_TIMEOUT_MS) {
                safe { tryNoTorrent() }
            }
            if (winner == null) {
                winner = withTimeoutOrNull(FAST_LANE_TIMEOUT_MS) {
                    safe { tryVidStorm() }
                }
            }
            if (winner != null) {
                Log.i("Player", "⚡ FAST LANE winner: ${winner.providerName} (skipped parallel race)")
            }

            // ── PARALLEL LANE: remaining alive extractors race together ──
            // Only reached if the fast lane missed. Dead/timeout-prone
            // extractors (VidSrcPro, SuperEmbed, VidSrcNet) are EXCLUDED so
            // they cannot inflate the wait. VidLink/VixSrc/TwoEmbed are kept
            // because they sometimes resolve via their (WebView-backed) paths.
            if (winner == null) {
                Log.d("Player", "🏁 FAST LANE empty → PARALLEL LANE (dead extractors excluded)")
                val allDone = CompletableDeferred<Unit>()
                winner = try {
                    withTimeoutOrNull(PlayerActivity.RACE_TIMEOUT_MS) {
                        coroutineScope {
                            val deferreds = listOf(
                                async { safe { tryVidSrc() } },
                                async { safe { tryVidLink() } },
                                async { safe { tryVixSrc() } },
                                async { safe { tryMeowTv() } },
                                async { safe { tryVideasy() } },
                                async { safe { tryKissKh() } },
                                async { safe { tryVidSync() } },
                                async { safe { tryLordFlix() } },
                                async { safe { tryDahmer() } },
                                async { safe { tryTwoEmbed() } }
                                // EXCLUDED (confirmed dead/timeout-prone in 2026):
                                //   tryVidSrcPro()   → 12 s read timeout
                                //   trySuperEmbed()  → seapi.link DNS dead
                                //   tryVidSrcNet()   → vidsrc.net embed is a parked lander
                            )

                            // Watch every async: the FIRST non-null result
                            // completes `winnerDeferred`; when ALL finish we
                            // complete `allDone` so we stop waiting early.
                            launch {
                                for (d in deferreds) {
                                    val res = try { d.await() } catch (e: Exception) { null }
                                    if (res != null) winnerDeferred.complete(res)
                                }
                                allDone.complete(Unit)
                            }

                            // Wait for EITHER a winner OR all-exhausted.
                            select<DirectWinner?> {
                                winnerDeferred.onAwait { it }
                                allDone.onAwait { null }
                            }
                        }
                    } ?: run {
                        // Timeout fired — salvage a winner completed before the
                        // deadline instead of discarding it.
                        safeGetCompleted(winnerDeferred)
                    }
                } catch (e: Exception) {
                    Log.w("Player", "Parallel race error: ${e.message}")
                    safeGetCompleted(winnerDeferred)
                }
            }

            // Belt-and-suspenders: if the select returned null but a winner
            // completed in the meantime (very tight race), use it.
            val finalWinner = winner ?: safeGetCompleted(winnerDeferred)

            if (finalWinner != null) {
                Log.i("Player", "🏁 Direct-API winner: ${finalWinner.providerName}")
                streamUrl = finalWinner.url
                streamHeaders = finalWinner.headers.ifEmpty {
                    mapOf("User-Agent" to DEFAULT_UA)
                }
                deliveringServerName = finalWinner.providerName
                isLoading = false
                return@LaunchedEffect
            } else {
                Log.w("Player", "All direct extractors yielded nothing; falling back to WebView embed pipeline.")
            }
        }

        // ── Stage 2: off-screen WebView embed extraction (PARALLEL — races multiple providers) (FALLBACK) ── //
        // This is the stage that actually honours the user's server choice:
        // EmbedExtractor calls ServerManager.getOrderedServers() which puts
        // the user-selected server first. So when start == STAGE_EMBED we
        // jump straight here.
        //
        // It is ALSO the fallback when ALL direct-API extractors (Stage 0+1)
        // yield nothing in auto mode (start <= STAGE_EMBED). The old code
        // gated this on `start == STAGE_EMBED`, which meant that in auto mode
        // — after the direct APIs all failed — we fell straight through to
        // LookMovie and never tried the WebView embed providers at all. That
        // is why "some movies/shows don't play": the direct APIs didn't have
        // them, and the embed pipeline was skipped. Using `<=` re-enables the
        // embed fallback in auto mode so every title gets a fair shot at the
        // WebView-based providers (2embed, vidsrc.to, smashystream…) which
        // can pass Cloudflare and resolve titles the pure-HTTP extractors
        // can't.
        if (start <= PlayerActivity.STAGE_EMBED && activity != null) {
            infoMessage = "Trying ${ServerManager.allServers().firstOrNull { it.id == selectedServerId }?.name ?: "your server"}…"
            currentStage = PlayerActivity.STAGE_EMBED
            val embedResult = try {
                EmbedExtractor.extractFromProviders(
                    context = activity,
                    contentType = contentType,
                    tmdbId = tmdbId,
                    season = season,
                    episode = episode,
                    onChallengeNeeded = null // surfaced via Result.Challenge below
                )
            } catch (e: Exception) {
                Log.e("Player", "💥 Embed extraction failed", e)
                EmbedExtractor.Result.Error(e.message ?: "Embed extraction failed")
            }

            when (embedResult) {
                is EmbedExtractor.Result.Stream -> {
                    Log.i("Player", "✅ Embed stream: ${embedResult.url}")
                    streamUrl = embedResult.url
                    streamHeaders = embedResult.headers.ifEmpty {
                        mapOf("User-Agent" to DEFAULT_UA)
                    }
                    deliveringServerName = embedResult.providerName.ifBlank { "Embed" }
                    isLoading = false
                    return@LaunchedEffect
                }
                is EmbedExtractor.Result.Challenge -> {
                    if (verificationRounds < PlayerActivity.MAX_VERIFICATION_ROUNDS) {
                        verificationRounds++
                        pendingChallengeUrl = embedResult.challengeUrl
                        pendingReferer = embedResult.embedUrl
                        infoMessage = "Human verification required. Please complete the challenge, then tap Done."
                        isLoading = false
                        return@LaunchedEffect
                    }
                    Log.w("Player", "Embeds still blocked after verification; trying LookMovie fallback.")
                }
                is EmbedExtractor.Result.NotFound, is EmbedExtractor.Result.Error -> {
                    Log.w("Player", "Embed extraction yielded nothing; trying LookMovie WebView fallback.")
                }
            }
        }

        // ── Stage 3: LookMovie via WebView (FALLBACK — mirrors Kodi addon) ── //
        if (start <= PlayerActivity.STAGE_LOOKMOVIE && activity != null) {
            if (start < PlayerActivity.STAGE_LOOKMOVIE) infoMessage = "Trying alternative sources…"
            currentStage = PlayerActivity.STAGE_LOOKMOVIE
            val lookResult = try {
                LookMovieWebExtractor.extract(
                    context = activity,
                    title = title,
                    year = year,
                    contentType = contentType,
                    season = season,
                    episode = episode
                )
            } catch (e: Exception) {
                Log.e("Player", "💥 LookMovie WebView extraction failed", e)
                LookMovieWebExtractor.Result.Error(e.message ?: "LookMovie extraction failed")
            }

            when (lookResult) {
                is LookMovieWebExtractor.Result.Stream -> {
                    Log.i("Player", "✅ LookMovie stream: ${lookResult.url}")
                    streamUrl = lookResult.url
                    streamHeaders = lookResult.headers.ifEmpty {
                        mapOf("User-Agent" to DEFAULT_UA)
                    }
                    isLoading = false
                    return@LaunchedEffect
                }
                is LookMovieWebExtractor.Result.Challenge -> {
                    if (verificationRounds < PlayerActivity.MAX_VERIFICATION_ROUNDS) {
                        verificationRounds++
                        pendingChallengeUrl = lookResult.challengeUrl
                        pendingReferer = lookResult.referer
                        infoMessage = "Human verification required. Please complete the challenge, then tap Done."
                        isLoading = false
                        return@LaunchedEffect
                    }
                    Log.w("Player", "LookMovie still blocked after verification; trying OkHttp fallback.")
                }
                is LookMovieWebExtractor.Result.Error -> {
                    Log.w("Player", "LookMovie WebView yielded nothing (${lookResult.message}); trying OkHttp fallback.")
                }
            }
        }

        // ── Stage 4: OkHttp LookMovie extractor (LAST RESORT) ── //
        if (start <= PlayerActivity.STAGE_OKHTTP) {
            infoMessage = "Trying last-resort source…"
            currentStage = PlayerActivity.STAGE_OKHTTP
        try {
            val result = StreamExtractor.extract(title, year, contentType, season, episode)

            when (result) {
                is StreamExtractor.Result.Stream -> {
                    Log.i("Player", "✅ LookMovie OkHttp stream: ${result.url}")
                    streamUrl = result.url
                    streamHeaders = result.headers
                    isLoading = false
                    return@LaunchedEffect
                }
                is StreamExtractor.Result.Challenge -> {
                    if (verificationRounds < PlayerActivity.MAX_VERIFICATION_ROUNDS) {
                        verificationRounds++
                        pendingChallengeUrl = result.challengeUrl
                        pendingReferer = result.referer
                        infoMessage = "Human verification required. Please complete the challenge in the browser that just opened, then tap Done."
                        isLoading = false
                        return@LaunchedEffect
                    } else {
                        error = "Still blocked after verification. Please try again later."
                    }
                }
                is StreamExtractor.Result.Error -> {
                    Log.e("Player", "❌ ${result.message}")
                    error = result.message
                }
            }
        } catch (e: Exception) {
            Log.e("Player", "💥 OkHttp extraction failed", e)
            error = e.message ?: "Failed to load stream."
        } finally {
            isLoading = false
        }
        } // end if (start <= STAGE_OKHTTP)

        // If we reached here with start == STAGE_EMBED (user picked a server
        // and the embed stage exhausted all providers), surface a clear error
        // instead of silently showing a blank loading screen.
        if (start == PlayerActivity.STAGE_EMBED && error == null) {
            error = "Couldn't find a working stream on the selected server. Try another server or Auto."
        }
    }

    // --------------------------------------------------------------- //
    //  Launch VerificationActivity when a challenge is pending         //
    // --------------------------------------------------------------- //
    LaunchedEffect(pendingChallengeUrl) {
        val url = pendingChallengeUrl ?: return@LaunchedEffect
        // Consume immediately so we don't re-launch on recomposition.
        pendingChallengeUrl = null
        onLaunchVerification(url, pendingReferer)
    }

    // --------------------------------------------------------------- //
    //  React to a completed verification round-trip                   //
    //  Keyed on the signal counter so it fires on every result.        //
    // --------------------------------------------------------------- //
    LaunchedEffect(verificationOutcome.signal) {
        // signal == 0 is the initial state; nothing to do.
        if (verificationOutcome.signal == 0) return@LaunchedEffect

        if (verificationOutcome.success) {
            // Cookies were already injected by the activity.
            // Bump attempt to re-run extraction with the fresh cookies.
            Log.i("Player", "🔄 Retrying extraction after verification…")
            infoMessage = "Verification complete — finding your stream…"
            isLoading = true
            error = null
            attempt++
        } else {
            error = "Verification was cancelled."
        }
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
                    // Reset verification rounds on a manual retry so the
                    // user can re-attempt if the site is challenging again.
                    verificationRounds = 0
                    error = null
                    // Retry from the top of the pipeline.
                    startStage = PlayerActivity.STAGE_VIDSTORM
                    attempt++
                },
                onBack = { (localContext as? ComponentActivity)?.finish() }
            )

            infoMessage != null && streamUrl == null -> {
                // Waiting for the user to finish verification in the WebView.
                VerificationWaitScreen(title, posterUrl, backdropUrl, infoMessage!!) {
                    attempt++
                    infoMessage = null
                }
            }

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

                        // ── Race-provider exclusion (the Interstellar fix) ──
                        // If the broken stream came from a Stage-0 direct
                        // extractor, exclude it and re-run the parallel
                        // race so a DIFFERENT extractor wins (e.g.
                        // NoTorrent for Interstellar once VidStorm's dead URL
                        // is excluded). We keep doing this until every direct
                        // extractor has been tried; only then do we fall
                        // through to the embed/LookMovie stages.
                        val raceProviderKey = failedProvider?.let { mapRaceProviderKey(it) }
                        if (currentStage == PlayerActivity.STAGE_VIDSTORM &&
                            raceProviderKey != null &&
                            raceProviderKey !in excludedRaceProviders
                        ) {
                            excludedRaceProviders = excludedRaceProviders + raceProviderKey
                            // If there are still direct extractors left to try,
                            // re-run Stage 0 (the race skips excluded ones);
                            // otherwise fall through to Stage 2 (embed).
                            // Count DISTINCT base providers (collapsing
                            // VidStorm sub-servers to one) so that many
                            // VidStorm sub-server failures don't prematurely
                            // exhaust the re-race budget. There are 15 base
                            // providers, so we allow up to 12 distinct-base
                            // exclusions before giving up.
                            val distinctBases = excludedRaceProviders
                                .map { it.substringBefore("·").trim() }
                                .toSet()
                            if (distinctBases.size < 14) {
                                Log.i("Player", "🔁 Re-racing Stage 0 with $raceProviderKey excluded (tried so far: $excludedRaceProviders)")
                                startStage = PlayerActivity.STAGE_VIDSTORM
                            } else {
                                Log.i("Player", "All race providers exhausted — falling through to embed/LookMovie stages.")
                                startStage = PlayerActivity.STAGE_EMBED
                                excludedRaceProviders = emptySet()
                            }
                        } else {
                            // Failed stream came from a later stage (embed /
                            // LookMovie / OkHttp) or all racers already tried.
                            // Resume from the stage after the one that delivered
                            // the broken URL. +1 lands on the next fallback.
                            startStage = (currentStage + 1).coerceAtMost(PlayerActivity.STAGE_OKHTTP)
                        }
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
                verificationRounds = 0
                streamUrl = null
                deliveringServerName = null
                error = null
                infoMessage = null
                // ── Server-selection fix ──
                // When the user picks a SPECIFIC server, jump straight to the
                // embed stage (STAGE_EMBED) so ServerManager's ordered list —
                // which puts their pick first — is actually honoured. VidStorm
                // and LookMovie ignore ServerManager ordering, so running them
                // first would override the user's explicit choice and often
                // land on a different (or dead) source, which is exactly the
                // "it stops working and I have to close the movie" bug.
                //
                // When the user picks "Auto" (id == null), run the full
                // pipeline from the top (VidStorm first).
                startStage = if (id != null) PlayerActivity.STAGE_EMBED
                             else PlayerActivity.STAGE_VIDSTORM
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
//  Loading / Error / Verification-wait screens with poster          //
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
}

/**
 * Shown while the user is solving a captcha in the foreground
 * [VerificationActivity]. Keeps the poster visible.
 */
@Composable
private fun VerificationWaitScreen(
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
    message: String,
    onRetry: () -> Unit
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
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onRetry) { Text("Retry extraction") }
        }
    }
}

// ---------------------------------------------------------------- //
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
    backdropUrl: String? = null,
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
    "TwoEmbed", "SuperEmbed", "VidSrcPro"
)

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
 * Default User-Agent. Matches the Firefox UA used by [StreamExtractor] and
 * [LookMovieWebExtractor] so segment requests look like the same browser that
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

