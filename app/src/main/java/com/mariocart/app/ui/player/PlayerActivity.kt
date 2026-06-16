package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.app.Dialog
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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
import com.mariocart.app.data.server.ServerTester
import com.mariocart.app.data.server.StreamExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"

        private const val MIN_DURATION_MS    = 5 * 60 * 1000L
        private const val EXTRACT_TIMEOUT_MS = 15_000L

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

    // ── Extraction state ──────────────────────────────────────────────────────
    private var extractJob: Job? = null
    private var isWebViewMode = false

    // ── Player state ─────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var isSeeking = false
    private var userInitiatedPause = false
    private var savedPositionMs = 0L
    private var selectedMaxHeight = Int.MAX_VALUE

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var rootContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var loadingDots: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var qualityBtn: TextView
    private lateinit var errorText: TextView

    // ── Handler / runnables ───────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    private var dotsRunnable: Runnable? = null
    private var progressRunnable: Runnable? = null
    private var dotsCount = 0

    private val adDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "adservice.google",
        "adnxs.com", "outbrain.com", "taboola.com", "popads.net", "popcash.net",
        "onclickads.net", "exoclick.com", "juicyads.com", "clksite.com"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season = intent.getIntExtra(EXTRA_SEASON, 1)
        episode = intent.getIntExtra(EXTRA_EPISODE, 1)

        buildLayout()
        initServersAndPlay()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout() {
        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setOnClickListener { if (!isWebViewMode) toggleControlsVisibility() }
        }
        rootContainer.addView(playerView)

        webView = WebView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: ""
                    // Block all redirects to different hosts (ad-prevention)
                    val embedHost = Uri.parse(currentEmbedUrl).host ?: ""
                    val targetHost = Uri.parse(url).host ?: ""
                    return if (targetHost.isNotEmpty() && !targetHost.contains(embedHost)) {
                        android.util.Log.d("WebView", "Blocked redirect to: $url")
                        true
                    } else false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString()?.lowercase() ?: ""
                    
                    // 1. AD BLOCKING
                    if (adDomains.any { url.contains(it) }) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                    }

                    // 2. SNIFFER: Catch video URLs in traffic
                    if (url.contains(".m3u8") || url.contains(".mp4") || url.contains("/hls/") || url.contains("/stream/")) {
                        if (!isWebViewMode && !url.contains("google.com") && !url.contains("gstatic.com")) {
                            handler.post { onVideoUrlFound(request?.url?.toString()!!) }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
        rootContainer.addView(webView)

        loadingOverlay = buildLoadingOverlay()
        rootContainer.addView(loadingOverlay)
        controlsOverlay = buildControlsOverlay()
        rootContainer.addView(controlsOverlay)

        setContentView(rootContainer)
    }

    private fun initServersAndPlay() {
        startDotsAnimation()
        ServerManager.resetHealth()
        extractJob = lifecycleScope.launch {
            setLoadingStatus("Checking direct sources…")
            val directUrl = withTimeoutOrNull(10_000L) {
                StreamExtractor.extractDirect(tmdbId, contentType, season, episode)
            }
            if (directUrl != null) {
                onVideoUrlFound(directUrl)
                return@launch
            }

            setLoadingStatus("Checking servers…")
            ServerManager.initialize(this@PlayerActivity)
            val raw = ServerManager.getOrderedServers()
            servers = ServerTester.rankForContent(raw, tmdbId, contentType, season, episode)
            loadServer(0)
        }
    }

    private fun loadServer(index: Int) {
        if (index >= servers.size) {
            showError("No working stream found.\nTap SOURCE to pick manually.")
            return
        }
        currentServerIndex = index
        extractJob?.cancel()
        releaseExoPlayer()
        isWebViewMode = false
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")

        val server = servers[index]
        currentEmbedUrl = if (contentType == "movie") server.movieUrl(tmdbId)
                          else server.tvUrl(tmdbId, season, episode)

        setLoadingStatus("Trying ${server.name}…")
        hideError()

        // Sniffing & Scraping in parallel
        webView.loadUrl(currentEmbedUrl)
        
        extractJob = lifecycleScope.launch {
            val videoUrl = withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
                StreamExtractor.extract(currentEmbedUrl, tmdbId, contentType, season, episode)
            }
            if (videoUrl != null) {
                onVideoUrlFound(videoUrl)
            } else {
                // If extraction fails, wait a few more seconds for the sniffer, then switch to WebView
                handler.postDelayed({
                    if (exoPlayer == null && !isWebViewMode) {
                        switchToWebView()
                    }
                }, 5000L)
            }
        }
    }

    private fun switchToWebView() {
        isWebViewMode = true
        loadingOverlay.visibility = View.GONE
        controlsOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // Force the webview to stay in full screen and handle its own playback
        android.util.Log.d("Player", "Switched to Protected WebView mode for $currentEmbedUrl")
    }

    private fun onVideoUrlFound(videoUrl: String) {
        if (exoPlayer != null || isWebViewMode) return
        
        ServerManager.markServerSuccess(servers.getOrNull(currentServerIndex)?.name ?: "")
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        val embedHost = try { "https://${Uri.parse(currentEmbedUrl).host}" } catch (_: Exception) { "" }
        val httpDsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to currentEmbedUrl, "Origin" to embedHost))

        val mi = MediaItem.fromUri(videoUrl)
        val source = if (videoUrl.lowercase().contains(".m3u8"))
            HlsMediaSource.Factory(httpDsf).createMediaSource(mi)
        else
            ProgressiveMediaSource.Factory(httpDsf).createMediaSource(mi)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    if (player.duration in 1L until MIN_DURATION_MS) {
                        releaseExoPlayer(); tryNextServer()
                    } else {
                        isPlaying = true; showPlayerControls(); startProgressUpdater(); resumeFromSavedPosition()
                    }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                releaseExoPlayer(); tryNextServer()
            }
        })
    }

    private fun tryNextServer() {
        val next = currentServerIndex + 1
        if (next < servers.size) loadServer(next)
        else showError("All sources tried.\nTap SOURCE to pick manually.")
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    private fun buildLoadingOverlay(): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(48, 0, 48, 24) })
            loadingDots = TextView(context).apply { text = "⬤  ○  ○"; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
            loadingStatus = TextView(context).apply { text = "Finding a stream…"; setTextColor(Color.GRAY); textSize = 13f; gravity = Gravity.CENTER; setPadding(48, 20, 48, 0) }
            errorText = TextView(context).apply { setTextColor(Color.RED); textSize = 13f; gravity = Gravity.CENTER; visibility = View.GONE }
            addView(loadingDots); addView(loadingStatus); addView(errorText)
        }
        addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun buildControlsOverlay(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        // Simplified for brevity - actual implementation should include full controls
        addView(View(this@PlayerActivity).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) })
        val bar = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            playPauseBtn = ImageButton(this@PlayerActivity).apply { setImageResource(android.R.drawable.ic_media_play); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setOnClickListener { togglePlayPause() } }
            addView(playPauseBtn)
            seekBar = SeekBar(this@PlayerActivity).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
            addView(seekBar)
            timeLabel = TextView(this@PlayerActivity).apply { text = "0:00 / 0:00"; setTextColor(Color.WHITE); textSize = 11f }
            addView(timeLabel)
        }
        addView(bar)
    }

    private fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    private fun toggleControlsVisibility() {
        controlsOverlay.visibility = if (controlsOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showPlayerControls() { loadingOverlay.visibility = View.GONE; controlsOverlay.visibility = View.VISIBLE }
    private fun showLoadingOverlay() { controlsOverlay.visibility = View.GONE; loadingOverlay.visibility = View.VISIBLE }
    private fun hideError() { errorText.visibility = View.GONE }
    private fun showError(msg: String) { errorText.text = msg; errorText.visibility = View.VISIBLE }
    private fun setLoadingStatus(msg: String) { handler.post { loadingStatus.text = msg } }
    private fun startDotsAnimation() { /* Implementation of dots animation */ }
    private fun startProgressUpdater() { /* Implementation of progress bar update */ }
    private fun resumeFromSavedPosition() { exoPlayer?.seekTo(savedPositionMs) }
    private fun releaseExoPlayer() { exoPlayer?.release(); exoPlayer = null; isPlaying = false }

    override fun onDestroy() { releaseExoPlayer(); webView.destroy(); super.onDestroy() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
