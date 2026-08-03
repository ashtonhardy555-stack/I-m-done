package com.ashtonhardy.piratesfilmcove.ui

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.ResponseInfo
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdManager — owns the AdMob interstitial ads used around playback.
 *
 * Two ad units:
 *  1. [INTERSTITIAL_AD_UNIT_ID] — shown before a **manually-tapped** movie or
 *     TV episode starts (a fresh PlayerActivity launch). Never shown for
 *     auto-play.
 *  2. [NEXT_EPISODE_AD_UNIT_ID] — shown while the player **auto-loads the next
 *     episode** of a show (the gap between one episode ending and the next
 *     beginning to play). It is dismissed/suppressed the moment the next
 *     episode actually starts playing.
 *
 * Critical behaviour — "all ads stop when playback starts":
 *  • [onPlaybackStarted] is called by PlayerActivity the instant ExoPlayer
 *    reports STATE_READY (the first frame of the selected show/movie renders).
 *  • That flips a [playbackActive] gate which:
 *      – prevents any ad that has NOT yet been shown from being shown, and
 *      – clears any pending "show next-episode ad" request,
 *    so no ad ever appears on top of playing video. If an interstitial is
 *    already fullscreen (it was shown during the loading gap), the user
 *    dismisses it to reveal the already-buffered video underneath; once
 *    dismissed we do not show another until the next loading gap.
 *
 * Implementation notes:
 *  • The Mobile Ads SDK is initialised once in
 *    PiratesfilmCoveApplication.onCreate() via [init], which also preloads both
 *    interstitials so they are usually ready when needed.
 *  • Ad loads are non-blocking: if an ad isn't ready when its show moment
 *    arrives, we simply skip it (and keep preloading) — playback is never
 *    blocked on a network ad load.
 *
 * ── Test ads ──────────────────────────────────────────────────────────────
 *  Set [USE_TEST_ADS] = true to use Google's official test interstitial ad
 *  unit IDs instead of your real ad units. This guarantees an ad always fills
 *  (no "no fill" errors) so you can verify the ad pipeline works end-to-end on
 *  any device, regardless of whether your AdMob account / ad units are still
 *  under review. **Keep this false for production.**
 *
 *  When [USE_TEST_ADS] is false (production), the SDK still tags the current
 *  device as a test device via [RequestConfiguration] if its hashed ID is in
 *  [TEST_DEVICE_IDS], so you don't violate AdMob policy while developing.
 */
object AdManager {

    private const val TAG = "AdManager"

    // ══ Toggle: set to true to use Google's test ad unit IDs (always fill) ══
    // Keep FALSE for production/release builds. Set to TRUE only while you
    // need to verify the ad pipeline works on a device where your real ad
    // units aren't serving yet.
    //
    // NOTE: This is currently TRUE so you can verify the ad pipeline works
    // (test ads always fill). Once you confirm ads appear, set this back to
    // FALSE and rebuild to use your real AdMob ad units.
    private const val USE_TEST_ADS = true

    // ══ Test device IDs (hashed advertising IDs) ════════════════════════════
    // Add your device's hashed ID here (find it in logcat after the first ad
    // request: "RequestConfiguration: To get test ads on this device, set
    // TestDeviceIds to [...]"). While developing with real ad unit IDs this
    // prevents accidental invalid-click policy violations.
    private val TEST_DEVICE_IDS = emptyList<String>()

    // Google's official test interstitial ad unit ID (always fills).
    private const val TEST_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"

    /**
     * AdMob interstitial ad-unit ID shown before a **manual** playback launch
     * (user taps a movie/show). App ID (with "~") lives in AndroidManifest.xml.
     */
    const val REAL_INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8069271908902310/6722496029"

    /**
     * AdMob interstitial ad-unit ID shown while the player **auto-loads the
     * next episode** of a show (the post-STATE_ENDED loading gap). Stopped the
     * moment the next episode starts playing.
     */
    const val REAL_NEXT_EPISODE_AD_UNIT_ID = "ca-app-pub-8069271908902310/6882278128"

    /** The ad unit ID actually used (test or real, depending on [USE_TEST_ADS]). */
    val INTERSTITIAL_AD_UNIT_ID: String =
        if (USE_TEST_ADS) TEST_INTERSTITIAL_AD_UNIT_ID else REAL_INTERSTITIAL_AD_UNIT_ID

