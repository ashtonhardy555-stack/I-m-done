# Mario Cart — Video Playback Fix & More Streams

## The Problem
Every movie and show showed **"no stream or video found"** because the app's only extraction path (`StreamExtractor`) exclusively queried **LookMovie** (`lookmovie2.to`), which now returns **403 Forbidden**. The app had a `servers.json` with embed providers and a `ServerManager` for health tracking, but **neither was ever wired into playback** — they had zero references outside their own files.

## The Fix

### 1. New Multi-Provider Extraction Pipeline (videos now play)
The playback pipeline was completely rebuilt. Instead of relying on a single dead source, it now tries **19 verified-alive embed providers** in order, then falls back to LookMovie:

**Step 1 — `EmbedExtractor` (new, primary):** For each provider, loads the embed page in an **off-screen, invisible WebView** (1×1 pixel). The page's JavaScript video player (hls.js / video.js) runs and requests the real stream URL at runtime. That URL (`.m3u8` or `.mp4`) is intercepted via `WebViewClient.shouldInterceptRequest` and handed to **ExoPlayer** for native in-app playback. The WebView is **never used for playback** — it's only a background extraction tool.

**Step 2 — `StreamExtractor` (LookMovie, fallback):** The original LookMovie extractor remains as a last-resort backend.

**Step 3 — Captcha handling:** If a provider shows a real human-verification challenge (reCAPTCHA, hCaptcha, Cloudflare turnstile), extraction returns a `Challenge` result and `PlayerActivity` launches the existing `VerificationActivity` — a WebView **surfaced to the user** for solving captchas. After the user solves it, cookies are injected and extraction retries automatically.

### 2. 19 New Stream Sources Added
**`StreamProviders.kt`** (new file) defines all providers with proper movie/TV URL builders, ordered by reliability:

| Tier | Providers |
|------|-----------|
| **Tier 1** (cleanest, direct MP4) | VidLink, Videasy |
| **Tier 2** (VidSrc family) | VidSrc.io, VidSrc.me, VidSrc.pm, VidSrc.in, VidSrc.dev, VidSrc.nl, VidSrc.su, VidSrc.fyi |
| **Tier 3** (generic embeds) | 2Embed.cc, 2Embed.skin, Embed.su, SmashyStream, AutoEmbed, CineHub, VidHub, EmbedStream, Embedrise |

**`servers.json`** (both root and `app/src/main/assets/`) was updated to match, so `ServerManager` now tracks health for all 19 providers.

### 3. ServerManager Now Actually Used
`ServerManager.initialize()` is called in `PlayerActivity.onCreate`. Every successful extraction calls `markServerSuccess()` (persistent score increment) and every failure calls `markServerDead()` (session-level demotion). This means providers that work reliably rise to the top over time.

## Files Changed

| File | Status | What it does |
|------|--------|-------------|
| `data/server/StreamProviders.kt` | **NEW** | 19 providers with movie/TV URL builders |
| `data/server/EmbedExtractor.kt` | **NEW** | Off-screen WebView extraction; intercepts direct URLs; captcha detection with grace period |
| `ui/player/PlayerActivity.kt` | **REWRITTEN** | New pipeline: EmbedExtractor → StreamExtractor fallback → ExoPlayer; captcha → VerificationActivity → retry |
| `servers.json` (root) | **UPDATED** | 19 verified-alive providers |
| `app/src/main/assets/servers.json` | **UPDATED** | 19 verified-alive providers |

## Build Verification
✅ **BUILD SUCCESSFUL** — Full `assembleDebug` completed. The debug APK (22 MB) was generated and is included.

## How It Works (User's Perspective)
1. User taps a movie or show → `PlayerActivity` opens
2. App silently tries VidLink, then Videasy, then VidSrc family, then other embeds
3. The first provider that yields a direct video URL wins → **video plays in ExoPlayer** (native player, full controls)
4. If a captcha appears, a WebView opens for the user to solve it, then playback retries automatically
5. If all 19 providers fail, it tries LookMovie as a final fallback
6. The only time the user sees a WebView is for captcha/Cloudflare/human verification — exactly as requested
