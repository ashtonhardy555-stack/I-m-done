# Piratesfilm Cove — Web Version (Server-Side VPN, Ad-Free)

A web-based version of the Piratesfilm Cove streaming app that:

1. **Uses only the LookMovie extractor** — the same Kodi addon engine flow
   (`plugin.video.lookmovietomb`): search → storage → security API → direct
   `.m3u8` URL. No other streaming servers, no embeds.

2. **Routes all traffic through a server-side VPN** — the server's outbound
   IP is never exposed to LookMovie. Set the `PSIPHON_PROXY` environment
   variable to route all requests through a VPN (Psiphon or any HTTP/SOCKS
   proxy). TMDB API calls are also proxied through the server so the user's
   IP is hidden.

3. **Blocks all ads and redirects** — the player only ever talks to our
   server. The HLS manifest is fetched server-side (with the `t_hash` cookie,
   exactly like the Kodi addon's `serverHTTP.py`), and all segment URLs are
   rewritten to go through our proxy. No third-party JavaScript, no ad
   iframes, no redirect scripts — the browser never loads anything from
   LookMovie's domain directly.

## Architecture

```
Browser (user)
    │
    ├── TMDB API calls ──────────► Server ──► TMDB API
    │                              (hides user IP)
    │
    └── Stream playback ─────────► Server ──► LookMovie CDN
        (only our proxy URLs)      │           (via VPN proxy)
                                   │
                                   ├── Headless Chromium
                                   │   (bypasses Cloudflare)
                                   │
                                   └── HLS Proxy
                                       (t_hash cookie injection,
                                        ad/redirect blocking)
```

## How it works

### LookMovie Extraction (lookmovie_extractor.py)

Mirrors the Android app's `LookMovieWebExtractor` and the Kodi addon:

1. **Search** — search LookMovie by title to get the internal id
2. **Storage** — load `/play/{id}`, extract the `movie_storage`/`show_storage`
   JS object (hash, id, expires, seasons for TV)
3. **Security API** — call the security endpoint from inside a headless
   Chromium browser (to carry the Cloudflare `cf_clearance` cookie)
4. **Play** — the first stream in the response is the direct `.m3u8` URL

A headless Chromium browser is used (via Playwright) to bypass Cloudflare's
JS challenge, exactly like the Android app uses a WebView.

### HLS Proxy (web_server.py)

The resolved stream URL is proxied through the server:

- The `.m3u8` manifest is fetched with the `t_hash` cookie (exactly like the
  Kodi addon's `serverHTTP.py`)
- All segment and sub-manifest URLs are rewritten to go through our proxy
- The player never loads any third-party content — no ads, no redirects
- All outbound requests go through the VPN proxy (if `PSIPHON_PROXY` is set)

## Running

```bash
# Install dependencies
pip install -r requirements-web.txt
playwright install chromium

# Run the server
python3 web_server.py

# Or with VPN proxy (Psiphon)
PSIPHON_PROXY=http://127.0.0.1:8080 python3 web_server.py
```

The web app is served at `http://localhost:8000`.

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Server port | `8000` |
| `PSIPHON_PROXY` | VPN proxy URL (HTTP or SOCKS) for all outbound traffic | (none) |
| `TMDB_API_KEY` | TMDB API key for metadata | (hardcoded) |

## Files

- `web_server.py` — FastAPI server (TMDB proxy, stream resolution, HLS proxy)
- `lookmovie_extractor.py` — LookMovie stream extraction via headless Chromium
- `web/index.html` — Web frontend (Netflix-style UI)
- `web/style.css` — Dark theme styles
- `web/app.js` — Frontend application logic (browsing, search, playback)
