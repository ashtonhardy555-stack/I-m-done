package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.ServerTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID    = "tmdb_id"
        private const val EXTRA_TYPE       = "type"
        private const val EXTRA_TITLE      = "title"
        private const val EXTRA_SEASON     = "season"
        private const val EXTRA_EPISODE    = "episode"

        // How long to wait on a page before giving up and switching server
        private const val FALLBACK_TIMEOUT_MS           = 8_000L
        // Interval between auto-play injection retries
        private const val AUTO_PLAY_RETRY_MS            = 1_800L
        // Max number of auto-play retries per page load
        private const val AUTO_PLAY_MAX_RETRIES         = 12
        // How long to wait for ExoPlayer to intercept a video URL
        private const val EXOPLAYER_EXTRACT_TIMEOUT_MS  = 10_000L
        // After this many block-caused reloads on the SAME server, give up and switch
        private const val MAX_BLOCK_RELOADS_PER_SERVER  = 5
        // If video hasn't started within this window, switch server
        private const val VIDEO_WATCHDOG_MS             = 12_000L

        fun newIntent(
            context: Context,
            tmdbId: Int,
            type: String,
            title: String,
            season: Int = 1,
            episode: Int = 1
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_TMDB_ID, tmdbId)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_SEASON, season)
            putExtra(EXTRA_EPISODE, episode)
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var servers: List<StreamingServer> = emptyList()
    private var currentServerIndex = 0
    private var tmdbId = 0
    private var contentType = "movie"
    private var season = 1
    private var episode = 1
    private var currentEmbedDomain = ""
    private var currentEmbedUrl = ""

    // Block / reload tracking
    private var blockedBeforeReload      = false   // reload was caused by a block
    private var sameServerBlockReloads   = 0       // how many block-reloads on this server
    private var pageLoadFailed           = false

    // Player state
    private var autoPlayRetries = 0
    private var isPlaying       = false
    private var isFullscreen    = false
    private var isSeeking       = false
    private var usingExoPlayer  = false
    private var extractedVideoUrl: String? = null

    // User-control flags — prevent auto-timers fighting manual actions
    private var userInitiatedPause  = false
    private var playRetryRunnable: Runnable? = null
    private var playRetryCount      = 0
    private val PLAY_RETRY_MAX      = 3
    private val PLAY_RETRY_DELAY_MS = 2_000L

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var webView:             WebView
    private lateinit var exoPlayerView:       PlayerView
    private lateinit var statusText:          TextView
    private lateinit var titleText:           TextView
    private lateinit var serverSpinner:       Spinner
    private lateinit var loadingBar:          ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var controlBar:          LinearLayout
    private lateinit var playPauseBtn:        ImageButton
    private lateinit var skipBackBtn:         ImageButton
    private lateinit var skipForwardBtn:      ImageButton
    private lateinit var fullscreenBtn:       ImageButton
    private lateinit var topBar:              LinearLayout
    private lateinit var videoSeekBar:        SeekBar
    private lateinit var timeText:            TextView
    private lateinit var seekRow:             LinearLayout
    private lateinit var controlsContainer:   LinearLayout
    private lateinit var playerFrame:         FrameLayout
    // Full-screen spinner shown while searching for a working stream
    private lateinit var loadingOverlay:      FrameLayout
    private lateinit var loadingStatusText:   TextView
    private var serversTried  = 0
    // Playback position saved before a server switch so we can resume from it
    private var savedPositionMs = 0L
    private var adSkipRunnable: Runnable? = null

    // ── Runnables ─────────────────────────────────────────────────────────────
    private var exoPlayer:               ExoPlayer? = null
    private var progressRunnable:        Runnable?  = null
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackRunnable:        Runnable?  = null
    private var autoPlayRunnable:        Runnable?  = null
    private var extractTimeoutRunnable:  Runnable?  = null
    private var videoWatchdogRunnable:   Runnable?  = null
    private var customView:              View?      = null
    private var customViewCallback:      WebChromeClient.CustomViewCallback? = null

    // ── URL helpers ───────────────────────────────────────────────────────────
    private val videoExtensions = listOf(".m3u8", ".mp4", ".webm", ".mkv", ".avi", ".flv", ".ts")

    private val adDomains = listOf(
        "doubleclick","googlesyndication","adservice","adnxs","outbrain","taboola",
        "revcontent","mgid","propellerads","popcash","popads","trafficjunky","exoclick",
        "juicyads","adsterra","hilltopads","eroadvertising","traffichunt","clickadu",
        "richpush","pushground","mondiad","bidvertiser","advertserve","yieldmo","undertone",
        "adblade","media.net","zedo","valueclick","tradedoubler","popunder","onclickads",
        "betterads","ad-maven","admaven","adcash","adfly","shorte.st","linkvertise",
        "ouo.io","ouo.press","bc.vc","adf.ly","linkbucks","adfoc.us","coinurl",
        "clk.sh","shrink.pe","earnow","adlinkfly","linkshrink","grabify","iplogger",
        "blasze","ps.ht","2no.co","pornhub","xvideos","xhamster","redtube","youporn",
        "chaturbate","livejasmin","cam4","myfreecams","bongacams","1xbet","betway",
        "bet365","stake.com","roobet","pagead2","syndication","ampproject","adcolony",
        "applovin","moatads","criteo","pubmatic","smartadserver","teads","amazon-adsystem",
        "advertising.com","rubiconproject","openx","appnexus","indexexchange","casalemedia",
        "mediavine","carbonads","ethicalads","buysellads"
    )
    private val adPathPatterns = listOf(
        "/ad/","/ads/","/advert","/banner","/popup","/popunder",
        "/track","/click","/redirect","/redir","/pagead","/sponsor","/promo",
        "doubleclick","googlesyndication"
    )

    private fun isAdUrl(url: String): Boolean {
        return try {
            val lower = url.lowercase()
            val host  = Uri.parse(url).host?.lowercase() ?: return false
            if (adDomains.any { host.contains(it) }) return true
            val path = Uri.parse(url).path?.lowercase() ?: ""
            adPathPatterns.any { path.contains(it) || lower.contains(it) }
        } catch (_: Exception) { false }
    }

    private fun isVideoUrl(url: String) =
        videoExtensions.any { url.lowercase().contains(it) } && !isAdUrl(url)

    // ── JS → Kotlin bridge ────────────────────────────────────────────────────
    inner class PopupBridge {
        /** Called by the MutationObserver when it removes a popup/overlay. */
        @JavascriptInterface
        fun onPopupBlocked() = handler.post {
            setStatus("Popup blocked → pressing play…")
            injectAutoPlay(webView)
            injectAdBlocker(webView)
            handler.postDelayed({ injectAutoPlay(webView); injectAdBlocker(webView) }, 1_200)
        }

        /** Called when the video's `play` / `playing` event fires inside the page. */
        @JavascriptInterface
        fun onVideoStarted() = handler.post {
            if (!isPlaying && !usingExoPlayer) {
                isPlaying = true
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                setStatus("")
                cancelFallbackTimer()
                cancelVideoWatchdog()
                // Video confirmed playing — remember this server, hide spinner
                ServerManager.markServerSuccess(servers.getOrNull(currentServerIndex)?.name ?: "")
                hideLoadingOverlay()
                resumeFromSavedPosition()
                startAdSkipTimer()
            }
        }

        /** Called when JS intercepts a location.href / assign / replace redirect. */
        @JavascriptInterface
        fun onRedirectBlocked() = handler.post { handleBlockEvent() }
    }

    // ── onCreate ──────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        tmdbId      = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        season      = intent.getIntExtra(EXTRA_SEASON, 1)
        episode     = intent.getIntExtra(EXTRA_EPISODE, 1)
        val title   = intent.getStringExtra(EXTRA_TITLE) ?: ""

        servers = ServerManager.getOrderedServers()

        buildLayout(title)

        // Step 1 — immediately show UI with the first known-good server
        loadServer(0)

        // Server selection: we rely on the sequential WebView load — the only
        // reliable way to know if a server plays video is to actually load it.
    }


    // ── Layout builder ────────────────────────────────────────────────────────
    private fun buildLayout(title: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Top bar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(24, 12, 24, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        titleText = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleText)

        serverSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setupServerSpinner()
        topBar.addView(serverSpinner)
        topBar.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(16, 8, 8, 8)
            setOnClickListener { finish() }
        })
        root.addView(topBar)

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 6, 24, 6)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            visibility = View.GONE
        }
        root.addView(statusText)

        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6)
        }
        root.addView(loadingBar)

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(fullscreenContainer)

        playerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
        }

        exoPlayerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
            visibility = View.GONE
        }
        playerFrame.addView(exoPlayerView)

        webView = buildWebView()
        playerFrame.addView(webView)

        controlsContainer = buildControlsOverlay()
        controlsContainer.visibility = View.GONE   // hidden until video starts
        playerFrame.addView(controlsContainer)

        // Full-screen loading overlay (on top of everything in playerFrame)
        loadingOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        val spinnerBig = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(180, 180, android.view.Gravity.CENTER).also {
                it.topMargin = -80
            }
        }
        loadingStatusText = TextView(this).apply {
            text = "Finding a stream\u2026"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding(32, 0, 32, 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            ).also { it.topMargin = 120 }
        }
        loadingOverlay.addView(spinnerBig)
        loadingOverlay.addView(loadingStatusText)
        playerFrame.addView(loadingOverlay)

        root.addView(playerFrame)
        setContentView(root)
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
        }
        addJavascriptInterface(PopupBridge(), "PopupBridge")

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (isAdUrl(url)) { handleSubFrameBlock(); return true }

                if (request.isForMainFrame && currentEmbedDomain.isNotEmpty()) {
                    val newHost = request.url.host?.lowercase() ?: ""
                    if (newHost.isNotEmpty()
                        && newHost != currentEmbedDomain
                        && !newHost.endsWith(".$currentEmbedDomain")
                    ) {
                        setStatus("Redirect blocked → reloading…")
                        handleBlockEvent()
                        return true
                    }
                }
                if (!request.isForMainFrame && isAdUrl(url)) return true
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isAdUrl(url)) return WebResourceResponse("text/plain", "UTF-8", null)
                if (!usingExoPlayer && extractedVideoUrl == null && isVideoUrl(url)) {
                    extractedVideoUrl = url
                    handler.post { switchToExoPlayer(url) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingBar.visibility = View.VISIBLE
                pageLoadFailed = false
                cancelAutoPlayTimer()
                cancelVideoWatchdog()
                startFallbackTimer()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loadingBar.visibility = View.GONE
                cancelFallbackTimer()
                if (!pageLoadFailed) setStatus("")

                injectRedirectGuard(view)
                injectAdBlocker(view)

                if (blockedBeforeReload) {
                    // This load was triggered by a block — press play immediately
                    blockedBeforeReload = false
                    setStatus("Pressing play after block…")
                    injectAutoPlay(view)
                    autoPlayRetries = 0
                    startAutoPlayTimer(immediate = false)
                } else {
                    autoPlayRetries = 0
                    startAutoPlayTimer(immediate = true)
                }

                if (!usingExoPlayer && extractedVideoUrl == null) {
                    startExtractTimeout()
                    startVideoWatchdog()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageLoadFailed = true
                    setStatus("⚠️ Server error, trying next…")
                    ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                    tryNextServer()
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    // 404 = content not on this server, 4xx = access denied, 5xx = server down
                    if (code == 404 || code >= 500) {
                        pageLoadFailed = true
                        setStatus("⚠️ HTTP $code — trying next server…")
                        ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                        tryNextServer()
                    }
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                customView = view; customViewCallback = callback
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                this@PlayerActivity.webView.visibility = View.GONE
            }
            override fun onHideCustomView() {
                fullscreenContainer.removeAllViews()
                fullscreenContainer.visibility = View.GONE
                this@PlayerActivity.webView.visibility = View.VISIBLE
                customViewCallback?.onCustomViewHidden()
                customView = null; customViewCallback = null
            }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
        }
    }

    // ── Block event handling ──────────────────────────────────────────────────
    /**
     * Main-frame block (redirect / popup that takes over the page).
     * Reloads the same server and presses play, OR switches after too many attempts.
     */
    private fun handleBlockEvent() {
        sameServerBlockReloads++
        if (sameServerBlockReloads > MAX_BLOCK_RELOADS_PER_SERVER) {
            setStatus("⚠️ Server keeps redirecting — trying next…")
            sameServerBlockReloads = 0
            tryNextServer()
        } else {
            blockedBeforeReload = true
            handler.postDelayed({ reloadCurrentServer() }, 300)
        }
    }

    /** Sub-frame block (ad iframe etc.) — just re-inject ad blocker, no reload needed. */
    private fun handleSubFrameBlock() = handler.post { injectAdBlocker(webView) }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────
    private fun switchToExoPlayer(videoUrl: String) {
        if (usingExoPlayer) return
        usingExoPlayer = true
        cancelExtractTimeout(); cancelAutoPlayTimer(); cancelVideoWatchdog()
        setStatus("")

        webView.visibility = View.GONE
        exoPlayerView.visibility = View.VISIBLE
        // ExoPlayer is handling playback — remember this server, hide spinner
        ServerManager.markServerSuccess(servers.getOrNull(currentServerIndex)?.name ?: "")
        hideLoadingOverlay()

        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        exoPlayerView.player = player

        val dsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val mi = MediaItem.fromUri(videoUrl)
        if (videoUrl.lowercase().contains(".m3u8")) {
            player.setMediaSource(HlsMediaSource.Factory(dsf).createMediaSource(mi))
        } else {
            player.setMediaSource(
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dsf).createMediaSource(mi)
            )
        }
        player.prepare()
        player.playWhenReady = true
        isPlaying = true
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY    -> {
                        setStatus("")
                        startProgressUpdater()
                        resumeFromSavedPosition()
                        startAdSkipTimer()
                    }
                    Player.STATE_ENDED    -> { isPlaying = false; playPauseBtn.setImageResource(android.R.drawable.ic_media_play) }
                    Player.STATE_BUFFERING -> setStatus("⏳ Buffering…")
                    else -> {}
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                setStatus("⚠️ Native player failed, using embed…")
                switchToWebViewFallback()
            }
        })
        startProgressUpdater()
    }

    private fun switchToWebViewFallback() {
        usingExoPlayer = false; extractedVideoUrl = null
        releaseExoPlayer()
        exoPlayerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        injectHideEmbedUI(webView)
        autoPlayRetries = 0
        startAutoPlayTimer(immediate = true)
        startProgressUpdater()
    }

    private fun releaseExoPlayer() { exoPlayer?.release(); exoPlayer = null }

    // ── Timeouts / watchdog ───────────────────────────────────────────────────
    private fun startExtractTimeout() {
        cancelExtractTimeout()
        extractTimeoutRunnable = Runnable {
            if (!usingExoPlayer && extractedVideoUrl == null) {
                setStatus("Using embed player…")
                switchToWebViewFallback()
            }
        }
        handler.postDelayed(extractTimeoutRunnable!!, EXOPLAYER_EXTRACT_TIMEOUT_MS)
    }
    private fun cancelExtractTimeout() { extractTimeoutRunnable?.let { handler.removeCallbacks(it) }; extractTimeoutRunnable = null }

    /**
     * If no video has started playing after VIDEO_WATCHDOG_MS, switch to the next server.
     * Cancelled as soon as ExoPlayer or JS confirms the video is actually playing.
     */
    private fun startVideoWatchdog() {
        cancelVideoWatchdog()
        videoWatchdogRunnable = Runnable {
            if (!isPlaying && !usingExoPlayer) {
                setStatus("⚠️ No video — trying next server…")
                tryNextServer()
            }
        }
        if (!userInitiatedPause) handler.postDelayed(videoWatchdogRunnable!!, VIDEO_WATCHDOG_MS)
    }
    private fun cancelVideoWatchdog() { videoWatchdogRunnable?.let { handler.removeCallbacks(it) }; videoWatchdogRunnable = null }

    // ── Controls overlay ──────────────────────────────────────────────────────
    private fun buildControlsOverlay(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC141414"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        }
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        videoSeekBar = SeekBar(this).apply {
            max = 1000; progress = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) seekVideo(p) }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isSeeking = true
                    cancelAutoPlayTimer()
                    cancelPlayRetryTimer()
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isSeeking = false
                    if (isPlaying && !userInitiatedPause) {
                        handler.postDelayed({
                            if (!userInitiatedPause && !isSeeking) {
                                simulateTapAtCenter()
                                injectAutoPlay(webView)
                            }
                        }, 400L)
                    }
                }
            })
        }
        seekRow.addView(videoSeekBar)
        timeText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA")); textSize = 11f
            text = "0:00 / 0:00"; setPadding(12, 0, 0, 0)
        }
        seekRow.addView(timeText)
        container.addView(seekRow)

        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 12)
            gravity = android.view.Gravity.CENTER
        }
        skipBackBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setPadding(20, 12, 20, 12)
            setOnClickListener { skipVideo(-10) }
        }
        controlBar.addView(skipBackBtn)
        playPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setPadding(28, 12, 28, 12)
            setOnClickListener { togglePlayPause() }
        }
        controlBar.addView(playPauseBtn)
        skipForwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setPadding(20, 12, 20, 12)
            setOnClickListener { skipVideo(10) }
        }
        controlBar.addView(skipForwardBtn)
        controlBar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        fullscreenBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setPadding(20, 12, 20, 12)
            setOnClickListener { toggleFullscreen() }
        }
        controlBar.addView(fullscreenBtn)
        container.addView(controlBar)
        return container
    }

    private fun togglePlayPause() {
        if (usingExoPlayer) {
            val p = exoPlayer ?: return
            if (p.isPlaying) {
                userInitiatedPause = true
                p.pause(); isPlaying = false
                playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
            } else {
                userInitiatedPause = false
                p.play(); isPlaying = true
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
            }
        } else {
            if (isPlaying) {
                // User explicitly pausing — kill all background retry timers
                userInitiatedPause = true
                cancelAutoPlayTimer()
                cancelPlayRetryTimer()
                runWebViewVideoCommand("v.pause();")
                isPlaying = false
                playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
            } else {
                // User pressing play — tap + inject, then quietly retry if needed
                userInitiatedPause = false
                cancelAutoPlayTimer()
                cancelPlayRetryTimer()
                simulateTapAtCenter()
                injectAutoPlay(webView)
                isPlaying = true
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                schedulePlayRetry()
            }
        }
    }

    private fun skipVideo(s: Int) {
        if (usingExoPlayer) {
            exoPlayer?.seekTo((exoPlayer!!.currentPosition + s * 1000L))
        } else {
            cancelAutoPlayTimer()
            cancelPlayRetryTimer()
            runWebViewVideoCommand("v.currentTime += $s;")
            if (isPlaying && !userInitiatedPause) {
                handler.postDelayed({
                    if (!userInitiatedPause && !isSeeking) {
                        simulateTapAtCenter()
                        injectAutoPlay(webView)
                    }
                }, 600L)
            }
        }
    }

    private fun seekVideo(pv: Int) {
        if (usingExoPlayer) { val p = exoPlayer ?: return; if (p.duration > 0) p.seekTo(p.duration * pv / 1000L) }
        else runWebViewVideoCommand("if(v.duration) v.currentTime = v.duration * ($pv / 1000.0);")
    }

    private fun toggleFullscreen() { if (isFullscreen) exitCustomFullscreen() else enterCustomFullscreen() }
    private fun enterCustomFullscreen() {
        isFullscreen = true; topBar.visibility = View.GONE; statusText.visibility = View.GONE
        controlsContainer.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    private fun exitCustomFullscreen() {
        isFullscreen = false; topBar.visibility = View.VISIBLE; controlsContainer.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    // ── Progress ──────────────────────────────────────────────────────────────
    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressRunnable = object : Runnable {
            override fun run() {
                if (isSeeking) { handler.postDelayed(this, 1000); return }
                if (usingExoPlayer) {
                    val p = exoPlayer
                    if (p != null && p.duration > 0) {
                        videoSeekBar.progress = ((p.currentPosition.toFloat() / p.duration) * 1000).toInt()
                        timeText.text = "${formatTime((p.currentPosition / 1000).toInt())} / ${formatTime((p.duration / 1000).toInt())}"
                        if (p.isPlaying && !isPlaying && !userInitiatedPause) { isPlaying = true; playPauseBtn.setImageResource(android.R.drawable.ic_media_pause); cancelVideoWatchdog() }
                        else if (!p.isPlaying && isPlaying && !userInitiatedPause && p.playbackState != Player.STATE_BUFFERING) { isPlaying = false; playPauseBtn.setImageResource(android.R.drawable.ic_media_play) }
                    }
                } else {
                    webView.evaluateJavascript("""
                        (function(){var v=document.querySelector('video');
                        if(!v){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{v=fs[i].contentDocument.querySelector('video');if(v)break;}catch(e){}}}
                        if(v&&v.duration)return JSON.stringify({c:v.currentTime,d:v.duration,p:v.paused});return 'null';})();
                    """.trimIndent()) { r ->
                        try {
                            val s = r.trim('"').replace("\\\"", "\"")
                            if (s != "null") {
                                val j = org.json.JSONObject(s)
                                val c = j.getDouble("c"); val d = j.getDouble("d"); val paused = j.getBoolean("p")
                                handler.post {
                                    if (d > 0) videoSeekBar.progress = ((c / d) * 1000).toInt()
                                    timeText.text = "${formatTime(c.toInt())} / ${formatTime(d.toInt())}"
                                    if (!paused && !isPlaying && !userInitiatedPause) { isPlaying = true; playPauseBtn.setImageResource(android.R.drawable.ic_media_pause); setStatus(""); cancelVideoWatchdog() }
                                    else if (paused && isPlaying && !isSeeking && !userInitiatedPause) { isPlaying = false; playPauseBtn.setImageResource(android.R.drawable.ic_media_play) }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(progressRunnable!!, 1000)
    }
    private fun stopProgressUpdater() { progressRunnable?.let { handler.removeCallbacks(it) }; progressRunnable = null }
    private fun formatTime(s: Int) = if (s / 3600 > 0) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)

    // ── JS helpers ────────────────────────────────────────────────────────────
    private fun runWebViewVideoCommand(js: String) {
        webView.evaluateJavascript("""(function(){var v=document.querySelector('video');if(!v){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{v=fs[i].contentDocument.querySelector('video');if(v)break;}catch(e){}}}if(v){$js}})();""", null)
    }

    private fun injectHideEmbedUI(view: WebView?) {
        view?.evaluateJavascript("""
(function(){var s=document.createElement('style');s.textContent='video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important}body>*:not(video):not(script):not(style):not(link){display:none!important}body{background:#000!important;margin:0!important;padding:0!important;overflow:hidden!important}.vjs-control-bar,.jw-controlbar,.plyr__controls,.ytp-chrome-bottom,.vjs-big-play-button,.jw-display-icon-container,.plyr__control--overlaid,.ytp-large-play-button{display:none!important}[class*="overlay"],[class*="controls"],[class*="ad-"],[class*="popup"],[class*="banner"],[class*="modal"]{display:none!important}iframe{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483646!important;border:none!important}';document.head.appendChild(s);var v=document.querySelector('video');if(v){var p=v.parentElement;while(p&&p!==document.body){p.style.cssText='display:block!important;position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483646!important;overflow:visible!important;';p=p.parentElement;}}})();
        """.trimIndent(), null)
    }

    private fun injectRedirectGuard(view: WebView?) {
        val d = currentEmbedDomain
        view?.evaluateJavascript("""
(function(){
if(window.__rg)return;window.__rg=true;
var ed='$d';
function ext(h){try{var u=new URL(h,location.href);var n=u.hostname.toLowerCase();if(n===ed||n.endsWith('.'+ed)||n===location.hostname)return false;return true;}catch(e){return true;}}
var fw={closed:false,close:function(){},focus:function(){},blur:function(){},postMessage:function(){},location:{href:'',assign:function(){},replace:function(){},reload:function(){}},document:{write:function(){},createElement:function(){return document.createElement('div');},body:null},navigator:navigator,addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return true;},setTimeout:function(){return 0;},setInterval:function(){return 0;},clearTimeout:function(){},clearInterval:function(){}};
window.open=function(){return fw;};
try{var oa=location.assign.bind(location),or=location.replace.bind(location);Object.defineProperty(location,'assign',{value:function(u){if(!ext(u))oa(u);else if(window.PopupBridge)window.PopupBridge.onRedirectBlocked();},writable:false,configurable:false});Object.defineProperty(location,'replace',{value:function(u){if(!ext(u))or(u);else if(window.PopupBridge)window.PopupBridge.onRedirectBlocked();},writable:false,configurable:false});}catch(e){}
try{var lo=location,oh=Object.getOwnPropertyDescriptor(location.__proto__,'href');if(oh&&oh.set)Object.defineProperty(location,'href',{get:function(){return oh.get.call(lo);},set:function(v){if(!ext(v))oh.set.call(lo,v);else if(window.PopupBridge)window.PopupBridge.onRedirectBlocked();},configurable:false});}catch(e){}
document.addEventListener('click',function(e){var el=e.target;while(el&&el!==document){if(el.tagName==='A'&&el.href){var t=(el.getAttribute('target')||'').toLowerCase();if(t==='_blank'||t==='_top'||t==='_parent'||ext(el.href)){e.preventDefault();e.stopImmediatePropagation();return false;}}el=el.parentNode;}},true);
document.addEventListener('touchend',function(e){var el=e.target;while(el&&el!==document){if(el.tagName==='A'&&el.href&&ext(el.href)){e.preventDefault();e.stopImmediatePropagation();return false;}el=el.parentNode;}},true);
window.alert=function(){};window.confirm=function(){return false;};window.prompt=function(){return null;};
window.onbeforeunload=null;window.onunload=null;
try{Object.defineProperty(window,'onbeforeunload',{get:function(){return null;},set:function(){},configurable:false});}catch(e){}
document.querySelectorAll('meta[http-equiv="refresh"]').forEach(function(m){m.remove();});
if(document.body){document.body.onclick=null;document.body.onmousedown=null;document.body.onauxclick=null;document.body.oncontextmenu=null;}
var obs=new MutationObserver(function(ms){var blocked=false;ms.forEach(function(m){m.addedNodes.forEach(function(n){if(n.nodeType!==1)return;if(n.tagName==='META'&&n.getAttribute('http-equiv')==='refresh'){n.remove();blocked=true;return;}if(n.tagName==='SCRIPT'){var s=n.src||'';if(ext(s)&&(s.includes('pop')||s.includes('redirect')||s.includes('click')||s.includes('track'))){n.remove();blocked=true;return;}}try{var st=window.getComputedStyle(n);var z=parseInt(st.zIndex)||0;if((st.position==='fixed'||st.position==='absolute')&&z>9000){if(!n.querySelector('video')&&!n.querySelector('iframe[src*="embed"]')&&n.tagName!=='VIDEO'){n.remove();blocked=true;}}}catch(e){}});});if(blocked&&window.PopupBridge)window.PopupBridge.onPopupBlocked();});
if(document.body)obs.observe(document.body,{childList:true,subtree:true});
function wv(){var v=document.querySelector('video');if(v){v.addEventListener('play',function(){if(window.PopupBridge)window.PopupBridge.onVideoStarted();});v.addEventListener('playing',function(){if(window.PopupBridge)window.PopupBridge.onVideoStarted();});}}
wv();setTimeout(wv,1500);setTimeout(wv,3500);setTimeout(wv,7000);
})();
        """.trimIndent(), null)
    }

    private fun injectAdBlocker(view: WebView?) {
        view?.evaluateJavascript("""
(function(){var sel=['iframe[src*="doubleclick"]','iframe[src*="googlesyndication"]','div[id*="ad-"]','div[class*="ad-"]','div[id*="ads"]','div[class*="ads"]','.ad-overlay','.popup-overlay','[class*="popup"]','[id*="overlay"]','.modal-backdrop','[class*="banner-ad"]','.overlay','#overlay','.modal','div[style*="z-index: 9999"]','div[style*="z-index:9999"]','div[style*="z-index: 99999"]','div[style*="position: fixed"][style*="z-index"]','a[target="_blank"][href*="click"]','[onclick*="window.open"]'];sel.forEach(function(s){try{document.querySelectorAll(s).forEach(function(el){if(!el.querySelector('video')&&!el.querySelector('iframe[src*="embed"]')&&el.tagName!=='VIDEO')el.remove();});}catch(e){}});var all=document.querySelectorAll('*');for(var i=0;i<all.length;i++){try{var el=all[i];var st=window.getComputedStyle(el);var z=parseInt(st.zIndex)||0;if((st.position==='fixed'||st.position==='absolute')&&z>5000){if(parseFloat(st.opacity)<0.2||(el.offsetWidth>window.innerWidth*0.8&&el.offsetHeight>window.innerHeight*0.8&&!el.querySelector('video')))el.remove();}}catch(e){}}document.querySelectorAll('iframe').forEach(function(f){if(f.offsetWidth<=1||f.offsetHeight<=1)f.remove();});})();
        """.trimIndent(), null)
    }

    /**
     * Dispatches a real Android touch DOWN+UP at the center of the WebView.
     * This works even on cross-origin iframes where JS injection is blocked,
     * because it's a genuine system touch event — the browser treats it as a
     * real user tap and will honour play-button clicks on any player.
     */
    private fun simulateTapAtCenter() {
        val w = webView.width.toFloat()
        val h = webView.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val now = android.os.SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, cx, cy, 0)
        val up   = android.view.MotionEvent.obtain(now, now + 80L, android.view.MotionEvent.ACTION_UP, cx, cy, 0)
        webView.dispatchTouchEvent(down)
        webView.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    /**
     * Presses every known play-button selector, then calls .play() on all <video> elements.
     * Skips if video is already playing.
     */
    private fun injectAutoPlay(view: WebView?) {
        view?.evaluateJavascript("""
(function(){
var v=document.querySelector('video');
if(!v){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{v=fs[i].contentDocument.querySelector('video');if(v)break;}catch(e){}}}
if(v&&!v.paused&&v.currentTime>0)return;
var ps=['.vjs-big-play-button','.jw-icon-display','.jw-display-icon-container','[class*="play-btn"]','[class*="playBtn"]','[class*="play-button"]','[class*="playButton"]','[id*="play"]','button[aria-label*="play" i]','button[title*="play" i]','[data-plyr="play"]','.plyr__control--overlaid','.ytp-large-play-button','.video-play-button','.btn-play','.play','#play','div[role="button"]'];
var clicked=false;
for(var i=0;i<ps.length;i++){try{var btns=document.querySelectorAll(ps[i]);for(var j=0;j<btns.length;j++){if(btns[j].offsetWidth>0&&btns[j].offsetHeight>0){btns[j].click();clicked=true;break;}}if(clicked)break;}catch(e){}}
document.querySelectorAll('video').forEach(function(v){try{v.muted=false;v.autoplay=true;v.play().catch(function(){v.muted=true;v.play().catch(function(){});});}catch(e){}});
try{document.querySelectorAll('iframe').forEach(function(f){try{var fd=f.contentDocument||f.contentWindow.document;if(fd){fd.querySelectorAll('video').forEach(function(v){try{v.muted=false;v.autoplay=true;v.play().catch(function(){v.muted=true;v.play().catch(function(){});});}catch(e){}});for(var i=0;i<ps.length;i++){try{var btns=fd.querySelectorAll(ps[i]);for(var j=0;j<btns.length;j++){if(btns[j].offsetWidth>0){btns[j].click();break;}}}catch(e){}}}}catch(e){}});}catch(e){}
})();
        """.trimIndent(), null)
    }

    private fun startAutoPlayTimer(immediate: Boolean = true) {
        cancelAutoPlayTimer()
        autoPlayRunnable = object : Runnable {
            override fun run() {
                if (userInitiatedPause) return  // never override an explicit user pause
                autoPlayRetries++
                if (autoPlayRetries <= AUTO_PLAY_MAX_RETRIES) {
                    injectAutoPlay(webView)
                    injectAdBlocker(webView)
                    simulateTapAtCenter()
                    handler.postDelayed(this, AUTO_PLAY_RETRY_MS)
                }
            }
        }
        handler.postDelayed(autoPlayRunnable!!, if (immediate) 400L else AUTO_PLAY_RETRY_MS)
    }
    private fun cancelAutoPlayTimer() { autoPlayRunnable?.let { handler.removeCallbacks(it) }; autoPlayRunnable = null }

    /** Retry play up to PLAY_RETRY_MAX times — no server switch, no interference. */
    private fun schedulePlayRetry() {
        cancelPlayRetryTimer()
        playRetryCount = 0
        playRetryRunnable = object : Runnable {
            override fun run() {
                if (usingExoPlayer || userInitiatedPause) return
                val jsCheck = "(function(){var v=document.querySelector('video');if(!v){var fs=document.querySelectorAll('iframe');for(var i=0;i<fs.length;i++){try{v=fs[i].contentDocument.querySelector('video');if(v)break;}catch(e){}}}if(v&&!v.paused&&v.currentTime>0)return 'playing';return 'not_playing';})();"
                webView.evaluateJavascript(jsCheck) { result ->
                    handler.post {
                        if (userInitiatedPause) return@post
                        if (result.trim('"') == "playing") {
                            isPlaying = true
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                            setStatus("")
                            return@post
                        }
                        if (playRetryCount < PLAY_RETRY_MAX) {
                            playRetryCount++
                            setStatus("Retrying play ($playRetryCount/$PLAY_RETRY_MAX)…")
                            simulateTapAtCenter()
                            injectAutoPlay(webView)
                            handler.postDelayed(playRetryRunnable!!, PLAY_RETRY_DELAY_MS)
                        } else {
                            isPlaying = false
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                            setStatus("Tap the video to start playback")
                        }
                    }
                }
            }
        }
        handler.postDelayed(playRetryRunnable!!, PLAY_RETRY_DELAY_MS)
    }
    private fun cancelPlayRetryTimer() { playRetryRunnable?.let { handler.removeCallbacks(it) }; playRetryRunnable = null }

    // ── Server management ─────────────────────────────────────────────────────
    private fun setupServerSpinner() {
        val names = servers.map { it.name }.toTypedArray()
        serverSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (pos != currentServerIndex) loadServer(pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadServer(index: Int) {
        if (index >= servers.size) { showLoadingOverlay("No working stream found.\nTry selecting a server manually."); return }
        // Snapshot position BEFORE resetting state so resumeFromSavedPosition can use it
        if (isPlaying || lastWebViewPositionMs > 3_000L) savedPositionMs = getCurrentPositionMs()
        currentServerIndex = index
        sameServerBlockReloads = 0; blockedBeforeReload = false
        usingExoPlayer = false; extractedVideoUrl = null
        userInitiatedPause = false; cancelPlayRetryTimer(); cancelAdSkipTimer()
        if (index == 0) serversTried = 0
        releaseExoPlayer(); cancelExtractTimeout(); cancelVideoWatchdog()

        serverSpinner.setSelection(index, false)
        val server = servers[index]
        setStatus("⏳ Loading ${server.name}…")

        currentEmbedUrl = if (contentType == "movie") server.movieUrl(tmdbId) else server.tvUrl(tmdbId, season, episode)
        currentEmbedDomain = try { Uri.parse(currentEmbedUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }

        exoPlayerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        isPlaying = false
        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
        controlsContainer.visibility = View.GONE   // hide controls until video plays
        showLoadingOverlay(if (serversTried == 0) "Finding a stream\u2026" else "Trying source ${currentServerIndex + 1} of ${servers.size}\u2026")
        webView.loadUrl(currentEmbedUrl)
    }

    private fun reloadCurrentServer() {
        if (currentEmbedUrl.isNotEmpty()) { setStatus("⏳ Reloading…"); webView.loadUrl(currentEmbedUrl) }
    }

    private fun tryNextServer() {
        val next = currentServerIndex + 1
        if (next < servers.size) loadServer(next) else setStatus("❌ All servers tried. Select one manually.")
    }

    private fun startFallbackTimer() {
        cancelFallbackTimer()
        fallbackRunnable = Runnable { setStatus("⚠️ Timeout — switching server…"); tryNextServer() }
        handler.postDelayed(fallbackRunnable!!, FALLBACK_TIMEOUT_MS)
    }
    private fun cancelFallbackTimer() { fallbackRunnable?.let { handler.removeCallbacks(it) }; fallbackRunnable = null }

    // ── Position memory + ad-skip AI ──────────────────────────────────────────

    private var lastWebViewPositionMs = 0L   // kept fresh by the progress loop

    /** Current playback position in ms from whichever player is active. */
    private fun getCurrentPositionMs(): Long =
        if (usingExoPlayer) exoPlayer?.currentPosition ?: 0L else lastWebViewPositionMs

    /**
     * After a server switch, seek back to the position the user was watching.
     * Only kicks in if we were more than 3 s into the video.
     */
    private fun resumeFromSavedPosition() {
        val pos = savedPositionMs
        savedPositionMs = 0L
        if (pos <= 3_000L) return
        if (usingExoPlayer) {
            handler.postDelayed({ exoPlayer?.seekTo(pos) }, 600L)
        } else {
            val sec = pos / 1000.0
            handler.postDelayed({
                webView.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');"
                    +"if(!v){var fs=document.querySelectorAll('iframe');"
                    +"for(var i=0;i<fs.length;i++){try{v=fs[i].contentDocument.querySelector('video');if(v)break;}catch(e){}}}"
                    +"if(v&&v.duration>$sec)v.currentTime=$sec;})();",
                    null
                )
            }, 1_500L)
        }
    }

    /**
     * Silent AI: runs every 800 ms while video is playing.
     * Clicks skip-ad buttons, removes overlay divs, and nudges stalled video back to play.
     */
    private fun startAdSkipTimer() {
        cancelAdSkipTimer()
        val js = "(function(){var ss=['[class*=skip i]','[id*=skip i]','[class*=Skip]','[id*=Skip]','.videoAdUiSkipButton','.ytp-ad-skip-button','[data-purpose*=skip]','[aria-label*=Skip i]','[title*=Skip i]','[class*=closeBtn]','[class*=close-btn]'];for(var i=0;i<ss.length;i++){try{var els=document.querySelectorAll(ss[i]);for(var j=0;j<els.length;j++){var e=els[j];if(e.offsetWidth>0&&e.offsetHeight>0&&!e.disabled){e.click();break;}}}catch(ex){}}var all=document.querySelectorAll('*');for(var k=0;k<all.length;k++){try{var el=all[k];var cs=window.getComputedStyle(el);var z=parseInt(cs.zIndex)||0;if((cs.position==='fixed'||cs.position==='absolute')&&z>999&&!el.querySelector('video')&&el.tagName!=='VIDEO'&&!el.querySelector('iframe')&&el.tagName!=='IFRAME'){el.style.display='none';}}catch(ex){}}var v=document.querySelector('video');if(!v){var fs=document.querySelectorAll('iframe');for(var m=0;m<fs.length;m++){try{v=fs[m].contentDocument.querySelector('video');if(v)break;}catch(ex){}}}if(v&&v.paused&&v.readyState>=2&&v.duration>0&&v.currentTime>0){try{v.play();}catch(ex){}}var spans=document.querySelectorAll('span,div,button');for(var n=0;n<spans.length;n++){var t=spans[n].textContent||'';if(/skip ad in 0/i.test(t)||/skip in 0/i.test(t)||/skip \(0\)/i.test(t)){spans[n].click();break;}}})();"
        adSkipRunnable = object : Runnable {
            override fun run() {
                if (!usingExoPlayer && isPlaying && !userInitiatedPause) {
                    webView.evaluateJavascript(js, null)
                }
                handler.postDelayed(this, 800L)
            }
        }
        handler.postDelayed(adSkipRunnable!!, 800L)
    }
    private fun cancelAdSkipTimer() {
        adSkipRunnable?.let { handler.removeCallbacks(it) }
        adSkipRunnable = null
    }

    private fun showLoadingOverlay(msg: String = "Finding a stream\u2026") = handler.post {
        if (::loadingStatusText.isInitialized) loadingStatusText.text = msg
        if (::loadingOverlay.isInitialized) loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoadingOverlay() = handler.post {
        if (::loadingOverlay.isInitialized) loadingOverlay.visibility = View.GONE
        if (::controlsContainer.isInitialized) controlsContainer.visibility = View.VISIBLE
        startProgressUpdater()
    }

    private fun setStatus(msg: String) = handler.post {
        if (msg.isBlank()) statusText.visibility = View.GONE
        else { statusText.text = msg; statusText.visibility = View.VISIBLE }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullscreen) { exitCustomFullscreen(); return true }
            if (customView != null) { webView.webChromeClient?.onHideCustomView(); return true }
            finish(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onPause() { super.onPause(); webView.onPause(); exoPlayer?.pause(); cancelAutoPlayTimer(); cancelPlayRetryTimer(); cancelAdSkipTimer(); stopProgressUpdater() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() {
        cancelFallbackTimer(); cancelAutoPlayTimer(); cancelPlayRetryTimer(); cancelAdSkipTimer(); cancelExtractTimeout()
        cancelVideoWatchdog(); stopProgressUpdater(); releaseExoPlayer(); webView.destroy()
        super.onDestroy()
    }
}
