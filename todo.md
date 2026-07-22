# Fix: Android TV UX — top bar, fully-visible hero banner, and Load More button

## Goals (all for Android TV)
1. **Replace the side rail with a top bar** so pressing Left to scroll back through movies doesn't pop open a sidebar.
2. **Hero banner fully visible** — the top half is cut off and can't be selected/seen. Make the hero banner fully visible (no clipping), keep the Play button selected by default, and make sure the user knows there's more content below.
3. **Load More button** — missing on category/genre screens, and sometimes disappears prematurely after pressing it on the home screen.

## Tasks

### 1. MainActivity.kt — replace TV side rail with top bar
- [x] Remove the entire TV side-rail layout branch (state, onKeyEvent, AnimatedVisibility, TvSideNav).
- [x] Remove the side-rail BackHandler.
- [x] Remove the side-rail auto-reveal/auto-retract LaunchedEffects.
- [x] Unify the layout: one Box with NetflixScreenSwitch + NetflixTopBar overlay for BOTH phone and TV.
- [x] Remove the now-dead TvSideNav / SideNavHintChip / TvNavItem composables.
- [x] Make NetflixTopBar D-pad focusable for TV (red focus ring, larger sizing) without stealing initial focus from the hero Play button.

### 2. DeviceInfo.kt ResponsiveDims
- [x] Add a TV topContentPadding so non-hero screens push their first row below the top bar.
- [x] Set navRailWidth = 0 for TV (no side rail anymore).
- [x] Adjust TV heroHeight so the full hero fits within the visible viewport.

### 3. HeroBanner.kt — full visibility + Play selected + "more below" hint
- [x] Hero height reduced to 500dp so full hero fits in TV viewport.
- [x] Keep the Play button as the default D-pad focus.
- [x] "scroll down for more" hint stays visible (55% alpha) even when Play is focused.
- [x] Hero content bottom-aligned with safe-area bottom inset.

### 4. Load More button fixes
- [x] Investigate ContentRow.kt for the Load More button.
- [x] Investigate SearchScreen.kt / BrowseScreen.kt — both already have Load More buttons.
- [x] Fix the premature-disappear bug in HomeViewModel.kt (loadMoreTrending now checks raw TMDB page size, not movie-filtered size).
- [x] Update ContentRow.kt comment to reference top bar instead of old side rail.
- [x] Update HomeViewModel.kt canLoadMore comment to document the raw-size fix.

### 5. Build / compile check
- [x] Run a gradle compile to ensure no errors. (BUILD SUCCESSFUL — only pre-existing warnings, no errors)

### 6. Commit + push branch + open PR
- [ ] Create a feature branch, commit, push, and open a pull request.
