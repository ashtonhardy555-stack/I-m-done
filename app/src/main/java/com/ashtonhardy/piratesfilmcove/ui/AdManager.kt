package com.ashtonhardy.piratesfilmcove.ui

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
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
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * AdMob interstitial ad-unit ID shown before a **manual** playback launch
     * (user taps a movie/show). App ID (with "~") lives in AndroidManifest.xml.
     */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8069271908902310/6722496029"

    /**
     * AdMob interstitial ad-unit ID shown while the player **auto-loads the
     * next episode** of a show (the post-STATE_ENDED loading gap). Stopped the
     * moment the next episode starts playing.
     */
    const val NEXT_EPISODE_AD_UNIT_ID = "ca-app-pub-8069271908902310/6882278128"

    // ── Manual-launch interstitial state ──────────────────────────────── //
    @Volatile
    private var loadedManualAd: InterstitialAd? = null
    private val manualLoading = AtomicBoolean(false)
    /** Prevents double-showing across an Activity recreation for one launch. */
    private val manualAlreadyShown = AtomicBoolean(false)

    // ── Next-episode interstitial state ───────────────────────────────── //
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

    // ── Playback gate ─────────────────────────────────────────────────── //
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
        runCatching {
            com.google.android.gms.ads.MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised.")
                preloadManualAd(context)
                preloadNextEpisodeAd(context)
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

    // ── Preloading ────────────────────────────────────────────────────── //

    /** Loads the manual-launch interstitial if one isn't already loaded/loading. */
    fun preloadManualAd(context: android.content.Context) {
        if (loadedManualAd != null || !manualLoading.compareAndSet(false, true)) return
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedManualAd = ad
                    manualLoading.set(false)
                    Log.d(TAG, "Manual interstitial loaded.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedManualAd = null
                    manualLoading.set(false)
                    Log.w(TAG, "Manual interstitial failed: code=${error.code} msg=${error.message}")
                }
            }
        )
    }

    /** Loads the next-episode interstitial if one isn't already loaded/loading. */
    fun preloadNextEpisodeAd(context: android.content.Context) {
        if (loadedNextEpisodeAd != null || !nextEpisodeLoading.compareAndSet(false, true)) return
        InterstitialAd.load(
            context.applicationContext,
            NEXT_EPISODE_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedNextEpisodeAd = ad
                    nextEpisodeLoading.set(false)
                    Log.d(TAG, "Next-episode interstitial loaded.")
                    // If a show request was pending (the next episode started
                    // loading before the ad was ready), honour it now — unless
                    // playback has already started in the meantime.
                    if (nextEpisodeShowPending.get() && !playbackActive.get()) {
                        nextEpisodeShowPending.set(false)
                        hostActivity?.let { showLoadedNextEpisodeAd(it) }
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedNextEpisodeAd = null
                    nextEpisodeLoading.set(false)
                    Log.w(TAG, "Next-episode interstitial failed: code=${error.code} msg=${error.message}")
                }
            }
        )
    }

    // ── Showing ───────────────────────────────────────────────────────── //

    /**
     * Shows the manual-launch interstitial before playback, **once per manual
     * launch**. Call from PlayerActivity.onCreate for a user-initiated launch
     * only. Non-blocking: skips if not loaded yet.
     *
     * @param isAutoPlay True only for an auto-advance launch — suppresses the ad.
     */
    fun showInterstitialBeforePlayback(activity: Activity, isAutoPlay: Boolean) {
        if (isAutoPlay) return
        if (!manualAlreadyShown.compareAndSet(false, true)) return

        val ad = loadedManualAd
        if (ad == null) {
            Log.d(TAG, "Manual interstitial not ready — skipping, preloading.")
            preloadManualAd(activity)
            return
        }
        runCatching { ad.show(activity) }.onFailure {
            Log.w(TAG, "Manual ad.show() threw: ${it.message}")
            loadedManualAd = null
            preloadManualAd(activity)
        }
        // The ad is consumed either way once shown; reload for next time.
        loadedManualAd = null
        preloadManualAd(activity)
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
        val ad = loadedNextEpisodeAd
        if (ad == null) {
            // Ad not ready yet — remember the request and honour it when the
            // ad loads (unless playback has started by then).
            nextEpisodeShowPending.set(true)
            preloadNextEpisodeAd(activity)
            Log.d(TAG, "Next-episode ad not ready — request pending, preloading.")
            return
        }
        nextEpisodeShowPending.set(false)
        showLoadedNextEpisodeAd(activity)
    }

    /** Internal: actually presents an already-loaded next-episode interstitial. */
    private fun showLoadedNextEpisodeAd(activity: Activity) {
        val ad = loadedNextEpisodeAd ?: return
        // If playback has already started by the time we'd show, don't show.
        if (playbackActive.get()) {
            Log.d(TAG, "Playback already started — not showing next-episode ad.")
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Next-episode ad dismissed — reloading.")
                loadedNextEpisodeAd = null
                preloadNextEpisodeAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Next-episode ad failed to show: ${error.message}")
                loadedNextEpisodeAd = null
                preloadNextEpisodeAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Next-episode ad showed fullscreen content.")
            }
        }
        runCatching { ad.show(activity) }.onFailure {
            Log.w(TAG, "Next-episode ad.show() threw: ${it.message}")
            loadedNextEpisodeAd = null
            preloadNextEpisodeAd(activity)
        }
        loadedNextEpisodeAd = null
    }

    // ── Playback-start gate (the "ads stop when playback starts" hook) ── //

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
        playbackActive.set(true)
        nextEpisodeShowPending.set(false)
        Log.d(TAG, "Playback started — all pending ads suppressed.")
    }

    /**
     * Resets the per-launch "already shown" guard for the manual interstitial.
     * Called by PlayerActivity when a brand-new manual launch begins.
     */
    fun resetForNewLaunch() {
        manualAlreadyShown.set(false)
        playbackActive.set(false)
        nextEpisodeShowPending.set(false)
    }
}
