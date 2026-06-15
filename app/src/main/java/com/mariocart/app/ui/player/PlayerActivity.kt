package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.ServerTester
import kotlinx.coroutines.launch
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    // ── Companion ─────────────────────────────────────────────────────────────
    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"

        // How long to wait for the hidden WebView to intercept a video URL
        private const val EXTRACT_TIMEOUT_MS    = 18_000L
        // How long to wait for a page to even start responding
        private const val PAGE_LOAD_TIMEOUT_MS  = 12_000L
        // Max block-reloads on the same server before moving on
        private const val MAX_BLOCK_RELOADS     = 3

        val AUTO_PLAY_JS = """
(function(){
  function tryPlay(v){try{v.muted=false;v.volume=1;var p=v.play();if(p&&p.catch)p.catch(function(){v.muted=true;v.play();});}catch(e){}}
  var vids=document.querySelectorAll('video');
  for(var i=0;i<vids.length;i++){tryPlay(vids[i]);}
  var frames=document.querySelectorAll('iframe');
  for(var j=0;j<frames.length;j++){
    try{var fv=frames[j].contentDocument.querySelectorAll('video');
    for(var k=0;k<fv.length;k++){tryPlay(fv[k]);}}catch(e){}
  }
  var origOpen=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(method,url){
    if(url&&(url.indexOf('.m3u8')>=0||url.indexOf('.mp4')>=0||url.indexOf('.ts')>=0)){
      try{Android.onVideoPlaying(url);}catch(e){}
    }
    return origOpen.apply(this,arguments);
  };
  var origFetch=window.fetch;
  window.fetch=function(input){
    var url=typeof input==='string'?input:(input&&input.url)||'';
    if(url&&(url.indexOf('.m3u8')>=0||url.indexOf('.mp4')>=0)){
      try{Android.onVideoPlaying(url);}catch(e){}
    }
    return origFetch.apply(this,arguments);
  };
})();
""".trimIndent()

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

    // ── Server / content state ────────────────────────────────────────────────
    private var servers: List<StreamingServer> = emptyList()
    private var currentServerIndex = 0
    private var tmdbId = 0
    private var contentType = "movie"
    private var season = 1
    private var episode = 1
    private var title = ""
    private var currentEmbedUrl = ""
    private var currentEmbedDomain = ""

    // ── Extraction state ──────────────────────────────────────────────────────
    private var extractedVideoUrl: String? = null
    private var blockReloadCount = 0
    private var blockedBeforeReload = false
    private var pageLoadFailed = false

    // ── Player state ──────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var videoCache: SimpleCache? = null
    private var isPlaying = false
    private var isSeeking = false
    private var userInitiatedPause = false
    private var savedPositionMs = 0L
    private var selectedMaxHeight = Int.MAX_VALUE

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var playerView: PlayerView
    private lateinit var hiddenWebView: WebView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingTitle: TextView
    private lateinit var loadingStatus: TextView
    private lateinit var loadingDots: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var sourceBtn: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var playPauseBtn: ImageButton
    private lateinit var rewindBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var qualityBtn: TextView
    private lateinit var errorText: TextView

    // ── Runnables / handler ───────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var extractTimeoutRunnable: Runnable? = null
    private var pageLoadTimeoutRunnable: Runnable? = null
    private var autoHideRunnable: Runnable? = null
    private var dotsRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var adSkipRunnable: Runnable? = null
    private var dotsCount = 0

    // ── Ad/video URL detection ────────────────────────────────────────────────
    private val videoExtensions = listOf(".m3u8", ".mp4", ".webm", ".ts")
    private val adDomains = listOf(
        "doubleclick","googlesyndication","adservice","adnxs","outbrain","taboola",
        "revcontent","mgid","propellerads","popcash","popads","trafficjunky","exoclick",
        "juicyads","adsterra","hilltopads","eroadvertising","pushground","mondiad",
        "bidvertiser","yieldmo","ad-maven","admaven","adcash","adfly","shorte.st",
        "pubmatic","openx","appnexus","indexexchange","casalemedia","rubiconproject",
        "criteo","teads","carbon","ethicalads","buysellads","pagead2","amazon-adsystem"
    )

    private fun isAdUrl(url: String): Boolean {
        return try {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            adDomains.any { host.contains(it) }
        } catch (_: Exception) { false }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return videoExtensions.any { lower.contains(it) } && !isAdUrl(url)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        tmdbId      = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        title       = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season      = intent.getIntExtra(EXTRA_SEASON, 1)
        episode     = intent.getIntExtra(EXTRA_EPISODE, 1)

        buildLayout()
        setupHiddenWebView()
        setupPlayerViewTap()
        initServersAndPlay()
    }

    // ── Layout ────────────────────────────────────────────────────────────────
    private fun buildLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ExoPlayer surface
        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(playerView)

        // Hidden WebView — purely for URL extraction, never visible
        hiddenWebView = WebView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(1, 1)
        }
        root.addView(hiddenWebView)

        // Loading overlay
        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val loadingCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        loadingTitle = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 24)
            maxLines = 2
        }
        loadingDots = TextView(this).apply {
            text = "⬤  ⬤  ⬤"
            setTextColor(Color.parseColor("#555555"))
            textSize = 14f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        loadingStatus = TextView(this).apply {
            text = "Finding a stream…"
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 20, 48, 0)
        }
        errorText = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6B6B"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(48, 12, 48, 0)
            visibility = View.GONE
        }
        loadingCenter.addView(loadingTitle)
        loadingCenter.addView(loadingDots)
        loadingCenter.addView(loadingStatus)
        loadingCenter.addView(errorText)
        loadingOverlay.addView(loadingCenter)
        root.addView(loadingOverlay)

        // Controls overlay (hidden until video plays)
        controlsOverlay = buildControlsOverlay()
        root.addView(controlsOverlay)

        setContentView(root)
    }

    private fun buildControlsOverlay(): LinearLayout {
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Gradient scrim at top
        val topScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(80)
            )
        }
        overlay.addView(topScrim)

        // Top bar: back + title + source picker
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Overlap the gradient scrim by pulling up
            (layoutParams as LinearLayout.LayoutParams).topMargin = -dp(80)
        }
        val backBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(16), dp(8), dp(16))
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)
        titleLabel = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(titleLabel)
        sourceBtn = TextView(this).apply {
            text = "SOURCE"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(Color.parseColor("#44FFFFFF"), dp(4))
            setOnClickListener { showServerPicker() }
        }
        topBar.addView(sourceBtn)
        overlay.addView(topBar)

        // Spacer — fills the middle
        overlay.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        // Gradient scrim at bottom
        val bottomScrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(120)
            )
        }
        overlay.addView(bottomScrim)

        // Seek row
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            (layoutParams as LinearLayout.LayoutParams).topMargin = -dp(120)
        }
        seekBar = SeekBar(this).apply {
            max = 1000
            progressDrawable?.setTint(Color.WHITE)
            thumb?.setTint(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) exoPlayer?.let { player ->
                        val dur = player.duration
                        if (dur > 0) player.seekTo((p.toLong() * dur) / 1000L)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { isSeeking = true; cancelAutoHide() }
                override fun onStopTrackingTouch(sb: SeekBar?) { isSeeking = false; scheduleAutoHide() }
            })
        }
        seekRow.addView(seekBar)
        timeLabel = TextView(this).apply {
            text = "0:00 / 0:00"
            setTextColor(Color.parseColor("#BBBBBB"))
            textSize = 11f
            setPadding(dp(10), 0, 0, 0)
        }
        seekRow.addView(timeLabel)
        overlay.addView(seekRow)

        // Bottom bar: controls
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rewindBtn = iconBtn(android.R.drawable.ic_media_rew) { seekRelative(-10_000L) }
        bottomBar.addView(rewindBtn)
        playPauseBtn = iconBtn(android.R.drawable.ic_media_play) { togglePlayPause() }
        (playPauseBtn.layoutParams as LinearLayout.LayoutParams).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        }
        bottomBar.addView(playPauseBtn)
        forwardBtn = iconBtn(android.R.drawable.ic_media_ff) { seekRelative(10_000L) }
        bottomBar.addView(forwardBtn)

        // Spacer
        bottomBar.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })

        qualityBtn = TextView(this).apply {
            text = "Auto"
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBg(Color.parseColor("#44FFFFFF"), dp(4))
            setOnClickListener { showQualityPicker() }
        }
        bottomBar.addView(qualityBtn)
        overlay.addView(bottomBar)

        return overlay
    }

    // ── Hidden WebView setup ──────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHiddenWebView() {
        hiddenWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }
        hiddenWebView.addJavascriptInterface(JsBridge(), "Android")
        hiddenWebView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // Block ad domains entirely
                val host = request.url.host?.lowercase() ?: ""
                if (adDomains.any { host.contains(it) }) return true
                // Block cross-origin main-frame redirects (popup hijacking)
                if (request.isForMainFrame) {
                    val newHost = host
                    if (newHost.isNotEmpty() && newHost != currentEmbedDomain
                        && !newHost.endsWith(".$currentEmbedDomain")) {
                        handleBlockEvent()
                        return true
                    }
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (isAdUrl(url)) return WebResourceResponse("text/plain", "UTF-8", null)
                if (extractedVideoUrl == null && isVideoUrl(url)) {
                    extractedVideoUrl = url
                    handler.post { onVideoUrlFound(url) }
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cancelPageLoadTimeout()
                if (pageLoadFailed) return
                // Inject auto-play so the embed starts and we can intercept the stream URL
                view?.evaluateJavascript(AUTO_PLAY_JS, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    pageLoadFailed = true
                    ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                    handler.post { tryNextServer() }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    val code = errorResponse?.statusCode ?: 0
                    if (code == 404 || code >= 500) {
                        pageLoadFailed = true
                        ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                        handler.post { tryNextServer() }
                    }
                }
            }
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onVideoPlaying(url: String) {
            if (extractedVideoUrl == null && isVideoUrl(url)) {
                extractedVideoUrl = url
                handler.post { onVideoUrlFound(url) }
            }
        }
    }

    // ── Initialization ────────────────────────────────────────────────────────
    private fun initServersAndPlay() {
        startDotsAnimation()
        lifecycleScope.launch {
            ServerManager.initialize(this@PlayerActivity)
            val raw = ServerManager.getOrderedServers()
            servers = ServerTester.rankForContent(
                raw, tmdbId, contentType, season, episode
            )
            loadServer(0)
        }
    }

    // ── Server loading ────────────────────────────────────────────────────────
    private fun loadServer(index: Int) {
        if (index >= servers.size) {
            showError("No working stream found.\nTry a different source.")
            return
        }
        currentServerIndex = index
        extractedVideoUrl = null
        blockReloadCount = 0
        blockedBeforeReload = false
        pageLoadFailed = false

        releaseExoPlayer()
        cancelExtractTimeout()
        cancelPageLoadTimeout()
        hiddenWebView.stopLoading()

        val server = servers[index]
        currentEmbedUrl = if (contentType == "movie") server.movieUrl(tmdbId)
                          else server.tvUrl(tmdbId, season, episode)
        currentEmbedDomain = try { Uri.parse(currentEmbedUrl).host?.lowercase() ?: "" } catch (_: Exception) { "" }

        setLoadingStatus("Trying ${server.name}…")
        hideError()

        startPageLoadTimeout()
        startExtractTimeout()
        hiddenWebView.loadUrl(currentEmbedUrl)
    }

    private fun tryNextServer() {
        val next = currentServerIndex + 1
        if (next < servers.size) loadServer(next)
        else showError("All sources tried.\nTap SOURCE to pick manually.")
    }

    private fun reloadCurrentServer() {
        extractedVideoUrl = null
        pageLoadFailed = false
        cancelExtractTimeout()
        startPageLoadTimeout()
        startExtractTimeout()
        hiddenWebView.loadUrl(currentEmbedUrl)
    }

    private fun handleBlockEvent() {
        blockReloadCount++
        if (blockReloadCount > MAX_BLOCK_RELOADS) {
            blockReloadCount = 0
            handler.post { tryNextServer() }
        } else {
            blockedBeforeReload = true
            handler.postDelayed({ reloadCurrentServer() }, 300L)
        }
    }

    // ── URL found → start ExoPlayer ───────────────────────────────────────────
    private fun onVideoUrlFound(videoUrl: String) {
        cancelExtractTimeout()
        cancelPageLoadTimeout()
        ServerManager.markServerSuccess(servers.getOrNull(currentServerIndex)?.name ?: "")

        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        val httpDsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        @Suppress("DEPRECATION")
        val cache = SimpleCache(
            File(cacheDir, "exo_cache"),
            LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024)
        )
        videoCache = cache

        val cacheDsf = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDsf)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mi = MediaItem.fromUri(videoUrl)
        val source = if (videoUrl.lowercase().contains(".m3u8"))
            HlsMediaSource.Factory(cacheDsf).createMediaSource(mi)
        else
            ProgressiveMediaSource.Factory(cacheDsf).createMediaSource(mi)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true

        if (selectedMaxHeight != Int.MAX_VALUE) {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, selectedMaxHeight)
                .build()
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        isPlaying = true
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                        showPlayerControls()
                        startProgressUpdater()
                        startAdSkipTimer()
                        resumeFromSavedPosition()
                    }
                    Player.STATE_ENDED -> {
                        isPlaying = false
                        playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                    }
                    Player.STATE_BUFFERING -> { /* optional: show a spinner */ }
                    else -> {}
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isPlaying = false
                savedPositionMs = exoPlayer?.currentPosition ?: 0L
                releaseExoPlayer()
                setLoadingStatus("Source failed, trying next…")
                hiddenWebView.stopLoading()
                showLoadingOverlay()
                handler.postDelayed({ tryNextServer() }, 500L)
            }
        })
    }

    // ── Player controls ───────────────────────────────────────────────────────
    private fun showPlayerControls() {
        loadingOverlay.visibility = View.GONE
        controlsOverlay.visibility = View.VISIBLE
        scheduleAutoHide()
    }

    private fun showLoadingOverlay() {
        controlsOverlay.visibility = View.GONE
        loadingOverlay.visibility = View.VISIBLE
        startDotsAnimation()
    }

    private fun toggleControlsVisibility() {
        if (controlsOverlay.visibility == View.VISIBLE) {
            controlsOverlay.visibility = View.GONE
            cancelAutoHide()
        } else {
            controlsOverlay.visibility = View.VISIBLE
            scheduleAutoHide()
        }
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        autoHideRunnable = Runnable { controlsOverlay.visibility = View.GONE }
        handler.postDelayed(autoHideRunnable!!, 3_500L)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { handler.removeCallbacks(it) }
        autoHideRunnable = null
    }

    private fun setupPlayerViewTap() {
        playerView.setOnClickListener { toggleControlsVisibility() }
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            userInitiatedPause = true
            isPlaying = false
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
        } else {
            player.play()
            userInitiatedPause = false
            isPlaying = true
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
        }
        scheduleAutoHide()
    }

    private fun seekRelative(offsetMs: Long) {
        exoPlayer?.let { player ->
            val pos = (player.currentPosition + offsetMs).coerceAtLeast(0L)
            player.seekTo(pos)
            scheduleAutoHide()
        }
    }

    private fun startProgressUpdater() {
        progressRunnable = object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                val pos = player.currentPosition
                val dur = player.duration
                if (!isSeeking && dur > 0) {
                    seekBar.progress = ((pos * 1000L) / dur).toInt()
                    timeLabel.text = "${formatMs(pos)} / ${formatMs(dur)}"
                }
                handler.postDelayed(this, 500L)
            }
        }
        handler.postDelayed(progressRunnable!!, 500L)
    }

    private fun stopProgressUpdater() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun resumeFromSavedPosition() {
        val pos = savedPositionMs; savedPositionMs = 0L
        if (pos > 3_000L) handler.postDelayed({ exoPlayer?.seekTo(pos) }, 600L)
    }

    // ── Quality picker ────────────────────────────────────────────────────────
    private fun showQualityPicker() {
        val labels  = arrayOf("Auto", "1080p", "720p", "480p", "360p")
        val heights = intArrayOf(Int.MAX_VALUE, 1080, 720, 480, 360)
        cancelAutoHide()
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        dialog.setContentView(buildPickerDialog("Quality", labels) { idx ->
            selectedMaxHeight = heights[idx]
            qualityBtn.text = labels[idx]
            exoPlayer?.trackSelectionParameters = exoPlayer!!.trackSelectionParameters
                .buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, heights[idx])
                .build()
            dialog.dismiss()
            scheduleAutoHide()
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { scheduleAutoHide() }
        dialog.show()
    }

    // ── Server picker (replaces the old Spinner) ──────────────────────────────
    private fun showServerPicker() {
        cancelAutoHide()
        val serverNames = servers.mapIndexed { i, s ->
            val marker = when {
                i == currentServerIndex -> "▶  "
                i < currentServerIndex  -> "✓  "
                else                    -> "     "
            }
            "$marker${s.name}"
        }.toTypedArray()

        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        dialog.setContentView(buildPickerDialog("Select Source", serverNames) { idx ->
            if (idx != currentServerIndex) {
                savedPositionMs = exoPlayer?.currentPosition ?: 0L
                releaseExoPlayer()
                showLoadingOverlay()
                loadServer(idx)
            }
            dialog.dismiss()
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener { if (isPlaying) scheduleAutoHide() }
        dialog.show()
    }

    private fun buildPickerDialog(title: String, items: Array<String>, onPick: (Int) -> Unit): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.parseColor("#1E1E1E"), dp(12))
            setPadding(0, dp(16), 0, dp(8))
            minimumWidth = dp(280)
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 11f
            letterSpacing = 0.15f
            setPadding(dp(20), 0, dp(20), dp(12))
        }
        wrapper.addView(titleView)

        val list = ListView(this).apply {
            adapter = object : ArrayAdapter<String>(this@PlayerActivity,
                android.R.layout.simple_list_item_1, items) {
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
                    val tv = super.getView(pos, convertView, parent) as TextView
                    tv.setTextColor(if (pos == currentServerIndex) Color.WHITE else Color.parseColor("#CCCCCC"))
                    tv.textSize = 15f
                    tv.setPadding(dp(20), dp(14), dp(20), dp(14))
                    tv.setBackgroundColor(Color.TRANSPARENT)
                    return tv
                }
            }
            divider = ColorDrawable(Color.parseColor("#2A2A2A"))
            dividerHeight = 1
            setBackgroundColor(Color.TRANSPARENT)
        }
        list.setOnItemClickListener { _, _, pos, _ -> onPick(pos) }
        wrapper.addView(list)
        return wrapper
    }

    // ── Loading UI helpers ────────────────────────────────────────────────────
    private fun setLoadingStatus(msg: String) = handler.post {
        loadingStatus.text = msg
    }

    private fun showError(msg: String) = handler.post {
        stopDotsAnimation()
        loadingDots.visibility = View.GONE
        loadingStatus.visibility = View.GONE
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        loadingOverlay.visibility = View.VISIBLE
        controlsOverlay.visibility = View.GONE
    }

    private fun hideError() = handler.post {
        errorText.visibility = View.GONE
        loadingDots.visibility = View.VISIBLE
        loadingStatus.visibility = View.VISIBLE
    }

    private fun startDotsAnimation() {
        stopDotsAnimation()
        dotsRunnable = object : Runnable {
            private val states = listOf(
                "⬤  ○  ○",
                "○  ⬤  ○",
                "○  ○  ⬤",
                "○  ⬤  ○"
            )
            override fun run() {
                loadingDots.text = states[dotsCount % states.size]
                dotsCount++
                handler.postDelayed(this, 400L)
            }
        }
        handler.post(dotsRunnable!!)
    }

    private fun stopDotsAnimation() {
        dotsRunnable?.let { handler.removeCallbacks(it) }
        dotsRunnable = null
    }

    // ── Ad skip (silent, runs in background while ExoPlayer plays) ────────────
    private fun startAdSkipTimer() {
        cancelAdSkipTimer()
        val js = """(function(){
var ss=['[class*=skip i]','[id*=skip i]','.videoAdUiSkipButton','.ytp-ad-skip-button','[data-purpose*=skip]','[aria-label*="Skip" i]','[title*="Skip" i]'];
for(var i=0;i<ss.length;i++){try{var els=document.querySelectorAll(ss[i]);for(var j=0;j<els.length;j++){var e=els[j];if(e.offsetWidth>0&&!e.disabled){e.click();break;}}}catch(x){}}
var all=document.querySelectorAll('*');for(var k=0;k<all.length;k++){try{var el=all[k];var cs=window.getComputedStyle(el);if((cs.position==='fixed'||cs.position==='absolute')&&(parseInt(cs.zIndex)||0)>999&&!el.querySelector('video')&&el.tagName!=='VIDEO'){el.style.display='none';}}catch(x){}}
})();"""
        adSkipRunnable = object : Runnable {
            override fun run() {
                hiddenWebView.evaluateJavascript(js, null)
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.postDelayed(adSkipRunnable!!, 1_000L)
    }

    private fun cancelAdSkipTimer() {
        adSkipRunnable?.let { handler.removeCallbacks(it) }
        adSkipRunnable = null
    }

    // ── Timeouts ──────────────────────────────────────────────────────────────
    private fun startExtractTimeout() {
        cancelExtractTimeout()
        extractTimeoutRunnable = Runnable {
            if (extractedVideoUrl == null) {
                ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                tryNextServer()
            }
        }
        handler.postDelayed(extractTimeoutRunnable!!, EXTRACT_TIMEOUT_MS)
    }

    private fun cancelExtractTimeout() {
        extractTimeoutRunnable?.let { handler.removeCallbacks(it) }
        extractTimeoutRunnable = null
    }

    private fun startPageLoadTimeout() {
        cancelPageLoadTimeout()
        pageLoadTimeoutRunnable = Runnable {
            if (!pageLoadFailed && extractedVideoUrl == null) {
                pageLoadFailed = true
                ServerManager.markServerDead(servers.getOrNull(currentServerIndex)?.name ?: "")
                tryNextServer()
            }
        }
        handler.postDelayed(pageLoadTimeoutRunnable!!, PAGE_LOAD_TIMEOUT_MS)
    }

    private fun cancelPageLoadTimeout() {
        pageLoadTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pageLoadTimeoutRunnable = null
    }

    // ── ExoPlayer release ─────────────────────────────────────────────────────
    private fun releaseExoPlayer() {
        stopProgressUpdater()
        cancelAdSkipTimer()
        exoPlayer?.release()
        exoPlayer = null
        try { videoCache?.release(); videoCache = null } catch (_: Exception) {}
        try { File(cacheDir, "exo_cache").deleteRecursively() } catch (_: Exception) {}
        isPlaying = false
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        stopProgressUpdater()
        cancelAdSkipTimer()
        hiddenWebView.onPause()
    }

    override fun onResume() {
        super.onResume()
        hiddenWebView.onResume()
        if (isPlaying && !userInitiatedPause) {
            exoPlayer?.play()
            startProgressUpdater()
            startAdSkipTimer()
        }
    }

    override fun onDestroy() {
        cancelExtractTimeout()
        cancelPageLoadTimeout()
        cancelAutoHide()
        stopDotsAnimation()
        releaseExoPlayer()
        hiddenWebView.destroy()
        super.onDestroy()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatMs(ms: Long): String {
        val s = ms / 1000L
        val m = s / 60L
        val h = m / 60L
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
               else       "%d:%02d".format(m, s % 60)
    }

    private fun iconBtn(resId: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(resId)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }

    private fun roundedBg(color: Int, radiusPx: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

}
