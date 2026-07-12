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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import com.mariocart.app.data.server.EmbedExtractor
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.StreamExtractor
import com.mariocart.app.ui.theme.MarioCartTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        // Guard so we only escalate to the user N times per playback session.
        const val MAX_VERIFICATION_ROUNDS = 2

        fun newIntent(
            context: Context,
            tmdbId: Int,
            contentType: String = "movie",
            season: Int = 1,
            episode: Int = 1,
            title: String = "Now Playing",
            year: String? = null
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("TMDB_ID", tmdbId)
            putExtra("CONTENT_TYPE", contentType)
            putExtra("SEASON", season)
            putExtra("EPISODE", episode)
            putExtra("TITLE", title)
            putExtra("YEAR", year)
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
                        // Push cookies into OkHttp's jar for the retry (LookMovie path).
                        StreamExtractor.injectCookies(cookies)
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
 * Extraction pipeline (in order):
 *  1. [EmbedExtractor] — runs each provider in [StreamProviders.ALL] inside
 *     an **off-screen WebView** to capture a direct `.m3u8` / `.mp4` URL the
 *     provider's JS player requests. If a real human-verification challenge
 *     appears, the WebView is surfaced to the user (see [onLaunchVerification]).
 *     The captured URL plays in **ExoPlayer** — never in a WebView.
 *  2. [StreamExtractor] — the legacy LookMovie extractor, used as a last-resort
 *     backend fallback (it returns a direct HLS URL when LookMovie is up).
 *  3. On a challenge → launch [VerificationActivity] so the user solves the
 *     captcha; cookies are injected and extraction retries.
 *  4. On error → show the message; allow manual retry.
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
    // How many times we've escalated to the user for human verification.
    var verificationRounds by remember { mutableStateOf(0) }
    // Track the pending challenge so we only launch the activity once.
    var pendingChallengeUrl by remember { mutableStateOf<String?>(null) }
    var pendingReferer by remember { mutableStateOf("https://vidlink.pro/") }

    // --------------------------------------------------------------- //
    //  Extraction LaunchedEffect                                       //
    // --------------------------------------------------------------- //
    LaunchedEffect(tmdbId, contentType, season, episode, attempt) {
        isLoading = true
        error = null
        infoMessage = null

        // Clear per-session server health for a fresh ordering on new content.
        if (attempt == 0) ServerManager.resetHealth()

        Log.d("Player", "🔎 Extraction attempt #$attempt for \"$title\" ($year) $contentType S$season E$episode")

        // ── Step 1: off-screen WebView embed extraction (primary) ── //
        val activity = localContext as? android.app.Activity
        if (activity != null) {
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
                    isLoading = false
                    return@LaunchedEffect
                }
                is EmbedExtractor.Result.Challenge -> {
                    // Surface the captcha to the user.
                    if (verificationRounds < PlayerActivity.MAX_VERIFICATION_ROUNDS) {
                        verificationRounds++
                        pendingChallengeUrl = embedResult.challengeUrl
                        pendingReferer = embedResult.embedUrl
                        infoMessage = "Human verification required. Please complete the challenge, then tap Done."
                        isLoading = false
                        return@LaunchedEffect
                    }
                    Log.w("Player", "Still blocked after verification; falling back to backend.")
                }
                is EmbedExtractor.Result.NotFound, is EmbedExtractor.Result.Error -> {
                    Log.w("Player", "Embed extraction yielded nothing; falling back to LookMovie backend.")
                }
            }
        }

        // ── Step 2: legacy LookMovie extractor (fallback backend) ── //
        try {
            val result = StreamExtractor.extract(title, year, contentType, season, episode)

            when (result) {
                is StreamExtractor.Result.Stream -> {
                    Log.i("Player", "✅ LookMovie stream: ${result.url}")
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
            Log.e("Player", "💥 Backend extraction failed", e)
            error = e.message ?: "Failed to load stream."
        } finally {
            isLoading = false
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
            // Cookies were already injected into OkHttp by the activity.
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            isLoading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        infoMessage ?: "Finding best stream…",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text("⚠️ $error", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        // Reset verification rounds on a manual retry so the
                        // user can re-attempt if the site is challenging again.
                        verificationRounds = 0
                        error = null
                        attempt++
                    }) { Text("Retry") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        (localContext as? ComponentActivity)?.finish()
                    }) { Text("Back") }
                }
            }

            infoMessage != null && streamUrl == null -> {
                // Waiting for the user to finish verification in the WebView.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        infoMessage!!,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = {
                        // If the auto-detect in VerificationActivity didn't
                        // work, the user can retry from here too.
                        attempt++
                        infoMessage = null
                    }) { Text("Retry extraction") }
                }
            }

            streamUrl != null -> {
                ExoPlayerView(streamUrl!!, streamHeaders)
            }
        }
    }
}

