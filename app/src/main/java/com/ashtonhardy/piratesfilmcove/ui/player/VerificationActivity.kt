package com.ashtonhardy.piratesfilmcove.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * VerificationActivity — a WebView that lets the **user** solve a human-
 * verification challenge (reCAPTCHA, Cloudflare "Just a moment", etc.) that
 * LookMovie throws up during stream extraction.
 *
 * The [StreamExtractor] detects the challenge and returns a [StreamExtractor.Result.Challenge].
 * [PlayerActivity] launches this activity with the challenge URL; the user
 * solves the challenge inside the WebView; the resulting session cookies are
 * extracted from [CookieManager] and returned to the caller, which injects them
 * back into the OkHttp cookie jar and retries extraction.
 *
 * This mirrors the Kodi addon's `resolveCaptcha()` flow, except we delegate the
 * actual solving to a real human (via the WebView) instead of attempting
 * automated reCAPTCHA bypass.
 */
class VerificationActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_REFERER = "extra_referer"

        /** Intent-extra key for the cookie string returned on success. */
        const val EXTRA_COOKIES = "extra_cookies"
        /** Intent-extra key for the final URL the WebView landed on. */
        const val EXTRA_FINAL_URL = "extra_final_url"

        // Match the StreamExtractor's User-Agent so cookies set in the WebView
        // are consistent with those expected by OkHttp.
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"

        private const val VERIFICATION_TIMEOUT_MS = 120_000L // 2 minutes

        /**
         * Create a launch intent.
         *
         * @param url     the challenge URL (e.g. the /play/{id} page or a
         *                Cloudflare interstitial URL).
         * @param referer optional Referer to send with the initial load
         *                (matches the extraction flow).
         */
        fun newIntent(context: Context, url: String, referer: String? = null): Intent =
            Intent(context, VerificationActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                referer?.let { putExtra(EXTRA_REFERER, it) }
            }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var timeoutHandler: Handler? = null

    // Guards against double-finish (auto-detect + manual button race).
    @Volatile
    private var solved = false

    // ---------------------------------------------------------------- //
    //  Lifecycle                                                        //
    // ---------------------------------------------------------------- //

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure the WebView's CookieManager persists and accepts cookies
        // (including third-party — reCAPTCHA loads from google.com).
        CookieManager.getInstance().setAcceptCookie(true)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = USER_AGENT
            // reCAPTCHA and Cloudflare challenges pull resources from other
            // origins, so third-party cookies must be allowed.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            // ── TV / D-pad support ──
            // On Google TV the user navigates with a remote (D-pad). The
            // WebView must be focusable so the directional pad can move focus
            // INTO the web content (e.g. to activate a Cloudflare "checkbox"
            // or a reCAPTCHA tile). Without this the remote cannot interact
            // with the challenge at all.
            isFocusable = true
            isFocusableInTouchMode = true

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    if (url == null || solved || isFinishing) return
                    // Give the WebView initial focus so the user can
                    // immediately start navigating the challenge with the
                    // remote on TV. On touch devices this is a no-op.
                    view?.requestFocus()
                    // Inspect the rendered page to see if the challenge is gone.
                    checkIfChallengeSolved(view, url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean = false // let the WebView handle all navigation
            }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        // ── TV-friendly banner ──
        // Larger text + explicit TV/remote instructions so the user on a
        // Google TV box understands they must use the remote to complete the
        // challenge shown below, then move focus to "Done" and press OK.
        val banner = TextView(this).apply {
            text = "⚠ Human verification required.\nUse your remote to complete the challenge below, then select Done."
            setTextColor(Color.WHITE)
            setBackgroundColor(0xE6000000.toInt())
            setPadding(28, 20, 28, 20)
            textSize = 15f
            gravity = Gravity.CENTER
            // Banner is informational, not focusable — keep D-pad focus on
            // the WebView / Done button.
            isFocusable = false
        }

        // ── Manual "Done" button (real Button = D-pad focusable by default) ──
        // Using a real android.widget.Button (not a TextView) so it is
        // focusable and navigable with a TV remote's D-pad out of the box.
        // A visible focus highlight (bright ring + background change) tells
        // the TV user which control is currently selected.
        val doneButton = Button(this).apply {
            text = "Done"
            setTextColor(Color.WHITE)
            setPadding(48, 20, 48, 20)
            textSize = 15f
            // Default (unfocused) appearance.
            background = ColorDrawable(0xFF1976D2.toInt())
            // Explicitly focusable for D-pad navigation on TV.
            isFocusable = true
            isFocusableInTouchMode = true
            // Visible focus highlight so the TV user can see it's selected.
            setOnFocusChangeListener { v, hasFocus ->
                v.background = if (hasFocus) {
                    ColorDrawable(0xFFFFC107.toInt()) // amber = focused
                } else {
                    ColorDrawable(0xFF1976D2.toInt())  // blue = normal
                }
            }
            setOnClickListener { onChallengeSolved(webView.url ?: "") }
        }

        doneButtonRef = doneButton

        val bannerRow = FrameLayout(this).apply {
            addView(banner, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL
            ))
            addView(doneButton, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply { setMargins(0, 6, 12, 6) })
        }

        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(bannerRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.addView(progressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            8, Gravity.BOTTOM
        ))

        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL) ?: run {
            finish()
            return
        }
        val referer = intent.getStringExtra(EXTRA_REFERER)

        setupVerificationTimeout()

        // Load the challenge URL, optionally with a Referer header to match
        // the extraction flow.
        val headers = mutableMapOf<String, String>()
        referer?.let { headers["Referer"] = it }
        webView.loadUrl(url, headers)
    }

    // ---------------------------------------------------------------- //
    //  D-pad / remote key handling (Google TV)                          //
    // ---------------------------------------------------------------- //

    /**
     * On a TV remote the directional pad moves focus between the WebView
     * (where the challenge lives) and the Done button. We help this along:
     *
     *  - DPAD_DOWN from the top banner area moves focus into the WebView.
     *  - DPAD_UP from the WebView moves focus back to the Done button so the
     *    user can confirm after solving.
     *  - KEYCODE_DPAD_CENTER / ENTER while focus is on the WebView submits
     *    any focused element (Cloudflare's checkbox etc.).
     *
     * This keeps the verification flow fully usable with only a remote.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (solved || isFinishing) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Moving up from the WebView → jump to the Done button so the
                // user can finish after completing the challenge.
                if (webView.hasFocus()) {
                    doneButtonRef?.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Moving down from the Done button → drop into the WebView.
                if (doneButtonRef?.hasFocus() == true) {
                    webView.requestFocus()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Holder so onKeyDown can reach the Done button. */
    private var doneButtonRef: Button? = null

    // ---------------------------------------------------------------- //
    //  Challenge detection                                              //
    // ---------------------------------------------------------------- //

    /**
     * Inspects the rendered page content for captcha markers. If none are
     * found AND the URL no longer looks like a challenge URL, we consider the
     * challenge solved.
     *
     * This is more robust than a pure URL check because Cloudflare/reCAPTCHA
     * challenges sometimes stay on the same URL while the page content changes.
     */
    private fun checkIfChallengeSolved(view: WebView?, url: String) {
        val challengeUrl = isChallengeUrl(url)

        // Run a small JS snippet that checks the page's HTML for captcha /
        // Cloudflare markers. Returns the string "true" or "false".
        val js = (
            "(function(){" +
            "  try {" +
            "    var h = (document.documentElement.outerHTML || '').toLowerCase();" +
            "    return (" +
            "      h.indexOf('g-recaptcha') !== -1 ||" +
            "      h.indexOf('data-sitekey') !== -1 ||" +
            "      h.indexOf('recaptcha/api.js') !== -1 ||" +
            "      h.indexOf('cf-challenge') !== -1 ||" +
            "      h.indexOf('cf-browser-verification') !== -1 ||" +
            "      h.indexOf('just a moment') !== -1 ||" +
            "      h.indexOf('attention required') !== -1 ||" +
            "      h.indexOf('access denied') !== -1" +
            "    ).toString();" +
            "  } catch(e) { return 'true'; }" + // be safe — keep waiting
            "})()"
        )

        view?.evaluateJavascript(js) { result ->
            if (solved || isFinishing) return@evaluateJavascript
            val hasCaptcha = "true".equals(result, ignoreCase = true)
            if (!hasCaptcha && !challengeUrl) {
                onChallengeSolved(url)
            }
        }
    }

    // ---------------------------------------------------------------- //
    //  Success / cookie extraction                                      //
    // ---------------------------------------------------------------- //

    /**
     * Called when the challenge appears solved. Extracts all cookies for the
     * LookMovie domain from [CookieManager] and returns them to the caller.
     */
    private fun onChallengeSolved(finalUrl: String) {
        if (solved) return
        solved = true

        val cookies = collectCookies(finalUrl)

        val resultIntent = Intent().apply {
            putExtra(EXTRA_COOKIES, cookies)
            putExtra(EXTRA_FINAL_URL, finalUrl)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Collects cookies for the given URL (and the bare-domain variant) from
     * the WebView's [CookieManager]. Returns them as a "name=value; name2=value2"
     * string, ready for [StreamExtractor.injectCookies].
     */
    private fun collectCookies(url: String): String {
        val cm = CookieManager.getInstance()

        // Primary: cookies for the exact URL the WebView ended on.
        val primary = cm.getCookie(url) ?: ""

        // Also try the bare domain (without www.) — some cookies are set on
        // the broader .lookmovie2.to domain.
        val withoutWww = url.replace("://www.", "://")
        val secondary = if (withoutWww != url) (cm.getCookie(withoutWww) ?: "") else ""

        // Merge, deduplicating by cookie name (prefer primary).
        return mergeCookies(primary, secondary)
    }

    private fun mergeCookies(a: String, b: String): String {
        if (a.isBlank()) return b
        if (b.isBlank()) return a
        val seen = mutableMapOf<String, String>()
        for (raw in (a + "; " + b).split(";")) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isNotEmpty() && !seen.containsKey(name)) {
                seen[name] = value
            }
        }
        return seen.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // ---------------------------------------------------------------- //
    //  Timeout                                                          //
    // ---------------------------------------------------------------- //

    private fun setupVerificationTimeout() {
        timeoutHandler = Handler(Looper.getMainLooper())
        timeoutHandler?.postDelayed({
            if (!isFinishing && !solved) {
                setResult(RESULT_CANCELED)
                finish()
            }
        }, VERIFICATION_TIMEOUT_MS)
    }

    // ---------------------------------------------------------------- //
    //  URL heuristics                                                   //
    // ---------------------------------------------------------------- //

    private fun isChallengeUrl(url: String): Boolean {
        val lower = url.lowercase()
        val isChallenge = lower.contains("verify") || lower.contains("captcha") ||
            lower.contains("checkpoint") || lower.contains("challenge")
        // Exclude obvious clickbait / ad redirect URLs.
        val isClickbait = lower.contains("clickbait") || lower.contains("/ads") ||
            lower.contains("popads") || lower.contains("/redirect") ||
            lower.contains("bet") || lower.contains("casino") ||
            lower.contains("porn") || lower.contains("dating") ||
            lower.contains("survey") || lower.contains("congratulations") ||
            lower.contains("winner")
        return isChallenge && !isClickbait
    }

    // ---------------------------------------------------------------- //
    //  Back press / cleanup                                             //
    // ---------------------------------------------------------------- //

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutHandler?.removeCallbacksAndMessages(null)
        doneButtonRef = null
        webView.destroy()
    }
}
