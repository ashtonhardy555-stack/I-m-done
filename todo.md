# Todo: Banner ads only on loading screens (simplest approach)

## Tasks
- [ ] Rewrite AdManager.kt: replace rewarded video ads with simple banner ad helpers (unit 2488038861)
- [ ] Remove ad gate logic from PlayerActivity (adGateOpen, rewarded ad LaunchedEffect)
- [ ] Add 3 banner AdViews to the LoadingScreen composable
- [ ] Remove next-episode rewarded ad call (banners show automatically when loading)
- [ ] Remove/simplify onPlaybackStarted (banners die when loading screen goes away)
- [ ] Update ProGuard rules for banner ads
- [ ] Update AndroidManifest comment + other comments
- [ ] Commit and push, verify CI build
- [ ] Report to user
