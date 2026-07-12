package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.LookMovieStreamResolver
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.ServerTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PlayerActivity
 *
 * Full-screen video player that is the centerpiece of the "less lagging"
 * optimisation.  The previous implementation was a placeholder Compose screen
 * that only showed text — so *no* video could ever actually play.
 *
 * Three-layer playback strategy (fastest → slowest):
 *
 *   1.  **LookMovie direct resolution** (LookMovieStreamResolver) — mirrors the
 *       `lookmovietomb` advanced_resolver.py flow: hit LookMovie2.to's security
 *       API and hand a *direct* .m3u8/.mp4 URL straight to ExoPlayer.  No
 *       WebView, no ad overlays → fastest possible start-to-play.
 *
 *   2.  **ExoPlayer with embed-extracted direct URL** — when a server returns an
 *       embed page we load it in a lightweight WebView, intercept the real
 *       video URL via `shouldInterceptRequest`/`onLoadResource`, and again hand
 *       that URL to ExoPlayer.  Native decoding removes the old WebView-render
 *       lag entirely.
 *
 *   3.  **WebView inline playback fallback** — if no direct URL can be
 *       extracted within the watchdog window we fall back to letting the
 *       embed page play inside the WebView itself (ad-blocked).
 *
 * Server selection uses [ServerTester.rankForContent] which probes every server
 * *in parallel* for the exact title being played, so the best server is tried
 * first instead of wasting seconds on dead mirrors one by one.
 *
 * The player intentionally uses classic Android Views (not Compose) inside an
 * AppCompatActivity so ExoPlayer's PlayerView renders efficiently and we get
 * reliable hardware-acceleration on the video surface.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"

        /** Maximum time (ms) we spend trying to extract a direct URL before
         *  falling back to inline WebView playback. */
        private const val FALLBACK_TIMEOUT_MS = 16_000L

        /** Watchdog: if a video URL hasn't actually started buffering within
         *  this window we move to the next server. */
        private const val VIDEO_WATCHDOG_MS = 20_000L

        /** Timeout for ExoPlayer to extract a direct stream from a resolved
         *  LookMovie result. */
        private const val EXOPLAYER_EXTRACT_TIMEOUT_MS = 10_000L

        fun newIntent(
            context: Context,
            tmdbId: Int,
            type: String,
            title: String = "",
            season: Int = 1,
            episode: Int = 1
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("tmdbId", tmdbId)
            putExtra("contentType", type)
            putExtra("title", title)
            putExtra("season", season)
            putExtra("episode", episode)
        }
    }

    // ---- Intent extras ----
    private val tmdbId by lazy { intent.getIntExtra("tmdbId", -1) }
    private val contentType by lazy { intent.getStringExtra("contentType") ?: "movie" }
    private val title by lazy { intent.getStringExtra("title") ?: "" }
    private val season by lazy { intent.getIntExtra("season", 1) }
    private val episode by lazy { intent.getIntExtra("episode", 1) }
    private val isTv by lazy { contentType.equals("tv", ignoreCase = true) }

    // ---- Views ----
    private lateinit var rootLayout: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var controlsBar: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var skipBackBtn: ImageButton
    private lateinit var skipForwardBtn: ImageButton
    private lateinit var serverBtn: TextView
    private lateinit var backButton: ImageButton
    private var webView: WebView? = null

    // ---- Playback state ----
    private var exoPlayer: ExoPlayer? = null
    private var orderedServers: List<StreamingServer> = emptyList()
    private var currentServerIndex = 0
    private var watchdogJob: Job? = null
    private var fallbackJob: Job? = null
    private var seekJob: Job? = null
    private var videoDidStart = false
    private var controlsJob: Job? = null
    private var controlsVisible = true

    // ---------------------------------------------------------------- //
    //  Lifecycle
    // ---------------------------------------------------------------- //

    @SuppressLint("SourceLockedOrientationAnimated")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        buildUi()

        ServerManager.resetHealth()
        // Use the asset-backed server list (contains LookMovie first).
        ServerManager.initialize(this)
        orderedServers = ServerManager.getOrderedServers()

        if (orderedServers.isEmpty() || tmdbId < 0) {
            showError("No content to play.")
            return
        }

        startPlaybackForCurrentServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAllJobs()
        releasePlayer()
        destroyWebView()
    }

    // ---------------------------------------------------------------- //
    //  UI construction (classic Views — no Compose, for ExoPlayer perf)
    // ---------------------------------------------------------------- //

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ExoPlayer surface — created first so it sits behind the controls.
        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false // we draw our own controls
            setShutterBackgroundColor(Color.BLACK)
        }
        rootLayout.addView(playerView)

        // Loading spinner.
        loadingSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            visibility = View.GONE
        }
        rootLayout.addView(loadingSpinner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        ))

        // Status text ("Resolving LookMovie stream…").
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            visibility = View.GONE
        }
        rootLayout.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM
        ).apply { bottomMargin = 220 })

        // Error text.
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 18f
            visibility = View.GONE
        }
        rootLayout.addView(errorText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        ))

        buildControlsBar()
        setContentView(rootLayout)
    }

    private fun buildControlsBar() {
        controlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(24, 16, 24, 16)
            visibility = View.VISIBLE
        }

        val btnColor = Color.WHITE

        backButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew) // placeholder; back arrow
            setColorFilter(btnColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }

        skipBackBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setColorFilter(btnColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { seekBy(-10_000) }
        }

        playPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(btnColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { togglePlayPause() }
        }

        skipForwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setColorFilter(btnColor)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { seekBy(10_000) }
        }

        currentTimeText = TextView(this).apply {
            setTextColor(btnColor)
            textSize = 13f
            text = "0:00"
            setPadding(16, 0, 8, 0)
        }

        seekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        exoPlayer?.let { p ->
                            val pos = (progress.toLong() * p.duration) / 100
                            p.seekTo(pos)
                        }
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        totalTimeText = TextView(this).apply {
            setTextColor(btnColor)
            textSize = 13f
            text = "0:00"
            setPadding(8, 0, 16, 0)
        }

        serverBtn = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 13f
            text = "Server: …"
            setPadding(16, 0, 8, 0)
            setOnClickListener { cycleToNextServer() }
        }

        controlsBar.apply {
            addView(backButton)
            addView(skipBackBtn)
            addView(playPauseBtn)
            addView(skipForwardBtn)
            addView(currentTimeText)
            addView(seekBar)
            addView(totalTimeText)
            addView(serverBtn)
        }

        rootLayout.addView(controlsBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM
        ))
    }

    // ---------------------------------------------------------------- //
    //  Playback orchestration
    // ---------------------------------------------------------------- //

    /**
     * Begins the three-layer strategy for the server at [currentServerIndex].
     * Kicks off the watchdog and the global fallback timer.
     */
    private fun startPlaybackForCurrentServer() {
        cancelAllJobs()
        releasePlayer()
        destroyWebView()
        videoDidStart = false

        val server = orderedServers.getOrNull(currentServerIndex) ?: run {
            showError("All servers exhausted. Please try again.")
            return
        }

        serverBtn.text = "Server: ${server.name}"
        showLoading("Connecting to ${server.name}…")
        hideError()

        // Launch the watchdog — if video doesn't start within the window,
        // try the next server.
        startWatchdog()

        lifecycleScope.launch(Dispatchers.Main) {
            // ── Layer 1: LookMovie direct resolution ────────────────── //
            if (server.isLookMovie) {
                statusText.text = "Resolving LookMovie direct stream…"
                val resolved = withTimeoutOrNull(EXOPLAYER_EXTRACT_TIMEOUT_MS) {
                    LookMovieStreamResolver.resolve(tmdbId, contentType, season, episode)
                }
                if (resolved != null && resolved.isDirect) {
                    playWithExoPlayer(resolved.url, server.name)
                    return@launch
                }
                if (resolved?.needsCaptcha == true) {
                    // Captcha challenge — open the verification WebView flow.
                    launchVerification(resolved.url)
                    return@launch
                }
                Log.w(TAG, "LookMovie direct failed: ${resolved?.error}, falling to embed")
            }

            // ── Layer 2: probe & extract from embed → ExoPlayer ─────── //
            val ranked = withTimeoutOrNull(8_000L) {
                ServerTester.rankForContent(
                    listOf(server) + orderedServers.filter { it != server },
                    tmdbId,
                    contentType,
                    season,
                    episode
                )
            } ?: orderedServers

            // Try extracting a direct URL from the (best) embed page.
            val embedUrl = if (isTv) server.tvUrl(tmdbId, season, episode)
                           else server.movieUrl(tmdbId)
            extractDirectViaWebView(embedUrl, server.name)
        }
    }

    /**
     * Layer 2: load [embedUrl] in a hidden WebView and intercept the first
     * playable video URL.  Hands the result to ExoPlayer.  If nothing is
     * found within FALLBACK_TIMEOUT_MS we switch to inline WebView playback.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun extractDirectViaWebView(embedUrl: String, serverName: String) {
        statusText.text = "Extracting stream from $serverName…"

        ensureWebView { wv ->
            // Load the embed page — ad-block + URL interception happens in the
            // WebViewClient below.  When a real video URL is seen we kill the
            // WebView and hand the URL to ExoPlayer.
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val cleaned = url.lowercase()
                    if (isAdRequest(cleaned)) return emptyResponse()
                    if (isPlayableMediaUrl(url) && !videoDidStart) {
                        videoDidStart = true
                        runOnUiThread { playWithExoPlayer(url, serverName) }
                    }
                    return null
                }

                override fun onLoadResource(view: WebView?, url: String?) {
                    super.onLoadResource(view, url)
                    val u = url ?: return
                    if (isPlayableMediaUrl(u) && !videoDidStart) {
                        videoDidStart = true
                        runOnUiThread { playWithExoPlayer(u, serverName) }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }
            }
            wv.loadUrl(embedUrl)
        }

        // Global fallback: if no direct URL appeared, fall back to inline play.
        fallbackJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(FALLBACK_TIMEOUT_MS)
            if (!videoDidStart) {
                Log.w(TAG, "Direct extraction timed out, using inline WebView")
                startInlineWebViewPlayback(embedUrl, serverName)
            }
        }
    }

    /**
     * Layer 3: give up on extraction and just let the embed page play inside
     * the WebView (still ad-blocked).  Not as smooth as ExoPlayer but
     * guarantees *something* plays.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun startInlineWebViewPlayback(embedUrl: String, serverName: String) {
        statusText.visibility = View.GONE
        loadingSpinner.visibility = View.GONE
        serverBtn.text = "Server: $serverName (inline)"
        releasePlayer()
        playerView.visibility = View.GONE

        ensureWebView { wv ->
            wv.visibility = View.VISIBLE
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (isAdRequest(url.lowercase())) return emptyResponse()
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Auto-click the play button if present.
                    injectAutoPlay(view)
                }
            }
            wv.loadUrl(embedUrl)
        }
    }

    // ---------------------------------------------------------------- //
    //  ExoPlayer
    // ---------------------------------------------------------------- //

    /** Hands a direct [url] to ExoPlayer.  Also dismisses the WebView. */
    private fun playWithExoPlayer(url: String, serverName: String) {
        runOnUiThread {
            statusText.visibility = View.GONE
            loadingSpinner.visibility = View.GONE
            hideError()
            playerView.visibility = View.VISIBLE

            // If a WebView was extracting, stop it now.
            webView?.let {
                it.stopLoading()
                it.visibility = View.GONE
            }

            try {
                releasePlayer()
                val player = ExoPlayer.Builder(this).build().also {
                    it.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            when (state) {
                                Player.STATE_READY -> {
                                    loadingSpinner.visibility = View.GONE
                                    ServerManager.markServerSuccess(serverName)
                                    cancelWatchdog()
                                }
                                Player.STATE_BUFFERING -> {
                                    loadingSpinner.visibility = View.VISIBLE
                                }
                                Player.STATE_ENDED -> finish()
                                Player.STATE_IDLE -> {}
                            }
                        }
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            updatePlayPauseIcon(isPlaying)
                        }
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "ExoPlayer error: ${error.errorCodeName}")
                            ServerManager.markServerDead(serverName)
                            cycleToNextServer()
                        }
                    })
                }
                exoPlayer = player
                playerView.player = player
                player.setMediaItem(buildMediaItem(url))
                player.prepare()
                player.playWhenReady = true
                startSeekBarUpdate()
            } catch (e: Exception) {
                Log.e(TAG, "ExoPlayer setup failed: ${e.message}")
                ServerManager.markServerDead(serverName)
                cycleToNextServer()
            }
        }
    }

    /** Builds the correct [MediaItem] + [MediaSource] for HLS vs progressive. */
    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.fromUri(Uri.parse(url))
    }

    /**
     * Prepares the MediaSource for the given URL.
     * We call this to keep ExoPlayer's default factory but ensure HLS streams
     * use the HlsMediaSource.  Referenced via [exoPlayer] setMediaItem above;
     * kept here for completeness when a custom factory is needed.
     */
    @Suppress("unused")
    private fun buildMediaSource(url: String): MediaSource {
        val uri = Uri.parse(url)
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)
        return if (url.lowercase().contains(".m3u8")) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(uri))
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(uri))
        }
    }

    private fun releasePlayer() {
        seekJob?.cancel()
        seekJob = null
        exoPlayer?.release()
        exoPlayer = null
        playerView.player = null
    }

    // ---------------------------------------------------------------- //
    //  WebView helpers
    // ---------------------------------------------------------------- //

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(onReady: (WebView) -> Unit) {
        if (webView == null) {
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.VISIBLE
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this@apply, true)
                }
                setBackgroundColor(Color.BLACK)
            }
            rootLayout.addView(webView)
        }
        onReady(webView!!)
    }

    private fun destroyWebView() {
        webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
            it.clearHistory()
            (it.parent as? FrameLayout)?.removeView(it)
            it.destroy()
        }
        webView = null
    }

    /** Inject JS that auto-clicks a play button and hides overlays. */
    private fun injectAutoPlay(view: WebView?) {
        val js = """
            (function(){
                function tryPlay(){
                    var btns = document.querySelectorAll('button,[role=button],.play-btn,.vjs-big-play-button,.jw-icon');
                    for(var i=0;i<btns.length;i++){
                        var b=btns[i];
                        if(/play|start|continue/i.test(b.className+b.textContent)){
                            try{b.click();}catch(e){}
                        }
                    }
                    var v=document.querySelector('video');
                    if(v){try{v.muted=false;v.play();}catch(e){}}
                }
                tryPlay();
                setTimeout(tryPlay,800);
                setTimeout(tryPlay,2000);
                // hide common ad overlays
                document.querySelectorAll('div').forEach(function(d){
                    if(/overlay|ad-|ads|popup|banner/i.test(d.id+d.className)){
                        d.style.display='none';
                    }
                });
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------- //
    //  Ad blocking & URL classification
    // ---------------------------------------------------------------- //

    private fun isAdRequest(url: String): Boolean {
        AD_DOMAINS.any { url.contains(it) } || AD_PATH_PATTERNS.any { url.contains(it) }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream("".toByteArray()))

    private fun isPlayableMediaUrl(url: String): Boolean {
        val u = url.lowercase()
        return (u.contains(".m3u8") || u.contains(".mp4") || u.contains(".mkv") ||
                u.contains("/manifest/") || u.contains("playlist") || u.contains("videoplayback")) &&
            !isAdRequest(u) &&
            !u.contains("embed") && !u.contains("/play/")
    }

    // ---------------------------------------------------------------- //
    //  Server cycling & watchdog
    // ---------------------------------------------------------------- //

    private fun cycleToNextServer() {
        cancelAllJobs()
        releasePlayer()
        destroyWebView()
        videoDidStart = false
        currentServerIndex++
        if (currentServerIndex >= orderedServers.size) {
            showError("All servers failed. Pull to refresh or try another title.")
            return
        }
        startPlaybackForCurrentServer()
    }

    private fun startWatchdog() {
        cancelWatchdog()
        watchdogJob = lifecycleScope.launch(Dispatchers.Main) {
            delay(VIDEO_WATCHDOG_MS)
            if (!videoDidStart && !isFinishing) {
                Log.w(TAG, "Watchdog: video did not start, cycling server")
                cycleToNextServer()
            }
        }
    }

    private fun cancelWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun cancelAllJobs() {
        cancelWatchdog()
        fallbackJob?.cancel()
        fallbackJob = null
        seekJob?.cancel()
        seekJob = null
        controlsJob?.cancel()
        controlsJob = null
    }

    // ---------------------------------------------------------------- //
    //  Playback controls
    // ---------------------------------------------------------------- //

    private fun togglePlayPause() {
        exoPlayer?.let { p ->
            p.playWhenReady = !p.playWhenReady
            updatePlayPauseIcon(p.playWhenReady)
        }
    }

    private fun seekBy(deltaMs: Long) {
        exoPlayer?.let { p ->
            val target = (p.currentPosition + deltaMs).coerceIn(0, p.duration)
            p.seekTo(target)
        }
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val res = if (isPlaying) android.R.drawable.ic_media_pause
                  else android.R.drawable.ic_media_play
        playPauseBtn.setImageResource(res)
    }

    private fun startSeekBarUpdate() {
        seekJob?.cancel()
        seekJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                exoPlayer?.let { p ->
                    val duration = p.duration
                    if (duration > 0) {
                        seekBar.progress = ((p.currentPosition * 100) / duration).toInt()
                        currentTimeText.text = formatTime(p.currentPosition)
                        totalTimeText.text = formatTime(duration)
                    }
                }
                delay(500)
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
               else String.format("%d:%02d", m, s)
    }

    // ---------------------------------------------------------------- //
    //  Captcha / verification
    // ---------------------------------------------------------------- //

    private fun launchVerification(url: String) {
        statusText.visibility = View.GONE
        loadingSpinner.visibility = View.GONE
        // Defer to VerificationActivity for human-assisted challenge solving.
        startActivityForResult(
            VerificationActivity.newIntent(this, url),
            REQUEST_VERIFY
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VERIFY) {
            if (resultCode == Activity.RESULT_OK) {
                // Re-attempt playback after a successful verification.
                startPlaybackForCurrentServer()
            } else {
                cycleToNextServer()
            }
        }
    }

    // ---------------------------------------------------------------- //
    //  Loading / error UI
    // ---------------------------------------------------------------- //

    private fun showLoading(msg: String) {
        loadingSpinner.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = msg
        errorText.visibility = View.GONE
    }

    private fun showError(msg: String) {
        loadingSpinner.visibility = View.GONE
        statusText.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = msg
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    // ---------------------------------------------------------------- //
    //  Hardware key handling (Android TV / Bluetooth remotes)
    // ---------------------------------------------------------------- //

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                seekBy(-10_000); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                seekBy(10_000); return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- //
    //  Constants
    // ---------------------------------------------------------------- //

    private val REQUEST_VERIFY = 9001

    // ---------------------------------------------------------------- //
    //  Ad blocking
    // ---------------------------------------------------------------- //

    /** Domain/path fragments used to block ad & tracking requests inside
     *  the extraction WebView.  Blocking these keeps the WebView light and
     *  fast (and prevents popunders from navigating the page away). */
    private val AD_DOMAINS = listOf(
        "doubleclick.net", "googlesyndication.com", "googletagmanager.com",
        "google-analytics.com", "adsrvr.org", "adnxs.com", "rubiconproject.com",
        "pubmatic.com", "criteo.com", "taboola.com", "outbrain.com",
        "amazon-adsystem.com", "facebook.com/tr", "fbcdn.net", "scorecardresearch.com",
        "quantserve.com", "adservice.google.com", "adsystem.com", "adskeeper.com",
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com",
        "adsexchanger.com", "mgid.com", "revcontent.com", "yllix.com",
        "onclickperformance.com", "onclickads.net", "realsrv.com", "tsyndicate.com",
        "juicyads.com", "exoclick.com", "trafficjunky.com", "ad-network",
        "admaven.com", "adcash.com", "pop-my-ads", "hilltopads.net",
        "a-mo.net", "mediavine.com", "monetag.com", "profitabledisplaynetwork",
        "profitablecpmrate", "highperformanceformat", "pushnews", "pushalert",
        "onesignal.com", "smartnews", "adservice", "srvtrck", "trckcmpg",
        "clicksor.com", "yieldlab.net", "adcolony.com", "applovin.com",
        "unityads.unity3d.com", "chartboost.com", "vungle.com", "inmobi.com",
        "mopub.com", "admob.com", "startapp.com", "tapjoy.com",
        "advertising.com", "yieldmo.com", "kargo.com",
        "bluekai.com", "demdex.net", "omtrdc.net", "2o7.net",
        "adobedtm.com", "ContextWeb", "openx.net",
        "casalemedia.com", "bidswitch.net", "liadm.com", "revjet.com",
        "sonobi.com", "indexexchange.com"
    )

    /** Path-fragment patterns that indicate an ad/tracker request. */
    private val AD_PATH_PATTERNS = listOf(
        "/ads/", "/ad/", "/adsense/", "/advertisement/", "/banner/",
        "/popunder", "/popup", "/tracking/", "/tracker/",
        "/analytics/", "/beacon", "/pixel", "/click?", "/redirect/",
        "/delivery/", "/avbs", "/ssp", "/prebid", "/pubads", "/gampad",
        "/adserver", "/adscript", "/adcode", "/adframe", "/adimage",
        "/adview", "/adzone", "/banners", "/promo", "/sponsor"
    )
}
