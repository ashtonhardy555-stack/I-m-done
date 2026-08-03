package com.ashtonhardy.piratesfilmcove.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
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
 * Two REAL ad units (no test ads):
 *  1. [INTERSTITIAL_AD_UNIT_ID] — shown before a **manually-tapped** movie or
 *     TV episode starts (a fresh PlayerActivity launch). Never shown for
 *     auto-play.
 *  2. [NEXT_EPISODE_AD_UNIT_ID] — shown while the player **auto-loads the next
 *     episode** of a show (the gap between one episode ending and the next
 *     beginning to play). It is dismissed/suppressed the moment the next
 *     episode actually starts playing.
 *
 * Critical behaviours:
 *  • "All ads stop when playback starts": [onPlaybackStarted] is called by
 *    PlayerActivity the instant ExoPlayer reports STATE_READY. That flips a
 *    [playbackActive] gate which prevents any not-yet-shown ad from appearing
 *    and cancels pending requests.
 *  • "Auto-playback won't start until the ad is closed": Both show methods
 *    accept an [onAdDismissed] callback that fires when the ad is dismissed
 *    (by user tap, by the 10-second auto-close timer, or on load failure /
 *    timeout). The caller gates playback on this callback so the next episode
 *    does NOT begin resolving/playing until the ad is gone.
 *  • "Ad auto-closes after 10 seconds": Each shown ad starts a
 *    [AD_AUTO_CLOSE_SECONDS] timer. When it fires, the ad's dismissal
 *    callback runs (which fires [onAdDismissed]) and the ad reference is
 *    cleared so the system can reclaim it. If the user dismisses the ad
 *    manually first, the timer is cancelled.
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

    /** Number of seconds after which a shown interstitial auto-closes. */
    private const val AD_AUTO_CLOSE_SECONDS = 10L

    private val mainHandler = Handler(Looper.getMainLooper())

    // Handler tokens for cancelling the auto-close timers.
    private val MANUAL_AD_TOKEN = Any()
    private val NEXT_EPISODE_AD_TOKEN = Any()

    // ── Manual-launch interstitial state ────────────────────────────────── //
    @Volatile
    private var loadedManualAd: InterstitialAd? = null
    private val manualLoading = AtomicBoolean(false)
    /** Prevents double-showing across an Activity recreation for one launch. */
    private val manualAlreadyShown = AtomicBoolean(false)

    /** The ad currently fullscreen (for auto-close), if any. */
    @Volatile
    private var currentManualAd: InterstitialAd? = null
    @Volatile
    private var manualAdDismissedCallback: (() -> Unit)? = null

    // ── Next-episode interstitial state ─────────────────────────────────── //
    @Volatile
    private var loadedNextEpisodeAd: InterstitialAd? = null
    private val nextEpisodeLoading = AtomicBoolean(false)
    /** True when a "show the next-episode ad" request is pending but the ad
     *  wasn't loaded yet — we honour it once the ad loads, unless playback
     *  has already started by then. */
    private val nextEpisodeShowPending = AtomicBoolean(false)

    /** The ad currently fullscreen (for auto-close), if any. */
    @Volatile
    private var currentNextEpisodeAd: InterstitialAd? = null
    @Volatile
    private var nextEpisodeAdDismissedCallback: (() -> Unit)? = null

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

    // ── Playback gate ───────────────────────────────────────────────────── //
    // True while a show/movie is actively playing. While true, no ad is shown
    // (a pending next-episode ad is cancelled). Set by [onPlaybackStarted],
    // cleared by [resetForNewLaunch] / when a loading gap begins.
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

    // ── Preloading ──────────────────────────────────────────────────────── //

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

    // ── Showing ─────────────────────────────────────────────────────────── //

    /**
     * Shows the manual-launch interstitial before playback, **once per manual
     * launch**. Call from PlayerScreen for a user-initiated launch only.
     *
     * If the ad is not loaded yet, a background thread polls every 500 ms for
     * up to 5 seconds; if it loads within that window the ad is shown,
     * otherwise the [onAdDismissed] callback is fired immediately (so playback
     * is never blocked forever on a missing ad).
     *
     * @param isAutoPlay True only for an auto-advance launch — suppresses the ad.
     * @param onAdDismissed Fires when the ad is dismissed (user tap, 10-second
     *     auto-close, load failure, or 5-second timeout). The caller uses this
     *     to gate playback start.
     */
    fun showInterstitialBeforePlayback(
        activity: Activity,
        isAutoPlay: Boolean,
        onAdDismissed: () -> Unit = {}
    ) {
        if (isAutoPlay) {
            // Auto-play launches never show the manual ad — playback can
            // proceed immediately.
            onAdDismissed()
            return
        }
        if (!manualAlreadyShown.compareAndSet(false, true)) {
            // Already shown for this launch — don't double-fire.
            onAdDismissed()
            return
        }

        val ad = loadedManualAd
        if (ad == null) {
            Log.d(TAG, "Manual interstitial not ready — polling for up to 5s.")
            manualAdDismissedCallback = onAdDismissed
            pollAndShowManualAd(activity)
            return
        }
        showLoadedManualAd(activity, ad, onAdDismissed)
    }

    /**
     * Polls the manual ad for up to 5 seconds (every 500 ms). Shows it when it
     * loads, or fires [manualAdDismissedCallback] on timeout.
     */
    private fun pollAndShowManualAd(activity: Activity) {
        Thread {
            var waited = 0
            val step = 500
            while (waited < 5000) {
                Thread.sleep(step.toLong())
                waited += step
                val ad = loadedManualAd
                if (ad != null) {
                    mainHandler.post {
                        val cb = manualAdDismissedCallback
                        manualAdDismissedCallback = null
                        showLoadedManualAd(activity, ad, cb ?: {})
                    }
                    return@Thread
                }
            }
            // Timed out — fire the callback so playback can proceed.
            Log.d(TAG, "Manual ad poll timed out after 5s — proceeding without ad.")
            mainHandler.post { fireManualDismissed() }
        }.start()
    }

    /** Internal: presents an already-loaded manual interstitial. */
    private fun showLoadedManualAd(
        activity: Activity,
        ad: InterstitialAd,
        onAdDismissed: () -> Unit
    ) {
        manualAdDismissedCallback = onAdDismissed
        currentManualAd = ad
        loadedManualAd = null
        preloadManualAd(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Manual ad dismissed by user — firing callback.")
                cancelManualAutoClose()
                currentManualAd = null
                fireManualDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Manual ad failed to show: ${error.message}")
                cancelManualAutoClose()
                currentManualAd = null
                fireManualDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Manual ad showed fullscreen — starting 10s auto-close.")
                startManualAutoClose()
            }
        }
        runCatching { ad.show(activity) }.onFailure {
            Log.w(TAG, "Manual ad.show() threw: ${it.message}")
            cancelManualAutoClose()
            currentManualAd = null
            fireManualDismissed()
        }
    }

    /** Starts the 10-second auto-close timer for the manual ad. */
    private fun startManualAutoClose() {
        mainHandler.postDelayed({
            Log.d(TAG, "Manual ad 10s auto-close fired.")
            currentManualAd = null
            fireManualDismissed()
        }, AD_AUTO_CLOSE_SECONDS * 1000, MANUAL_AD_TOKEN)
    }

    /** Cancels the manual ad auto-close timer (e.g. user dismissed first). */
    private fun cancelManualAutoClose() {
        mainHandler.removeCallbacksAndMessages(MANUAL_AD_TOKEN)
    }

    /** Fires the manual ad dismissed callback once, then clears it. */
    @Synchronized
    private fun fireManualDismissed() {
        val cb = manualAdDismissedCallback
        manualAdDismissedCallback = null
        cb?.invoke()
    }

    // ── Next-episode ad ─────────────────────────────────────────────────── //

    /**
     * Shows the next-episode interstitial **during the auto-load gap** — i.e.
     * right after one episode ends and the next begins resolving. Call this
     * from PlayerActivity's onEpisodeEnded path, BEFORE the next episode
     * starts playing. The ad is automatically suppressed/dismissed the moment
     * playback starts (see [onPlaybackStarted]).
     *
     * @param onAdDismissed Fires when the ad is dismissed (user tap, 10-second
     *     auto-close, or load failure). The caller should advance to the next
     *     episode INSIDE this callback so auto-playback does not begin until
     *     the ad is closed.
     */
    fun showNextEpisodeAdDuringLoad(
        activity: Activity,
        onAdDismissed: () -> Unit = {}
    ) {
        hostActivity = activity
        // We're entering a loading gap → playback is not active yet.
        playbackActive.set(false)
        nextEpisodeAdDismissedCallback = onAdDismissed

        val ad = loadedNextEpisodeAd
        if (ad == null) {
            // Ad not ready yet — remember the request and honour it when the
            // ad loads. We also start a 5-second poll: if the ad loads within
            // that window it is shown (and its own 10s auto-close + dismissal
            // callback handle the rest); if it doesn't, we fire onAdDismissed
            // so the caller (and the next episode) is not blocked forever.
            nextEpisodeShowPending.set(true)
            preloadNextEpisodeAd(activity)
            Log.d(TAG, "Next-episode ad not ready — polling for up to 5s.")
            pollAndShowNextEpisodeAd(activity)
            return
        }
        nextEpisodeShowPending.set(false)
        showLoadedNextEpisodeAd(activity)
    }

    /**
     * Polls the next-episode ad for up to 5 seconds (every 500 ms). Shows it
     * when it loads, or fires [nextEpisodeAdDismissedCallback] on timeout so
     * the next episode is not blocked forever on a missing ad.
     */
    private fun pollAndShowNextEpisodeAd(activity: Activity) {
        Thread {
            var waited = 0
            val step = 500
            while (waited < 5000) {
                Thread.sleep(step.toLong())
                waited += step
                // If playback already started (shouldn't happen since we gate
                // on the callback, but be safe), bail out.
                if (playbackActive.get()) return@Thread
                if (!nextEpisodeShowPending.get()) return@Thread // ad shown via load callback
                val ad = loadedNextEpisodeAd
                if (ad != null) {
                    nextEpisodeShowPending.set(false)
                    mainHandler.post { showLoadedNextEpisodeAd(activity) }
                    return@Thread
                }
            }
            // Timed out — fire the callback so the next episode can proceed.
            Log.d(TAG, "Next-episode ad poll timed out after 5s — proceeding without ad.")
            mainHandler.post { fireNextEpisodeDismissed() }
        }.start()
    }

    /** Internal: actually presents an already-loaded next-episode interstitial. */
    private fun showLoadedNextEpisodeAd(activity: Activity) {
        val ad = loadedNextEpisodeAd
        if (ad == null) {
            // No ad loaded — fire the callback so playback can proceed.
            fireNextEpisodeDismissed()
            return
        }

        // If playback has already started by the time we'd show, don't show.
        if (playbackActive.get()) {
            Log.d(TAG, "Playback already started — not showing next-episode ad.")
            fireNextEpisodeDismissed()
            return
        }
        currentNextEpisodeAd = ad
        loadedNextEpisodeAd = null
        preloadNextEpisodeAd(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Next-episode ad dismissed by user — firing callback.")
                cancelNextEpisodeAutoClose()
                currentNextEpisodeAd = null
                fireNextEpisodeDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Next-episode ad failed to show: ${error.message}")
                cancelNextEpisodeAutoClose()
                currentNextEpisodeAd = null
                fireNextEpisodeDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Next-episode ad showed fullscreen — starting 10s auto-close.")
                startNextEpisodeAutoClose()
            }
        }
        runCatching { ad.show(activity) }.onFailure {
            Log.w(TAG, "Next-episode ad.show() threw: ${it.message}")
            cancelNextEpisodeAutoClose()
            currentNextEpisodeAd = null
            fireNextEpisodeDismissed()
        }
    }

    /** Starts the 10-second auto-close timer for the next-episode ad. */
    private fun startNextEpisodeAutoClose() {
        mainHandler.postDelayed({
            Log.d(TAG, "Next-episode ad 10s auto-close fired.")
            currentNextEpisodeAd = null
            fireNextEpisodeDismissed()
        }, AD_AUTO_CLOSE_SECONDS * 1000, NEXT_EPISODE_AD_TOKEN)
    }

    /** Cancels the next-episode ad auto-close timer (e.g. user dismissed first). */
    private fun cancelNextEpisodeAutoClose() {
        mainHandler.removeCallbacksAndMessages(NEXT_EPISODE_AD_TOKEN)
    }

    /** Fires the next-episode ad dismissed callback once, then clears it. */
    @Synchronized
    private fun fireNextEpisodeDismissed() {
        val cb = nextEpisodeAdDismissedCallback
        nextEpisodeAdDismissedCallback = null
        // Reload for next time regardless.
        hostActivity?.let { preloadNextEpisodeAd(it) }
        cb?.invoke()
    }

    // ── Playback-start gate (the "ads stop when playback starts" hook) ──── //

    /**
     * Called by PlayerActivity the instant the selected show/movie actually
     * starts playing (ExoPlayer STATE_READY). This stops all ad activity:
     *  • cancels any pending next-episode ad that hasn't been shown yet,
     *  • ensures no ad is presented on top of playing video.
     *
     * If an interstitial is already fullscreen (shown during the loading gap),
     * it remains until the user dismisses it or the 10-second auto-close fires
     * — at which point the already-buffered/playing video is revealed. We do
     * not show any further ad until the next loading gap.
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
        cancelManualAutoClose()
        // If a stale callback exists (e.g. Activity destroyed mid-ad), fire it
        // so nothing is left hanging.
        fireManualDismissed()
    }
}
