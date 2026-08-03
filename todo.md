# Todo: Fix Manual Pre-Playback Ad Not Showing for Movies/Shows

## Investigation
- [x] Pull latest main and read current AdManager.kt
- [x] Read current PlayerActivity.kt (manual ad LaunchedEffect + extraction gate)
- [x] Read MainActivity.kt launch paths — all pass isAutoPlay=false ✓
- [x] Read PiratesfilmCoveApplication.kt (init) ✓
- [x] Root cause: manual ad not preloaded in time → 5s poll times out → no ad shown.
        Next-episode ad works because SDK has minutes to load by then.

## Fix (AdManager.kt)
- [ ] Add auto-retry on manual ad load failure (retry after delay)
- [ ] Increase manual ad poll timeout (5s → 15s) to wait for slow SDK init
- [ ] Kick off a fresh preload from showInterstitialBeforePlayback when ad is null
- [ ] Add a "warm-up" preload trigger so the manual ad loads ASAP on app open
- [ ] Verify code compiles (logic check)

## Ship
- [ ] Commit and push to main (triggers CI build)
- [ ] Report to user
