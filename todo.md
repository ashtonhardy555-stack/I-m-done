# Rename app to "mariokart" + Add interstitial ads on manual play

## Research
- [x] Explore repo structure (Android/Kotlin streaming app)
- [x] Identify current name refs (strings.xml, launcher icon comment, settings.gradle)
- [x] PlayerActivity launched via newIntent() from MainActivity (manual clicks)
- [x] Auto-play next episode = same activity, STATE_ENDED re-fires LaunchedEffect (no onCreate) -> ad suppressed naturally
- [x] Auto-update already works (AutoUpdater + GitHub Releases, triggered at app open)

## Rename to "mariokart" (display name only — preserves auto-update + existing installs)
- [x] Update strings.xml app_name -> "mariokart"
- [x] Update launcher icon comment + settings.gradle rootProject name

## Add AdMob interstitial ad (unit ca-app-pub-8069271908902310/6722496029)
- [x] Add Google Mobile Ads dependency to app/build.gradle.kts
- [x] Add AdMob App ID meta-data to AndroidManifest.xml
- [x] Create AdManager (loads + shows interstitial)
- [x] Wire ad into PlayerActivity: show ad BEFORE playback on MANUAL clicks only
- [x] Ensure auto-play next episode does NOT trigger ads (guard with isAutoPlay flag)

## Verify & ship
- [ ] Verify changes compile-consistent (imports, references)
- [ ] Commit on a new branch, push, open PR
