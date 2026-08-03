package com.ashtonhardy.piratesfilmcove.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdManager — owns the AdMob **Rewarded Video** ads used around playback.
 *
 * A rewarded video ad plays a full-screen video ad **inside the app** (just like
 * a YouTube pre-roll ad) and shows a built-in **skip / close button after 5
 * seconds**. The real movie/show video does NOT start loading until the user
 * skips or closes the ad (the dismissal callback opens the playback gate).
 *
 * Two ad slots share the same ad unit:
 *  1. [REWARDED_AD_UNIT_ID] — shown before a **manually-tapped** movie or TV
 *     episode starts (a fresh PlayerActivity launch). Never shown for auto-play.
 *  2. The same unit is reused for the **next-episode** auto-play gap ad.
 *
 * Critical behaviours:
 *  • "Plays like a YouTube ad": The rewarded video ad plays full-screen in the
 *    app. After 5 seconds the SDK reveals a close button (the same UX as a
 *    YouTube skippable ad). Tapping it dismisses the ad and fires
 *    [onAdDismissedFullScreenContent], which opens the playback gate so the
 *    real video starts.
 *  • "Video only plays after the ad is closed": Both show methods accept an
 *    [onAdDismissed] callback that fires when the ad is dismissed (by user
 *    skip/close, on load failure, or on poll timeout). The caller gates
 *    playback (stream resolution) on this callback.
 *  • "All ads stop when playback starts": [onPlaybackStarted] is called by
 *    PlayerActivity the instant ExoPlayer reports STATE_READY. That flips a
 *    [playbackActive] gate which prevents any not-yet-shown ad from appearing.
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * AdMob **Rewarded Video** ad-unit ID. This is the unit the user asked us
     * to use (`7919374650`). Rewarded video ads play a full-screen video ad
     * in-app with a built-in skip / close button that appears after 5 seconds
     * — exactly like a YouTube pre-roll ad. Both the manual pre-playback ad
     * and the next-episode auto-play ad use this unit. The AdMob app ID (with
     * "~") lives in AndroidManifest.xml.
     */
    const val REWARDED_AD_UNIT_ID = "ca-app-pub-8069271908902310/7919374650"

    /**
     * How long [showRewardedBeforePlayback] polls waiting for the manual ad to
     * finish loading before giving up (and letting playback proceed without an
     * ad). Kept short (5s) so playback is never delayed long when no ad is
     * available. The ad is preloaded at app start (warmUp) so it is normally
     * ready well before the user taps anything.
     */
    private const val MANUAL_AD_POLL_TIMEOUT_MS = 5_000L

    /** Poll interval used while waiting for an ad to load. */
    private const val AD_POLL_STEP_MS = 500L

    /** Delay before retrying a failed manual-ad load. */
    private const val MANUAL_AD_RETRY_DELAY_MS = 3_000L

    /** Max number of times a failed manual-ad load is retried automatically. */
    private const val MANUAL_AD_MAX_RETRIES = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Manual-launch rewarded ad state ──────────────────────────────────── //
    @Volatile
    private var loadedManualAd: RewardedAd? = null
    private val manualLoading = AtomicBoolean(false)
    /** Prevents double-showing across an Activity recreation for one launch. */
    private val manualAlreadyShown = AtomicBoolean(false)
    /** How many times the current manual-ad load has been retried. */
    private var manualLoadRetries = 0

    /** The ad currently fullscreen, if any. */
    @Volatile
    private var currentManualAd: RewardedAd? = null
    @Volatile
    private var manualAdDismissedCallback: (() -> Unit)? = null

    // ── Next-episode rewarded ad state ───────────────────────────────────── //
    @Volatile
    private var loadedNextEpisodeAd: RewardedAd? = null
    private val nextEpisodeLoading = AtomicBoolean(false)
    /** True when a "show the next-episode ad" request is pending but the ad
     *  wasn't loaded yet — we honour it once the ad loads, unless playback
     *  has already started by then. */
    private val nextEpisodeShowPending = AtomicBoolean(false)

    /** The ad currently fullscreen, if any. */
    @Volatile
    private var currentNextEpisodeAd: RewardedAd? = null
    @Volatile
    private var nextEpisodeAdDismissedCallback: (() -> Unit)? = null

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

    /** Application context captured at [init] time so background retries can
     *  load ads without needing a fresh context each time. */
    @Volatile
    private var appContext: android.content.Context? = null

    // ── Playback gate ────────────────────────────────────────────────────── //
    // True while a show/movie is actively playing. While true, no ad is shown
    // (a pending next-episode ad is cancelled). Set by [onPlaybackStarted],
    // cleared by [resetForNewLaunch] / when a loading gap begins.
    private val playbackActive = AtomicBoolean(false)

    /** Activity we should show ads against (set when a show request is made). */
    @Volatile
    private var hostActivity: Activity? = null

    /**
     * Initialises the Mobile Ads SDK and kicks off a background preload of both
     * rewarded ads so they are ready when needed. Safe to call multiple times.
     */
    fun init(context: android.content.Context) {
        if (initialised) return
        initialised = true
        appContext = context.applicationContext
        runCatching {
            com.google.android.gms.ads.MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised.")
                preloadManualAd(context.applicationContext)
                preloadNextEpisodeAd(context.applicationContext)
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

    /**
     * Best-effort "warm up" — call this as early as possible (e.g. from the
     * home screen becoming visible) to make sure the Mobile Ads SDK is
     * initialised and the manual rewarded ad is preloading. No-op if already
     * done. This exists so the manual ad has the maximum possible head start
     * before the user taps a movie/show, which is the single biggest factor in
     * whether the ad is ready in time.
     */
    fun warmUp(context: android.content.Context) {
        if (!initialised) {
            init(context)
        } else {
            // Already initialised — just make sure a preload is in flight.
            appContext?.let { preloadManualAd(it) }
        }
    }

    // ── Preloading ───────────────────────────────────────────────────────── //

    /**
     * Loads the manual-launch rewarded ad if one isn't already loaded/loading.
     * If the load fails, it is automatically retried (up to
     * [MANUAL_AD_MAX_RETRIES] times with a [MANUAL_AD_RETRY_DELAY_MS] gap) so a
     * transient network/SDK hiccup doesn't leave the manual ad permanently
     * unavailable — which was the root cause of "ads not playing for movies".
     */
    fun preloadManualAd(context: android.content.Context) {
        val ctx = context.applicationContext
        if (loadedManualAd != null || !manualLoading.compareAndSet(false, true)) return
        RewardedAd.load(
            ctx,
            REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadedManualAd = ad
                    manualLoading.set(false)
                    manualLoadRetries = 0
                    Log.d(TAG, "Manual rewarded ad loaded.")
                    // If a manual show request was polling waiting for this ad,
                    // the poll thread will pick up loadedManualAd on its next
                    // iteration — no extra wiring needed here.
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedManualAd = null
                    manualLoading.set(false)
                    Log.w(TAG, "Manual rewarded ad failed (attempt ${manualLoadRetries + 1}): code=${error.code} msg=${error.message}")
                    // Auto-retry so a transient failure doesn't permanently
                    // kill the manual ad for the session.
                    if (manualLoadRetries < MANUAL_AD_MAX_RETRIES) {
                        manualLoadRetries++
                        mainHandler.postDelayed({
                            // Re-attempt the load (preloadManualAd guards
                            // against double-loads via manualLoading).
                            appContext?.let { preloadManualAd(it) }
                        }, MANUAL_AD_RETRY_DELAY_MS)
                    } else {
                        Log.w(TAG, "Manual rewarded ad gave up after $MANUAL_AD_MAX_RETRIES retries.")
                    }
                }
            }
        )
    }

    /** Loads the next-episode rewarded ad if one isn't already loaded/loading. */
    fun preloadNextEpisodeAd(context: android.content.Context) {
        if (loadedNextEpisodeAd != null || !nextEpisodeLoading.compareAndSet(false, true)) return
        RewardedAd.load(
            context.applicationContext,
            REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    loadedNextEpisodeAd = ad
                    nextEpisodeLoading.set(false)
                    Log.d(TAG, "Next-episode rewarded ad loaded.")
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
                    Log.w(TAG, "Next-episode rewarded ad failed: code=${error.code} msg=${error.message}")
                }
            }
        )
    }

    // ── Showing ──────────────────────────────────────────────────────────── //

    /**
     * Shows the manual-launch rewarded video ad before playback, **once per
     * manual launch**. Call from PlayerScreen for a user-initiated launch only.
     *
     * The rewarded ad plays a full-screen video ad in-app (like a YouTube
     * pre-roll). After 5 seconds the SDK reveals a skip / close button. When
     * the user taps it (or the ad finishes naturally) the
     * [onAdDismissedFullScreenContent] callback fires → [onAdDismissed] is
     * invoked → the caller opens the playback gate so the real video starts.
     *
     * If the ad is not loaded yet, a background thread polls every
     * [AD_POLL_STEP_MS] for up to [MANUAL_AD_POLL_TIMEOUT_MS]; if it loads
     * within that window the ad is shown, otherwise the [onAdDismissed]
     * callback is fired immediately (so playback is never blocked forever on a
     * missing ad). A fresh preload is also kicked off immediately so a failed
     * initial load gets a second chance within the poll window.
     *
     * @param isAutoPlay True only for an auto-advance launch — suppresses the ad.
     * @param onAdDismissed Fires when the ad is dismissed (user skip/close, load
     *     failure, or poll timeout). The caller uses this to gate playback
     *     start.
     */
    fun showRewardedBeforePlayback(
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
            // Already shown for this launch — do NOT fire onAdDismissed here.
            // Firing it would open the playback gate prematurely (before the
            // already-in-flight ad is dismissed by the user), which would
            // cause the video to start playing under the ad. The in-flight
            // show's own onAdDismissedFullScreenContent callback will open
            // the gate when the user actually closes/skips the ad.
            Log.d(TAG, "Manual ad already requested for this launch — ignoring duplicate request (gate stays closed).")
            return
        }

        val ad = loadedManualAd
        if (ad == null) {
            Log.d(TAG, "Manual rewarded ad not ready — kicking off preload + polling for up to ${MANUAL_AD_POLL_TIMEOUT_MS}ms.")
            // Kick off a fresh load right now in case the initial preload
            // failed or hasn't run yet (e.g. user tapped very quickly after
            // a cold launch). preloadManualAd is a no-op if one is already
            // loading, so this is safe to call unconditionally.
            preloadManualAd(activity.applicationContext)
            manualAdDismissedCallback = onAdDismissed
            pollAndShowManualAd(activity)
            return
        }
        showLoadedManualAd(activity, ad, onAdDismissed)
    }

    /**
     * Polls the manual ad for up to [MANUAL_AD_POLL_TIMEOUT_MS] (every
     * [AD_POLL_STEP_MS]). Shows it when it loads, or fires
     * [manualAdDismissedCallback] on timeout so playback is never blocked
     * forever on a missing ad.
     */
    private fun pollAndShowManualAd(activity: Activity) {
        Thread {
            var waited = 0L
            while (waited < MANUAL_AD_POLL_TIMEOUT_MS) {
                Thread.sleep(AD_POLL_STEP_MS)
                waited += AD_POLL_STEP_MS
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
            Log.d(TAG, "Manual ad poll timed out after ${MANUAL_AD_POLL_TIMEOUT_MS}ms — proceeding without ad.")
            mainHandler.post { fireManualDismissed() }
        }.start()
    }

    /** Internal: presents an already-loaded manual rewarded ad. */
    private fun showLoadedManualAd(
        activity: Activity,
        ad: RewardedAd,
        onAdDismissed: () -> Unit
    ) {
        manualAdDismissedCallback = onAdDismissed
        currentManualAd = ad
        loadedManualAd = null
        preloadManualAd(activity)

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                // The user skipped/closed the ad (or it finished naturally).
                // This fires after the 5-second skip button is tapped, OR
                // when the ad plays to completion — either way the real
                // video can now start.
                Log.d(TAG, "Manual ad dismissed by user — firing callback (video will start now).")
                currentManualAd = null
                fireManualDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Manual ad failed to show: ${error.message}")
                currentManualAd = null
                fireManualDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                // The rewarded video ad is now playing full-screen. After 5
                // seconds the SDK will reveal a skip / close button (just
                // like a YouTube ad). Playback is gated on the dismissal
                // callback so the real video will NOT start until the user
                // skips/closes the ad.
                Log.d(TAG, "Manual rewarded ad is playing — skip button appears after 5s.")
            }
        }
        runCatching {
            // show() with an OnUserEarnedRewardListener so the rewarded ad
            // can be displayed (required by the API). We don't use the
            // reward — the "reward" here is simply unlocking the video.
            ad.show(activity) { rewardItem: RewardItem ->
                Log.d(TAG, "Ad reward earned: ${rewardItem.amount} ${rewardItem.type} — unlocking video.")
            }
        }.onFailure {
            Log.w(TAG, "Manual ad.show() threw: ${it.message}")
            currentManualAd = null
            fireManualDismissed()
        }
    }

    /** Fires the manual ad dismissed callback once, then clears it. */
    @Synchronized
    private fun fireManualDismissed() {
        val cb = manualAdDismissedCallback
        manualAdDismissedCallback = null
        cb?.invoke()
    }

    // ── Next-episode ad ──────────────────────────────────────────────────── //

    /**
     * Shows the next-episode rewarded video ad **during the auto-load gap** —
     * i.e. right after one episode ends and the next begins resolving. Call
     * this from PlayerActivity's onEpisodeEnded path, BEFORE the next episode
     * starts playing. The ad plays full-screen (YouTube-style) with a skip
     * button after 5 seconds; the next episode does NOT begin resolving/playing
     * until the user skips/closes the ad.
     *
     * @param onAdDismissed Fires when the ad is dismissed (user skip/close, or
     *     load failure). The caller should advance to the next episode INSIDE
     *     this callback so auto-playback does not begin until the ad is closed.
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
            // that window it is shown (and its own dismissal callback handles
            // the rest); if it doesn't, we fire onAdDismissed so the caller
            // (and the next episode) is not blocked forever.
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

    /** Internal: actually presents an already-loaded next-episode rewarded ad. */
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
                // The user skipped/closed the ad (skip button after 5s) or it
                // finished. The next episode can now begin resolving/playing.
                Log.d(TAG, "Next-episode ad dismissed by user — firing callback.")
                currentNextEpisodeAd = null
                fireNextEpisodeDismissed()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Next-episode ad failed to show: ${error.message}")
                currentNextEpisodeAd = null
                fireNextEpisodeDismissed()
            }

            override fun onAdShowedFullScreenContent() {
                // The rewarded video ad is now playing full-screen. After 5
                // seconds the SDK reveals a skip / close button. The next
                // episode does NOT begin resolving/playing until this
                // dismissal fires.
                Log.d(TAG, "Next-episode rewarded ad is playing — skip button appears after 5s.")
            }
        }
        runCatching {
            ad.show(activity) { rewardItem: RewardItem ->
                Log.d(TAG, "Next-episode ad reward earned: ${rewardItem.amount} ${rewardItem.type}.")
            }
        }.onFailure {
            Log.w(TAG, "Next-episode ad.show() threw: ${it.message}")
            currentNextEpisodeAd = null
            fireNextEpisodeDismissed()
        }
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

    // ── Playback-start gate (the "ads stop when playback starts" hook) ────── //

    /**
     * Called by PlayerActivity the instant the selected show/movie actually
     * starts playing (ExoPlayer STATE_READY). This stops all ad activity:
     *  • cancels any pending next-episode ad that hasn't been shown yet,
     *  • ensures no ad is presented on top of playing video.
     *
     * If a rewarded ad is already fullscreen (shown during the loading gap),
     * it remains until the user dismisses it — at which point the already-
     * buffered/playing video is revealed. We do not show any further ad until
     * the next loading gap.
     */
    fun onPlaybackStarted() {
        playbackActive.set(true)
        nextEpisodeShowPending.set(false)
        Log.d(TAG, "Playback started — all pending ads suppressed.")
    }

    /**
     * Resets the per-launch "already shown" guard for the manual ad.
     * Called by PlayerActivity when a brand-new manual launch begins.
     */
    fun resetForNewLaunch() {
        manualAlreadyShown.set(false)
        playbackActive.set(false)
        nextEpisodeShowPending.set(false)
        // If a stale callback exists (e.g. Activity destroyed mid-ad), fire it
        // so nothing is left hanging.
        fireManualDismissed()
    }
}
