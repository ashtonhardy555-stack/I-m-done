package com.ashtonhardy.piratesfilmcove.ui

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/**
 * AdManager — owns the AdMob **Banner** ad configuration.
 *
 * Banner ads are shown **only on loading screens** (the screen displayed while
 * a movie or show is resolving its stream URL). Three banner ads are displayed
 * simultaneously on that loading screen. They are destroyed automatically the
 * moment the loading screen disappears (i.e. when the video starts playing or
 * an error is shown). No ads appear anywhere else in the app.
 *
 * The banner ad unit ID is [BANNER_AD_UNIT_ID]. The AdMob app ID (with "~")
 * lives in `AndroidManifest.xml`.
 */
object AdManager {

    private const val TAG = "AdManager"

    /**
     * AdMob **Banner** ad-unit ID (`2488038861`). This is a banner-only unit.
     * Three instances of this banner are shown on the loading screen while a
     * movie/show is loading. They are destroyed when playback starts.
     */
    const val BANNER_AD_UNIT_ID = "ca-app-pub-8069271908902310/2488038861"

    /** Whether the SDK has been initialised. */
    @Volatile
    private var initialised = false

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
     * Best-effort "warm up" — ensures the Mobile Ads SDK is initialised early.
     * No-op if already done.
     */
    fun warmUp(context: Context) {
        if (!initialised) init(context)
    }
}
