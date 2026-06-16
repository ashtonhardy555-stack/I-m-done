package com.mariocart.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

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
    private var videoTitle = ""
    
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        tmdbId = intent.getIntExtra(EXTRA_TMDB_ID, 0)
        contentType = intent.getStringExtra(EXTRA_TYPE) ?: "movie"
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        season = intent.getIntExtra(EXTRA_SEASON, 1)
        episode = intent.getIntExtra(EXTRA_EPISODE, 1)

        setupLayout()
        loadVideo()
    }

    private fun setupLayout() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.VISIBLE
            setupCleanWebView(this)
        }

        root.addView(webView)
        setContentView(root)
    }

    private fun loadVideo() {
        val embedUrl = if (contentType == "movie") {
            "https://vidsrc.to/embed/movie/$tmdbId"
        } else {
            "https://vidsrc.to/embed/tv/$tmdbId/$season/$episode"
        }
        webView.loadUrl(embedUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupCleanWebView(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        web.webViewClient = object : WebViewClient() {
            // THE "FIRST-CLICK" AD TRAP BLOCKER
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: ""
                val host = request?.url?.host ?: ""
                
                // Essential domains only
                val allowedDomains = listOf("vidsrc.to", "vidsrc.me", "vidlink.pro", "vsembed.ru", "megacloud.live", "vizcloud.co", "2embed")
                val isAllowed = allowedDomains.any { host.contains(it) }
                
                // If it's not an allowed domain, it's almost certainly an ad redirect from the first click
                return if (isAllowed) {
                    false 
                } else {
                    true // BLOCK THE AD REDIRECT
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString()?.lowercase() ?: ""
                val adKeywords = listOf("google-analytics", "doubleclick", "adsystem", "adservice", "popunder", "popup", "vast", "prebid", "proads")
                
                if (adKeywords.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCleanupScript(view)
            }
        }
    }

    private fun injectCleanupScript(view: WebView?) {
        val script = """
            (function() {
                const clean = () => {
                    // 1. Remove ad overlays
                    const selectors = [
                        '.ad-overlay', '.popup-container', '#popunder', 
                        'div[class*="overlay"]', 'div[class*="popup"]',
                        'iframe[src*="ads"]', 'a[href*="click"]',
                        '.fixed-bottom', '.top-ad', '#disclaimer'
                    ];
                    selectors.forEach(s => {
                        document.querySelectorAll(s).forEach(el => el.remove());
                    });

                    // 2. Force click play button
                    const playButtons = [
                        '#play-button', '.play-button', 'div[aria-label="Play"]',
                        '#pl_but', '.vjs-big-play-button', '.play-btn', '.vjs-big-play-button'
                    ];
                    playButtons.forEach(s => {
                        const btn = document.querySelector(s);
                        if (btn && btn.offsetParent !== null) {
                            btn.click();
                            // Dispatch a real click event to bypass some anti-bot checks
                            btn.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, view: window}));
                        }
                    });
                };

                // Continuous cleaning for 15 seconds
                clean();
                let count = 0;
                const interval = setInterval(() => {
                    clean();
                    if (++count > 15) clearInterval(interval);
                }, 1000);

                // Disable window.open to stop popups
                window.open = function() { return null; };
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
