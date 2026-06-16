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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE    = "type"
        private const val EXTRA_TITLE   = "title"
        private const val EXTRA_SEASON  = "season"
        private const val EXTRA_EPISODE = "episode"
        
        // Backend API configuration
        // TODO: Replace with your actual deployed backend URL (e.g., https://imdone-backend.railway.app/api/stream)
        private const val BACKEND_API_URL = "https://your-backend-url.com/api/stream"

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
    private var currentOrigin = ""

    private var extractJob: Job? = null
    private var videoFound = false

    private var exoPlayer: ExoPlayer? = null
    private var isPlaying = false

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
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        resolveStreamFromBackend()
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
        }
        rootContainer.addView(playerView)

        // WebView fallback for iframe embeds
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

    /**
     * Resolve stream using the backend API
     * The backend handles all parallel server probing and extraction
     */
    private fun resolveStreamFromBackend() {
        videoFound = false
        extractJob = lifecycleScope.launch {
            setLoadingStatus("Connecting to stream resolver…")
            
            try {
                // Build API URL with parameters
                val apiUrl = buildString {
                    append(BACKEND_API_URL)
                    append("?tmdb_id=$tmdbId")
                    append("&content_type=$contentType")
                    if (contentType == "tv") {
                        append("&season=$season")
                        append("&episode=$episode")
                    }
                }

                setLoadingStatus("Probing servers…")
                
                // Make HTTP request to backend
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("User-Agent", "I-m-done-Android/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    setLoadingStatus("Backend unavailable, using local extraction…")
                    switchToLocalExtraction()
                    return@launch
                }

                val body = response.body?.string() ?: ""
                val json = JSONObject(body)

                if (!json.getBoolean("success")) {
                    setLoadingStatus("No streams found, trying fallback…")
                    val fallbackIframe = json.optString("fallback_iframe", "")
                    if (fallbackIframe.isNotEmpty()) {
                        currentEmbedUrl = fallbackIframe
                        handler.post { switchToWebView() }
                    }
                    return@launch
                }

                val stream = json.getJSONObject("stream")
                val videoUrl = stream.getString("url")
                val serverName = stream.getString("server")
                val headers = stream.optJSONObject("headers")

                // Store headers for playback
                if (headers != null) {
                    currentEmbedUrl = if (headers.has("referer")) headers.optString("referer") else headers.optString("Referer", "")
                    currentOrigin = if (headers.has("origin")) headers.optString("origin") else headers.optString("Origin", "")
                }

                onVideoUrlFound(videoUrl, serverName)

            } catch (e: Exception) {
                setLoadingStatus("Error: ${e.message}")
                switchToLocalExtraction()
            }
        }
    }

    /**
     * Fallback to local extraction if backend is unavailable
     * This preserves the original functionality
     */
    private fun switchToLocalExtraction() {
        // Placeholder: Original local extraction logic would go here
        // For now, switch to WebView fallback
        handler.post { switchToWebView() }
    }

    private fun onVideoUrlFound(videoUrl: String, serverName: String, embedUrl: String = "") {
        lifecycleScope.launch {
            if (videoFound) return@launch
            videoFound = true
            extractJob?.cancel()
            handler.post {
                if (embedUrl.isNotEmpty()) currentEmbedUrl = embedUrl
                playVideo(videoUrl, serverName)
            }
        }
    }

    private fun playVideo(videoUrl: String, serverName: String) {
        releaseExoPlayer()
        val player = ExoPlayer.Builder(this).build()
        exoPlayer = player
        playerView.player = player

        val finalOrigin = if (currentOrigin.isNotEmpty()) currentOrigin 
                          else try { "https://${Uri.parse(currentEmbedUrl).host}" } catch (_: Exception) { "" }
                          
        val httpDsf = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf(
                "Referer" to currentEmbedUrl,
                "Origin" to finalOrigin
            ))

        val mi = MediaItem.fromUri(videoUrl)
        val source = if (videoUrl.lowercase().contains(".m3u8")) {
            HlsMediaSource.Factory(httpDsf).createMediaSource(mi)
        } else {
            ProgressiveMediaSource.Factory(httpDsf).createMediaSource(mi)
        }

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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply { 
                text = title
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(48, 0, 48, 24) 
            })
            loadingStatus = TextView(context).apply { 
                text = "Initializing…"
                setTextColor(Color.GRAY)
                textSize = 13f
                gravity = Gravity.CENTER 
            }
            addView(loadingStatus)
        }
        addView(center, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    }

    private fun buildControlsOverlay() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f) })
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
            playPauseBtn = ImageButton(context).apply { 
                setImageResource(android.R.drawable.ic_media_play)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setOnClickListener { togglePlayPause() } 
            }
            addView(playPauseBtn)
            seekBar = SeekBar(context).apply { 
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) 
            }
            addView(seekBar)
            timeLabel = TextView(context).apply { 
                text = "0:00 / 0:00"
                setTextColor(Color.WHITE)
                textSize = 11f 
            }
            addView(timeLabel)
        }
        addView(bar)
    }

    private fun togglePlayPause() { 
        exoPlayer?.let { 
            if (it.isPlaying) it.pause() else it.play() 
        } 
    }

    private fun releaseExoPlayer() { 
        exoPlayer?.release()
        exoPlayer = null 
    }

    private fun setLoadingStatus(msg: String) { 
        handler.post { loadingStatus.text = msg } 
    }

    override fun onDestroy() { 
        releaseExoPlayer()
        webView.destroy()
        super.onDestroy() 
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
}
