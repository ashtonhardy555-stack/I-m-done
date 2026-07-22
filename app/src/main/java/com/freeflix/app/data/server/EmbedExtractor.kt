package com.freeflix.app.data.server

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * EmbedExtractor — extracts a direct, in-app playable video URL
 * (`.m3u8` / `.mp4`) from an embed provider's HTML page by running the
 * page's JavaScript video player inside an **off-screen WebView**.
 *
 * ## Why a WebView?
 * Providers such as VidLink, VidSrc, 2Embed … serve an HTML page whose
 * JavaScript (hls.js / video.js) builds and requests the real stream URL
 * at runtime. That URL is not present in the static HTML, so plain HTTP
 * cannot retrieve it. Running the page in a real WebView lets the JS
 * execute (which also auto-solves Cloudflare's "Just a moment" JS
 * challenge), and we intercept the resulting media request.
 *
 * ## How the direct URL is captured
 * A custom [WebViewClient.shouldInterceptRequest] inspects **every**
 * resource the page loads. When a request URL looks like a playable
 * manifest/stream (`.m3u8`, `.mp4`, `/playlist`, `/master`, …) it is
 * captured as the playable URL. ExoPlayer then plays it natively — the
 * WebView is never used for playback.
 *
 * ## Captcha / human verification
 * If the page presents a real human challenge (reCAPTCHA, hCaptcha,
 * Cloudflare turnstile that needs a click) that JS alone cannot pass,
 * extraction returns [Result.Challenge] so the caller can surface a
 * verification WebView to the user (see
 * [com.freeflix.app.ui.player.VerificationActivity]). Once the user
 * solves it, cookies are stored in the app-wide [CookieManager] and a
 * retry picks them up automatically. This keeps the only-WebView-for-
 * captchas rule: the off-screen extraction WebView is invisible; the
 * only WebView the user ever sees is the verification one.
 *
 * The class is intentionally framework-light: it owns a single WebView
 * per extraction and tears it down on completion.
 */
