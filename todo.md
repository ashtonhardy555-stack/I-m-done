# Piratesfilm Cove — app improvements

## 1. New Kodi addon sources (no debrid / no trakt)
- [x] Research NEW Kodi addons online (Free99 + others) for fresh scraper/API URLs NOT already in the app
- [x] Create VidSrcToExtractor.kt — vidsrc.to RC4 decryption flow (pure OkHttp, contains VidPlay + FileMoon internally)
- [x] Analyse existing extractors NOT yet wired into the KodiEngine (15 of them, all TMDB-id based)
- [x] Wire VidSrcTo + 15 existing headless extractors into KodiEngine.kt (adapters + addons list)

## 2. UI bugs
- [x] Fix "Load More" buttons disappearing prematurely (HomeViewModel, MoviesViewModel, TvViewModel, SearchViewModel, BrowseViewModel — base canLoadMore on RAW TMDB page size)
- [x] Fix hero banner being clipped on home screen (HeroBanner height / HomeScreen top padding / DeviceInfo TV dims)
- [x] Fix search results disappearing before Enter (SearchViewModel/SearchScreen IME noise — updateQuery same-value guard + onKeyEvent Enter handler that hides keypad without clearFocus)
- [x] Show search results in side-to-side layout + active category/genre label (SearchScreen — LazyVerticalGrid with side-to-side genre pill LazyRow + active category/genre label header, mirrors BrowseScreen)

## 3. Ship it
- [x] Commit, push, create PR to GitHub on feat/more-addons-and-ui-fixes
