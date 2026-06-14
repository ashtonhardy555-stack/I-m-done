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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val FALLBACK_TIMEOUT_MS = 15_000L
        private const val AUTO_PLAY_RETRY_MS = 2_000L
        private const val AUTO_PLAY_MAX_RETRIES = 8
        private const val EXOPLAYER_EXTRACT_TIMEOUT_MS = 10_000L
        private const val MAX_RELOAD_ATTEMPTS = 3

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

    private var servers: List<StreamingServer> = emptyList()
    private var currentServerIndex = 0
    private var tmdbId = 0
    private var contentType = "movie"
    private var season = 1
    private var episode = 1
    private var currentEmbedDomain = ""
    private var currentEmbedUrl = ""
    private var pageLoadFailed = false
    private var autoPlayRetries = 0
    private var isPlaying = false
    private var isFullscreen = false
    private var isSeeking = false
    private var reloadAttempts = 0
    private var usingExoPlayer = false
    private var extractedVideoUrl: String? = null

    // Views
    private lateinit var webView: WebView
    private lateinit var exoPlayerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var loadingBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var controlBar: LinearLayout
    private lateinit var playPauseBtn: ImageButton
    private lateinit var skipBackBtn: ImageButton
    private lateinit var skipForwardBtn: ImageButton
    private lateinit var fullscreenBtn: ImageButton
    private lateinit var topBar: LinearLayout
    private lateinit var videoSeekBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var seekRow: LinearLayout
    private lateinit var controlsContainer: LinearLayout
    private lateinit var playerFrame: FrameLayout

    private var exoPlayer: ExoPlayer? = null
    private var progressRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null
    private var autoPlayRunnable: Runnable? = null
    private var extractTimeoutRunnable: Runnable? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Video URL patterns to intercept
    private val videoExtensions = listOf(
        ".m3u8", ".mp4", ".webm", ".mkv", ".avi", ".flv", ".ts"
    )
    private val videoMimePatterns = listOf(
        "application/x-mpegurl", "application/vnd.apple.mpegurl",
        "video/mp4", "video/webm", "video/x-matroska"
    )

    // Ad domain blocklist
    private val adDomains = listOf(
        "doubleclick", "googlesyndication", "adservice", "adnxs", "outbrain",
        "taboola", "revcontent", "mgid", "propellerads", "popcash", "popads",
        "trafficjunky", "exoclick", "juicyads", "adsterra", "hilltopads",
        "eroadvertising", "traffichunt", "clickadu", "richpush", "pushground",
        "mondiad", "bidvertiser", "advertserve", "yieldmo", "undertone",
        "adblade", "media.net", "zedo", "valueclick", "tradedoubler",
        "popunder", "onclickads", "betterads", "ad-maven",
        "admaven", "adcash", "adfly", "shorte.st", "linkvertise",
        "ouo.io", "ouo.press", "bc.vc", "adf.ly",
        "linkbucks", "adfoc.us", "coinurl",
        "clk.sh", "shrink.pe", "earnow", "adlinkfly", "linkshrink",
        "grabify", "iplogger", "blasze", "ps.ht", "2no.co",
        "pornhub", "xvideos", "xhamster", "redtube", "youporn",
        "chaturbate", "livejasmin", "cam4", "myfreecams", "bongacams",
        "1xbet", "betway", "bet365", "stake.com", "roobet",
        "pagead2", "syndication", "ampproject", "adcolony", "applovin",
        "moatads", "criteo", "pubmatic", "smartadserver", "teads",
        "amazon-adsystem", "advertising.com", "rubiconproject", "openx",
        "appnexus", "indexexchange", "casalemedia", "mediavine",
        "carbonads", "ethicalads", "buysellads"
    )

    private val adPathPatterns = listOf(
        "/ad/", "/ads/", "/advert", "/banner", "/popup", "/popunder",
        "/track", "/click", "/redirect", "/redir", "/pagead",
        "/sponsor", "/promo", "doubleclick", "googlesyndication"
    )

    private fun isAdUrl(url: String): Boolean {
        return try {
            val lower = url.lowercase()
            val host = Uri.parse(url).host?.lowercase() ?: return false
            if (adDomains.any { host.contains(it) }) return true
            val path = Uri.parse(url).path?.lowercase() ?: ""
            adPathPatterns.any { path.contains(it) || lower.contains(it) }
        } catch (_: Exception) { false }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return videoExtensions.any { lower.contains(it) } && !isAdUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        season = intent.getIntExtra(EXTRA_SEASON, 1)
        episode = intent.getIntExtra(EXTRA_EPISODE, 1)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

        servers = ServerManager.getOrderedServers()

        // ---- Build layout ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // Top bar: title + server spinner + close
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

        // Status text
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 6, 24, 6)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            visibility = View.GONE
        }
        root.addView(statusText)

        // Loading bar
        loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6)
        }
        root.addView(loadingBar)

        // Fullscreen container (for WebView custom fullscreen)
        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(fullscreenContainer)

        // Player frame: holds ExoPlayer, WebView, and controls overlay
        playerFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
        }

        // ExoPlayer view (native video player — visible when video URL is extracted)
        exoPlayerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false // We use our own controls
            visibility = View.GONE
        }
        playerFrame.addView(exoPlayerView)

        // WebView (loads embed page — visible as fallback)
        webView = WebView(this).apply {
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

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView, request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    if (isAdUrl(url)) return true
                    if (request.isForMainFrame && currentEmbedDomain.isNotEmpty()) {
                        val newHost = request.url.host?.lowercase() ?: ""
                        if (newHost.isNotEmpty() && newHost != currentEmbedDomain &&
                            !newHost.endsWith(".$currentEmbedDomain")
                        ) {
                            setStatus("Redirect blocked \u2192 reloading...")
                            handler.postDelayed({ reloadCurrentServer() }, 300)
                            return true
                        }
                    }
                    if (!request.isForMainFrame && isAdUrl(url)) return true
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView, request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()
                    if (isAdUrl(url)) {
                        return WebResourceResponse("text/plain", "UTF-8", null)
                    }
                    // Intercept video URLs for ExoPlayer
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
                    startFallbackTimer()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loadingBar.visibility = View.GONE
                    cancelFallbackTimer()
                    if (!pageLoadFailed) setStatus("")
                    injectRedirectGuard(view)
                    injectAdBlocker(view)
                    // Start auto-play to trigger video loading (so we can intercept the URL)
                    autoPlayRetries = 0
                    startAutoPlayTimer()
                    // If we haven't extracted a video URL yet, start the timeout
                    // to fall back to WebView mode
                    if (!usingExoPlayer && extractedVideoUrl == null) {
                        startExtractTimeout()
                    }
                }

                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        pageLoadFailed = true
                        setStatus("\u26A0\uFE0F Server error, trying next...")
                        ServerManager.markServerDead(
                            servers.getOrNull(currentServerIndex)?.name ?: ""
                        )
                        tryNextServer()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        val code = errorResponse?.statusCode ?: 0
                        if (code >= 500) {
                            pageLoadFailed = true
                            setStatus("\u26A0\uFE0F Server $code, trying next...")
                            ServerManager.markServerDead(
                                servers.getOrNull(currentServerIndex)?.name ?: ""
                            )
                            tryNextServer()
                        }
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    customView = view
                    customViewCallback = callback
                    fullscreenContainer.addView(view)
                    fullscreenContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                }
                override fun onHideCustomView() {
                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                    customViewCallback?.onCustomViewHidden()
                    customView = null
                    customViewCallback = null
                }
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean,
                    isUserGesture: Boolean, resultMsg: android.os.Message?
                ): Boolean = false
            }
        }
        playerFrame.addView(webView)

        // ---- Custom controls overlay (at bottom of playerFrame) ----
        controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC141414"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        }

        // Seek bar row
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 8, 24, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        videoSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) seekVideo(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true }
                override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false }
            })
        }
        seekRow.addView(videoSeekBar)
        timeText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
            text = "0:00 / 0:00"
            setPadding(12, 0, 0, 0)
        }
        seekRow.addView(timeText)
        controlsContainer.addView(seekRow)

        // Control buttons row
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 12)
            gravity = android.view.Gravity.CENTER
        }
        skipBackBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_rew)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(20, 12, 20, 12)
            setOnClickListener { skipVideo(-10) }
        }
        controlBar.addView(skipBackBtn)
        playPauseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(28, 12, 28, 12)
            setOnClickListener { togglePlayPause() }
        }
        controlBar.addView(playPauseBtn)
        skipForwardBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_ff)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(20, 12, 20, 12)
            setOnClickListener { skipVideo(10) }
        }
        controlBar.addView(skipForwardBtn)
        controlBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        fullscreenBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(20, 12, 20, 12)
            setOnClickListener { toggleFullscreen() }
        }
        controlBar.addView(fullscreenBtn)
        controlsContainer.addView(controlBar)
        playerFrame.addView(controlsContainer)

        root.addView(playerFrame)
        setContentView(root)

        // Load first server
        loadServer(0)
    }

    // ---- ExoPlayer: native video playback ----

    private fun switchToExoPlayer(videoUrl: String) {
        if (usingExoPlayer) return
        usingExoPlayer = true
        cancelExtractTimeout()
        cancelAutoPlayTimer()
        setStatus("")

        // Hide WebView, show ExoPlayer
        webView.visibility = View.GONE
        exoPlayerView.visibility = View.VISIBLE

        // Create ExoPlayer
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        exoPlayerView.player = player

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val mediaItem = MediaItem.fromUri(videoUrl)

        if (videoUrl.lowercase().contains(".m3u8")) {
            val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(hlsSource)
        } else {
            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource
                .Factory(dataSourceFactory)
                .createMediaSource(mediaItem)
            player.setMediaSource(mediaSource)
        }

        player.prepare()
        player.playWhenReady = true
        isPlaying = true
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        setStatus("")
                        startProgressUpdater()
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                    }
                    Player.STATE_BUFFERING -> setStatus("\u23F3 Buffering...")
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // ExoPlayer failed — fall back to WebView mode
                setStatus("\u26A0\uFE0F Native player failed, using embed...")
                switchToWebViewFallback()
            }
        })

        startProgressUpdater()
    }

    private fun switchToWebViewFallback() {
        usingExoPlayer = false
        extractedVideoUrl = null
        releaseExoPlayer()

        exoPlayerView.visibility = View.GONE
        webView.visibility = View.VISIBLE

        // Inject CSS to hide ALL embed UI except the <video> tag
        injectHideEmbedUI(webView)
        // Inject auto-play
        autoPlayRetries = 0
        startAutoPlayTimer()
        startProgressUpdater()
    }

    private fun releaseExoPlayer() {
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startExtractTimeout() {
        cancelExtractTimeout()
        extractTimeoutRunnable = Runnable {
            if (!usingExoPlayer && extractedVideoUrl == null) {
                // No video URL was intercepted — fall back to WebView with hidden UI
                setStatus("Using embed player...")
                switchToWebViewFallback()
            }
        }
        handler.postDelayed(extractTimeoutRunnable!!, EXOPLAYER_EXTRACT_TIMEOUT_MS)
    }

    private fun cancelExtractTimeout() {
        extractTimeoutRunnable?.let { handler.removeCallbacks(it) }
        extractTimeoutRunnable = null
    }

    // ---- Controls ----

    private fun togglePlayPause() {
        if (usingExoPlayer) {
            val player = exoPlayer ?: return
            if (player.isPlaying) {
                player.pause()
                isPlaying = false
                playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
            } else {
                player.play()
                isPlaying = true
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
            }
        } else {
            // WebView fallback — try JS, then simulate click
            runWebViewVideoCommand(if (isPlaying) "v.pause();" else "v.play();")
            isPlaying = !isPlaying
            playPauseBtn.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }
    }

    private fun skipVideo(seconds: Int) {
        if (usingExoPlayer) {
            val player = exoPlayer ?: return
            player.seekTo(player.currentPosition + seconds * 1000L)
        } else {
            runWebViewVideoCommand("v.currentTime += $seconds;")
        }
    }

    private fun seekVideo(progressValue: Int) {
        if (usingExoPlayer) {
            val player = exoPlayer ?: return
            if (player.duration > 0) {
                player.seekTo((player.duration * progressValue / 1000L))
            }
        } else {
            runWebViewVideoCommand("if(v.duration) v.currentTime = v.duration * ($progressValue / 1000.0);")
        }
    }

    private fun toggleFullscreen() {
        if (isFullscreen) {
            exitCustomFullscreen()
        } else {
            enterCustomFullscreen()
        }
    }

    private fun enterCustomFullscreen() {
        isFullscreen = true
        topBar.visibility = View.GONE
        statusText.visibility = View.GONE
        controlsContainer.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun exitCustomFullscreen() {
        isFullscreen = false
        topBar.visibility = View.VISIBLE
        controlsContainer.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    // ---- Progress updater ----

    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressRunnable = object : Runnable {
            override fun run() {
                if (isSeeking) {
                    handler.postDelayed(this, 1000)
                    return
                }
                if (usingExoPlayer) {
                    val player = exoPlayer
                    if (player != null && player.duration > 0) {
                        val current = player.currentPosition
                        val duration = player.duration
                        videoSeekBar.progress = ((current.toFloat() / duration) * 1000).toInt()
                        timeText.text = "${formatTime((current / 1000).toInt())} / ${formatTime((duration / 1000).toInt())}"
                        if (player.isPlaying && !isPlaying) {
                            isPlaying = true
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                        } else if (!player.isPlaying && isPlaying && player.playbackState != Player.STATE_BUFFERING) {
                            isPlaying = false
                            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                } else {
                    // WebView fallback — try to read video state via JS
                    webView.evaluateJavascript("""
                        (function(){
                            var v = document.querySelector('video');
                            if(!v) {
                                var iframes = document.querySelectorAll('iframe');
                                for(var i=0;i<iframes.length;i++){
                                    try { v = iframes[i].contentDocument.querySelector('video'); if(v) break; } catch(e){}
                                }
                            }
                            if(v && v.duration) {
                                return JSON.stringify({current:v.currentTime, duration:v.duration, paused:v.paused});
                            }
                            return 'null';
                        })();
                    """.trimIndent()) { result ->
                        try {
                            val cleaned = result.trim('"').replace("\\\"", "\"")
                            if (cleaned != "null") {
                                val json = org.json.JSONObject(cleaned)
                                val current = json.getDouble("current")
                                val duration = json.getDouble("duration")
                                val paused = json.getBoolean("paused")
                                handler.post {
                                    if (duration > 0) {
                                        videoSeekBar.progress = ((current / duration) * 1000).toInt()
                                    }
                                    timeText.text = "${formatTime(current.toInt())} / ${formatTime(duration.toInt())}"
                                    if (paused && isPlaying) {
                                        isPlaying = false
                                        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                                    } else if (!paused && !isPlaying) {
                                        isPlaying = true
                                        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                                    }
                                }
                            }
                        } catch (_: Exception) { }
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(progressRunnable!!, 1000)
    }

    private fun stopProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun formatTime(totalSeconds: Int): String {
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hrs > 0) String.format("%d:%02d:%02d", hrs, mins, secs)
        else String.format("%d:%02d", mins, secs)
    }

    // ---- WebView video commands (fallback mode) ----

    private fun runWebViewVideoCommand(js: String) {
        webView.evaluateJavascript("""
            (function(){
                var v = document.querySelector('video');
                if(!v) {
                    var iframes = document.querySelectorAll('iframe');
                    for(var i=0;i<iframes.length;i++){
                        try { v = iframes[i].contentDocument.querySelector('video'); if(v) break; } catch(e){}
                    }
                }
                if(v) { $js }
            })();
        """.trimIndent(), null)
    }

    // ---- Hide ALL embed UI except <video> (WebView fallback mode) ----

    private fun injectHideEmbedUI(view: WebView?) {
        view?.evaluateJavascript("""
(function(){
    // Hide everything except the video element
    var style = document.createElement('style');
    style.textContent = [
        // Make video fill the entire viewport
        'video { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 2147483647 !important; object-fit: contain !important; background: #000 !important; }',
        // Hide all other elements
        'body > *:not(video):not(script):not(style):not(link) { display: none !important; }',
        // But show containers that HAVE a video inside them
        'body { background: #000 !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; }',
        // Hide common player UI elements
        '.vjs-control-bar, .jw-controlbar, .jw-controls, .plyr__controls, .ytp-chrome-bottom { display: none !important; }',
        '.vjs-big-play-button, .jw-display-icon-container, .plyr__control--overlaid, .ytp-large-play-button { display: none !important; }',
        '[class*="overlay"], [class*="Overlay"], [class*="controls"], [class*="Controls"] { display: none !important; }',
        '[class*="ad-"], [class*="popup"], [class*="banner"], [class*="modal"] { display: none !important; }',
        // Show iframes that contain the video
        'iframe { position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 2147483646 !important; border: none !important; }'
    ].join('\n');
    document.head.appendChild(style);

    // Make parents of video visible
    var video = document.querySelector('video');
    if(video) {
        var parent = video.parentElement;
        while(parent && parent !== document.body) {
            parent.style.cssText = 'display: block !important; position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 2147483646 !important; overflow: visible !important;';
            parent = parent.parentElement;
        }
    }

    // Also inject into same-origin iframes
    try {
        document.querySelectorAll('iframe').forEach(function(f){
            try {
                var fd = f.contentDocument;
                if(fd) {
                    var s2 = fd.createElement('style');
                    s2.textContent = style.textContent;
                    fd.head.appendChild(s2);
                    var fv = fd.querySelector('video');
                    if(fv) {
                        var p = fv.parentElement;
                        while(p && p !== fd.body) {
                            p.style.cssText = 'display: block !important; position: fixed !important; top: 0 !important; left: 0 !important; width: 100vw !important; height: 100vh !important; z-index: 2147483646 !important; overflow: visible !important;';
                            p = p.parentElement;
                        }
                    }
                }
            } catch(e){}
        });
    } catch(e){}
})();
        """.trimIndent(), null)
    }

    // ---- Redirect guard ----

    private fun injectRedirectGuard(view: WebView?) {
        val embedDomain = currentEmbedDomain
        view?.evaluateJavascript("""
(function(){
    if(window.__redirectGuard) return;
    window.__redirectGuard = true;

    var embedDomain = '$embedDomain';

    function isExternal(href) {
        try {
            var u = new URL(href, location.href);
            var h = u.hostname.toLowerCase();
            if (h === embedDomain) return false;
            if (h.endsWith('.' + embedDomain)) return false;
            if (h === location.hostname) return false;
            return true;
        } catch(e) { return true; }
    }

    var fakeWin = {
        closed: false, close: function(){ this.closed = true; },
        focus: function(){}, blur: function(){}, postMessage: function(){},
        moveTo: function(){}, resizeTo: function(){}, moveBy: function(){},
        resizeBy: function(){},
        location: {
            href: '', origin: '', protocol: 'https:', host: '',
            hostname: '', pathname: '/', search: '', hash: '',
            assign: function(){}, replace: function(){}, reload: function(){}
        },
        document: {
            write: function(){}, writeln: function(){},
            open: function(){ return this; }, close: function(){},
            createElement: function(){ return document.createElement('div'); },
            body: null
        },
        navigator: navigator,
        history: { back: function(){}, forward: function(){}, go: function(){} },
        addEventListener: function(){}, removeEventListener: function(){},
        dispatchEvent: function(){ return true; },
        setTimeout: function(){ return 0; }, setInterval: function(){ return 0; },
        clearTimeout: function(){}, clearInterval: function(){},
        getComputedStyle: function(){ return {}; },
        requestAnimationFrame: function(){ return 0; },
        screen: window.screen, innerWidth: 0, innerHeight: 0,
        outerWidth: 0, outerHeight: 0, scrollX: 0, scrollY: 0,
        pageXOffset: 0, pageYOffset: 0
    };
    window.open = function() { return fakeWin; };

    try {
        var origAssign = location.assign.bind(location);
        var origReplace = location.replace.bind(location);
        Object.defineProperty(location, 'assign', {
            value: function(u) { if (!isExternal(u)) origAssign(u); },
            writable: false, configurable: false
        });
        Object.defineProperty(location, 'replace', {
            value: function(u) { if (!isExternal(u)) origReplace(u); },
            writable: false, configurable: false
        });
    } catch(e) {}

    try {
        var loc = location;
        var origHref = Object.getOwnPropertyDescriptor(location.__proto__, 'href');
        if (origHref && origHref.set) {
            Object.defineProperty(location, 'href', {
                get: function() { return origHref.get.call(loc); },
                set: function(v) { if (!isExternal(v)) origHref.set.call(loc, v); },
                configurable: false
            });
        }
    } catch(e) {}

    document.addEventListener('click', function(e) {
        var el = e.target;
        while (el && el !== document) {
            if (el.tagName === 'A' && el.href) {
                var t = (el.getAttribute('target') || '').toLowerCase();
                if (t === '_blank' || t === '_top' || t === '_parent' || isExternal(el.href)) {
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    return false;
                }
            }
            el = el.parentNode;
        }
    }, true);

    // Also block touch-triggered navigations
    document.addEventListener('touchend', function(e) {
        var el = e.target;
        while (el && el !== document) {
            if (el.tagName === 'A' && el.href && isExternal(el.href)) {
                e.preventDefault();
                e.stopImmediatePropagation();
                return false;
            }
            el = el.parentNode;
        }
    }, true);

    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        var t = (this.getAttribute('target') || '').toLowerCase();
        if (t === '_blank' || t === '_top' || t === '_parent') return;
        if (this.href && isExternal(this.href)) return;
        return origAnchorClick.apply(this, arguments);
    };

    var origFormSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function() {
        var t = (this.getAttribute('target') || '').toLowerCase();
        if (t === '_blank' || t === '_top' || t === '_parent') return;
        var action = this.getAttribute('action') || '';
        if (action && isExternal(action)) return;
        return origFormSubmit.apply(this, arguments);
    };

    window.alert = function() {};
    window.confirm = function() { return false; };
    window.prompt = function() { return null; };

    window.onbeforeunload = null;
    window.onunload = null;
    Object.defineProperty(window, 'onbeforeunload', { get: function(){ return null; }, set: function(){}, configurable: false });

    var metas = document.querySelectorAll('meta[http-equiv="refresh"]');
    metas.forEach(function(m) { m.remove(); });

    if(document.body) {
        document.body.onclick = null;
        document.body.onmousedown = null;
        document.body.onmouseup = null;
        document.body.onauxclick = null;
        document.body.oncontextmenu = null;
    }

    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType !== 1) return;
                if (node.tagName === 'META' && node.getAttribute('http-equiv') === 'refresh') {
                    node.remove();
                    return;
                }
                if (node.tagName === 'SCRIPT') {
                    var src = node.src || '';
                    if (isExternal(src) && (
                        src.includes('pop') || src.includes('redirect') ||
                        src.includes('click') || src.includes('track')
                    )) {
                        node.remove();
                        return;
                    }
                }
                try {
                    var st = window.getComputedStyle(node);
                    var z = parseInt(st.zIndex) || 0;
                    if ((st.position === 'fixed' || st.position === 'absolute') && z > 9000) {
                        if (!node.querySelector('video') && !node.querySelector('iframe[src*="embed"]') && node.tagName !== 'VIDEO') {
                            node.remove();
                        }
                    }
                } catch(e) {}
            });
        });
    });
    if (document.body) {
        observer.observe(document.body, { childList: true, subtree: true });
    }
})();
        """.trimIndent(), null)
    }

    // ---- Ad blocker ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun injectAdBlocker(view: WebView?) {
        view?.evaluateJavascript("""
(function() {
    var selectors = [
        'iframe[src*="doubleclick"]', 'iframe[src*="googlesyndication"]',
        'iframe[src*="adservice"]', 'div[id*="ad-"]', 'div[class*="ad-"]',
        'div[id*="ads"]', 'div[class*="ads"]', '.ad-container',
        '.ad-overlay', '.popup-overlay', '[class*="popup"]',
        '[id*="overlay"]', '.modal-backdrop', '[class*="banner-ad"]',
        '[id*="banner-ad"]', '.overlay', '#overlay', '.modal',
        'div[style*="z-index: 9999"]', 'div[style*="z-index:9999"]',
        'div[style*="z-index: 99999"]', 'div[style*="z-index:99999"]',
        'div[style*="z-index: 999999"]', 'div[style*="z-index:999999"]',
        'div[style*="position: fixed"][style*="z-index"]',
        'a[target="_blank"][href*="click"]', 'a[target="_blank"][href*="ad"]',
        '[onclick*="window.open"]', '[onmousedown*="window.open"]'
    ];
    selectors.forEach(function(s) {
        try {
            document.querySelectorAll(s).forEach(function(el) {
                if (!el.querySelector('video') && !el.querySelector('iframe[src*="embed"]') &&
                    el.tagName !== 'VIDEO') {
                    el.remove();
                }
            });
        } catch(e) {}
    });
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
        try {
            var el = all[i];
            var st = window.getComputedStyle(el);
            var z = parseInt(st.zIndex) || 0;
            if ((st.position === 'fixed' || st.position === 'absolute') && z > 5000) {
                if (parseFloat(st.opacity) < 0.2 ||
                    (el.offsetWidth > window.innerWidth * 0.8 && el.offsetHeight > window.innerHeight * 0.8 && !el.querySelector('video'))) {
                    el.remove();
                }
            }
        } catch(e) {}
    }
    document.querySelectorAll('iframe').forEach(function(f) {
        if (f.offsetWidth <= 1 || f.offsetHeight <= 1) f.remove();
    });
})();
        """.trimIndent(), null)
    }

    // ---- Auto-play ----

    private fun injectAutoPlay(view: WebView?) {
        view?.evaluateJavascript("""
(function() {
    var playSelectors = [
        '.vjs-big-play-button', '.jw-icon-display', '.jw-display-icon-container',
        '[class*="play-btn"]', '[class*="play_btn"]', '[class*="playBtn"]',
        '[class*="play-button"]', '[class*="play_button"]', '[class*="playButton"]',
        '[class*="Play"]', '[id*="play"]', '[id*="Play"]',
        'button[aria-label*="play"]', 'button[aria-label*="Play"]',
        'button[title*="play"]', 'button[title*="Play"]',
        '[data-plyr="play"]', '.plyr__control--overlaid',
        'svg[data-icon="play"]', '.ytp-large-play-button',
        '.video-play-button', '.btn-play', '.icon-play',
        '.play', '#play', '.playButton', '#playButton',
        'div[role="button"]'
    ];
    var clicked = false;
    for (var i = 0; i < playSelectors.length; i++) {
        try {
            var btns = document.querySelectorAll(playSelectors[i]);
            for (var j = 0; j < btns.length; j++) {
                if (btns[j].offsetWidth > 0 && btns[j].offsetHeight > 0) {
                    btns[j].click();
                    clicked = true;
                    break;
                }
            }
            if (clicked) break;
        } catch(e) {}
    }
    var videos = document.querySelectorAll('video');
    videos.forEach(function(v) {
        try {
            v.muted = false;
            v.autoplay = true;
            v.play().catch(function() {
                v.muted = true;
                v.play().catch(function(){});
            });
        } catch(e) {}
    });
    try {
        document.querySelectorAll('iframe').forEach(function(f) {
            try {
                var fdoc = f.contentDocument || f.contentWindow.document;
                if (fdoc) {
                    fdoc.querySelectorAll('video').forEach(function(v) {
                        try {
                            v.muted = false;
                            v.autoplay = true;
                            v.play().catch(function(){ v.muted = true; v.play().catch(function(){}); });
                        } catch(e) {}
                    });
                    for (var i = 0; i < playSelectors.length; i++) {
                        try {
                            var btns = fdoc.querySelectorAll(playSelectors[i]);
                            for (var j = 0; j < btns.length; j++) {
                                if (btns[j].offsetWidth > 0) { btns[j].click(); break; }
                            }
                        } catch(e) {}
                    }
                }
            } catch(e) {}
        });
    } catch(e) {}
})();
        """.trimIndent(), null)
    }

    private fun startAutoPlayTimer() {
        cancelAutoPlayTimer()
        autoPlayRunnable = Runnable {
            autoPlayRetries++
            if (autoPlayRetries <= AUTO_PLAY_MAX_RETRIES) {
                injectAutoPlay(webView)
                injectAdBlocker(webView)
                handler.postDelayed(autoPlayRunnable!!, AUTO_PLAY_RETRY_MS)
            }
        }
        handler.postDelayed(autoPlayRunnable!!, 1000)
    }

    private fun cancelAutoPlayTimer() {
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
        autoPlayRunnable = null
    }

    // ---- Server management ----

    private fun setupServerSpinner() {
        val serverNames = servers.map { it.name }.toTypedArray()
        serverSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, serverNames
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        serverSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, v: View?, pos: Int, id: Long
            ) {
                if (pos != currentServerIndex) loadServer(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadServer(index: Int) {
        if (index >= servers.size) {
            setStatus("\u274C All servers tried. Select one manually.")
            return
        }
        currentServerIndex = index
        reloadAttempts = 0
        usingExoPlayer = false
        extractedVideoUrl = null
        releaseExoPlayer()
        cancelExtractTimeout()

        serverSpinner.setSelection(index, false)
        val server = servers[index]
        setStatus("\u23F3 Loading ${server.name}...")

        currentEmbedUrl = if (contentType == "movie") {
            server.movieUrl(tmdbId)
        } else {
            server.tvUrl(tmdbId, season, episode)
        }
        currentEmbedDomain = try {
            Uri.parse(currentEmbedUrl).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }

        // Reset views
        exoPlayerView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        isPlaying = false
        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)

        webView.loadUrl(currentEmbedUrl)
    }

    private fun reloadCurrentServer() {
        if (currentEmbedUrl.isNotEmpty()) {
            setStatus("\u23F3 Reloading...")
            webView.loadUrl(currentEmbedUrl)
        }
    }

    private fun tryNextServer() {
        val next = currentServerIndex + 1
        if (next < servers.size) {
            loadServer(next)
        } else {
            setStatus("\u274C All servers tried. Select one manually.")
        }
    }

    private fun startFallbackTimer() {
        cancelFallbackTimer()
        fallbackRunnable = Runnable {
            setStatus("\u26A0\uFE0F Timeout, switching server...")
            tryNextServer()
        }
        handler.postDelayed(fallbackRunnable!!, FALLBACK_TIMEOUT_MS)
    }

    private fun cancelFallbackTimer() {
        fallbackRunnable?.let { handler.removeCallbacks(it) }
        fallbackRunnable = null
    }

    private fun setStatus(msg: String) {
        handler.post {
            if (msg.isBlank()) {
                statusText.visibility = View.GONE
            } else {
                statusText.text = msg
                statusText.visibility = View.VISIBLE
            }
        }
    }

    // ---- Lifecycle ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullscreen) {
                exitCustomFullscreen()
                return true
            }
            if (customView != null) {
                webView.webChromeClient?.onHideCustomView()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        exoPlayer?.pause()
        cancelAutoPlayTimer()
        stopProgressUpdater()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        cancelFallbackTimer()
        cancelAutoPlayTimer()
        cancelExtractTimeout()
        stopProgressUpdater()
        releaseExoPlayer()
        webView.destroy()
        super.onDestroy()
    }
}
