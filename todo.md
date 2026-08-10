# Streaming App: Continue Watching Progress + Remove Ads

## Investigation
- [x] Clone repo & inspect structure
- [x] Read WatchProgress model & WatchProgressStore
- [x] Read AdManager
- [x] Examine how WatchProgressStore is used (init, upsert, resume) in PlayerActivity, HomeScreen/HomeViewModel, MainActivity
- [x] Examine how AdManager is used across screens (init, warmUp, showInterstitialAd, banner views)
- [x] Examine AndroidManifest for AdMob app id & permissions

## ROOT CAUSE FOUND
- WatchProgressStore.init() IS called at app start
- Player DOES save progress periodically + on destroy + on STATE_ENDED
- BUG: MainActivity.launchResume() for TV looked up WatchProgressStore.get("tv_${item.id}")
  but the real key is tv_${id}_S{season}_E{episode} -> always returned null -> resumed S1E1
  instead of the episode the user was on. Fixed by passing season/episode from HomeScreen's
  wp through the onResume callback chain.

## Fix: Continue Watching -- properly save episode progress & resume next time
- [x] Ensure WatchProgressStore.init() is called at app start (already done)
- [x] Verify player saves progress periodically + on destroy + on STATE_ENDED (already done)
- [x] Verify Home continue-watching row reads from activeItems() (already done)
- [x] Fix: pass season/episode through onResume so the right episode resumes
- [x] Add player fallback: resume from saved progress by exact episode key

## Remove Ads
- [x] Delete AdManager.kt entirely (no callers remain)
- [x] Remove AdManager.init() call from PiratesfilmCoveApplication.onCreate
- [x] Remove AdManager.warmUp() from MainActivity
- [x] Remove interstitial ad preload in PlayerActivity.onCreate
- [x] Remove adDismissed/adTriggered state vars in PlayerScreen
- [x] Remove preloadBannerAds call in PlayerScreen LaunchedEffect
- [x] Remove interstitial ad gate LaunchedEffect in PlayerScreen
- [x] Remove AdGateScreen branch from UI when-block
- [x] Remove banner ads Column from LoadingScreen
- [x] Remove BannerAdView composable function
- [x] Remove AdGateScreen composable function
- [x] Remove AD_ID permission from AndroidManifest.xml
- [x] Remove AdMob APPLICATION_ID meta-data from AndroidManifest.xml
- [x] Remove play-services-ads dependency from build.gradle.kts
- [x] Remove AdMob keep rules from proguard-rules.pro
- [x] Clean up stale ad comments across files

## Build & PR
- [x] Commit changes
- [x] Push branch
- [x] Open PR with summary