// ---------------------------------------------------------------- //
//  ExoPlayer composable                                            //
// ---------------------------------------------------------------- //

@UnstableApi
@Composable
private fun ExoPlayerView(url: String, headers: Map<String, String>) {
    var player: ExoPlayer? by remember { mutableStateOf(null) }
    var trackSelector: DefaultTrackSelector? by remember { mutableStateOf(null) }

    // ── Quality state ──
    var availableQualities by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf("Auto") }
    var showQualityMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val userAgent = headers["User-Agent"] ?: DEFAULT_UA
                val remainingHeaders = headers.filterKeys { it != "User-Agent" }

                val httpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(remainingHeaders)

                val dataSourceFactory: DataSource.Factory = httpFactory

                // Detect HLS more broadly — not just .m3u8 in the URL, but
                // also /hls/, /master, /playlist, manifest patterns that the
                // extractor accepts.  Getting the mime type right is what lets
                // ExoPlayer parse the manifest and report a real duration
                // (without it the player shows 00:00 and can't seek).
                val isHls = looksLikeHls(url)
                val mediaItem = if (isHls) {
                    MediaItem.Builder()
                        .setUri(Uri.parse(url))
                        .setMimeType("application/x-mpegurl")
                        .build()
                } else {
                    MediaItem.fromUri(Uri.parse(url))
                }

                val selector = DefaultTrackSelector(ctx).apply {
                    // Start in auto / adaptive mode — ExoPlayer picks the best
                    // quality based on available bandwidth. The user can
                    // override via the quality picker once tracks are loaded.
                    parameters = DefaultTrackSelector.ParametersBuilder(ctx)
                        .setForceHighestSupportedBitrate(false)
                        .build()
                }
                trackSelector = selector

                val exoPlayer = ExoPlayer.Builder(ctx)
                    .setTrackSelector(selector)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory)
                    )
                    .build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                Log.d("ExoPlayer", "State: $state")
                                if (state == Player.STATE_READY) {
                                    populateQualities(this@apply) { q -> availableQualities = q }
                                }
                            }

                            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                // Tracks can become available after STATE_READY
                                // for HLS — fire here too so the quality list
                                // populates as soon as the manifest is parsed.
                                populateQualities(this@apply) { q -> availableQualities = q }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e("ExoPlayer", "Error: ${error.errorCodeName} - ${error.message}")
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
                }
            },
            update = {},
            onRelease = { player?.release() },
            modifier = Modifier.fillMaxSize()
        )

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
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
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
 * Returns true if the URL points at an HLS stream.  ExoPlayer can auto-detect
 * in many cases, but being explicit avoids sniffing failures on CDNs that
 * serve .m3u8 from extension-less paths — which is exactly what causes the
 * 00:00 duration bug when the mime type is missing.
 */
private fun looksLikeHls(url: String): Boolean {
    val u = url.lowercase()
    if (u.contains(".m3u8")) return true
    if (u.contains("/hls/")) return true
    if (u.contains("/master")) return true
    if (u.contains("/playlist") && u.contains("m3u8")) return true
    if (u.contains("manifest") && u.contains("m3u8")) return true
    // Some providers use path-based HLS with no extension at all.
    if (u.contains("/playlist.m3u8")) return true
    return false
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

private const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
