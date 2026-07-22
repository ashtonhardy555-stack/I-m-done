package com.freeflix.app.data.server

import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * LookMovieWebExtractor — resolves a direct playable `.m3u8` stream from
 * LookMovie using the **same flow as the Kodi addon**, but executed inside
 * an off-screen WebView so Cloudflare's "Just a moment" JS challenge is
 * solved automatically (plain HTTP gets a 403 from lookmovie2.to).
 *
 * ## The Kodi flow (mirrored exactly)
 *
 *  1. SEARCH — search the site by title to obtain LookMovie's internal id.
 *        Movies: /movies/search/page/1?q=<title>
 *        Shows : /shows/search/page/1?q=<title>
 *
 *  2. STORAGE — load the /play/{id} page and pull the `movie_storage` or
 *     `show_storage` JS object out of the rendered DOM (hash, id_movie /
 *     id_episode, expires, and for shows the seasons array).
 *
 *  3. SECURITY API — call the security endpoint from inside the WebView via
 *     `fetch()` (so it carries the Cloudflare `cf_clearance` cookie the
 *     WebView just earned):
 *        Movies: /api/v1/security/movie-access?id_movie=&hash=&expires=
 *        Shows : /api/v1/security/episode-access?id_episode=&hash=&expires=
 *     Both require Referer + X-Requested-With: XMLHttpRequest.
 *
 *  4. PLAY — the first non-empty value in the returned `streams` object is
 *     the direct `.m3u8` URL. ExoPlayer plays it (with the storage hash sent
 *     as a `t_hash` cookie on segment requests, exactly like serverHTTP.py).
 *
 * ## Why a WebView (and not the OkHttp StreamExtractor)
 * `lookmovie2.to` sits behind a Cloudflare JS challenge. OkHttp receives a
 * 403 every time. A WebView executes the challenge JavaScript, earns the
 * `cf_clearance` cookie, and all subsequent navigation + the security-API
 * `fetch()` run authenticated. This is what makes streams actually play.
 *
 * ## Human verification
 * If the page presents a *real* human challenge (reCAPTCHA / Cloudflare
 * turnstile that needs a click) that JS alone cannot pass, extraction
 * returns [Result.Challenge] so [com.freeflix.app.ui.player.PlayerActivity]
 * can surface a WebView to the user (via
 * [com.freeflix.app.ui.player.VerificationActivity]). After the user solves
 * it, cookies are in [CookieManager] and a retry picks them up — so the only
 * WebView the user ever sees is for captchas.
 */
