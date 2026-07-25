# Piratesfilm Cove — side rail Left-press fix

## Goal
The TV side navigation rail opens on EVERY Left press. It should ONLY open when:
- the user is on the FIRST card of a row and presses Left (nothing further left), OR
- the user is on the hero banner and presses Left

## Root cause investigation
- [x] Understand the current Left-press handling in MainActivity (outer Box onKeyEvent + inner focusGroup)
- [x] Understand how ContentRow / LazyRow focusGroup handles Left
- [x] Understand how the hero banner handles Left
- [x] Confirm whether focusGroup() consumes Left at the first item or bubbles it up
      → CONFIRMED: focusGroup() does NOT consume key events; every Left bubbles to the outer onKeyEvent which opens the rail.

## Fix — focused-element X-position tracking
- [x] Create a CompositionLocal LocalSideRailXReporter + a shared focusedX state at AppRoot
- [x] Provide the reporter from the TV content Box in MainActivity
- [x] ContentCard reports its screen X (onGloballyPositioned + LaunchedEffect) when focused
- [x] HeroButton reports X (≈0) when focused
- [x] Outer onKeyEvent: only open rail + return true when focusedX <= edge threshold; else return false so default card-to-card move runs
- [x] Fix the incorrect comment about focusGroup() consuming Left

## Verify the fix against all screens
- [x] Home: hero (x≈rowPadding → opens rail) + content rows (first card x≈rowPadding, mid-row x≫threshold)
- [x] Movies / TV: content rows via ContentRow → ContentCard (reports X)
- [x] Browse: LazyVerticalGrid first column x≈rowPadding (within threshold), cols 2-5 x>threshold
- [x] Search: overlay shown before the TV layout block — LocalSideRailXReporter is null (no-op)
- [x] Side rail visible + Left: outer onKeyEvent returns false, TvSideNav handles Right-dismiss
- [x] threshold = rowPadding + 24dp; first card & hero at x≈rowPadding, 2nd card x≫threshold
- [x] imports verified for all 3 modified files
- [x] No state write during layout (report via LaunchedEffect coroutine)

## Ship it
- [ ] Commit, push, update PR
