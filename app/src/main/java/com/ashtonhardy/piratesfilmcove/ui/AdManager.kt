package com.ashtonhardy.piratesfilmcove.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AdManager — owns the AdMob ad configuration for the app.
 *
 * Two ad types are used:
 *
 *  **1. Banner ads** ([BANNER_AD_UNIT_ID]) — shown **only on loading screens**
 *  (the screen displayed while a movie or show is resolving its stream URL).
 *  Three banner ads are displayed simultaneously on that loading screen. They
 *  are destroyed automatically the moment the loading screen disappears (i.e.
 *  when the video starts playing or an error is shown). Pre-loaded via a pool
 *  so they appear instantly.
 *
 *  **2. Interstitial ad** ([INTERSTITIAL_AD_UNIT_ID]) — a full-screen ad shown
 *  **before video playback starts**. When the user taps a movie or show, the
 *  stream URL is resolved (loading screen with banners), then the interstitial
 *  ad is shown. **Playback does NOT start until the ad is closed/exited.**
 *  If the ad fails to load or is unavailable, playback proceeds immediately
 *  without blocking the user.
 *
 * The AdMob app ID (with "~") lives in `AndroidManifest.xml`.
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * AdMob **Banner** ad-unit ID (`6225652058`). This is a banner-only unit.
     * Three instances of this banner are shown on the loading screen while a
     * movie/show is loading. They are destroyed when playback starts.
     */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-8069271908902310/6225652058"

    /**
     * AdMob **Interstitial** ad-unit ID (`1002345614`). A full-screen ad shown
     * before video playback starts. The user must close the ad before the
     * movie/show begins playing. If the ad fails to load, playback proceeds
     * without blocking.
     */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8069271908902310/1002345614"

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

    // ── Banner ad preloading pool ────────────────────────────────────── //

    /**
     * Pool of pre-loaded [AdView]s. Each entry is an AdView that has already
     * been created, configured, and had `loadAd()` called — so the ad content
     * is either already fetched or actively fetching. When the loading screen
     * needs a banner it pops one from here for instant display.
     *
     * Uses a [ConcurrentLinkedQueue] because preload (background) and
     * take (main/compose thread) can race.
     */
    private val bannerPool = ConcurrentLinkedQueue<AdView>()

    // ── Interstitial ad ──────────────────────────────────────────────── //

    /**
     * The currently loaded interstitial ad, or `null` if none is loaded or
     * it has already been shown. Access is guarded by [interstitialLock].
     */
    @Volatile
    private var loadedInterstitial: InterstitialAd? = null

    /** Lock for interstitial load/show coordination. */
    private val interstitialLock = Any()

    /**
     * True if an interstitial ad load is currently in flight (prevents
     * duplicate concurrent load requests).
     */
    @Volatile
    private var interstitialLoading = false

    /**
     * Pending callbacks waiting for an in-flight interstitial load to
     * complete. When [loadInterstitialAdInternal] finishes, all pending
     * callbacks are invoked. This prevents duplicate ad requests when
     * [preloadInterstitialAd] and [showInterstitialAd] race.
     */
    private val pendingLoadCallbacks = java.util.concurrent.ConcurrentLinkedQueue<(Boolean) -> Unit>()

    // ── SDK initialisation ───────────────────────────────────────────── //

    /**
     * Initialises the Google Mobile Ads SDK. Safe to call multiple times.
     * Called from `PiratesfilmCoveApplication.onCreate`.
     */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        runCatching {
            com.google.android.gms.ads.MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised.")
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

    // ── Banner ad methods ────────────────────────────────────────────── //

    /**
     * Creates and returns a fresh [AdView] configured as a banner ad using
     * [BANNER_AD_UNIT_ID]. The caller (a Compose `AndroidView`) is responsible
     * for calling `adView.destroy()` when the loading screen goes away.
     *
     * The returned AdView has MATCH_PARENT width / WRAP_CONTENT height so it
     * fills the horizontal width of whatever container it is placed in.
     */
    fun createBannerAd(context: Context): AdView {
        val adView = AdView(context)
        adView.setAdSize(AdSize.BANNER)
        adView.adUnitId = BANNER_AD_UNIT_ID
        adView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return adView
    }

    /** Loads an ad into the given [AdView]. */
    fun loadBannerAd(adView: AdView) {
        runCatching {
            adView.loadAd(AdRequest.Builder().build())
        }.onFailure { Log.w(TAG, "Banner loadAd failed: ${it.message}") }
    }

    /**
     * Pre-loads [count] banner AdViews into the pool so they are ready (or
     * actively loading) by the time the loading screen needs them. Each AdView
     * is created with [createBannerAd] and immediately has an ad request fired
     * via [loadBannerAd]. Safe to call multiple times — it tops up the pool.
     *
     * Should be called as early as possible (e.g. from the home screen's
     * `LaunchedEffect(Unit)`) so the ads are fetched in the background while
     * the user is still browsing.
     */
    fun preloadBannerAds(context: Context, count: Int = 3) {
        if (!initialised) init(context)
        val current = bannerPool.size
        val needed = count - current
        if (needed <= 0) {
            Log.d(TAG, "preloadBannerAds: pool already has $current, nothing to do.")
            return
        }
        Log.d(TAG, "preloadBannerAds: preloading $needed banner ad(s) (pool had $current).")
        repeat(needed) {
            runCatching {
                val adView = createBannerAd(context)
                loadBannerAd(adView)
                bannerPool.add(adView)
            }.onFailure { Log.w(TAG, "preloadBannerAds: failed to create ad #$it: ${it.message}") }
        }
    }

    /**
     * Pops a pre-loaded [AdView] from the pool, or creates a fresh one (and
     * loads an ad into it) if the pool is empty. The returned AdView is
     * **owned by the caller** — the caller must `destroy()` it when done.
     *
     * This is what [BannerAdView] calls in its `AndroidView` factory so the
     * loading screen shows ads instantly instead of waiting for a fresh load.
     */
    fun takePreloadedBannerAd(context: Context): AdView {
        // Try the pool first — a pre-loaded ad is either already filled or
        // actively loading, so it will appear on the loading screen with no
        // blank delay.
        val pooled = bannerPool.poll()
        if (pooled != null) {
            Log.d(TAG, "takePreloadedBannerAd: served from pool (${bannerPool.size} remaining).")
            return pooled
        }
        // Pool empty (user tapped fast or preloading didn't finish) — create
        // and load a fresh one as a graceful fallback.
        Log.d(TAG, "takePreloadedBannerAd: pool empty, creating fresh ad.")
        val adView = createBannerAd(context)
        loadBannerAd(adView)
        return adView
    }

    // ── Interstitial ad methods ──────────────────────────────────────── //

    /**
     * Pre-loads an interstitial ad in the background so it is ready to show
     * instantly when the user taps a movie/show. Safe to call multiple times —
     * if an ad is already loaded or currently loading, this is a no-op.
     *
     * Called from the home screen's `LaunchedEffect(Unit)` (alongside banner
     * preloading) so by the time the user taps something the interstitial is
     * already fetched.
     *
     * @param context Any context (application context is fine).
     */
    fun preloadInterstitialAd(context: Context) {
        if (!initialised) init(context)
        synchronized(interstitialLock) {
            if (loadedInterstitial != null) {
                Log.d(TAG, "preloadInterstitialAd: already loaded, skipping.")
                return
            }
            if (interstitialLoading) {
                Log.d(TAG, "preloadInterstitialAd: already loading, skipping.")
                return
            }
        }
        Log.d(TAG, "preloadInterstitialAd: requesting interstitial ad…")
        // Start the ad load directly. The ad is stored in loadedInterstitial
        // when it arrives, ready for showInterstitialAd to pick up. Any
        // showInterstitialAd call that arrives while this load is in flight
        // will queue its callback via loadInterstitialAdInternal and be fired
        // when this load completes.
        synchronized(interstitialLock) {
            interstitialLoading = true
        }
        InterstitialAd.load(
            context,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial ad loaded successfully (preload).")
                    synchronized(interstitialLock) {
                        loadedInterstitial = ad
                        interstitialLoading = false
                    }
                    // Fire any pending callbacks from showInterstitialAd.
                    while (true) {
                        val cb = pendingLoadCallbacks.poll() ?: break
                        cb.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Interstitial ad failed to load (preload): ${error.message}")
                    synchronized(interstitialLock) {
                        loadedInterstitial = null
                        interstitialLoading = false
                    }
                    // Fire any pending callbacks with failure.
                    while (true) {
                        val cb = pendingLoadCallbacks.poll() ?: break
                        cb.invoke(false)
                    }
                }
            }
        )
    }

    /**
     * Loads an interstitial ad. If an ad is already loaded, the [onLoaded]
     * callback fires immediately. If a load is already in progress, the
     * callback is stored and fired when the load completes.
     *
     * @param context  Any context (application context is fine).
     * @param onLoaded Called when the ad is ready to show (or on load failure
     *                 with `null` if the ad could not be loaded). The caller
     *                 should then call [showInterstitialAd].
     */
    private fun loadInterstitialAdInternal(
        context: Context,
        onLoaded: ((Boolean) -> Unit)?
    ) {
        // If a load is already in flight, just queue the callback instead of
        // starting a duplicate request.
        synchronized(interstitialLock) {
            if (interstitialLoading && onLoaded != null) {
                pendingLoadCallbacks.add(onLoaded)
                Log.d(TAG, "loadInterstitialAdInternal: load in flight, queued callback (${pendingLoadCallbacks.size} pending).")
                return
            }
            interstitialLoading = true
            if (onLoaded != null) {
                pendingLoadCallbacks.add(onLoaded)
            }
        }
        Log.d(TAG, "loadInterstitialAdInternal: requesting interstitial ad…")
        InterstitialAd.load(
            context,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.d(TAG, "Interstitial ad loaded successfully.")
                    synchronized(interstitialLock) {
                        loadedInterstitial = ad
                        interstitialLoading = false
                    }
                    // Fire all pending callbacks.
                    while (true) {
                        val cb = pendingLoadCallbacks.poll() ?: break
                        cb.invoke(true)
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "Interstitial ad failed to load: ${error.message}")
                    synchronized(interstitialLock) {
                        loadedInterstitial = null
                        interstitialLoading = false
                    }
                    // Fire all pending callbacks with failure.
                    while (true) {
                        val cb = pendingLoadCallbacks.poll() ?: break
                        cb.invoke(false)
                    }
                }
            }
        )
    }

    /**
     * Shows the pre-loaded interstitial ad over the given [activity], or loads
     * one on the spot if none is pre-loaded.
     *
     * **Playback gate:** The [onAdDismissed] callback fires when the ad is
     * closed by the user (or when the ad fails to load / is unavailable). The
     * caller MUST wait for this callback before starting video playback —
     * playback must NOT begin while the interstitial is on screen.
     *
     * If the ad fails to load, [onAdDismissed] is called immediately so the
     * user is never blocked waiting for an ad that will never come.
     *
     * @param activity     The activity to show the ad over (full-screen).
     * @param onAdDismissed Called when the ad is closed or unavailable.
     *                       Playback should start when this fires.
     */
    fun showInterstitialAd(activity: Activity, onAdDismissed: () -> Unit) {
        if (!initialised) init(activity)

        // Guard: ensure onAdDismissed is only called once (timeout OR ad
        // callback, whichever fires first).
        val dismissed = java.util.concurrent.atomic.AtomicBoolean(false)
        fun safeDismiss() {
            if (dismissed.compareAndSet(false, true)) {
                onAdDismissed()
            }
        }

        val ad: InterstitialAd? = synchronized(interstitialLock) {
            loadedInterstitial
        }

        if (ad != null) {
            // Ad is already loaded — show it now.
            Log.d(TAG, "showInterstitialAd: showing pre-loaded interstitial.")
            showLoadedInterstitial(ad, activity) { safeDismiss() }
        } else {
            // No pre-loaded ad — load one on the spot, then show (or dismiss
            // if the load fails so the user is never blocked). If a load is
            // already in flight (from preloadInterstitialAd), the callback
            // is queued and fired when that load completes — no duplicate
            // ad request is made.
            Log.d(TAG, "showInterstitialAd: no pre-loaded ad, loading on the spot…")

            // Safety timeout: if the ad doesn't load within 6 seconds, proceed
            // to playback so the user is never stuck on the ad gate screen.
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!dismissed.get()) {
                    Log.w(TAG, "showInterstitialAd: timed out after 6s, proceeding without ad.")
                    safeDismiss()
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 6000L)

            loadInterstitialAdInternal(activity) { success ->
                mainHandler.removeCallbacks(timeoutRunnable)
                if (success) {
                    val loadedAd = synchronized(interstitialLock) { loadedInterstitial }
                    if (loadedAd != null) {
                        showLoadedInterstitial(loadedAd, activity) { safeDismiss() }
                    } else {
                        // Race: ad was shown/used elsewhere. Dismiss to unblock.
                        Log.w(TAG, "showInterstitialAd: loaded but ad was null on show, dismissing.")
                        safeDismiss()
                    }
                } else {
                    // Load failed — don't block the user, proceed to playback.
                    Log.w(TAG, "showInterstitialAd: load failed, proceeding without ad.")
                    safeDismiss()
                }
            }
        }
    }

    /**
     * Shows an already-loaded [InterstitialAd] and wires up the dismissal
     * callback. Clears the [loadedInterstitial] reference so the next show
     * triggers a fresh load.
     */
    private fun showLoadedInterstitial(
        ad: InterstitialAd,
        activity: Activity,
        onAdDismissed: () -> Unit
    ) {
        // Clear the stored reference — this ad is being consumed.
        synchronized(interstitialLock) {
            loadedInterstitial = null
        }

        ad.fullScreenContentCallback = object : com.google.android.gms.ads.FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Interstitial ad dismissed — starting playback.")
                onAdDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(
                adError: com.google.android.gms.ads.AdError
            ) {
                Log.w(TAG, "Interstitial ad failed to show: ${adError.message}")
                // Don't block the user — proceed to playback.
                onAdDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Interstitial ad shown (full-screen).")
            }
        }

        ad.show(activity)
    }

    // ── Warm-up ──────────────────────────────────────────────────────── //

    /**
     * Best-effort "warm up" — ensures the Mobile Ads SDK is initialised early,
     * kicks off banner ad preloading (so the loading screen gets instant ads),
     * and pre-loads an interstitial ad (so it's ready to show before playback).
     * No-op if already done (preload still tops up if under count).
     */
    fun warmUp(context: Context) {
        if (!initialised) init(context)
        preloadBannerAds(context, 3)
        preloadInterstitialAd(context)
    }
}