class LookMovieWebExtractor private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "LookMovieWeb"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        /** How long to wait for a page / fetch result before giving up. */
        private const val PAGE_TIMEOUT_MS = 25_000L

        /** Give Cloudflare's JS challenge time to auto-solve. */
        private const val CHALLENGE_GRACE_MS = 12_000L

        // LookMovie mirrors — the addon hard-codes lookmovie2.to.
        private val BASES = listOf(
            "https://www.lookmovie2.to",
            "https://lookmovie2.to"
        )

        /**
         * Extract a direct playable stream from LookMovie for the given content.
         *
         * @param context an Activity context (WebView requires it).
         * @param title display title (from TMDB) used to search LookMovie.
         * @param year release year, used to disambiguate search results.
         * @param contentType "movie" or "tv".
         * @param season season number (tv only).
         * @param episode episode number (tv only).
         */
        suspend fun extract(
            context: Context,
            title: String,
            year: String? = null,
            contentType: String,
            season: Int = 1,
            episode: Int = 1
        ): Result = LookMovieWebExtractor(context)
            .doExtract(title, year, contentType, season, episode)
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  Result type                                                        //
    // ─────────────────────────────────────────────────────────────────── //

    sealed class Result {
        /** A direct playable URL + headers ExoPlayer should send. */
        data class Stream(
            val url: String,
            val headers: Map<String, String>
        ) : Result()

        /** A human-verification challenge was detected — surface to user. */
        data class Challenge(
            val challengeUrl: String,
            val referer: String
        ) : Result()

        /** Extraction failed with a message. */
        data class Error(val message: String) : Result()
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  State                                                             //
    // ─────────────────────────────────────────────────────────────────── //

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var rootView: FrameLayout? = null
    private var continuation: CancellableContinuation<Result>? = null

    /**
     * The `hash` value pulled from the `movie_storage` / `show_storage` object.
     * The Kodi addon's proxy (serverHTTP.py) sends this as a `t_hash` cookie on
     * EVERY m3u8 manifest and segment request — without it LookMovie's CDN
     * rejects the stream. We capture it here so the [Result.Stream] headers
     * include `Cookie: t_hash=<hash>`, exactly mirroring the addon.
     */
    @Volatile
    private var storageHash: String? = null

    private suspend fun doExtract(
        title: String,
        year: String?,
        contentType: String,
        season: Int,
        episode: Int
    ): Result {
        for (base in BASES) {
            try {
                val r = runFlow(base, title, year, contentType, season, episode)
                if (r is Result.Stream) return r
                if (r is Result.Challenge) return r
                // Error -> try next mirror
            } catch (e: Exception) {
                Log.w(TAG, "Flow failed on $base: ${e.message}")
            }
        }
        return Result.Error("No stream found for \"$title\" from LookMovie.")
    }

    /**
     * Runs the full Kodi flow for one mirror inside a single WebView.
     * The WebView stays alive across the search -> play -> security-api steps
     * so the Cloudflare cookie persists.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runFlow(
        base: String,
        title: String,
        year: String?,
        contentType: String,
        season: Int,
        episode: Int
    ): Result = suspendCancellableCoroutine { cont ->
        mainHandler.post {
            if (cont.isCancelled) return@post
            continuation = cont
            storageHash = null // reset per flow attempt

            CookieManager.getInstance().setAcceptCookie(true)

            val root = FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            rootView = root

            val wv = WebView(context).also { webView = it }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode =
                    android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                blockNetworkImage = true // faster; we don't need images
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    // Capture any direct .m3u8 the page requests at runtime —
                    // some LookMovie mirrors inject the stream directly into
                    // the player instead of (or in addition to) the security
                    // API. If we see one, we can short-circuit to playback.
                    val reqUrl = request?.url?.toString() ?: return null
                    if (reqUrl.contains(".m3u8") || reqUrl.contains(".mp4")) {
                        maybeCaptureStream(reqUrl, base)
                    }
                    return null
                }
            }

            root.addView(wv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Attach off-screen 1x1 so the WebView actually runs its JS.
            try {
                val decor = (context as? android.app.Activity)
                    ?.window?.decorView as? android.view.ViewGroup
                decor?.addView(root, FrameLayout.LayoutParams(1, 1).apply {
                    gravity = Gravity.CENTER
                })
            } catch (e: Exception) {
                Log.w(TAG, "Could not attach WebView: ${e.message}")
            }

            cont.invokeOnCancellation { cleanup() }

            // ── Kick off the flow on a background coroutine, posting WebView
            //    commands to the main thread. Each step awaits a result via
            //    its own continuation-like latch.
            startFlow(wv, base, title, year, contentType, season, episode, cont)
        }
    }

    private fun startFlow(
        wv: WebView,
        base: String,
        title: String,
        year: String?,
        contentType: String,
        season: Int,
        episode: Int,
        cont: CancellableContinuation<Result>
    ) {
        // Step 1: prime cookies + Cloudflare clearance by loading the base.
        wv.loadUrl(base)

        // After the base page settles (Cloudflare solved), run the search.
        mainHandler.postDelayed({
            if (cont.isCancelled) return@postDelayed
            runSearchStep(wv, base, title, year, contentType, season, episode, cont)
        }, 6000L) // give Cloudflare JS challenge time to clear
    }

    /**
     * Step 2: load the search page and extract LookMovie's internal id from
     * the rendered DOM via JS (mirrors the addon's search regex).
     */
    private fun runSearchStep(
        wv: WebView,
        base: String,
        title: String,
        year: String?,
        contentType: String,
        season: Int,
        episode: Int,
        cont: CancellableContinuation<Result>
    ) {
        val searchPath = if (contentType == "tv")
            "/shows/search/page/1?q=" else "/movies/search/page/1?q="
        val searchUrl = "$base$searchPath${URLEncoder.encode(title, "UTF-8")}"

        loadAndExtract(
            wv, searchUrl, PAGE_TIMEOUT_MS,
            js = searchResultJs(year),
            onChallenge = { url -> resumeWithChallenge(cont, url, "$base/") },
            onError = { resumeWithError(cont, "Search failed for \"$title\".") }
        ) { raw ->
            val id = parseLookMovieId(raw, year)
            if (id != null) {
                Log.d(TAG, "Found LookMovie id $id for \"$title\"")
                mainHandler.post {
                    if (cont.isActive) {
                        runPlayStep(
                            wv, base, id, contentType, season, episode, cont
                        )
                    }
                }
            } else {
                Log.w(TAG, "No LookMovie id found for \"$title\"")
                resumeWithError(cont, "No match found for \"$title\" on LookMovie.")
            }
        }
    }

    /**
     * Step 3: load the /play/{id} page and pull the storage object out of the
     * rendered DOM. Then call the security API via fetch().
     */
    private fun runPlayStep(
        wv: WebView,
        base: String,
        lmId: Int,
        contentType: String,
        season: Int,
        episode: Int,
        cont: CancellableContinuation<Result>
    ) {
        val playUrl = if (contentType == "tv")
            "$base/shows/play/$lmId" else "$base/movies/play/$lmId"

        loadAndExtract(
            wv, playUrl, PAGE_TIMEOUT_MS,
            js = storageJs(),
            onChallenge = { url -> resumeWithChallenge(cont, url, playUrl) },
            onError = { resumeWithError(cont, "Could not load the play page.") }
        ) { raw ->
            val storage = extractStorageBlock(raw, contentType)
            if (storage == null) {
                resumeWithError(cont, "No storage object on the play page.")
                return@loadAndExtract
            }
            val hash = extractField(storage, "hash")
            val expires = extractNumber(storage, "expires")
            if (hash == null || expires == null) {
                resumeWithError(cont, "Incomplete storage object.")
                return@loadAndExtract
            }
            // Capture the storage hash so the returned stream carries the
            // t_hash cookie (mirrors the Kodi addon's serverHTTP.py proxy).
            storageHash = hash

            if (contentType == "tv") {
                val idEpisode = findEpisodeId(storage, season, episode)
                if (idEpisode == null) {
                    resumeWithError(
                        cont, "Episode S${season}E${episode} not found."
                    )
                    return@loadAndExtract
                }
                mainHandler.post {
                    if (cont.isActive) runSecurityApi(
                        wv, base, playUrl, contentType,
                        mapOf("id_episode" to idEpisode, "hash" to hash, "expires" to expires),
                        cont
                    )
                }
            } else {
                val idMovie = extractNumber(storage, "id_movie")
                if (idMovie == null) {
                    resumeWithError(cont, "No id_movie in storage object.")
                    return@loadAndExtract
                }
                mainHandler.post {
                    if (cont.isActive) runSecurityApi(
                        wv, base, playUrl, contentType,
                        mapOf("id_movie" to idMovie, "hash" to hash, "expires" to expires),
                        cont
                    )
                }
            }
        }
    }

    /**
     * Step 4: call the security API from inside the WebView via fetch() so it
     * carries the Cloudflare cf_clearance cookie. Returns the .m3u8 URL.
     */
    private fun runSecurityApi(
        wv: WebView,
        base: String,
        referer: String,
        contentType: String,
        params: Map<String, String>,
        cont: CancellableContinuation<Result>
    ) {
        val apiPath = if (contentType == "tv")
            "/api/v1/security/episode-access" else "/api/v1/security/movie-access"
        val query = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val apiUrl = "$base$apiPath?$query"

        val js = """
            (function(){
              try {
                fetch("$apiUrl", {
                  method: "GET",
                  headers: {
                    "X-Requested-With": "XMLHttpRequest",
                    "Accept": "application/json, text/javascript, */*; q=0.01"
                  },
                  credentials: "include"
                }).then(function(r){ return r.text(); })
                  .then(function(body){
                    __lmSecurityResult = body;
                  })
                  .catch(function(e){
                    __lmSecurityResult = "ERROR:" + e.message;
                  });
              } catch(e) {
                __lmSecurityResult = "ERROR:" + e.message;
              }
            })();
        """.trimIndent()

        mainHandler.post { wv.evaluateJavascript(js, null) }

        // Poll for the injected window variable.
        val poller = object : Runnable {
            override fun run() {
                if (cont.isCancelled) return
                mainHandler.post {
                    wv.evaluateJavascript(
                        "(function(){ try { return __lmSecurityResult; } catch(e){ return null; } })();"
                    ) { value ->
                        if (value != null && value != "null" && value != "\"\"") {
                            handleSecurityResult(value, base, referer, cont)
                        } else if (cont.isActive) {
                            mainHandler.postDelayed(this, 800)
                        }
                    }
                }
            }
        }
        mainHandler.post(poller)

        // Timeout safety.
        mainHandler.postDelayed({
            if (cont.isActive) {
                mainHandler.removeCallbacks(poller)
                resumeWithError(cont, "Timed out contacting the LookMovie security API.")
            }
        }, PAGE_TIMEOUT_MS)
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  Helpers                                                           //
    // ─────────────────────────────────────────────────────────────────── //

    private fun maybeCaptureStream(url: String, base: String) {
        val cont = continuation ?: return
        if (!cont.isActive) return
        if (url.contains(".m3u8") || url.contains(".mp4")) {
            Log.i(TAG, "✅ Captured direct stream from WebView: $url")
            // Use the shared header builder so the t_hash cookie (from the
            // storage object) is attached, exactly like the addon proxy.
            resumeWithStream(cont, url, buildStreamHeaders(base))
        }
    }

    private fun handleSecurityResult(
        value: String,
        base: String,
        referer: String,
        cont: CancellableContinuation<Result>
    ) {
        // evaluateJavascript wraps strings in quotes; unwrap.
        val body = unwrapJsString(value)
        if (body.startsWith("ERROR:")) {
            resumeWithError(cont, "Security API fetch failed: ${body.removePrefix("ERROR:")}")
            return
        }
        val streamUrl = parseSecurityApiJson(body, base)
        if (streamUrl != null) {
            Log.i(TAG, "✅ LookMovie stream: $streamUrl")
            resumeWithStream(cont, streamUrl, buildStreamHeaders(base))
        } else {
            resumeWithError(cont, "No stream URL in the security API response.")
        }
    }

    /**
     * Builds the headers ExoPlayer must send for the LookMovie stream.
     *
     * Mirrors the Kodi addon's `serverHTTP.py`: the storage `hash` is sent
     * as a `t_hash` cookie on the manifest AND every segment request,
     * otherwise LookMovie's CDN rejects playback. We also forward the
     * Cloudflare `cf_clearance` cookie the WebView earned, plus Referer + UA.
     */
    private fun buildStreamHeaders(base: String): Map<String, String> {
        val headers = mutableMapOf(
            "Referer" to "$base/",
            "User-Agent" to USER_AGENT
        )
        val cookieParts = mutableListOf<String>()
        val cfCookies = CookieManager.getInstance().getCookie(base) ?: ""
        if (cfCookies.isNotBlank()) cookieParts.add(cfCookies.trim())
        val tHash = storageHash
        if (!tHash.isNullOrBlank()) {
            // The addon sets cok = {'t_hash': hash_} on the m3u8 GET and
            // sends "Cookie: t_hash="+hash_ on segments. Avoid duplicating
            // t_hash if cf_clearance already supplies one.
            if (!cfCookies.contains("t_hash=")) {
                cookieParts.add("t_hash=$tHash")
            }
        }
        if (cookieParts.isNotEmpty()) {
            headers["Cookie"] = cookieParts.joinToString("; ")
        }
        return headers
    }

    /**
     * Loads [url], waits for the page to finish, runs [js] and returns its
     * result string to [onResult]. Handles Cloudflare challenge detection and
     * page-load timeouts.
     */
    private fun loadAndExtract(
        wv: WebView,
        url: String,
        timeoutMs: Long,
        js: String,
        onChallenge: (String) -> Unit,
        onError: (String) -> Unit,
        onResult: (String) -> Unit
    ) {
        var finished = false
        var challengeScheduled = false

        val client = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                super.onPageFinished(view, pageUrl)
                if (finished) return
                // Give the page a beat, then check for a challenge and run JS.
                mainHandler.postDelayed({
                    if (finished) return@postDelayed
                    view?.evaluateJavascript(challengeDetectJs()) { res ->
                        if ("true".equals(res, ignoreCase = true) && !challengeScheduled) {
                            challengeScheduled = true
                            // Grace period: Cloudflare JS may still auto-solve.
                            mainHandler.postDelayed({
                                if (finished) return@postDelayed
                                view?.evaluateJavascript(challengeDetectJs()) { res2 ->
                                    if ("true".equals(res2, ignoreCase = true)) {
                                        finished = true
                                        onChallenge(pageUrl ?: url)
                                    } else {
                                        finished = true
                                        runJsAndReturn(view, js, onResult)
                                    }
                                }
                            }, CHALLENGE_GRACE_MS)
                        } else {
                            finished = true
                            runJsAndReturn(view, js, onResult)
                        }
                    }
                }, 1500L)
            }
        }
        wv.webViewClient = client
        wv.loadUrl(url)

        mainHandler.postDelayed({
            if (!finished) {
                finished = true
                onError("Timed out loading $url")
            }
        }, timeoutMs)
    }

    private fun runJsAndReturn(
        view: WebView?,
        js: String,
        onResult: (String) -> Unit
    ) {
        view?.evaluateJavascript("(function(){ $js })();") { value ->
            onResult(unwrapJsString(value ?: "null"))
        }
    }

    // ── Resume helpers (guard against double-resume) ── //
    private fun resumeWithStream(
        cont: CancellableContinuation<Result>, url: String, headers: Map<String, String>
    ) {
        cleanup()
        if (cont.isActive) cont.resume(Result.Stream(url, headers))
    }

    private fun resumeWithChallenge(
        cont: CancellableContinuation<Result>, url: String, referer: String
    ) {
        cleanup()
        if (cont.isActive) cont.resume(Result.Challenge(url, referer))
    }

    private fun resumeWithError(cont: CancellableContinuation<Result>, msg: String) {
        cleanup()
        if (cont.isActive) cont.resume(Result.Error(msg))
    }

    private fun cleanup() {
        mainHandler.post {
            runCatching {
                (rootView?.parent as? android.view.ViewGroup)?.removeView(rootView)
            }
            runCatching { webView?.destroy() }
            webView = null
            rootView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────── //
    //  JS snippets                                                       //
    // ─────────────────────────────────────────────────────────────────── //

    /** Returns the page HTML (lowercased) so we can regex it from Kotlin. */
    private fun storageJs(): String =
        "try { return document.documentElement.outerHTML; } catch(e){ return ''; }"

    /**
     * Returns a JSON-ish string of the search results' href+year pairs so
     * Kotlin can pick the best id (mirrors the addon's search).
     */
    private fun searchResultJs(year: String?): String {
        val y = year?.take(4) ?: ""
        return """
            try {
              var out = [];
              var blocks = document.querySelectorAll('div.movie-item, div[class*="movie-item"], div[class*="show-item"]');
              blocks.forEach(function(b){
                var a = b.querySelector('a[href*="/movies/view/"], a[href*="/shows/view/"]');
                if (!a) return;
                var href = a.getAttribute('href') || '';
                var m = href.match(/\/(?:movies|shows)\/view\/(\d+)/);
                if (!m) return;
                var yEl = b.querySelector('[class*="year"]');
                var yr = yEl ? (yEl.textContent || '').trim() : '';
                out.push(m[1] + '|' + yr);
              });
              if (out.length === 0) {
                var as = document.querySelectorAll('a[href*="/movies/view/"], a[href*="/shows/view/"]');
                as.forEach(function(a){
                  var m = (a.getAttribute('href')||'').match(/\/(?:movies|shows)\/view\/(\d+)/);
                  if (m) out.push(m[1] + '|');
                });
              }
              return out.join(',');
            } catch(e){ return ''; }
        """.trimIndent()
    }

    private fun challengeDetectJs(): String = """
        try {
          var h = (document.documentElement.outerHTML || '').toLowerCase();
          return (
            h.indexOf('g-recaptcha') !== -1 ||
            h.indexOf('data-sitekey') !== -1 ||
            h.indexOf('recaptcha/api.js') !== -1 ||
            h.indexOf('cf-challenge') !== -1 ||
            h.indexOf('cf-turnstile') !== -1 ||
            h.indexOf('just a moment') !== -1 ||
            h.indexOf('attention required') !== -1 ||
            h.indexOf('access denied') !== -1
          ).toString();
        } catch(e){ return 'true'; }
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────── //
    //  Parsing (pure Kotlin, mirrors StreamExtractor regexes)            //
    // ─────────────────────────────────────────────────────────────────── //

    private fun parseLookMovieId(raw: String, year: String?): Int? {
        if (raw.isBlank()) return null
        val y = year?.take(4)
        var bestId: Int? = null
        for (entry in raw.split(",")) {
            val parts = entry.split("|")
            val id = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val resultYear = parts.getOrNull(1)?.trim()?.take(4)
            if (y.isNullOrBlank()) {
                bestId = id
                break
            }
            if (resultYear == y) {
                bestId = id
                break
            }
            if (bestId == null) bestId = id
        }
        return bestId
    }

    private fun extractStorageBlock(html: String, contentType: String): String? {
        if (html.isBlank()) return null
        val normalized = html.replace("\\\"", "'").replace("'", "\"")
        val key = if (contentType == "tv") "show_storage" else "movie_storage"
        val regex = Regex("""$key"\]\s*=\s*(\{[\s\S]*?\})""")
        return regex.find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun extractField(text: String, key: String): String? {
        val r = Regex("""$key\s*:\s*"([^"]+)"""")
        return r.find(text)?.groupValues?.getOrNull(1)
    }

    private fun extractNumber(text: String, key: String): String? {
        val r = Regex("""$key\s*:\s*(\d+)""")
        return r.find(text)?.groupValues?.getOrNull(1)
    }

    private fun findEpisodeId(storage: String, season: Int, episode: Int): String? {
        val seasonsRegex = Regex("""seasons\s*:\s*(\[[\s\S]*?\])""")
        val seasonsBlock = seasonsRegex.find(storage)?.groupValues?.getOrNull(1) ?: return null
        val episodeObjRegex = Regex("""\{([^{}]*)\}""")
        for (m in episodeObjRegex.findAll(seasonsBlock)) {
            val obj = m.groupValues[1]
            val s = extractField(obj, "season")?.toIntOrNull() ?: continue
            val e = extractField(obj, "episode")?.toIntOrNull() ?: continue
            if (s == season && e == episode) {
                return extractNumber(obj, "id_episode")
            }
        }
        return null
    }

    private fun parseSecurityApiJson(body: String, base: String): String? {
        // Try JSON parsing first (the addon does .json()).
        return runCatching {
            val json = org.json.JSONObject(body)
            val streams = json.optJSONObject("streams") ?: return null
            val keys = streams.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val streamUrl = streams.optString(key, "")
                if (streamUrl.isNotBlank() && isPlayableManifest(streamUrl)) {
                    return if (streamUrl.startsWith("http")) streamUrl else "$base$streamUrl"
                }
            }
            null
        }.getOrElse {
            val streamsRegex = Regex(""""streams"\s*:\s*\{([^}]*)\}""")
            val streamsBlock = streamsRegex.find(body)?.groupValues?.getOrNull(1) ?: return null
            val urlRegex = Regex(""":\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)""")
            val first = urlRegex.find(streamsBlock)?.groupValues?.getOrNull(1)
            first?.let { if (it.startsWith("http")) it else "$base$it" }
        }
    }

    private fun isPlayableManifest(url: String): Boolean {
        val c = url.lowercase()
        return c.startsWith("http") && (c.contains(".m3u8") || c.contains(".mp4"))
    }

    private fun unwrapJsString(value: String): String {
        // evaluateJavascript returns a JS-literal string, e.g. "\"foo\"".
        if (value == "null" || value == "undefined") return ""
        var v = value
        if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length - 1)
            // Unescape common sequences.
            v = v.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }
        return v
    }
}
