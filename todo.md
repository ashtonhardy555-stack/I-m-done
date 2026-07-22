# Add Headless Kodi-Style Server Extractors

## Context
The app has a "Kodi-like headless engine" (KodiEngine) that runs the LookMovie
addon flow (search → storage → security API → .m3u8) in pure OkHttp (no WebView,
no Kodi runtime). The user wants MORE servers added that work the same way —
headless, pure OkHttp extractors that resolve direct stream URLs through the
Kodi engine.

## Tasks

### Phase 1: Research & Understand Existing Architecture
- [x] Merge PR #43 (animateFloat fix) — build was broken
- [x] Verify the build passes after merge (run #29888416981 SUCCESS)
- [x] Read LookMovieHeadlessExtractor.kt — the reference implementation
- [x] Read KodiEngine.kt — the addon orchestration layer (Addon interface)
- [x] Read PlayerActivity.kt — the parallel race that uses all extractors
- [x] Read existing extractor patterns (VidStorm, NoTorrent, VidLink, VixSrc, etc.)
- [x] Research Stremio addon API endpoints (NuvioStreams, etc.)

### Phase 2: Create New Headless Extractors (Kodi-style, pure OkHttp)
- [x] Create SmashStreamsExtractor.kt — Stremio addon API (JSON streams)
- [x] Create NuvioStreamsExtractor.kt — Stremio addon with direct stream URLs
- [x] Create AnnasCinemaExtractor.kt — Stremio addon aggregator
- [x] Create NovaStreamExtractor.kt — Stremio addon with direct stream URLs

### Phase 3: Wire Into KodiEngine (Addon interface)
- [x] Add each new extractor as a KodiEngine.Addon adapter
- [x] Ensure they participate in the engine's pre-resolve / cache flow
- [x] Extend ResolveRequest to include tmdbId + contentType

### Phase 4: Wire Into PlayerActivity Parallel Race
- [x] Add try*() helper for each new extractor (SmashStreams, NuvioStreams, AnnasCinema, NovaStream)
- [x] Add each to the deferreds list in the parallel race
- [x] Add provider reliability weights for new providers
- [x] Add new providers to RACE_PROVIDER_BASES set
- [x] Add new providers to isEnglishStream default-English allowlist
- [x] Update engine-first ResolveRequest in PlayerActivity to include tmdbId

### Phase 5: Build & Test
- [ ] Create PR with the new extractors
- [ ] Monitor the CI build to ensure it passes
