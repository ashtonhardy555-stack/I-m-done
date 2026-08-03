# Todo: Fix Manual Pre-Playback Ad — take 2

## Problem (user feedback)
- Shows auto-play next episode ad: WORKS ✓
- Manual tap on movie/show: NO ad, just longer loading ✗
- My 15s poll made loading slower without ever showing an ad
- => Manual ad unit (6722496029) is NOT getting ad fill, OR show() path is broken

## Investigation
- [x] Re-read current AdManager.kt show path for manual ad
- [ ] Re-read PlayerActivity manual ad LaunchedEffect + adGateOpen
- [ ] Determine real root cause (ad fill vs show() vs gate logic)
- [ ] Decide fix approach

## Fix
- [ ] Implement real fix
- [ ] Revert/shorten the 15s poll that made loading slow
- [ ] Verify logic

## Ship
- [ ] Commit + push to main
- [ ] Verify CI build
- [ ] Report to user