    /** The next-episode ad unit ID actually used (test or real). */
    val NEXT_EPISODE_AD_UNIT_ID: String =
        if (USE_TEST_ADS) TEST_INTERSTITIAL_AD_UNIT_ID else REAL_NEXT_EPISODE_AD_UNIT_ID

    // ── Manual-launch interstitial state ─────────────────────────────────── //
    @Volatile
    private var loadedManualAd: InterstitialAd? = null
    private val manualLoading = AtomicBoolean(false)
    /** Prevents double-showing across an Activity recreation for one launch. */
    private val manualAlreadyShown = AtomicBoolean(false)

    // ── Next-episode interstitial state ──────────────────────────────────── //
    @Volatile
    private var loadedNextEpisodeAd: InterstitialAd? = null
    private val nextEpisodeLoading = AtomicBoolean(false)
    /** True when a "show the next-episode ad" request is pending but the ad
     *  wasn't loaded yet — we honour it once the ad loads, unless playback
     *  has already started by then. */
    private val nextEpisodeShowPending = AtomicBoolean(false)

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

    // ── Playback gate ────────────────────────────────────────────────────── //
    // True while a show/movie is actively playing. While true, no ad is shown
    // (a pending next-episode ad is cancelled). Set by [onPlaybackStarted],
    // cleared by [onLoadingGap] / [resetForNewLaunch].
    private val playbackActive = AtomicBoolean(false)

    /** Activity we should show ads against (set when a show request is made). */
    @Volatile
    private var hostActivity: Activity? = null

