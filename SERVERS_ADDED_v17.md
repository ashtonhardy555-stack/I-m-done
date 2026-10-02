# Servers added: VidUp + VidCore (registry v17)

**Date:** 2026-10-02
**App version:** `1.4.1` (next release tag: `v1.4.1-build<N>`)
**Files changed:** `working-servers.json`, `app/src/main/assets/servers.json`,
`ui/player/PlayerActivity.kt`, and **two new extractors**
(`data/server/VidUpExtractor.kt`, `data/server/VidCoreExtractor.kt`)
**Server count:** 54 → 55 (VidUp added; VidCore switched on)

---

## Why

The app is **headless-only** (pure OkHttp, no WebView). A registry entry in
`working-servers.json` alone does **not** produce playback — a provider only
plays if it has a dedicated `data/server/*Extractor.kt` class wired into the
`PlayerActivity` parallel race. Two providers in the enc-dec.app ecosystem were
missing that wiring:

* **VidUp** (`vidup.to`) — was not in the registry at all.
* **VidCore** (`vidcore.io`) — had a registry row but **no extractor**, so it
  never actually fired.

Both were reverse-engineered, implemented, and **verified end-to-end** against
the live sites.

---

## New extractors

### `VidUpExtractor.kt`
Resolves a direct playable HLS URL from `vidup.to` using only plain HTTP.

### `VidCoreExtractor.kt`
Identical flow against `vidcore.io` (separate provider, separate CDN servers).

Both follow the same five-step, fully-headless flow that the existing
`VidFastExtractor` uses:

1. **Page** — `GET https://<host>/movie/{tmdbId}/` (or
   `/tv/{tmdbId}/{season}/{episode}/`). The Next.js RSC payload embeds a
   short-lived token as `\"en\":\"<token>\"` (or `\"token\":\"<token>\"`).
2. **Token exchange** — `GET https://enc-dec.app/api/enc-vidup|enc-vidcore?text=<token>`
   → `{ servers, stream, token }`.
3. **Server list** — `POST <servers>` with header `X-CSRF-Token: <csrf>` → an
   encrypted blob → `POST https://enc-dec.app/api/dec-vidup|dec-vidcore`
   `{ "text": <blob> }` → `[{ name, data }, …]`.
4. **Stream** — for each server, `POST <stream>/<data>` with the same CSRF
   header → blob → `dec-…` → `{ url, title, tracks, … }`.
5. **Pick** the first server whose `url` looks playable (`.m3u8/.mp4/.mkv/.mpd`,
   `/playlist/`, `/hls/`, `manifest`). A dead CDN node simply rolls to the next
   server instead of sinking the provider.

No JS execution, no WebView, no Cloudflare challenge on the page itself.

---

## Verification (live, end-to-end)

All requests were routed through a residential exit IP (the sandbox's default
datacenter IP is Cloudflare-blocked on these hosts). Each resolved URL was then
re-fetched with a ranged GET to confirm it is a real, playable manifest.

| Title (TMDB)                     | VidUp | VidCore |
|----------------------------------|-------|---------|
| Game of Thrones S1E1 (1399)      | ✅ HTTP 200 `application/vnd.apple.mpegurl` | ✅ HTTP 200 `application/vnd.apple.mpegurl` |
| Breaking Bad S1E1 (1396)         | ✅ HTTP 200 `application/vnd.apple.mpegurl` | ✅ HTTP 200 `application/vnd.apple.mpegurl` |
| Stranger Things S1E1 (66732)     | ✅ HTTP 200 `application/vnd.apple.mpegurl` | ✅ HTTP 200 `application/vnd.apple.mpegurl` |
| The Last of Us S1E1 (100088)     | ✅ HTTP 200 `application/vnd.apple.mpegurl` | ✅ HTTP 200 `application/vnd.apple.mpegurl` |
| Interstellar (157336, movie)     | ✅ resolved | ✅ resolved |

**Steven Universe Future S1E1–S1E3 (94280):** extraction **succeeds** — VidUp
(CineX) and VidCore (Prime) both return a URL — but the resolved upstream CDN
node (`moon.zenoak.top`) answers **HTTP 502** for that specific episode, and
4 of the 5 upstream servers return **404** (the episode is not on those
mirrors). This is a **provider-side content gap for that one title**, not an
extraction bug: the same code path returns HTTP 200 HLS for the four
mainstream titles above. Because the race collects **all** candidates and
falls through on a bad node, a 502 from VidUp/VidCore never blocks another
provider from playing SUF.

---

## Wiring in `PlayerActivity.kt`

* **Imports** — `VidUpExtractor`, `VidCoreExtractor`.
* **`tryVidUp()` / `tryVidCore()`** — new race lanes, identical shape to
  `tryVidFast()` (per-provider `PROVIDER_TIMEOUT_MS`, honours the `excluded`
  set, returns a `DirectWinner`).
* **Race** — added to the `deferreds` list so both fire in parallel with every
  other extractor.
* **Ranking** — `providerReliability("vidup") = 47`; `vidcore` was already
  weighted (`46`). Both are listed in the English-default allowlist so their
  streams sort ahead of ambiguous foreign sources.

---

## Registry changes (`working-servers.json` + `assets/servers.json`)

* `version` 16 → **17**.
* **Added** `vidup` (`https://vidup.to`, tier 2, reliability 95, enabled).
* **Fixed** `vidcore` → base `https://vidcore.io`, templates
  `{base}/movie/{id}` and `{base}/tv/{id}/{season}/{episode}`, **enabled**,
  reliability 90.

---

## Deploy

Push to `main`; the `build-apk.yml` workflow builds a signed APK and publishes
release `v1.4.1-build<N>`. Installs update over previous builds (same signing
key) and the in-app updater picks up the new tag automatically.