class EmbedExtractor private constructor(
    private val context: Context,
    @Suppress("unused") private val onChallengeNeeded: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "EmbedExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** How long to wait for a media URL before giving up on a provider. */
        private const val EXTRACT_TIMEOUT_MS = 18_000L

        /**
         * How many providers to race in parallel per wave. More = faster (more
         * chances of an instant hit) but uses more memory (one off-screen
         * WebView per concurrent provider). 4 is a good balance for mobile.
         */
        private const val RACE_BATCH = 4

        /**
         * Grace period after a challenge is detected before we declare it
         * needs human verification. Cloudflare JS challenges ("just a
         * moment") auto-solve within a few seconds; if a stream URL is
         * captured during this window we treat it as auto-solved.
         */
        private const val CHALLENGE_GRACE_MS = 12_000L

        /**
         * Extract a direct playable URL from the given embed [url].
         *
         * @param context an Activity context (WebView requires an Activity
         *        Context to create its window).
         * @param url the embed page URL (e.g. https://vidlink.pro/movie/123)
         * @param onChallengeNeeded optional callback invoked on the main
         *        thread when the WebView should be surfaced to the user to
         *        solve a human-verification challenge.
         */
        suspend fun extract(
            context: Context,
            url: String,
            onChallengeNeeded: (() -> Unit)? = null
        ): Result = EmbedExtractor(context, onChallengeNeeded).extractFromUrl(url)

        /**
         * Try every provider (from the auto-updating list, ordered by
         * ServerManager) for a piece of content, returning the first direct
         * playable URL found.
         *
         * The server list comes from [ServerManager.getOrderedServers], which
         * (a) puts the user-selected server first if the user pinned one, and
         * (b) orders by per-session health + persistent success scores +
         * remote reliability. This means the user's chosen server is tried
         * first, then historically-good servers, then fallbacks.
         *
         * ## Speed: parallel racing (the key optimisation)
         * Providers are tried **in parallel waves** instead of one-by-one:
         *
         *   Wave 1 — the top [RACE_BATCH] providers race concurrently. The
         *            first one to return a [Result.Stream] wins immediately;
         *            every other race in the wave is cancelled.
         *
         *   Wave 2+ — if wave 1 produced only challenges / errors, the next
         *            [RACE_BATCH] providers race. This continues until all
         *            providers are exhausted.
         *
         * Because WebView extraction of a single provider already waits up to
         * [EXTRACT_TIMEOUT_MS] for the page's JS player to emit its stream,
         * racing the best providers simultaneously turns a worst-case
         * `servers × 18 s` sequential wait into a single ~18 s wave for the
         * first handful. This is the single biggest speed-up for the user.
         *
         * Challenge handling strategy: providers are ordered clean-first, so
         * the no-challenge providers (VidLink, 2Embed) are tried before any
         * Cloudflare-protected one. A [Result.Challenge] from a provider is NOT
         * returned immediately — it is remembered and we keep trying the
         * remaining providers. Only if NO provider yields a playable stream do
         * we surface the best challenge we found to the user. This way a single
         * Cloudflare-protected provider never blocks the user from getting a
         * video that a clean provider could have delivered.
         *
         * The id of the provider that delivered the stream is recorded on the
         * returned [Result.Stream] so the UI can show which server worked.
         */
        suspend fun extractFromProviders(
            context: Context,
            contentType: String,
            tmdbId: Int,
            season: Int = 1,
            episode: Int = 1,
            onChallengeNeeded: (() -> Unit)? = null
        ): Result {
            // Load the auto-updating, health-ordered server list.
            ServerManager.initialize(context)
            val providers = ServerManager.getOrderedServers()
            if (providers.isEmpty()) {
                Log.e(TAG, "No servers available — falling back to bundled list.")
                ServerManager.initialize(context, forceRefresh = true)
            }
            val list = ServerManager.getOrderedServers()
            if (list.isEmpty()) {
                return Result.Error("No stream servers available.")
            }

            var bestChallenge: Result.Challenge? = null

            // Race providers in parallel waves for speed.
            var i = 0
            while (i < list.size) {
                val wave = list.subList(i, minOf(i + RACE_BATCH, list.size))
                i += wave.size
                Log.d(TAG, "🏁 Racing wave: ${wave.joinToString { it.name }}")

                // Launch every provider in this wave concurrently inside a
                // coroutineScope so the scope is not leaked. Each provider has
                // its own internal timeout (EXTRACT_TIMEOUT_MS), so a wave
                // cannot run longer than that regardless of provider count.
                val waveResults = coroutineScope {
                    wave.map { provider ->
                        async {
                            val url = provider.urlFor(contentType, tmdbId, season, episode)
                            Log.d(TAG, "↻ Trying ${provider.name} (${provider.id}, tier=${provider.tier}): $url")
                            try {
                                val r = extract(context, url, onChallengeNeeded)
                                Pair(provider, r)
                            } catch (e: Exception) {
                                Log.w(TAG, "✗ ${provider.name}: ${e.message}")
                                Pair(provider, Result.Error(e.message ?: "extraction failed"))
                            }
                        }
                    }.awaitAll()
                }

                // Process wave results — first Stream wins immediately.
                for ((provider, r) in waveResults) {
                    when (r) {
                        is Result.Stream -> {
                            Log.i(TAG, "✅ ${provider.name} → ${r.url}")
                            ServerManager.markServerSuccess(provider.id)
                            return r.copy(providerName = provider.name, providerId = provider.id)
                        }
                        is Result.Challenge -> {
                            Log.w(TAG, "🤖 ${provider.name} requires verification — deferring, trying next provider")
                            if (bestChallenge == null) bestChallenge = r
                            // Don't mark dead — the provider is alive, just challenged.
                        }
                        is Result.Error, Result.NotFound -> {
                            Log.w(TAG, "✗ ${provider.name}: ${(r as? Result.Error)?.message ?: "not found"}")
                            ServerManager.markServerDead(provider.id)
                        }
                    }
                }
            }

            if (bestChallenge != null) {
                Log.w(TAG, "🤖 No clean stream; surfacing challenge for user verification.")
                return bestChallenge
            }
            return Result.Error("No playable stream found from any provider.")
        }
    }

    // ────────────────────────────────────────────────────────────────── //
    //  Result type                                                         //
    // ────────────────────────────────────────────────────────────────── //

    sealed class Result {
        /** A direct playable URL (+ headers ExoPlayer should send). */
        data class Stream(
            val url: String,
            val headers: Map<String, String> = emptyMap(),
            val providerName: String = "",
            /** The server id that delivered the stream (for the UI / scoring). */
            val providerId: String = ""
        ) : Result()

        /**
         * A human-verification challenge was detected. The caller should
         * launch a verification WebView at [challengeUrl] (using
         * [embedUrl] as the referer). After the user solves it, cookies
         * will be in [CookieManager]; retry extraction to pick them up.
         */
        data class Challenge(
            val cookies: String = "",
            val challengeUrl: String,
            val embedUrl: String
        ) : Result()

        /** The provider returned nothing useful. */
        object NotFound : Result()

        /** Extraction failed with a message. */
        data class Error(val message: String) : Result()
    }

    // ────────────────────────────────────────────────────────────────── //
    //  Extraction                                                          //
    // ────────────────────────────────────────────────────────────────── //

    private val mainHandler = Handler(Looper.getMainLooper())
    private val capturedUrl = AtomicReference<String?>(null)
    private val capturedHeaders = ConcurrentHashMap<String, String>()
    private var webView: WebView? = null
    private var challengeVisible = false
    private var timedOut = false

    // Stored so challenge-detection callbacks (which run on the main thread
    // outside the suspendCancellableCoroutine lambda) can resume the
    // coroutine and clean up the view.
    private var continuation: CancellableContinuation<Result>? = null
    private var rootView: FrameLayout? = null
    private var currentEmbedUrl: String = ""

    // A tiny okhttp client to (optionally) verify a captured URL is actually
    // reachable before declaring success — avoids handing ExoPlayer a 403.
    private val verifier = OkHttpClient.Builder()
        .followRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractFromUrl(url: String): Result =
        suspendCancellableCoroutine { cont ->
            // All WebView work MUST happen on the main thread.
            mainHandler.post {
                if (cont.isCancelled) return@post

                // Store for challenge callbacks.
                continuation = cont
                currentEmbedUrl = url

                CookieManager.getInstance().setAcceptCookie(true)

                val root = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                }
                rootView = root

                // Off-screen: 1x1 px. We use VISIBLE (not INVISIBLE) because
                // some WebView implementations defer JS rendering/layout for
                // INVISIBLE views, which can prevent the video player from
                // initializing and fetching its source. At 1x1 px it is
                // effectively invisible to the user. Human-verification
                // challenges are handled by the caller via
                // VerificationActivity, not by enlarging this WebView.
                root.visibility = View.VISIBLE

                val wv = WebView(context).also { webView = it }
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    userAgentString = USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                wv.webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null
                        // Capture the first thing that looks like a real media
                        // manifest / stream. This runs for every sub-resource
                        // the player requests (m3u8, mp4, ts segments, …).
                        tryCaptureMediaUrl(reqUrl, request.requestHeaders ?: emptyMap())
                        return null // let the WebView load it normally
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        pageUrl ?: return
                        Log.d(TAG, "onPageFinished: $pageUrl")
                        // Kick the player: many embeds start on a poster and
                        // only build the source after a play click or a short
                        // delay. We attempt an auto-click + a direct probe.
                        autoStartPlayer(view)
                        // Also check if this page is a captcha challenge.
                        if (capturedUrl.get() == null) {
                            checkForChallenge(view, pageUrl)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        // Only care about the main frame failing.
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "main frame error: ${error?.description}")
                            // Fail fast: if the embed page itself didn't
                            // load (404, DNS, TLS error), don't waste the
                            // full timeout waiting. Move to the next provider.
                            if (capturedUrl.get() == null && !challengeVisible && cont.isActive) {
                                mainHandler.post {
                                    if (capturedUrl.get() == null && !challengeVisible &&
                                        cont.isActive
                                    ) {
                                        timedOut = true
                                        cleanupAndResume(Result.NotFound)
                                    }
                                }
                            }
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        // Main-frame HTTP error (e.g. 403, 404, 500) — fail
                        // fast unless it might be a Cloudflare challenge page.
                        if (request?.isForMainFrame == true) {
                            val code = errorResponse?.statusCode ?: 0
                            Log.w(TAG, "main frame HTTP error: $code")
                            // A 403 from Cloudflare is a challenge, not a
                            // dead page — let the challenge detection handle
                            // it. Other 4xx/5xx = dead, move on fast.
                            if (code != 403 && capturedUrl.get() == null &&
                                !challengeVisible && cont.isActive
                            ) {
                                mainHandler.post {
                                    if (capturedUrl.get() == null && !challengeVisible &&
                                        cont.isActive
                                    ) {
                                        timedOut = true
                                        cleanupAndResume(Result.NotFound)
                                    }
                                }
                            }
                        }
                    }
                }

                wv.webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(
                        consoleMessage: android.webkit.ConsoleMessage?
                    ): Boolean {
                        // Keep quiet; some players log the resolved stream.
                        val msg = consoleMessage?.message()
                        if (msg != null &&
                            (msg.contains(".m3u8") || msg.contains(".mp4"))
                        ) {
                            tryCaptureMediaUrl(msg, emptyMap())
                        }
                        return true
                    }
                }

                root.addView(wv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))

                // Attach the off-screen root to the activity's window decor
                // so the WebView actually runs. We add it to the decor view's
                // root with 1x1 size and INVISIBLE so it never shows.
                try {
                    val decor = (context as? android.app.Activity)
                        ?.window?.decorView as? android.view.ViewGroup
                    decor?.addView(root, FrameLayout.LayoutParams(1, 1).apply {
                        gravity = Gravity.CENTER
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Could not attach off-screen WebView: ${e.message}")
                }

                // Load the embed URL.
                wv.loadUrl(url)

                // Timeout: if no media URL captured, report challenge or
                // give up. This always resumes the coroutine (never hangs).
                mainHandler.postDelayed({
                    if (cont.isActive && capturedUrl.get() == null) {
                        timedOut = true
                        if (challengeVisible) {
                            // A challenge was detected and the grace period
                            // timer (started in checkForChallenge) is handling
                            // the resolution. Don't double-resume here.
                            return@postDelayed
                        }
                        cleanupAndResume(Result.NotFound)
                    }
                }, EXTRACT_TIMEOUT_MS)

                // Poll for a captured URL and verify it before resuming.
                val poller = object : Runnable {
                    override fun run() {
                        val candidate = capturedUrl.get()
                        if (candidate != null && cont.isActive) {
                            // Stop polling; verify on a background thread.
                            mainHandler.removeCallbacks(this)
                            verifyAndResume(candidate)
                            return
                        }
                        if (cont.isActive && !timedOut) {
                            mainHandler.postDelayed(this, 600)
                        }
                    }
                }
                mainHandler.post(poller)

                cont.invokeOnCancellation { cleanupView(root) }
            }
        }

    // ────────────────────────────────────────────────────────────────── //
    //  Media-URL capture                                                   //
    // ────────────────────────────────────────────────────────────────── //

    private fun tryCaptureMediaUrl(url: String, headers: Map<String, String>) {
        if (capturedUrl.get() != null) return // already have one
        if (!looksLikeMedia(url)) return
        // Save headers (Referer / User-Agent / Origin) so ExoPlayer can
        // replay them — some CDNs require the Referer of the embed host.
        if (headers.isNotEmpty()) {
            headers.forEach { (k, v) -> capturedHeaders[k] = v }
        }
        // Ensure a Referer/UA fallback if the request didn't include them.
        capturedHeaders.putIfAbsent("User-Agent", USER_AGENT)
        // Use the embed page as Referer if the request didn't carry one.
        if (currentEmbedUrl.isNotBlank()) {
            capturedHeaders.putIfAbsent("Referer", currentEmbedUrl)
        }
        Log.d(TAG, "🎯 captured media candidate: $url")
        capturedUrl.set(url)
    }

    private fun looksLikeMedia(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http")) return false
        // Reject HLS segment fragments (.ts / .m4s) — we want the manifest,
        // not individual segments. ExoPlayer needs the .m3u8 playlist.
        if (u.endsWith(".ts") || u.contains(".ts?")) return false
        if (u.endsWith(".m4s") || u.contains(".m4s?")) return false
        if (u.endsWith(".aac") || u.contains(".aac?")) return false
        // Reject obvious non-media (ads, tracking, analytics, fonts).
        if (u.contains("/ads/") || u.contains("google") || u.contains("doubleclick")) return false
        if (u.contains("analytics") || u.contains("tracker")) return false
        if (u.endsWith(".js") || u.endsWith(".css") || u.endsWith(".png") ||
            u.endsWith(".jpg") || u.endsWith(".svg") || u.endsWith(".woff")) return false

        // Strong direct-media signals — manifests & container files.
        if (u.contains(".m3u8")) return true
        if (u.endsWith(".mp4") || u.contains(".mp4?")) return true
        if (u.endsWith(".mkv") || u.contains(".mkv?")) return true
        if (u.endsWith(".webm") || u.contains(".webm?")) return true
        if (u.endsWith(".mpd") || u.contains(".mpd?")) return true
        // HLS/DASH playlist path patterns.
        if (u.contains("/playlist") && u.contains("m3u8")) return true
        if (u.contains("/master")) return true
        if (u.contains("manifest") && (u.contains("m3u8") || u.contains("mpd"))) return true
        // VidLink-style direct mp resource.
        if (u.contains("/mp/resource/") && u.contains(".mp4")) return true
        // Some providers serve HLS from a path like /hls/ or /play/ with no
        // file extension — accept if the path strongly implies a stream.
        if (u.contains("/hls/") && !u.endsWith(".ts")) return true
        return false
    }

    // ────────────────────────────────────────────────────────────────── //
    //  Auto-start + captcha detection                                      //
    // ────────────────────────────────────────────────────────────────── //

    private fun autoStartPlayer(view: WebView?) {
        view ?: return
        // Click any obvious play button + call play() on the video element,
        // including inside same-origin iframes (2Embed nests iframes).
        // Cross-origin iframes can't be reached from here, but the
        // shouldInterceptRequest still captures their media requests.
        val js = (
            "(function(){function kick(doc){" +
            "  try { var v = doc.querySelector('video');" +
            "    if (v) { v.muted=true; var p=v.play(); if(p&&p.then)p.catch(function(){}); }" +
            "  } catch(e){}" +
            "  try { var b = doc.querySelector('[class*=\"play\" i],button.play,.vjs-big-play-button,[id*=\"play\" i]');" +
            "    if (b) b.click();" +
            "  } catch(e){}" +
            "  try { var f = doc.querySelectorAll('iframe');" +
            "    for (var i=0;i<f.length;i++){" +
            "      try { kick(f[i].contentWindow.document); } catch(e){}" +
            "    }" +
            "  } catch(e){}" +
            "}" +
            "kick(document);" +
            "})()"
        )
        mainHandler.post { view.evaluateJavascript(js, null) }
        // Re-attempt after a short delay — some players initialize their
        // source asynchronously and the first kick comes too early.
        mainHandler.postDelayed({ view.evaluateJavascript(js, null) }, 2500)
        mainHandler.postDelayed({ view.evaluateJavascript(js, null) }, 6000)
    }

    private fun checkForChallenge(view: WebView?, url: String) {
        val js = (
            "(function(){" +
            "  try { var h = (document.documentElement.outerHTML || '').toLowerCase();" +
            "    return (" +
            "      h.indexOf('g-recaptcha') !== -1 ||" +
            "      h.indexOf('data-sitekey') !== -1 ||" +
            "      h.indexOf('recaptcha/api.js') !== -1 ||" +
            "      h.indexOf('h-captcha') !== -1 ||" +
            "      h.indexOf('hcaptcha.com') !== -1 ||" +
            "      h.indexOf('cf-challenge') !== -1 ||" +
            "      h.indexOf('cf-turnstile') !== -1 ||" +
            "      h.indexOf('just a moment') !== -1 ||" +
            "      h.indexOf('attention required') !== -1 ||" +
            "      h.indexOf('access denied') !== -1" +
            "    ).toString();" +
            "  } catch(e) { return 'false'; }" +
            "})()"
        )
        mainHandler.post {
            view?.evaluateJavascript(js) { result ->
                if ("true".equals(result, ignoreCase = true)) {
                    scheduleChallengeReport(url)
                }
            }
        }
    }

    /**
     * A human-verification challenge was detected. Give the page a short
     * grace period — Cloudflare JS challenges auto-solve in a few seconds
     * and may still produce a stream URL. If nothing is captured after the
     * grace period, resume the coroutine with [Result.Challenge] so the
     * caller can surface a verification WebView to the user.
     */
    private fun scheduleChallengeReport(challengeUrl: String) {
        if (challengeVisible) return
        challengeVisible = true
        Log.w(TAG, "🤖 Challenge detected at $challengeUrl — waiting up to " +
            "${CHALLENGE_GRACE_MS}ms for auto-solve…")

        mainHandler.postDelayed({
            // If a stream URL was captured during the grace period, the
            // poller already handled it — nothing to do.
            if (capturedUrl.get() != null) return@postDelayed
            val cont = continuation ?: return@postDelayed
            if (cont.isActive) {
                Log.w(TAG, "🤖 Challenge not auto-solved — reporting to caller.")
                cleanupAndResume(
                    Result.Challenge(
                        cookies = CookieManager.getInstance().getCookie(challengeUrl) ?: "",
                        challengeUrl = challengeUrl,
                        embedUrl = currentEmbedUrl
                    )
                )
            }
        }, CHALLENGE_GRACE_MS)
    }

    // ────────────────────────────────────────────────────────────────── //
    //  Verify + resume                                                     //
    // ────────────────────────────────────────────────────────────────── //

    private fun verifyAndResume(candidate: String) {
        Thread {
            // The URL was captured from a real WebView request that the
            // provider's JS player made — it already succeeded in that
            // context. We attempt a lightweight verification, but we do NOT
            // discard the URL if verification fails: OkHttp here has no
            // access to the WebView's cookies/CF tokens, so a 403 from the
            // CDN does NOT mean the URL won't play in ExoPlayer when the
            // captured headers (Referer/UA/Origin) are replayed. ExoPlayer's
            // DefaultHttpDataSource will carry those headers.
            val verified = runCatching {
                val builder = Request.Builder()
                    .url(candidate)
                    .header("User-Agent", capturedHeaders["User-Agent"] ?: USER_AGENT)
                    .apply {
                        capturedHeaders["Referer"]?.let { header("Referer", it) }
                        capturedHeaders["Origin"]?.let { header("Origin", it) }
                    }
                // Try a ranged GET that reads only the first 2 bytes. This
                // works for most CDNs and confirms reachability + media type.
                val getResp = verifier.newCall(
                    builder.get().header("Range", "bytes=0-1").build()
                ).execute()
                val code = getResp.code
                val ct = getResp.header("Content-Type") ?: ""
                getResp.close()
                // 200/206 = reachable. 416 = range issue but URL valid.
                // 403/401 = likely needs cookies ExoPlayer will have via
                //   headers — don't discard. Any non-5xx response means the
                //   host is up; trust the WebView capture.
                code in 200..299 || code == 416 || code == 403 ||
                    code == 401 || code == 405
            }.getOrDefault(false)

            // Also extract cookies from the WebView's CookieManager for the
            // captured URL's host, so ExoPlayer can replay them (needed for
            // Cloudflare-protected CDNs that set cf_clearance cookies).
            val cookieHost = runCatching {
                java.net.URI(candidate).host
            }.getOrNull()
            val cookies = if (cookieHost != null) {
                mainHandler.let { h ->
                    val cookieJar = java.util.concurrent.CountDownLatch(1)
                    var result = ""
                    h.post {
                        result = CookieManager.getInstance()
                            .getCookie("https://$cookieHost") ?: ""
                        cookieJar.countDown()
                    }
                    cookieJar.await(2, TimeUnit.SECONDS)
                    result
                }
            } else ""

            mainHandler.post {
                // Build the final headers. If we got cookies from the WebView,
                // add them so ExoPlayer can access cookie-gated CDNs.
                val finalHeaders = capturedHeaders.toMap().toMutableMap()
                if (cookies.isNotBlank()) {
                    finalHeaders["Cookie"] = cookies
                }
                // Always resume with a Stream — the URL came from a real
                // WebView player request. If it fails in ExoPlayer, the
                // caller will fall back to the next provider anyway.
                val msg = if (verified) "verified" else "unverified (trusted WebView capture)"
                Log.d(TAG, "▶ resuming with $msg stream; cookies=${cookies.length} chars")
                cleanupAndResume(
                    Result.Stream(
                        url = candidate,
                        headers = finalHeaders,
                        providerName = "",
                        providerId = ""
                    )
                )
            }
        }.start()
    }

    /**
     * Tears down the off-screen WebView and resumes the coroutine with
     * [result]. Safe to call from the main thread; guards against
     * double-resume.
     */
    private fun cleanupAndResume(result: Result) {
        val cont = continuation ?: return
        val root = rootView
        continuation = null
        rootView = null
        cleanupView(root)
        if (cont.isActive) {
            cont.resume(result)
        }
    }

    private fun cleanupView(root: FrameLayout?) {
        mainHandler.post {
            runCatching {
                (root?.parent as? android.view.ViewGroup)?.removeView(root)
            }
            runCatching { webView?.destroy() }
            webView = null
        }
    }
}