    /**
     * Initialises the Mobile Ads SDK and kicks off a background preload of both
     * interstitials so they are ready when needed. Safe to call multiple times.
     */
    fun init(context: android.content.Context) {
        if (initialised) return
        initialised = true
        Log.d(TAG, "init() — initialising Mobile Ads SDK. USE_TEST_ADS=$USE_TEST_ADS")
        runCatching {
            // Register test devices (for development with real ad unit IDs).
            if (TEST_DEVICE_IDS.isNotEmpty()) {
                MobileAds.setRequestConfiguration(
                    RequestConfiguration.Builder()
                        .setTestDeviceIds(TEST_DEVICE_IDS)
                        .build()
                )
                Log.d(TAG, "Registered ${TEST_DEVICE_IDS.size} test device(s).")
            }

            MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised — version=${MobileAds.getVersion()}")
                // Use the application context for preloading so the ads outlive
                // any single Activity instance.
                preloadManualAd(context.applicationContext)
                preloadNextEpisodeAd(context.applicationContext)
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

    // ── Preloading ───────────────────────────────────────────────────────── //

    /** Loads the manual-launch interstitial if one isn't already loaded/loading. */
    fun preloadManualAd(context: android.content.Context) {
        if (loadedManualAd != null) {
            Log.d(TAG, "preloadManualAd: already loaded, skipping.")
            return
        }
        if (!manualLoading.compareAndSet(false, true)) {
            Log.d(TAG, "preloadManualAd: load already in progress, skipping.")
            return
        }
        Log.d(TAG, "preloadManualAd: requesting ad for unit=$INTERSTITIAL_AD_UNIT_ID")
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedManualAd = ad
                    manualLoading.set(false)
                    val ri: ResponseInfo? = ad.responseInfo
                    Log.d(TAG, "✅ Manual interstitial LOADED. responseId=${ri?.responseId} " +
                            "mediation=${ri?.mediationAdapterClassName}")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedManualAd = null
                    manualLoading.set(false)
                    Log.w(TAG, "❌ Manual interstitial FAILED: code=${error.code} " +
                            "domain=${error.domain} msg=${error.message}")
                    // Log the underlying network error if any.
                    error.responseInfo?.let { ri ->
                        Log.w(TAG, "   responseInfo: responseId=${ri.responseId} " +
                                "adapter=${ri.mediationAdapterClassName}")
                    }
                }
            }
        )
    }

    /** Loads the next-episode interstitial if one isn't already loaded/loading. */
    fun preloadNextEpisodeAd(context: android.content.Context) {
        if (loadedNextEpisodeAd != null) {
            Log.d(TAG, "preloadNextEpisodeAd: already loaded, skipping.")
            return
        }
        if (!nextEpisodeLoading.compareAndSet(false, true)) {
            Log.d(TAG, "preloadNextEpisodeAd: load already in progress, skipping.")
            return
        }
        Log.d(TAG, "preloadNextEpisodeAd: requesting ad for unit=$NEXT_EPISODE_AD_UNIT_ID")
        InterstitialAd.load(
            context.applicationContext,
            NEXT_EPISODE_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedNextEpisodeAd = ad
                    nextEpisodeLoading.set(false)
                    val ri: ResponseInfo? = ad.responseInfo
                    Log.d(TAG, "✅ Next-episode interstitial LOADED. responseId=${ri?.responseId} " +
                            "mediation=${ri?.mediationAdapterClassName}")
                    // If a show request was pending (the next episode started
                    // loading before the ad was ready), honour it now — unless
                    // playback has already started in the meantime.
                    if (nextEpisodeShowPending.get() && !playbackActive.get()) {
                        Log.d(TAG, "  pending next-episode request honoured now.")
                        nextEpisodeShowPending.set(false)
                        hostActivity?.let { showLoadedNextEpisodeAd(it) }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedNextEpisodeAd = null
                    nextEpisodeLoading.set(false)
                    Log.w(TAG, "❌ Next-episode interstitial FAILED: code=${error.code} " +
                            "domain=${error.domain} msg=${error.message}")
                    error.responseInfo?.let { ri ->
                        Log.w(TAG, "   responseInfo: responseId=${ri.responseId} " +
                                "adapter=${ri.mediationAdapterClassName}")
                    }
                }
            }
        )
    }

    // ── Showing ──────────────────────────────────────────────────────────── //

    /**
     * Shows the manual-launch interstitial before playback, **once per manual
     * launch**. Call from PlayerActivity.onCreate for a user-initiated launch
     * only. Non-blocking: skips if not loaded yet.
     *
     * @param isAutoPlay True only for an auto-advance launch — suppresses the ad.
     */
    fun showInterstitialBeforePlayback(activity: Activity, isAutoPlay: Boolean) {
        Log.d(TAG, "showInterstitialBeforePlayback: isAutoPlay=$isAutoPlay " +
                "manualAlreadyShown=${manualAlreadyShown.get()}")
        if (isAutoPlay) {
            Log.d(TAG, "  → auto-play, skipping manual ad.")
            return
        }
        if (!manualAlreadyShown.compareAndSet(false, true)) {
            Log.d(TAG, "  → already shown this launch, skipping.")
            return
        }

        val ad = loadedManualAd
        if (ad == null) {
            // The ad isn't loaded yet. Start a background thread that polls
            // for up to 5 seconds (checking every 500ms) — if the ad loads
            // within that window we show it; otherwise we give up so playback
            // is never blocked for more than 5s.
            Log.d(TAG, "  → manual ad not ready yet — starting 5s wait-and-show poll.")
            preloadManualAd(activity)
            Thread {
                val maxChecks = 10 // 10 × 500ms = 5s
                for (i in 1..maxChecks) {
                    Thread.sleep(500)
                    if (playbackActive.get()) {
                        Log.d(TAG, "  → playback already started during wait — abandoning ad.")
                        return@Thread
                    }
                    val loaded = loadedManualAd
                    if (loaded != null) {
                        Log.d(TAG, "  → ad became ready after ${i * 500}ms — showing.")
                        activity.runOnUiThread {
                            if (!playbackActive.get()) {
                                showManualAdNow(activity, loaded)
                            } else {
                                Log.d(TAG, "  → playback started just as ad loaded — not showing.")
                            }
                        }
                        return@Thread
                    }
                }
                Log.d(TAG, "  → ad did not load within 5s — giving up (playback continues).")
            }.start()
            return
        }

        // Show the ad. Only null out the reference AFTER a successful show
        // call (the FullScreenContentCallback handles reloading on dismiss).
        showManualAdNow(activity, ad)
    }

    /** Internal: presents an already-loaded manual interstitial. */
    private fun showManualAdNow(activity: Activity, ad: InterstitialAd) {
        if (playbackActive.get()) {
            Log.d(TAG, "showManualAdNow: playback already started — not showing.")
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Manual ad dismissed — reloading.")
                loadedManualAd = null
                preloadManualAd(activity.applicationContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Manual ad failed to SHOW: code=${error.code} msg=${error.message}")
                loadedManualAd = null
                preloadManualAd(activity.applicationContext)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Manual ad SHOWED fullscreen content.")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Manual ad impression recorded.")
            }
        }

        // Show the ad. Only null out the reference AFTER a successful show
        // call (the FullScreenContentCallback handles reloading on dismiss).
        runCatching {
            ad.show(activity)
            Log.d(TAG, "  → ad.show() called for manual interstitial.")
            loadedManualAd = null  // consumed; will reload on dismiss/failure
        }.onFailure {
            Log.w(TAG, "  → ad.show() threw: ${it.message}")
            loadedManualAd = null
            preloadManualAd(activity.applicationContext)
        }
    }

    /**
     * Shows the next-episode interstitial **during the auto-load gap** — i.e.
     * right after one episode ends and the next begins resolving. Call this
     * from PlayerActivity's onEpisodeEnded path, BEFORE the next episode
     * starts playing. The ad is automatically suppressed/dismissed the moment
     * playback starts (see [onPlaybackStarted]).
     */
    fun showNextEpisodeAdDuringLoad(activity: Activity) {
        hostActivity = activity
        // We're entering a loading gap → playback is not active yet.
        playbackActive.set(false)
        Log.d(TAG, "showNextEpisodeAdDuringLoad: called. " +
                "adLoaded=${loadedNextEpisodeAd != null} playbackActive=false")
        val ad = loadedNextEpisodeAd
        if (ad == null) {
            // Ad not ready yet — remember the request and honour it when the
            // ad loads (unless playback has started by then).
            nextEpisodeShowPending.set(true)
            preloadNextEpisodeAd(activity)
            Log.d(TAG, "  → next-episode ad not ready — request pending, preloading.")
            return
        }
        nextEpisodeShowPending.set(false)
        showLoadedNextEpisodeAd(activity)
    }

    /** Internal: actually presents an already-loaded next-episode interstitial. */
    private fun showLoadedNextEpisodeAd(activity: Activity) {
        val ad = loadedNextEpisodeAd ?: run {
            Log.d(TAG, "showLoadedNextEpisodeAd: no ad available.")
            return
        }
        // If playback has already started by the time we'd show, don't show.
        if (playbackActive.get()) {
            Log.d(TAG, "showLoadedNextEpisodeAd: playback already started — NOT showing.")
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Next-episode ad dismissed — reloading.")
                loadedNextEpisodeAd = null
                preloadNextEpisodeAd(activity.applicationContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Next-episode ad failed to SHOW: code=${error.code} msg=${error.message}")
                loadedNextEpisodeAd = null
                preloadNextEpisodeAd(activity.applicationContext)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Next-episode ad SHOWED fullscreen content.")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Next-episode ad impression recorded.")
            }
        }
        runCatching {
            ad.show(activity)
            Log.d(TAG, "  → ad.show() called for next-episode interstitial.")
            loadedNextEpisodeAd = null  // consumed; reloads on dismiss/failure
        }.onFailure {
            Log.w(TAG, "  → next-episode ad.show() threw: ${it.message}")
            loadedNextEpisodeAd = null
            preloadNextEpisodeAd(activity.applicationContext)
        }
    }

    // ── Playback-start gate (the "ads stop when playback starts" hook) ──── //

    /**
     * Called by PlayerActivity the instant the selected show/movie actually
     * starts playing (ExoPlayer STATE_READY). This stops all ad activity:
     *  • cancels any pending next-episode ad that hasn't been shown yet,
     *  • ensures no ad is presented on top of playing video.
     *
     * If an interstitial is already fullscreen (shown during the loading gap),
     * it remains until the user dismisses it — at which point the already
     * buffered/playing video is revealed. We do not show any further ad until
     * the next loading gap.
     */
    fun onPlaybackStarted() {
        val wasActive = playbackActive.get()
        playbackActive.set(true)
        nextEpisodeShowPending.set(false)
        Log.d(TAG, "onPlaybackStarted: playbackActive=true (was=$wasActive) — " +
                "all pending ads suppressed.")
    }

    /**
     * Resets the per-launch "already shown" guard for the manual interstitial.
     * Called by PlayerActivity when a brand-new manual launch begins.
     */
    fun resetForNewLaunch() {
        manualAlreadyShown.set(false)
        playbackActive.set(false)
        nextEpisodeShowPending.set(false)
        Log.d(TAG, "resetForNewLaunch: all flags cleared for new launch.")
    }

    /** Debug helper: returns whether each ad is currently loaded. */
    fun debugState(): String =
        "manualLoaded=${loadedManualAd != null}, " +
        "manualLoading=${manualLoading.get()}, " +
        "nextEpisodeLoaded=${loadedNextEpisodeAd != null}, " +
        "nextEpisodeLoading=${nextEpisodeLoading.get()}, " +
        "playbackActive=${playbackActive.get()}, " +
        "nextEpisodeShowPending=${nextEpisodeShowPending.get()}, " +
        "initialised=$initialised"
}
