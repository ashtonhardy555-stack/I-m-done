# Optimization & LookMovie Alignment — Task Plan

## 1. Fix pre-existing VidLink URL bug
- [x] Remove stale movie ID from VidLink baseUrl in ContentRepository.kt

## 2. Replace PlayerActivity.kt with optimized player
- [x] Write full ExoPlayer + WebView fallback + LookMovie direct resolution player
- [x] Include parallel server probing, ad blocking, playback controls, watchdog timers

## 3. Update MainActivity.kt
- [x] Use new PlayerActivity.newIntent() signature (pass title, handle season/episode)
- [x] Call ServerManager.initialize(this) in onCreate

## 4. Verify & ship
- [ ] Verify all Kotlin files for syntax correctness
- [ ] Commit and push branch optimize/perf-lookmovie-alignment
- [ ] Create Pull Request
