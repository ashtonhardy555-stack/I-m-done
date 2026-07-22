# Fix: Load More, Sidebar D-pad, Top Banner, Search Timeout

## Issues reported
1. Load More button missing in category browsing
2. Sidebar opens on every left D-pad press (not just at leftmost edge)
3. Full top banner (hero) not visible
4. Search results disappear if you sit on the search keypad too long

## Root causes
1. BrowseViewModel.loadGenre sets canLoadMore=false when raw page < 20.
   Some TMDB genre pages return <20, killing the button immediately.
   Also, after filtering, the grid may show very few items but the
   button should still appear (there ARE more TMDB pages). The fix:
   keep canLoadMore true when TMDB returned a full page, and also
   auto-load the next page if the filtered result is too short (so the
   user always sees a reasonable number of cards + the Show More button).
2. MainActivity.kt outer onKeyEvent on the content Box fires for EVERY
   Key.DirectionLeft KeyUp, because the focusGroup() on the same Box
   lets the event bubble up even when the focus is NOT at the leftmost
   edge. The fix: use a dedicated flag that tracks whether the focus is
   at the left boundary of the content, and only open the rail then.
   Practically: intercept Left at the screen level and check whether
   focus is already at the first item of a row / first genre pill.
3. HeroBanner content is at BottomStart with bottom padding 40dp+safeArea.
   The heroHeight is 600dp on TV. The content (title+rating+overview+
   buttons) should fit, but the issue is the bottom padding + safeArea
   (27dp) = 67dp pushes content up, and the LazyColumn in HomeScreen may
   be cutting off the top. Actually the issue is likely that the hero
   height (600dp) is not enough to show the full backdrop + content on
   some TV overscan, or the content is clipped. Fix: increase heroHeight
   on TV and reduce bottom padding so the full content is visible.
4. SearchViewModel.updateQuery cancels the searchJob on every keystroke.
   When the user stops typing (sits on the keypad), the last searchJob
   runs the 700ms delay, fetches, then calls refineResults which
   replaces _results with the filtered subset. BUT the SearchScreen has
   a LaunchedEffect(searchCommitted, results) that does focusManager.
   clearFocus() — no, that's in the onKeyEvent. Actually the issue is
   that refineResults replaces results, but if the user never "commits"
   the search, the results should stay. The issue is likely that
   updateQuery is being called again with the same value (recomposition?)
   which cancels and restarts the job, and during the 700ms delay the
   results are cleared. OR the issue is that the focusGroup /
   LaunchedEffect re-triggers. The fix: don't clear results when the
   query hasn't changed, and make the search job not cancel if the same
   query is re-issued.

## Implementation plan
- [x] Read all relevant source files (diagnostics complete)
- [ ] Fix Issue 1: BrowseViewModel — keep canLoadMore true on full pages,
      auto-load next page if filtered result too short
- [ ] Fix Issue 2: MainActivity — track left-edge focus, only open rail
      when truly at the left boundary
- [ ] Fix Issue 3: HeroBanner / DeviceInfo — increase hero height on TV,
      adjust padding so full content is visible
- [ ] Fix Issue 4: SearchViewModel — don't clear results on re-query,
      guard against spurious re-issues
- [ ] Build & verify: create PR, merge, check CI build passes
