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
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.server.ServerManager

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TMDB_ID = "tmdb_id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SEASON = "season"
        private const val EXTRA_EPISODE = "episode"
        private const val FALLBACK_TIMEOUT_MS = 12_000L
        private const val AUTO_PLAY_RETRY_MS = 2_000L
        private const val AUTO_PLAY_MAX_RETRIES = 5

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

    private lateinit var playerView: WebView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var serverSpinner: Spinner
    private lateinit var progressBar: ProgressBar
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
    private var progressRunnable: Runnable? = null

    private val handler = Handler(Looper.getMainLooper())
    private var fallbackRunnable: Runnable? = null
    private var autoPlayRunnable: Runnable? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Comprehensive ad domain blocklist (inspired by uBlock Origin + EasyList)
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

    // Substrings found in ad/tracker URL paths
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        setupServerSpinner()
        topBar.addView(serverSpinner)

        val closeBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setPadding(16, 8, 8, 8)
            setOnClickListener { finish() }
        }
        topBar.addView(closeBtn)

        root.addView(topBar)

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(24, 6, 24, 6)
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            visibility = View.GONE
        }
        root.addView(statusText)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6)
        }
        root.addView(progressBar)

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(fullscreenContainer)

        playerView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
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

                    // Block ad URLs always
                    if (isAdUrl(url)) return true

                    // Block ALL main-frame navigations away from embed domain
                    if (request.isForMainFrame && currentEmbedDomain.isNotEmpty()) {
                        val newHost = request.url.host?.lowercase() ?: ""
                        if (newHost.isNotEmpty() && newHost != currentEmbedDomain &&
                            !newHost.endsWith(".$currentEmbedDomain")
                        ) {
                            // This is a redirect away from the embed — block and reload
                            setStatus("Redirect blocked \u2192 reloading...")
                            handler.postDelayed({ reloadCurrentServer() }, 300)
                            return true
                        }
                    }

                    // Block sub-frame navigations to known ad/redirect domains
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
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    pageLoadFailed = false
                    cancelAutoPlayTimer()
                    startFallbackTimer()
                    // Inject CSS early to hide original play button before page renders
                    injectHideOriginalPlayButton(view)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    cancelFallbackTimer()
                    if (!pageLoadFailed) {
                        setStatus("")
                    }
                    // Inject the redirect guard FIRST (before page scripts run their timers)
                    injectRedirectGuard(view)
                    // Then inject ad blocker CSS/DOM removal
                    injectAdBlocker(view)
                    // Hide the embed's original play button
                    injectHideOriginalPlayButton(view)
                    // Then start auto-play attempts
                    autoPlayRetries = 0
                    startAutoPlayTimer()
                    // Mark as playing once auto-play kicks in
                    isPlaying = true
                    handler.post { playPauseBtn.setImageResource(android.R.drawable.ic_media_pause) }
                    // Start progress bar updater
                    startProgressUpdater()
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
                    playerView.visibility = View.GONE
                }

                override fun onHideCustomView() {
                    fullscreenContainer.removeAllViews()
                    fullscreenContainer.visibility = View.GONE
                    playerView.visibility = View.VISIBLE
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
        root.addView(playerView)

        // ---- Progress bar (seek bar) ----
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
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

        root.addView(seekRow)

        // ---- Control buttons row ----
        controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(16, 8, 16, 16)
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

        // Spacer
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

        root.addView(controlBar)

        setContentView(root)

        // Auto-play: load first server immediately
        loadServer(0)
    }

    // JS helper that finds the video element (checks page + all same-origin iframes)
    private val findVideoJs = """
        (function(){
            var v = document.querySelector('video');
            if(v) return v;
            var iframes = document.querySelectorAll('iframe');
            for(var i=0;i<iframes.length;i++){
                try {
                    var fv = iframes[i].contentDocument.querySelector('video');
                    if(fv) return fv;
                } catch(e){}
            }
            return null;
        })()
    """

    private fun runVideoCommand(js: String) {
        playerView.evaluateJavascript("""
            (function(){
                var findVideo = function() {
                    var v = document.querySelector('video');
                    if(v) return v;
                    var iframes = document.querySelectorAll('iframe');
                    for(var i=0;i<iframes.length;i++){
                        try {
                            var fv = iframes[i].contentDocument.querySelector('video');
                            if(fv) return fv;
                        } catch(e){}
                    }
                    return null;
                };
                var v = findVideo();
                if(v) { $js }
            })();
        """.trimIndent(), null)
    }

    private fun togglePlayPause() {
        if (isPlaying) {
            runVideoCommand("v.pause();")
            isPlaying = false
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
        } else {
            runVideoCommand("v.play();")
            isPlaying = true
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun skipVideo(seconds: Int) {
        runVideoCommand("v.currentTime += $seconds;")
    }

    private fun seekVideo(progressValue: Int) {
        runVideoCommand("if(v.duration) v.currentTime = v.duration * ($progressValue / 1000.0);")
    }

    private fun startProgressUpdater() {
        stopProgressUpdater()
        progressRunnable = object : Runnable {
            override fun run() {
                if (isSeeking) {
                    handler.postDelayed(this, 1000)
                    return
                }
                playerView.evaluateJavascript("""
                    (function(){
                        var findVideo = function() {
                            var v = document.querySelector('video');
                            if(v) return v;
                            var iframes = document.querySelectorAll('iframe');
                            for(var i=0;i<iframes.length;i++){
                                try {
                                    var fv = iframes[i].contentDocument.querySelector('video');
                                    if(fv) return fv;
                                } catch(e){}
                            }
                            return null;
                        };
                        var v = findVideo();
                        if(v && v.duration) {
                            return JSON.stringify({
                                current: v.currentTime,
                                duration: v.duration,
                                paused: v.paused
                            });
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
                                timeText.text = "${'$'}{formatTime(current.toInt())} / ${'$'}{formatTime(duration.toInt())}"

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
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(progressRunnable!!, 2000)
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

    private fun toggleFullscreen() {
        if (isFullscreen) {
            playerView.evaluateJavascript("""
                (function(){
                    if(document.exitFullscreen) document.exitFullscreen();
                    else if(document.webkitExitFullscreen) document.webkitExitFullscreen();
                })();
            """.trimIndent(), null)
            exitCustomFullscreen()
        } else {
            runVideoCommand("""
                if(v.requestFullscreen) v.requestFullscreen();
                else if(v.webkitRequestFullscreen) v.webkitRequestFullscreen();
                else if(v.webkitEnterFullscreen) v.webkitEnterFullscreen();
            """)
            enterCustomFullscreen()
        }
    }

    private fun enterCustomFullscreen() {
        isFullscreen = true
        topBar.visibility = View.GONE
        statusText.visibility = View.GONE
        seekRow.visibility = View.GONE
        controlBar.visibility = View.GONE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun exitCustomFullscreen() {
        isFullscreen = false
        topBar.visibility = View.VISIBLE
        seekRow.visibility = View.VISIBLE
        controlBar.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    /**
     * Inject the redirect guard script — inspired by Chrome extensions like
     * "Redirect Shield Pro", "Popup Blocker (strict)", and the
     * aetherly-embed-guard project. This intercepts:
     *   - window.open → returns a fake window object
     *   - location.assign / location.replace → blocks cross-origin
     *   - anchor clicks with target=_blank/_top/_parent → cancelled
     *   - HTMLFormElement.submit with external targets → cancelled
     *   - HTMLAnchorElement.prototype.click → blocked if external
     *   - setTimeout/setInterval → clears click context for delayed popups
     *   - meta refresh tags → removed
     *   - beforeunload/unload handlers → disabled
     *   - MutationObserver to catch future injected redirect elements
     */
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

    // 1. Replace window.open with fake window (like aetherly-embed-guard)
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

    // 2. Block location.assign and location.replace for external URLs
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

    // 3. Intercept setting location.href directly
    try {
        var loc = location;
        var origHref = Object.getOwnPropertyDescriptor(location.__proto__, 'href');
        if (origHref && origHref.set) {
            Object.defineProperty(location, 'href', {
                get: function() { return origHref.get.call(loc); },
                set: function(v) {
                    if (!isExternal(v)) origHref.set.call(loc, v);
                },
                configurable: false
            });
        }
    } catch(e) {}

    // 4. Block anchor clicks to external or _blank targets
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

    // 5. Block programmatic anchor clicks
    var origAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function() {
        var t = (this.getAttribute('target') || '').toLowerCase();
        if (t === '_blank' || t === '_top' || t === '_parent') return;
        if (this.href && isExternal(this.href)) return;
        return origAnchorClick.apply(this, arguments);
    };

    // 6. Block form submissions to external targets
    var origFormSubmit = HTMLFormElement.prototype.submit;
    HTMLFormElement.prototype.submit = function() {
        var t = (this.getAttribute('target') || '').toLowerCase();
        if (t === '_blank' || t === '_top' || t === '_parent') return;
        var action = this.getAttribute('action') || '';
        if (action && isExternal(action)) return;
        return origFormSubmit.apply(this, arguments);
    };

    // 7. Block alert/confirm/prompt
    window.alert = function() {};
    window.confirm = function() { return false; };
    window.prompt = function() { return null; };

    // 8. Kill onbeforeunload / onunload handlers
    window.onbeforeunload = null;
    window.onunload = null;
    Object.defineProperty(window, 'onbeforeunload', { get: function(){ return null; }, set: function(){}, configurable: false });

    // 9. Remove meta refresh tags that cause redirects
    var metas = document.querySelectorAll('meta[http-equiv="refresh"]');
    metas.forEach(function(m) { m.remove(); });

    // 10. Remove onclick/onmousedown on body that open popups
    document.body.onclick = null;
    document.body.onmousedown = null;
    document.body.onmouseup = null;
    document.body.onauxclick = null;
    document.body.oncontextmenu = null;

    // 11. MutationObserver to catch future redirect/popup injections
    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(m) {
            m.addedNodes.forEach(function(node) {
                if (node.nodeType !== 1) return;
                // Remove meta refresh tags
                if (node.tagName === 'META' && node.getAttribute('http-equiv') === 'refresh') {
                    node.remove();
                    return;
                }
                // Remove scripts that contain redirect patterns
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
                // Remove high z-index overlays that aren't video-related
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

    /**
     * Remove ad DOM elements and overlay divs
     */
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

    // Remove high z-index positioned overlays
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

    // Remove invisible iframes (tracking pixels)
    document.querySelectorAll('iframe').forEach(function(f) {
        if (f.offsetWidth <= 1 || f.offsetHeight <= 1) f.remove();
    });
})();
        """.trimIndent(), null)
    }

    /**
     * Hide ONLY the fake overlay play buttons that streaming sites use to
     * hijack clicks and trigger redirects/popups. The real video player
     * controls (control bar play/pause, progress bar, etc.) are kept intact.
     *
     * Fake overlays are typically:
     *  - Large centered elements covering the entire video area
     *  - Have onclick/onmousedown handlers that call window.open
     *  - Are positioned absolute/fixed with high z-index on top of the video
     */
    private fun injectHideOriginalPlayButton(view: WebView?) {
        view?.evaluateJavascript("""
(function(){
    if(window.__fakePlayHidden) return;
    window.__fakePlayHidden = true;

    // Only target elements that are large overlays on top of the player,
    // NOT the small control-bar buttons.
    function isFakeOverlay(el) {
        try {
            var st = window.getComputedStyle(el);
            var pos = st.position;
            // Must be positioned (absolute/fixed) — control bar buttons are inline/relative
            if (pos !== 'absolute' && pos !== 'fixed') return false;
            // Must be large — fake overlays cover most of the video area
            if (el.offsetWidth < 80 || el.offsetHeight < 80) return false;
            // Check if it has redirect-related event handlers
            var onclick = el.getAttribute('onclick') || '';
            var onmousedown = el.getAttribute('onmousedown') || '';
            if (onclick.includes('window.open') || onmousedown.includes('window.open') ||
                onclick.includes('location') || onmousedown.includes('location')) {
                return true;
            }
            // Check if it's a high-z overlay sitting on top of a video
            var z = parseInt(st.zIndex) || 0;
            if (z > 100 && el.offsetWidth > 150 && el.offsetHeight > 100) {
                // Make sure it's not a real video or player container
                if (!el.querySelector('video') && el.tagName !== 'VIDEO' &&
                    !el.classList.contains('vjs-control-bar') &&
                    !el.classList.contains('jw-controlbar') &&
                    !el.classList.contains('plyr__controls')) {
                    return true;
                }
            }
            return false;
        } catch(e) { return false; }
    }

    // Scan and remove fake overlays
    function removeFakeOverlays() {
        var all = document.querySelectorAll('div, a, span, button');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (isFakeOverlay(el)) {
                el.style.pointerEvents = 'none';
                el.style.opacity = '0';
                el.style.display = 'none';
            }
        }
    }

    // Run immediately and after short delays (for dynamically injected overlays)
    removeFakeOverlays();
    setTimeout(removeFakeOverlays, 1000);
    setTimeout(removeFakeOverlays, 3000);

    // Also try inside same-origin iframes
    try {
        document.querySelectorAll('iframe').forEach(function(f){
            try {
                var fd = f.contentDocument;
                if(fd) {
                    var all = fd.querySelectorAll('div, a, span, button');
                    for (var i = 0; i < all.length; i++) {
                        if (isFakeOverlay(all[i])) {
                            all[i].style.pointerEvents = 'none';
                            all[i].style.opacity = '0';
                            all[i].style.display = 'none';
                        }
                    }
                }
            } catch(e){}
        });
    } catch(e){}

    // MutationObserver to catch future fake overlays
    if (document.body) {
        var obs = new MutationObserver(function(mutations) {
            mutations.forEach(function(m) {
                m.addedNodes.forEach(function(node) {
                    if (node.nodeType === 1 && isFakeOverlay(node)) {
                        node.style.pointerEvents = 'none';
                        node.style.opacity = '0';
                        node.style.display = 'none';
                    }
                });
            });
        });
        obs.observe(document.body, { childList: true, subtree: true });
    }
})();
        """.trimIndent(), null)
    }

    /**
     * Auto-play: aggressively find and click play buttons, then force
     * play on any video elements. Retries multiple times.
     */
    private fun injectAutoPlay(view: WebView?) {
        view?.evaluateJavascript("""
(function() {
    // Try clicking play buttons with many selectors
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
        '[class*="start"]', '[class*="Start"]',
        '.play', '#play', '.playButton', '#playButton',
        'div[role="button"]'
    ];
    
    var clicked = false;
    for (var i = 0; i < playSelectors.length; i++) {
        try {
            var btns = document.querySelectorAll(playSelectors[i]);
            for (var j = 0; j < btns.length; j++) {
                var btn = btns[j];
                if (btn.offsetWidth > 0 && btn.offsetHeight > 0) {
                    btn.click();
                    clicked = true;
                    break;
                }
            }
            if (clicked) break;
        } catch(e) {}
    }

    // Force play on all video elements
    var videos = document.querySelectorAll('video');
    videos.forEach(function(v) {
        try {
            v.muted = false;
            v.autoplay = true;
            v.play().catch(function() {
                // Some browsers require muted autoplay
                v.muted = true;
                v.play().catch(function(){});
            });
        } catch(e) {}
    });

    // Also try to find video inside iframes (same-origin only)
    try {
        var iframes = document.querySelectorAll('iframe');
        iframes.forEach(function(f) {
            try {
                var fdoc = f.contentDocument || f.contentWindow.document;
                if (fdoc) {
                    var fvideos = fdoc.querySelectorAll('video');
                    fvideos.forEach(function(v) {
                        try {
                            v.muted = false;
                            v.autoplay = true;
                            v.play().catch(function(){
                                v.muted = true;
                                v.play().catch(function(){});
                            });
                        } catch(e) {}
                    });
                    // Click play buttons inside iframe
                    for (var i = 0; i < playSelectors.length; i++) {
                        try {
                            var btns = fdoc.querySelectorAll(playSelectors[i]);
                            for (var j = 0; j < btns.length; j++) {
                                if (btns[j].offsetWidth > 0) { btns[j].click(); break; }
                            }
                        } catch(e) {}
                    }
                }
            } catch(e) {} // Cross-origin iframe, skip
        });
    } catch(e) {}
    
    // Return whether we found any video
    return document.querySelectorAll('video').length > 0;
})();
        """.trimIndent(), null)
    }

    private fun startAutoPlayTimer() {
        cancelAutoPlayTimer()
        autoPlayRunnable = Runnable {
            autoPlayRetries++
            if (autoPlayRetries <= AUTO_PLAY_MAX_RETRIES) {
                injectAutoPlay(playerView)
                // Also re-inject ad blocker to catch newly loaded ads
                injectAdBlocker(playerView)
                handler.postDelayed(autoPlayRunnable!!, AUTO_PLAY_RETRY_MS)
            }
        }
        // First auto-play attempt after 1 second (give page time to render player)
        handler.postDelayed(autoPlayRunnable!!, 1000)
    }

    private fun cancelAutoPlayTimer() {
        autoPlayRunnable?.let { handler.removeCallbacks(it) }
        autoPlayRunnable = null
    }

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

        playerView.loadUrl(currentEmbedUrl)
    }

    private fun reloadCurrentServer() {
        if (currentEmbedUrl.isNotEmpty()) {
            setStatus("\u23F3 Reloading...")
            playerView.loadUrl(currentEmbedUrl)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullscreen) {
                exitCustomFullscreen()
                return true
            }
            if (customView != null) {
                playerView.webChromeClient?.onHideCustomView()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        playerView.onPause()
        cancelAutoPlayTimer()
        stopProgressUpdater()
    }

    override fun onResume() {
        super.onResume()
        playerView.onResume()
    }

    override fun onDestroy() {
        cancelFallbackTimer()
        cancelAutoPlayTimer()
        stopProgressUpdater()
        playerView.destroy()
        super.onDestroy()
    }
}
