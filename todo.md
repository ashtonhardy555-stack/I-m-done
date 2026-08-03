# Todo: Remove auto-close + ensure video waits for user to close ad

## Changes
- [ ] Remove 10s auto-close timer for manual ad (ad stays until user closes)
- [ ] Remove 10s auto-close timer for next-episode ad (ad stays until user closes)
- [ ] Fix manual ad gate so video never plays before user closes the ad
  - manualAlreadyShown early-return must NOT fire onAdDismissed (opens gate prematurely)
- [ ] Show "Your video will play after a short ad" message while ad is showing

## Ship
- [ ] Commit + push to main
- [ ] Verify CI build
- [ ] Report to user
