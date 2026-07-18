# LookMovieTomb Kodi Addon — Headless Port for MarioCart

This directory contains the **actual source of the `plugin.video.lookmovietomb`
Kodi addon** (v0.8, the latest version), bundled in the MarioCart repo so that
the headless engine's Kotlin port (`LookMovieHeadlessExtractor.kt` +
`KodiEngine`) can be verified against the real addon source it was ported from.

The addon source lives here for **reference and verification**. The MarioCart
app does **not** run Kodi or Python — the extraction logic is ported to pure
Kotlin/OkHttp. This directory is the ground truth that the Kotlin port must
match.

---

## Version

**v0.8** — sourced from the `mbebe/blomqvist` GitHub repository
(PR [#1088](https://github.com/mbebe/blomqvist/pull/1088), author `amerr97`).

> **Note on the version number in `addon.xml`:** The upstream v0.8 zip ships
> with `addon.xml` still listing `version="0.7"` — the addon author forgot to
> bump the version attribute when packaging v0.8. The `main.py` content and
> `changelog.txt` both clearly reflect v0.8. This copy preserves the upstream
> files verbatim; see the v0.8 changelog entry below for what changed.

### v0.8 changelog
```
0.8 — fix captcha, add year 2024, 2025
0.7 — fix movies error
0.6 — fix urllib3 error
0.5 — fix categories
0.4 — fix tvshows
0.3 — fix captcha
0.2 — filters added
0.1 — initial version
```

### What changed in v0.8 (vs v0.6)

A `diff` of `main.py` between v0.6 and v0.8 reveals exactly three changes:

1. **Captcha detection** (lines 342 & 566):
   ```python
   # v0.6:
   if '>Thread Defence' in html:
   # v0.8:
   if 'g-recaptcha' in html:
   ```
   LookMovie replaced their "Thread Defence" interstitial marker with a
   Google reCAPTCHA v2 marker (`g-recaptcha`).

2. **Stream selection** (line 590):
   ```python
   # v0.6:
   vid_source = list((html.get('streams', None)).values())[0]
   # v0.8:
   vid_source = [x for x in list((html.get('streams', None)).values()) if x][0]
   ```
   Empty/falsy stream values are now filtered out before picking the first
   (highest-quality) stream.

3. **Year filter range** (lines 692 & 694):
   ```python
   # v0.6:
   label = [str(x) for x in xrange(1913, 2024)][::-1]
   # v0.8:
   label = [str(x) for x in xrange(1913, 2026)][::-1]
   ```
   The year filter now includes 2024 and 2025.

`serverHTTP.py` is **unchanged** between v0.6 and v0.8 (identical 6116 bytes).

---

## Extraction flow (the addon's `main.py` → the app's `LookMovieHeadlessExtractor.kt`)

The addon's `ListLinks()` function performs a three-step flow that the Kotlin
port reproduces 1:1:

### Step 1 — SEARCH
```
GET https://www.lookmovie2.to/movies/search/page/1?q=<title>
```
(TV: `/shows/search/page/1?q=<title>`)

Parse the HTML for the first matching slug: `/movies/view/{id-slug}`
(TV: `/shows/view/{id-slug}`). The app's `pickBestSlug()` scores candidates
by title + year match to pick the right result on ambiguous searches.

### Step 2 — STORAGE
```
GET https://www.lookmovie2.to/movies/play/{slug}
```
(TV: `/shows/play/{slug}`)

Regex out the JS storage object:
- **Movies:** `movie_storage"\\]\\s*=\\s*({.*?})` → extract `hash`,
  `id_movie`, `expires`
- **TV:** `show_storage"\\]\\s*=\\s*({.*?};\\n\\s+)` → extract `hash`,
  `expires`, then scan the `seasons` array for the episode matching
  `(season, episode)` and read its `id_episode`

### Step 3 — SECURITY API
```
GET https://www.lookmovie2.to/api/v1/security/movie-access?id_movie=<id>&hash=<hash>&expires=<expires>
```
(TV: `/api/v1/security/episode-access?id_episode=<id>&hash=<hash>&expires=<expires>`)

Headers: `Referer: <play page>` + `X-Requested-With: XMLHttpRequest`

Returns JSON:
```json
{
  "streams": { "1080p": "https://...m3u8", "720p": "https://...m3u8", ... },
  "subtitles": [...]
}
```

The v0.8 addon picks the first **non-empty** stream value
(`[x for x in list(streams.values()) if x][0]`), which is the highest quality.
The app's `LookMovieHeadlessExtractor.kt` does the same — iterating keys and
selecting the first whose value is a non-blank `http` URL.

### Step 4 — PLAY (headless, no proxy)

The addon runs a **local HTTP proxy** (`serverHTTP.py`) that injects the
`Cookie: t_hash={hash}` header on every `.m3u8` and segment request:

```python
# serverHTTP.py
hash_ = addon.getSetting('hash_')
cok = {'t_hash': hash_}
resp = session.get(url=pathx, headers=headersx, verify=False, cookies=cok)
self.send_header('Cookie', 't_hash=' + hash_)
```

The app **does not need a proxy**. ExoPlayer's `DefaultHttpDataSource.Factory`
sends the `headers` map on **every** playlist + segment fetch, so the Kotlin
port attaches `Cookie: t_hash={hash}` to the returned `Result.Stream` headers
and ExoPlayer carries it automatically — a true headless play with zero extra
server process.

---

## User-Agent

Both the addon and the app use the same User-Agent to match the request shape
the server expects:
```
Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0
```

---

## Captcha handling

LookMovie intermittently shows a **reCAPTCHA v2** interstitial (v0.8 marker:
`g-recaptcha`, previously `>Thread Defence` in v0.6). The addon's
`resolveCaptcha()` + `girc()` + `recaptcha_v2.py` solve it using a
Google token service.

Solving reCAPTCHA headlessly on-device is out of scope for the app. When the
app detects `g-recaptcha` on the search or play page, it returns
`Result.Error` and the rest of the parallel extractor race covers the title
(the other direct providers like VidSrc, VidStorm, etc.).

---

## File map

```
addon/plugin.video.lookmovietomb/
├── addon.xml              # Kodi addon manifest (v0.8 content; version attr says 0.7 — upstream bug)
├── changelog.txt          # Version history (0.1 → 0.8)
├── main.py                # Plugin source — the extraction logic (751 lines)
├── serverHTTP.py          # Local HTTP proxy injecting t_hash cookie (reference only — app uses ExoPlayer headers instead)
├── icon.png               # Addon icon
├── fanart.jpg             # Addon fanart
└── resources/
    ├── settings.xml       # Kodi settings UI
    ├── __init__.py
    ├── images/            # UI images
    ├── lib/
    │   ├── brotli-dict        # Brotli compression dictionary
    │   ├── brotlipython.py    # Brotli decompression (pure Python)
    │   ├── cmf2.py            # Cloudflare challenge solver v2
    │   ├── cmf3.py            # Cloudflare challenge solver v3
    │   ├── recaptcha_v2.py    # reCAPTCHA v2 solver
    │   └── jscrypto/          # JavaScript crypto (AES/PKCS7) for captcha token
    └── skins/Default/     # Kodi skin XML + media
```

---

## Kotlin port files

The headless engine that ports this addon's logic to the MarioCart app:

| File | Purpose |
|------|---------|
| `app/src/main/java/com/mariocart/app/data/server/LookMovieHeadlessExtractor.kt` | Pure-OkHttp port of `main.py` extraction flow (1:1) |
| `app/src/main/java/com/mariocart/app/data/engine/KodiEngine.kt` | Engine core: resolve / pre-resolve / cache / single-flight de-dup |
| `app/src/main/java/com/mariocart/app/data/engine/StreamCache.kt` | In-memory + JSON disk cache with TTL for resolved streams |
| `app/src/main/java/com/mariocart/app/data/engine/KodiEngineService.kt` | Foreground service keeping the engine alive in background |

All four files together deliver the "Kodi-like engine running in the
background" that pulls movies/TV from the LookMovieTomb addon flow — pure
headless OkHttp, **no WebView, no Kodi runtime, no Python**.
