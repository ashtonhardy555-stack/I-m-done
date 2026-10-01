# Developer To-Do & Handoff Notes

> **Read this first if you are picking up this project.**
> This file is the living to-do list and architecture map for the Android
> streaming app (`com.ashtonhardy.piratesfilmcove`, app name **TV Hub**).
> Keep it up to date: when you finish an item, tick it; when you find a new
> bug or idea, add it under the right section.

---

## 0. TL;DR for a new contributor

- **Language / stack:** Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer
  1.4.1), OkHttp 4.12, coroutines. `compileSdk 34`, `minSdk 21`, JDK 17.
- **The app has NO backend for playback.** All stream extraction happens
  on-device in `PlayerActivity.kt` + the `data/server/*Extractor.kt` classes.
- **Builds are produced by GitHub Actions**, not locally. Push to `main`
  → `.github/workflows/build-apk.yml` builds a signed APK and cuts a GitHub
  Release tagged `v<versionName>-build<runNumber>`.
- **Version:** `versionName` is in `app/build.gradle.kts`; `versionCode` is the
  CI run number (`CI_BUILD_NUMBER`).
- **Golden rule:** never let a broken/empty stream play ahead of a working one.
  See §3 (the verification pipeline) — this is the heart of the app.

---

## 1. High-level architecture

```
MainActivity ──▶ HomeScreen / Browse / Search ──▶ PlayerActivity (Compose)
                                                     │
                                                     ▼
                                    ┌─────────────────────────────────┐
                                    │  PlayerScreen (PlayerActivity)  │
                                    │  1. engine-first gate (LookMovie)│
                                    │  2. ALL-SERVERS PARALLEL RACE    │
                                    │  3. PRE-PLAY VERIFICATION        │
                                    │  4. RANK + play best             │
                                    │  5. candidateQueue failover      │
                                    └─────────────────────────────────┘
                                                     │
                       data/server/*Extractor.kt (26 extractors, all parallel)
                                                     │
                       data/cache/StreamAvailabilityCache.kt (per-title memory)
```

Key files:

| File | Responsibility |
| --- | --- |
| `ui/player/PlayerActivity.kt` | The whole playback pipeline: race, verification, ranking, failover, ExoPlayer wiring. **This is where most bugs live.** |
| `data/server/*Extractor.kt` | One class per provider. Each resolves a direct `.m3u8`/`.mp4` URL (or returns `null`). |
| `data/cache/StreamAvailabilityCache.kt` | Persistent per-title memory of which providers worked (`recordSuccess`) / failed (`recordFailure`). |
| `data/server/StreamAvailabilityChecker.kt` | Legacy reachability checks. |
| `ui/theme/`, `ui/*Screen.kt` | Compose UI. |

### 1.1 The parallel race (PlayerActivity.kt)

Every extractor is fired **simultaneously** with `async { safe { tryX() } }`.
`safe()` swallows all exceptions (including cancellation) and returns `null`,
so a hung extractor can never crash the scope or discard results.

- `RACE_FIRST_RESULT_TIMEOUT_MS = 7_000L` — how long we wait for the **first**
  candidate before giving up on the race.
- `RACE_GRACE_MS = 1_500L` — after the first candidate lands we keep collecting
  for this long (to build a failover queue + let a verified/English one arrive),
  then `cancel()` the stragglers.
- `PROVIDER_TIMEOUT_MS = 6_000L` — per-extractor cap.
- `ENGINE_FIRST_TIMEOUT_MS = 1_500L` — the LookMovie "engine-first" gate that
  runs *before* the race. Kept short on purpose (a cold LookMovie resolve used
  to block the race for 7 s).

### 1.2 Pre-play verification (the correctness fix)

`verifyStreamPlayable(url, headers)` does a tiny ranged GET
(`Range: bytes=0-2047`) and returns a **tri-state**:

- `true` — real media (HLS `#EXTM3U`, MP4 `ftyp` box, or `video/…` content-type).
- `false` — definitive non-media (HTTP ≠ 200/206, HTML body, or empty body).
- `null` — inconclusive (timeout / connection error) → *not* demoted.

All candidates are probed **in parallel**, capped at `VERIFY_TIMEOUT_MS = 2_500L`.
Ranking then sorts: `verified(true) > unknown(null) > broken(false)`, then
non-"unverified" providers, then LookMovie, then English, then
`providerReliability()`.

### 1.3 Failover

The ranked head plays; the rest go into `candidateQueue`. When ExoPlayer emits a
**fatal** error, `onPlayerError` pops the next candidate and plays it instantly
(no re-extraction). Only when the queue is empty does it re-fire the race.
`isTransientPlaybackError()` decides fatal vs transient — dead/empty streams
(`ERROR_CODE_IO_FILE_NOT_FOUND`, `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE`,
403/404) are **fatal** so we fail over immediately.

---

## 2. How to add a new source

1. Create `data/server/MyProviderExtractor.kt`:
   ```kotlin
   object MyProviderExtractor {
       suspend fun extract(tmdbId: Int, contentType: String, season: Int, episode: Int):
           Result.Stream? { /* return a direct .m3u8/.mp4 URL or null */ }
   }
   ```
2. Add a `tryMyProvider()` lambda next to the other `tryX()` helpers in
   `PlayerActivity.kt`.
3. Add `async { safe { tryMyProvider() }?.also { record(it) } }` to the
   `deferreds` list in the race.
4. Add a weight to `providerReliability()` (higher = more trusted).
5. If the provider serves multi-audio, add an English hint to `isEnglishStream()`.
6. If it returns "unverified" fallbacks, name them `MyProvider·unverified` so the
   ranking demotes them.
7. Test on a real device (see §5) and tick it off in §6.

---

## 3. Testing & reproduction notes

