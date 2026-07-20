# I'm Done — Web Edition

A web version of the **I'm Done** Android streaming app, built to use **only the LookMovie extractor** and the **Kodi engine** for playback. All other servers (VidLink, VidSrc, 2Embed, NoTorrent, VidStorm, etc.) are excluded — exactly as requested.

## What it is

A Netflix-style single-page web app that browses the TMDB catalog (movies & TV) and plays streams resolved through the **LookMovie addon flow** (the `plugin.video.lookmovietomb` Kodi addon), orchestrated by a headless **Kodi engine** running in the browser.

## Architecture — how it maps to the Android app

The web edition is a faithful port of the app's data + engine layer from Kotlin/OkHttp to JavaScript/fetch. Each module below is a 1:1 port of the corresponding Android file:

| Web file | Android source | Purpose |
|---|---|---|
| `js/lookmovie.js` | `data/server/LookMovieHeadlessExtractor.kt` | The LookMovie addon flow: search → storage → security API → `.m3u8` |
| `js/kodiEngine.js` | `data/engine/KodiEngine.kt` | The "Kodi-like engine running in the background": cache, single-flight de-dup, pre-resolve |
| `js/tmdb.js` | `data/api/TmdbApi.kt` + `ContentRepository.kt` | TMDB catalog: trending, browse, search, detail, seasons |
| `js/watchProgress.js` | `data/model/WatchProgress.kt` + `WatchProgressStore.kt` | Continue-watching tracking (localStorage) |
| `js/app.js` | UI screens (Home, Movies, TV, Search, Detail, Player) | SPA controller + all screens |
| `css/theme.css` | `ui/theme/Color.kt` | Netflix-style theme (exact colors ported) |
| `index.html` | `PlayerActivity.kt` (ExoPlayer) | HLS.js replaces ExoPlayer for `.m3u8` playback |

### The LookMovie extraction flow (the "Kodi addon")

`lookmovie.js` reproduces the addon's exact request sequence:

1. **Search** — `GET /movies/search/page/1?q=<title>` (TV: `/shows/search/...`). Parse the HTML for `<a href="/movies/view/{id-slug}">` links, then pick the slug that best matches the requested title + year.
2. **Storage** — `GET /movies/play/{slug}`. Regex out the `movie_storage` / `show_storage` JS object to get `hash`, `id_movie`/`id_episode`, and `expires` (TV: scan the `seasons` array for the episode matching season+episode, handling arbitrary field order).
3. **Security API** — `GET /api/v1/security/movie-access?id_movie=&hash=&expires=` (TV: `episode-access`) with `Referer` + `X-Requested-With: XMLHttpRequest`. Returns JSON with a `streams` object; take the first non-empty `http` stream (highest quality).
4. **Play** — The addon runs a local proxy (`serverHTTP.py`) that injects the `t_hash={hash}` cookie on every `.m3u8`/segment request. The web edition attaches that cookie to the HLS.js loader config instead — a true headless play with zero extra server.

### The Kodi engine (orchestration)

`kodiEngine.js` is the "Kodi addon running in the background":

- **resolve on demand** — the Player asks the engine; a cache hit returns instantly, otherwise a background resolve starts.
- **pre-resolve** — Home/Browse/Detail screens warm up the next likely title so Play is instant (the "working ahead" behaviour).
- **cache** — resolved streams go into memory + `localStorage` (45-min TTL) so replays never re-hit the network.
- **single-flight** — concurrent requests for the same title share one resolve.

Only the LookMovie addon is plugged into the engine (`addons = [lookmovieAddon]`). No other servers.

### CORS

LookMovie doesn't send CORS headers, so the extraction requests (search/play/security) go through public CORS proxies. The final `.m3u8` stream itself is **not** proxied — HLS.js fetches it directly (cross-origin media + range requests work natively via MSE), with the `t_hash` cookie attached.

LookMovie's nginx 403s datacenter IPs, so resolution may be intermittently blocked or hit a reCAPTCHA interstitial. When that happens the engine reports the miss gracefully (matching the Android extractor's best-effort behaviour).

## Run locally

```bash
# from the repo root
python3 serve.py          # serves web/ on http://localhost:8080
# or
cd web && python3 -m http.server 8080
```

Then open `http://localhost:8080` in a browser.

## Files

```
web/
├── index.html          # entry point (loads HLS.js + modules)
├── css/theme.css       # Netflix-style theme
├── js/
│   ├── tmdb.js         # TMDB catalog client
│   ├── watchProgress.js# continue-watching store
│   ├── lookmovie.js    # LookMovie extractor (addon flow)
│   ├── kodiEngine.js   # Kodi engine (cache + orchestration)
│   └── app.js          # SPA controller + all screens
└── img/
```

## Tech

- Vanilla JS (no build step, no framework) — fast and simple.
- [HLS.js](https://github.com/video-dev/hls.js/) for `.m3u8` playback (replaces ExoPlayer).
- TMDB for the catalog (same API key + endpoints as the app).
- localStorage for the stream cache + watch progress.
