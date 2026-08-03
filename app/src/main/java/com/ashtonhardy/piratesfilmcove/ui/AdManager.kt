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
 * AdManager — owns the AdMob interstitial ad shown before playback.
 *
 * Behaviour the product requires:
 *  • An interstitial ad is shown the first time the user **manually** taps a
 *    movie or TV episode to watch it (a fresh PlayerActivity launch).
 *  • The ad is **NOT** shown when the player auto-plays the next episode of a
 *    show — that advance happens inside the same PlayerActivity instance
 *    (on STATE_ENDED the extraction LaunchedEffect simply re-fires), so
 *    PlayerActivity.onCreate() — the only place that calls
 *    [showInterstitialBeforePlayback] — never runs again for the auto-play.
 *    This keeps the binge experience ad-free exactly as requested.
 *
 * Implementation notes:
 *  • The Mobile Ads SDK is initialised once in
 *    PiratesfilmCoveApplication.onCreate() via [init].
 *  • The interstitial is preloaded in the background right after init so it is
 *    usually ready by the time the user taps something. If it isn't ready yet
 *    when a manual launch happens, we skip the ad and just play — we never
 *    block playback on a network ad load (better UX, and matches "don't show
 *    ads when auto-playing" spirit of never getting in the way of the video).
 *  • A per-show flag ([alreadyShownThisSession]) guards against showing the ad
 *    more than once for a single manual launch, even if onCreate runs twice
 *    (e.g. a config-change recreation).
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * The AdMob interstitial ad-unit ID for this app.
     * App ID (with "~") lives in AndroidManifest.xml; this is the ad unit
     * (with "/") that identifies the interstitial placement.
     */
    const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-8069271908902310/6722496029"

    /** The currently loaded interstitial, or null while loading / after it
     *  has been shown and consumed. */
    @Volatile
    private var loadedAd: InterstitialAd? = null

    /** True while a load is in flight, to avoid stacking duplicate requests. */
    private val loading = AtomicBoolean(false)

    /** Prevents double-showing across an Activity recreation for one launch. */
    private val alreadyShownThisSession = AtomicBoolean(false)

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

    /**
     * Initialises the Mobile Ads SDK and kicks off a background preload of the
     * interstitial so it is ready when the user taps something to watch.
     * Safe to call multiple times; only the first call does work.
     */
    fun init(context: android.content.Context) {
        if (initialised) return
        initialised = true
        runCatching {
            com.google.android.gms.ads.MobileAds.initialize(context) {
                Log.d(TAG, "Mobile Ads SDK initialised.")
                preload(context)
            }
        }.onFailure { Log.w(TAG, "MobileAds.initialize failed: ${it.message}") }
    }

    /**
     * Starts (or re-starts) a background load of the interstitial if one isn't
     * already loaded or loading. Called from [init] and again after an ad is
     * shown so the next manual launch has a fresh ad ready.
     */
    fun preload(context: android.content.Context) {
        if (loadedAd != null || !loading.compareAndSet(false, true)) return
        val request = AdRequest.Builder().build()
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_AD_UNIT_ID,
            request,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    loadedAd = ad
                    loading.set(false)
                    Log.d(TAG, "Interstitial ad loaded and ready.")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadedAd = null
                    loading.set(false)
                    Log.w(TAG, "Interstitial failed to load: code=${error.code} msg=${error.message}")
                }
            }
        )
    }

    /**
     * Shows the interstitial ad before playback, **once per manual launch**.
     *
     * Call this from [PlayerActivity.onCreate] for a manual (user-initiated)
     * launch only. It returns immediately — playback should proceed in
     * parallel regardless; the ad simply overlays on top when ready and the
     * video is already waiting underneath. If no ad is loaded yet, or it has
     * already been shown for this launch, this is a no-op so playback is never
     * blocked.
     *
     * @param activity The PlayerActivity hosting playback.
     * @param isAutoPlay True only when the launch is an auto-advance (never
     *                   passed from a manual tap). When true the ad is
     *                   suppressed as a belt-and-braces guard.
     */
    fun showInterstitialBeforePlayback(activity: Activity, isAutoPlay: Boolean) {
        // Never show an ad for an auto-play next-episode launch.
        if (isAutoPlay) return
        // Only one ad per manual launch (survives config-change recreation).
        if (!alreadyShownThisSession.compareAndSet(false, true)) return

        val ad = loadedAd
        if (ad == null) {
            // Not ready yet — don't block the video. Try to preload for next time.
            Log.d(TAG, "No interstitial ready yet — skipping ad, preloading for next time.")
            preload(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Ad dismissed — preloading next interstitial.")
                loadedAd = null
                preload(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "Ad failed to show: ${error.message}")
                loadedAd = null
                preload(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Ad showed fullscreen content.")
            }
        }

        runCatching {
            ad.show(activity)
        }.onFailure {
            Log.w(TAG, "ad.show() threw: ${it.message}")
            loadedAd = null
            preload(activity)
        }
    }

    /**
     * Resets the per-launch "already shown" guard. Called by PlayerActivity
     * when a brand-new manual launch begins (so the ad can show again for the
     * next title the user taps) — distinct from an in-place auto-advance.
     */
    fun resetForNewLaunch() {
        alreadyShownThisSession.set(false)
    }
}
