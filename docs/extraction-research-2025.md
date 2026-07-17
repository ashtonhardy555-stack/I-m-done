# Streaming Extraction Research (2025)

This document captures the findings from researching current best practices
for video extraction from streaming providers, gathered from open-source
projects and community resources.

## Key Findings

### 1. Parallel Racing is the Industry Standard
All modern streaming aggregators (Cloudstream, Stremio addons, e-cinema, etc.)
race multiple providers in parallel rather than testing them sequentially.
The pattern is: launch all extractors concurrently, take the first successful
result, and cancel the rest. This is what we've now implemented in
PlayerActivity's direct-API stage using Kotlin `coroutineScope` + `select`
on `Deferred.onAwait`.

### 2. VidSrc Extraction Pipeline (vidsrc.to / vidsrc.me)
The VidSrc embed page contains a `data-id` attribute on a source link.
The extraction flow is:
1. Fetch the embed page: `https://vidsrc.to/embed/{type}/{id}[/{season}/{episode}]`
2. Parse the `data-id` from the HTML
3. Fetch sources: `https://vidsrc.to/ajax/embed/episode/{data_id}/sources?token={encoded_id}`
4. Each source has an encrypted URL fetched from: `https://vidsrc.to/ajax/embed/source/{source_id}?token={encoded_id}`
5. The encrypted URL is decrypted using an AES key fetched from a remote
   key server (currently whisperingauroras.com for RCP/PRORCP decryption)
6. The decrypted URL is a direct stream URL (m3u8/mp4) or a provider embed
   (e.g. vidplay.site) that requires further extraction

**Sources**: Ciarands/vidsrc-to-resolver (archived), cool-dev-guy/vidsrc.ts,
crxssed7/vidsrc-extractor

### 3. 2Embed Extraction (2embed.cc / 2embed.to)
2Embed uses a nested iframe approach:
1. Fetch the embed page: `https://www.2embed.cc/embed/{tmdb_id}` (movie)
   or `https://www.2embed.cc/embedtv/{tmdb_id}&s={season}&e={episode}` (TV)
2. The page contains an iframe pointing to `streamsrcs.2embed.cc`
3. The iframe source contains the actual video player with HLS streams
4. Extract the m3u8 URL from the player source

**Source**: parnexcodes/2embed-api (TypeScript, works without rabbitstream token)

### 4. MeowTV API (api.meowtv.ru)
MeowTV provides a direct API that queries multiple backend servers:
1. POST to `api.meowtv.ru` with TMDB ID, type, season, episode
2. The response contains encrypted data for each server
3. Decrypt via `enc-dec.app` API
4. The decrypted URLs are HLS manifests served from CDN paths like
   `/v4/.../cf-master.{timestamp}.txt`
5. **CRITICAL**: These `.txt` files are actually HLS (M3U8) playlists.
   ExoPlayer must be told the MIME type is `APPLICATION_M3U8` or it will
   try to parse them as MP4 and fail. This was the root cause of Rick &
   Morty not playing — MeowTV is the primary extractor for that title.

### 5. Additional Verified-Live Providers (2025)
| Provider | Movie URL | TV URL | Status |
|----------|-----------|--------|--------|
| curtstream.com | /embed/movie/{id} | /embed/tv/{id}/{s}/{e} | ✅ Live |
| vidsrc.online | /vsrc/movie/{id} | /vsrc/tv/{id}/{s}/{e} | ✅ Live |
| embed.smashystream.com | /playere.php?tmdb={id} | +&season=&episode= | ✅ Live |
| vidsrc.dev | /embed/tv/{id}/{s}/{e} | /embed/tv/{id}/{s}/{e} | ✅ Live (Cloudflare) |
| vidstorm.ru | /movie/{id} | /tv/{id}/{s}/{e} | ✅ Live |

### 6. Dead/Disabled Providers (2025)
| Provider | Issue |
|----------|-------|
| vidsrc.net | Serves parking page |
| vidsrc.in | Serves parking page |
| vidsrc.pm | Serves parking page |
| embed.lol | Dead |
| 2embed.org | Dead |
| 2embed.to | Dead |
| vidsrc.vip | Dead |
| fsapi.xyz | Dead |
| v2.apimdb.net | Dead |
| gomo.to | Dead |

## Implementation Summary
1. **Parallel race**: All 13 direct-API extractors now fire simultaneously.
   First verified Stream wins; rest are cancelled. Worst-case wait dropped
   from ~91s to ~7s (the per-extractor timeout).
2. **MIME fix**: `guessMimeType()` now recognizes `cf-master` and `/v4/*.txt`
   URLs as HLS, fixing MeowTV-sourced titles (Rick & Morty, etc.).
3. **Cache expansion**: `StreamAvailabilityCache.RACE_PROVIDERS` now tracks
   all 13 extractors, so per-title good/bad records work for every provider.
4. **New servers**: Added CurtStream and VidSrc.online to working-servers.json.
