package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mariocart.app.data.api.ApiClient
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager
import kotlinx.coroutines.launch

/**
 * PlayerActivity — Hybrid player supporting native ExoPlayer and WebView fallback.
 * 
 * It first attempts to find a direct stream via the API for instant playback.
 * If no direct stream is found, it falls back to manual server selection in a WebView.
 */
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

    private var tmdbId      = 0
    private var contentType = "movie"
    private var season      = 1
    private var episode     = 1
    private var videoTitle  = ""

    private lateinit var webView:       WebView
    private lateinit var playerView:    PlayerView
    private var exoPlayer:    ExoPlayer? = null
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingText:   TextView
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isVideoIntercepted = false
    private var currentServerIndex = -1
    private var autoTryServers = true
    private var discoveryJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        tmdbId      = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        videoTitle  = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season      = intent.getIntExtra(EXTRA_SEASON, 1)
        episode     = intent.getIntExtra(EXTRA_EPISODE, 1)

        setupLayout()
        startDiscovery()
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun setupLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            useController = true
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setupWebView(this)
        }

        loadingText = TextView(this).apply {
            text = "Finding best stream…"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            textSize = 18f
        }

        loadingOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(loadingText)
        }

        // Server selection button (always available)
        val serverButton = TextView(this).apply {
            text = "Servers"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(30, 15, 30, 15)
            textSize = 14f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 50
                rightMargin = 50
            }
            setOnClickListener { showServerSelection() }
        }

        root.addView(playerView)
        root.addView(webView)
        root.addView(loadingOverlay)
        root.addView(serverButton)
        setContentView(root)
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            try {
                loadingText.text = "Checking direct sources..."
                val response = ApiClient.streamingBackendApi.getStream(tmdbId, contentType, season, episode)
                if (response.success && response.url != null) {
                    playNative(response.url)
                } else {
                    tryNextServer()
                }
            } catch (e: Exception) {
                tryNextServer()
            }
        }
    }

    private fun tryNextServer() {
        if (!autoTryServers) return
        
        val servers = ServerManager.getOrderedServers()
        currentServerIndex++
        
        if (currentServerIndex < servers.size) {
            val server = servers[currentServerIndex]
            loadingText.text = "Trying ${server.name} (${currentServerIndex + 1}/${servers.size})..."
            loadServerInWebView(server)
            
            // Give each server 15 seconds to find a video before moving to the next
            lifecycleScope.launch {
                kotlinx.coroutines.delay(15000)
                if (!isVideoIntercepted && autoTryServers && currentServerIndex < servers.size - 1) {
                    tryNextServer()
                } else if (!isVideoIntercepted && currentServerIndex >= servers.size - 1) {
                    loadingText.text = "All servers tried. Please select manually."
                    showServerSelection()
                }
            }
        } else {
            showServerSelection()
        }
    }

    private fun playNative(url: String) {
        if (exoPlayer != null) {
            exoPlayer?.release()
            exoPlayer = null
        }
        
        loadingOverlay.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Only fallback if we haven't already intercepted something successfully
                    if (!isVideoIntercepted) {
                        fallbackToWebView()
                    }
                }
            })
        }
        playerView.player = exoPlayer
    }

    private fun fallbackToWebView() {
        if (exoPlayer != null) {
            exoPlayer?.release()
            exoPlayer = null
        }
        playerView.visibility = View.GONE
        loadingText.text = "Direct stream unavailable. Switching to manual selection…"
        
        ServerManager.initialize(this)
        showServerSelection()
    }

    private fun showServerSelection() {
        autoTryServers = false // Stop auto-cycling if user interacts
        val servers = ServerManager.getOrderedServers()
        val serverNames = servers.map { it.name }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Select Server")
            .setItems(serverNames) { _, which ->
                currentServerIndex = which
                loadServerInWebView(servers[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadServerInWebView(server: StreamingServer) {
        val embedUrl = if (contentType == "movie") {
            server.movieUrl(tmdbId)
        } else {
            server.tvUrl(tmdbId, season, episode)
        }

        isVideoIntercepted = false
        
        // If we're playing something, stop it
        exoPlayer?.stop()
        playerView.visibility = View.GONE
        
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "Loading ${server.name}…"

        webView.visibility = View.VISIBLE
        webView.stopLoading()
        webView.loadUrl(embedUrl)
        webView.webViewClient = buildWebViewClient()
        webView.webChromeClient = buildWebChromeClient()
    }

    private fun buildWebViewClient() = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            loadingOverlay.visibility = View.GONE
            injectCleanupScript(view)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            
            // Block ads/trackers
            if (isAdOrTracker(url)) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }

            // Intercept video streams
            if (!isVideoIntercepted && (url.contains(".m3u8") || url.contains(".mp4"))) {
                // Check if it's likely a real video and not an ad
                if (!url.contains("ads") && !url.contains("google") && !url.contains("doubleclick")) {
                    runOnUiThread {
                        handleInterceptedVideo(url)
                    }
                }
            }

            return null
        }
    }

    private fun handleInterceptedVideo(url: String) {
        if (isVideoIntercepted) return
        isVideoIntercepted = true
        
        Log.d("PlayerActivity", "Intercepted video URL: $url")
        
        // Stop WebView
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.visibility = View.GONE
        
        // Play natively
        playNative(url)
    }

    private fun buildWebChromeClient() = object : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            customView = view
            customViewCallback = callback
            (window.decorView as FrameLayout).addView(customView)
            webView.visibility = View.GONE
            hideSystemUI()
        }

        override fun onHideCustomView() {
            (window.decorView as FrameLayout).removeView(customView)
            customView = null
            customViewCallback?.onCustomViewHidden()
            webView.visibility = View.VISIBLE
            hideSystemUI()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        
        web.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onVideoFound(url: String) {
                runOnUiThread { handleInterceptedVideo(url) }
            }
        }, "Android")
    }

    private fun injectCleanupScript(view: WebView?) {
        val script = """
            (function() {
                // 1. Remove common ad overlays and popups
                const selectors = [
                    '[class*="ad-"]', '[id*="ad-"]', '.ad-unit', '.overlay', 
                    '.pop-under', '.popup', '#popunder', '#pop-under'
                ];
                selectors.forEach(s => {
                    document.querySelectorAll(s).forEach(el => el.remove());
                });

                // 2. Monitor for video elements
                function checkVideos() {
                    const videos = document.getElementsByTagName('video');
                    for (let v of videos) {
                        if (v.src && v.src.startsWith('http')) {
                            window.Android.onVideoFound(v.src);
                        }
                        const sources = v.getElementsByTagName('source');
                        for (let s of sources) {
                            if (s.src && s.src.startsWith('http')) {
                                window.Android.onVideoFound(s.src);
                            }
                        }
                    }
                }
                
                // 3. Auto-click play buttons to trigger video loading
                function triggerPlay() {
                    const playButtons = [
                        '.play-button', '.vjs-big-play-button', '.jw-display-icon-container',
                        '[aria-label="Play"]', '.play-icon'
                    ];
                    playButtons.forEach(s => {
                        const btn = document.querySelector(s);
                        if (btn) btn.click();
                    });
                }

                setInterval(checkVideos, 1000);
                setTimeout(triggerPlay, 2000);
                
                // 4. Block window.open to prevent popups
                window.open = function() { return null; };
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    private fun isAdOrTracker(url: String): Boolean {
        val adDomains = listOf(
            "ads", "tracker", "analytics", "doubleclick", "popads", "popcash",
            "propellerads", "adsterra", "exoclick", "juicyads", "onclickads",
            "ad-delivery", "mads", "trafficjunky", "clksite", "clknr", "clkrev"
        )
        val lowerUrl = url.lowercase()
        
        // Don't block video streams themselves
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4")) return false
        
        return adDomains.any { lowerUrl.contains(it) }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK") { _, _ -> finish() }.show()
    }

    override fun onDestroy() {
        exoPlayer?.release()
        webView.destroy()
        super.onDestroy()
    }
}
