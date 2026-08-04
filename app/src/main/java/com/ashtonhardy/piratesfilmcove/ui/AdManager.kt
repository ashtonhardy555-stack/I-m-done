package com.ashtonhardy.piratesfilmcove.ui

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AdManager — owns the AdMob **Banner** ad configuration.
 *
 * Banner ads are shown **only on loading screens** (the screen displayed while
 * a movie or show is resolving its stream URL). Three banner ads are displayed
 * simultaneously on that loading screen. They are destroyed automatically the
 * moment the loading screen disappears (i.e. when the video starts playing or
 * an error is shown). No ads appear anywhere else in the app.
 *
 * **Pre-loading:** To avoid the banner ads appearing blank for a second or two
 * after the loading screen pops up, [preloadBannerAds] creates and loads a pool
 * of AdViews in the background (called from MainActivity's LaunchedEffect as
 * soon as the home screen appears). When the loading screen's [BannerAdView]
 * composable needs an ad, it calls [takePreloadedBannerAd] which pops a
 * ready-or-loading AdView from the pool. If the pool is empty (e.g. the user
 * tapped a movie before preloading finished), a fresh AdView is created and
 * loaded on the spot so there is always a graceful fallback.
 *
 * The banner ad unit ID is [BANNER_AD_UNIT_ID]. The AdMob app ID (with "~")
 * lives in `AndroidManifest.xml`.
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * AdMob **Banner** ad-unit ID (`6225652058`). This is a banner-only unit.
     * Three instances of this banner are shown on the loading screen while a
     * movie/show is loading. They are destroyed when playback starts.
     */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-8069271908902310/6225652058"

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

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

    /**
     * Initialises the Google Mobile Ads SDK. Safe to call multiple times.
     * Called from `PiratesfilmCoveApplication.onCreate`.
     */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        runCatching {
            com.google.android.gms.ads.MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised (banner ads).")
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

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

    /**
     * Best-effort "warm up" — ensures the Mobile Ads SDK is initialised early
     * and kicks off banner ad preloading so the loading screen gets instant
     * ads. No-op if already done (preload still tops up if under count).
     */
    fun warmUp(context: Context) {
        if (!initialised) init(context)
        preloadBannerAds(context, 3)
    }
}
