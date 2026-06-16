package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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
import com.mariocart.app.data.server.StreamExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"

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

    private var tmdbId = 0
    private var contentType = "movie"
    private var season = 1
    private var episode = 1
    private var title = ""
    private var currentEmbedUrl = ""

    private var extractJob: Job? = null
    private val mutex = Mutex()
    private var videoFound = false

    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false
    private var savedPositionMs = 0L

    private lateinit var rootContainer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var webView: WebView
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var controlsOverlay: LinearLayout
    private lateinit var playPauseBtn: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season = intent.getIntExtra(EXTRA_SEASON, 1)
        episode = intent.getIntExtra(EXTRA_EPISODE, 1)

        buildLayout()
        startMassiveParallelSearch()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildLayout() {
        rootContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }
        playerView = PlayerView(this).apply { useController = false; setBackgroundColor(Color.BLACK); layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        rootContainer.addView(playerView)

        webView = WebView(this).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains(".m3u8") || url.contains(".mp4")) {
                        handler.post { onVideoUrlFound(url, "WebView Sniffer") }
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

    private fun startMassiveParallelSearch() {
        videoFound = false
        extractJob = lifecycleScope.launch {
            setLoadingStatus("Searching all servers at once…")
            ServerManager.initialize(this@PlayerActivity)
            val allServers = ServerManager.getOrderedServers()
            
            // Limit to top 15 servers for stability
            val topServers = allServers.take(15)

            val tasks = topServers.map { server ->
                async {
                    val embed = if (contentType == "movie") server.movieUrl(tmdbId) else server.tvUrl(tmdbId, season, episode)
                    val url = StreamExtractor.extract(embed, tmdbId, contentType, season, episode)
                    if (url != null) {
                        onVideoUrlFound(url, server.name, embed)
                    }
                }
            }
            
            // Also start a sniffer on the #1 server as a fallback
            if (topServers.isNotEmpty()) {
                val bestEmbed = if (contentType == "movie") topServers[0].movieUrl(tmdbId) else topServers[0].tvUrl(tmdbId, season, episode)
                currentEmbedUrl = bestEmbed
                webView.loadUrl(bestEmbed)
            }

            tasks.awaitAll()
            
            delay(10000) // Wait up to 10s for any parallel task or sniffer
            if (!videoFound) {
                setLoadingStatus("Switching to WebView fallback…")
                switchToWebView()
            }
        }
    }

    private fun onVideoUrlFound(videoUrl: String, serverName: String, embedUrl: String = "") {
        lifecycleScope.launch {
            mutex.withLock {
                if (videoFound) return@launch
                videoFound = true
                extractJob?.cancel()
                handler.post {
                    if (embedUrl.isNotEmpty()) currentEmbedUrl = embedUrl
                    playVideo(videoUrl, serverName)
                }
            }
        }
    }

    private fun playVideo(videoUrl: String, serverName: String) {
        releaseExoPlayer()
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        val embedHost = try { "https://${Uri.parse(currentEmbedUrl).host}" } catch (_: Exception) { "" }
        val httpDsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to currentEmbedUrl, "Origin" to embedHost))

        val mi = MediaItem.fromUri(videoUrl)
        val source = if (videoUrl.lowercase().contains(".m3u8")) HlsMediaSource.Factory(httpDsf).createMediaSource(mi)
                     else ProgressiveMediaSource.Factory(httpDsf).createMediaSource(mi)

        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isPlaying = true
                    loadingOverlay.visibility = View.GONE
                    controlsOverlay.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun switchToWebView() {
        loadingOverlay.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun buildLoadingOverlay() = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        val center = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(context).apply { text = title; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(48, 0, 48, 24) })
            loadingStatus = TextView(context).apply { text = "Initializing search…"; setTextColor(Color.GRAY); textSize = 13f; gravity = Gravity.CENTER }
            addView(loadingStatus)
        }
        addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun buildControlsOverlay() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(16, 16, 16, 16)
            playPauseBtn = ImageButton(context).apply { setImageResource(android.R.drawable.ic_media_play); setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE); setOnClickListener { togglePlayPause() } }
            addView(playPauseBtn)
            seekBar = SeekBar(context).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
            addView(seekBar)
            timeLabel = TextView(context).apply { text = "0:00 / 0:00"; setTextColor(Color.WHITE); textSize = 11f }
            addView(timeLabel)
        }
        addView(bar)
    }

    private fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }
    private fun releaseExoPlayer() { exoPlayer?.release(); exoPlayer = null }
    private fun setLoadingStatus(msg: String) { handler.post { loadingStatus.text = msg } }
    override fun onDestroy() { releaseExoPlayer(); webView.destroy(); super.onDestroy() }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