- **The sandbox/CI IP is blocked by Cloudflare** on many providers
  (`vidcore.io`, `vidup.to`, `x.eat-peach.sbs`, `vidfast.vc`, `lookmovie2.to`,
  `vixsrc.to`, `vidsrc.pro`). They return HTTP 403 from datacenter IPs but work
  on-device (residential IP). **Do not assume a provider is broken just because
  it 403s in CI** — verify on a real device.
- Reachable-from-sandbox providers used for smoke tests: the Stremio-style
  addons (NoTorrent, SmashStreams, NuvioStreams, AnnasCinema, NovaStream),
  `2embed.cc` → `vidsrc.buzz`, `vidsrc.pm`, `api.wingsdatabase.com`,
  `api.speedracelight.com`, `api.meowtv.ru`, `vidstorm.ru`.
- **Reference repro (Steven Universe Future, TMDB 94280, S1E1):**
  `vidsrc.buzz` returns 3 servers — `Server VNE` is dead
  (`{"error":"unavailable"}`, HTTP 502 = the "00:00" stream) while `VEM-1`/`VEM-2`
  mint real HLS URLs. This is the exact "can't tell a working stream from a
  00:00 stream on the same server" bug the verification pipeline fixes.
- General media egress check: `https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`
  (should return `audio/mpegurl`).

---

## 4. Build & release process

1. Commit to `main` (or merge a PR into `main`).
2. CI (`.github/workflows/build-apk.yml`) runs on `ubuntu-24.04`, JDK 17:
   decodes the release keystore from secrets, runs `./gradlew assembleRelease`,
   verifies the signature, and creates a GitHub Release with the APK attached.
3. Download the APK from the Release page and sideload it.

Secrets required: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`.

---

## 5. Known constraints / gotchas

- **Kotlin block comments NEST.** A literal `/*` inside a `/** … */` KDoc opens
  an unclosed nested comment and breaks the build. Avoid writing `video/*`,
  `/*`, etc. inside comments (use `video/…`). This has bitten us before.
- **Emoji in source:** `PlayerActivity.kt` uses literal `\uXXXX` escapes in
  string literals, not raw emoji glyphs. Keep it that way.
- **No local Android SDK/JDK in the dev sandbox** — CI is the compile gate.
  A brace/paren balance check is a useful pre-push sanity test.
- `guessMimeType(url)` sets the MediaItem mimeType; HLS is checked before
  progressive MP4. A wrong mimeType causes `INVALID_HTTP_CONTENT_TYPE`.
- `StreamAvailabilityCache` is **per-title** and was historically only written on
  *failure* — which is why working providers got excluded over time. We now also
  call `recordSuccess()` on playback start. Watch this invariant.

---

## 6. To-do list (future work)

### 6.1 Correctness / playback
- [ ] **Extend `verifyStreamPlayable` to HLS deep-check.** Today it only reads
      the first 2 KB. A manifest that returns `#EXTM3U` but has zero variants /
      zero segments (a real "00:00" stream) still passes. Consider fetching the
      variant playlist and confirming at least one segment URI exists.
- [ ] **Zero-duration detection.** If ExoPlayer reports a duration of ~0 ms
      after `STATE_READY`, treat it as a dead stream and fail over. Currently we
      only react to *errors*, not to a silent 0:00.
- [ ] **Parallel-verify the `candidateQueue` too**, not just the first batch, so
      failover order is always verified-first.
- [ ] **Cache verification results** for a few minutes so re-opening a title
      doesn't re-probe every URL.
- [ ] Audit `isTransientPlaybackError()`: `ERROR_CODE_IO_BAD_HTTP_STATUS` is
      still treated as transient (retry) even for a hard 5xx that will never
      recover. Consider a bounded retry then fatal.

### 6.2 Provider coverage
- [ ] Re-check the Cloudflare-walled providers (VidCore, VidUp, Peachify,
      VidFast, LookMovie, VixSrc, VidSrcPro) on a residential IP and confirm
      they still resolve; remove any that are permanently dead.
- [ ] NuvioStreams now serves a "deprecated" HTML page — replace or remove it.
- [ ] NoTorrent has poor TV coverage (returns 0 streams for some shows) —
      find a better Stremio-style addon.
- [ ] Add a health-check job that pings each provider's *resolver* endpoint and
      reports which are alive (there is a `server-health-check.yml` — extend it).

### 6.3 UX / features
- [ ] Surface *which* provider is playing in the UI (we already track
      `deliveringServerName`) so users can pick a manual server.
- [ ] Manual "next server" button that pops `candidateQueue` on demand.
- [ ] Show the verification result (verified/broken) in a debug overlay.
- [ ] Persist the last-good provider per *series* (not just per episode) so
      binge-watching keeps the same source.

### 6.4 Tech debt
- [ ] `PlayerActivity.kt` is ~3 200 lines. Extract the race + verification +
      ranking into a `PlaybackResolver` class with unit tests.
- [ ] Add unit tests for `verifyStreamPlayable` (mock server returning HLS, MP4,
      HTML, 404, empty) and for the ranking comparator.
- [ ] Consolidate the many `*.md` notes at the repo root (`FIXES_SUMMARY.md`,
      `PLAYBACK_FIXES_FINAL.md`, `SERVERS_ADDED*.md`, …) into `docs/`.
- [ ] Move the hard-coded TMDB API key out of source into `BuildConfig`/secrets.

---

## 7. Changelog (recent)

- **build 113** — reverted app code to release 104, added 3 headless sources
  (VidCore, VidUp, Peachify).
- **next** — pre-play stream verification + tri-state ranking; `recordSuccess`
  on playback start; dead/empty streams classified fatal; race early-exit +
  shorter engine-first timeout (load-time + correctness fixes).
