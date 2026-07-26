"""
PiratesfilmCove Web Backend — Server-Side VPN Streaming

This server:
  1. Serves the web frontend (static files)
  2. Proxies TMDB API calls (so the client IP is the server's, not the user's)
  3. Resolves LookMovie streams using the Kodi addon flow (headless Chromium
     to bypass Cloudflare) — ONLY LookMovie, no other servers
  4. Proxies the HLS .m3u8 manifest and .ts segments through the server,
     injecting the t_hash cookie (exactly like the Kodi addon's
     serverHTTP.py) — this blocks ALL ads and redirects because the player
     only ever talks to our server, never to LookMovie directly
  5. All outbound traffic goes through the server's VPN (Psiphon) so the
     server's real IP is never exposed to LookMovie

The web app never loads any third-party embeds, never redirects to ad
sites, and never shows popups — the only external requests are the TMDB
API (for metadata) and the LookMovie stream proxy (for the video).
"""

import os
import re
import sys
import json
import asyncio
import logging
import urllib.parse
from typing import Optional

import httpx
from fastapi import FastAPI, Request, Response, Query, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

# Local imports
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lookmovie_extractor import extract_stream as lm_extract

# ──────────────────────────────────────────────────────────────────────────
#  Configuration
# ──────────────────────────────────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("piratesfilmcove-web")

TMDB_API_KEY = os.environ.get("TMDB_API_KEY", "a15c24c2a5c00487b179f5d4b53b72b0")
TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMG_BASE = "https://image.tmdb.org/t/p"

# VPN proxy URL — if PSIPHON_PROXY is set, all outbound requests go through
# it. This can be an HTTP/SOCKS proxy that Psiphon provides, or any VPN
# proxy. The default is no proxy (direct), but in production you set
# PSIPHON_PROXY=http://127.0.0.1:8080 (or whatever Psiphon's local proxy is).
VPN_PROXY = os.environ.get("PSIPHON_PROXY", "")

# Build the httpx client with optional VPN proxy.
def make_client(timeout: float = 15.0) -> httpx.AsyncClient:
    kwargs = {
        "timeout": timeout,
        "follow_redirects": True,
        "verify": False,
    }
    if VPN_PROXY:
        kwargs["proxy"] = VPN_PROXY
        logger.info(f"Using VPN proxy: {VPN_PROXY}")
    return httpx.AsyncClient(**kwargs)


# ──────────────────────────────────────────────────────────────────────────
#  App setup
# ──────────────────────────────────────────────────────────────────────────

app = FastAPI(title="PiratesfilmCove Web", version="1.0.0")

# CORS — allow the frontend to call us.
from fastapi.middleware.cors import CORSMiddleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ──────────────────────────────────────────────────────────────────────────
#  TMDB proxy endpoints (hides the user's IP — server makes the request)
# ──────────────────────────────────────────────────────────────────────────

@app.get("/api/tmdb/trending")
async def tmdb_trending(media_type: str = "movie", time_window: str = "week", page: int = 1):
    """Get trending movies or TV shows from TMDB."""
    async with make_client() as client:
        url = f"{TMDB_BASE}/trending/{media_type}/{time_window}"
        params = {"api_key": TMDB_API_KEY, "page": page}
        resp = await client.get(url, params=params)
        return Response(content=resp.content, media_type="application/json")


@app.get("/api/tmdb/popular")
async def tmdb_popular(media_type: str = "movie", page: int = 1):
    """Get popular movies or TV shows from TMDB."""
    async with make_client() as client:
        url = f"{TMDB_BASE}/{media_type}/popular"
        params = {"api_key": TMDB_API_KEY, "page": page}
        resp = await client.get(url, params=params)
        return Response(content=resp.content, media_type="application/json")


@app.get("/api/tmdb/search")
async def tmdb_search(query: str, media_type: str = "movie", page: int = 1):
    """Search TMDB for movies or TV shows."""
    async with make_client() as client:
        url = f"{TMDB_BASE}/search/{media_type}"
        params = {
            "api_key": TMDB_API_KEY,
            "query": query,
            "page": page,
            "include_adult": "false",
        }
        resp = await client.get(url, params=params)
        return Response(content=resp.content, media_type="application/json")


@app.get("/api/tmdb/details")
async def tmdb_details(media_type: str, tmdb_id: int):
    """Get detailed info for a movie or TV show from TMDB."""
    async with make_client() as client:
        url = f"{TMDB_BASE}/{media_type}/{tmdb_id}"
        params = {
            "api_key": TMDB_API_KEY,
            "append_to_response": "credits,videos,images",
        }
        resp = await client.get(url, params=params)
        return Response(content=resp.content, media_type="application/json")


@app.get("/api/tmdb/season")
async def tmdb_season(tv_id: int, season_number: int):
    """Get season details (episode list) from TMDB."""
    async with make_client() as client:
        url = f"{TMDB_BASE}/tv/{tv_id}/season/{season_number}"
        params = {"api_key": TMDB_API_KEY}
        resp = await client.get(url, params=params)
        return Response(content=resp.content, media_type="application/json")


# ──────────────────────────────────────────────────────────────────────────
#  Stream resolution — LookMovie ONLY (the Kodi engine flow)
# ──────────────────────────────────────────────────────────────────────────

class StreamRequest(BaseModel):
    title: str
    year: Optional[str] = None
    content_type: str = "movie"
    season: int = 1
    episode: int = 1


@app.post("/api/stream/resolve")
async def resolve_stream(req: StreamRequest):
    """
    Resolve a direct playable stream from LookMovie.

    Uses the same flow as the Kodi addon and the Android app:
      search → storage → security API → direct .m3u8 URL

    The LookMovie extraction uses a headless Chromium browser to bypass
    Cloudflare's JS challenge. Only LookMovie is used — no other servers,
    no embeds, no redirects.
    """
    logger.info(
        f"Resolving stream: '{req.title}' ({req.year}) "
        f"type={req.content_type} S{req.season}E{req.episode}"
    )

    # Run the LookMovie extraction in a thread (Playwright is sync).
    loop = asyncio.get_event_loop()
    result = await loop.run_in_executor(
        None,
        lm_extract,
        req.title,
        req.year,
        req.content_type,
        req.season,
        req.episode,
    )

    if not result.get("success"):
        logger.warning(f"Stream resolution failed: {result.get('error')}")
        return JSONResponse(
            status_code=404,
            content={"success": False, "error": result.get("error", "No stream found.")}
        )

    # Store the stream info so the proxy endpoint can use the hash + referer.
    stream_key = _cache_stream(result)
    logger.info(f"Stream resolved, key={stream_key}, url={result['url']}")

    return {
        "success": True,
        "streamKey": stream_key,
        "proxyUrl": f"/api/stream/proxy/{stream_key}/master.m3u8",
        "serverId": "lookmovie_direct",
    }


# ──────────────────────────────────────────────────────────────────────────
#  HLS Proxy — proxy the .m3u8 manifest and .ts segments through the
#  server with the t_hash cookie, blocking ALL ads and redirects.
#  The player ONLY talks to our server — it never loads any third-party
#  content, never redirects, never shows ads.
# ──────────────────────────────────────────────────────────────────────────

import time

# In-memory cache of resolved stream info (url, hash, referer, base).
# Each entry expires after 2 hours (LookMovie streams expire).
_stream_cache: dict = {}
_STREAM_CACHE_TTL = 7200  # 2 hours


def _cache_stream(result: dict) -> str:
    """Cache the stream result and return a key for the proxy."""
    import secrets
    key = secrets.token_hex(8)
    _stream_cache[key] = {
        "url": result["url"],
        "hash": result.get("hash", ""),
        "referer": result.get("referer", ""),
        "base": result.get("base", ""),
        "ts": time.time(),
    }
    return key


def _get_cached_stream(key: str) -> Optional[dict]:
    entry = _stream_cache.get(key)
    if not entry:
        return None
    if time.time() - entry["ts"] > _STREAM_CACHE_TTL:
        _stream_cache.pop(key, None)
        return None
    return entry


@app.get("/api/stream/proxy/{stream_key}/{path:path}")
async def proxy_stream(stream_key: str, path: str, request: Request):
    """
    Proxy an HLS manifest or segment through the server.

    The player requests /api/stream/proxy/{key}/master.m3u8 and we fetch
    the real m3u8 from LookMovie with the t_hash cookie, rewrite the
    segment URLs to go through our proxy, and return the modified manifest.
    For .ts segments, we stream them through with the cookie.

    This ensures:
      - No ads (the player never loads third-party JS or ad iframes)
      - No redirects (we control all URLs)
      - The t_hash cookie is sent (LookMovie requires it)
      - The server's VPN IP is used (not the user's)
    """
    stream = _get_cached_stream(stream_key)
    if not stream:
        raise HTTPException(status_code=404, detail="Stream expired or not found.")

    base_url = stream["url"]
    # The base m3u8 URL might itself have query params; the segments are
    # relative to it. If path is "master.m3u8", we fetch the base URL.
    # Otherwise, path is a segment path relative to the m3u8.

    if path == "master.m3u8" or path.endswith(".m3u8"):
        # Fetch the manifest.
        target_url = base_url if path == "master.m3u8" else _resolve_url(base_url, path)
        return await _proxy_m3u8(stream_key, target_url, stream)

    elif path.endswith(".ts") or path.endswith(".mp4") or ".m3u8" in path:
        # Stream the segment/media.
        target_url = _resolve_url(base_url, path)
        return await _proxy_segment(target_url, stream)

    else:
        raise HTTPException(status_code=400, detail="Unsupported content type.")


def _resolve_url(base_url: str, path: str) -> str:
    """Resolve a relative path against the base m3u8 URL."""
    if path.startswith("http"):
        return path
    # Parse the base URL to get the directory.
    parsed = urllib.parse.urlparse(base_url)
    base_dir = parsed.path.rsplit("/", 1)[0]
    # If the path starts with /, it's root-relative to the CDN host.
    if path.startswith("/"):
        return f"{parsed.scheme}://{parsed.netloc}{path}"
    # Otherwise, it's relative to the base directory.
    return f"{parsed.scheme}://{parsed.netloc}{base_dir}/{path}"


async def _proxy_m3u8(stream_key: str, target_url: str, stream: dict) -> Response:
    """Fetch and rewrite the m3u8 manifest, proxying all segment URLs."""
    headers = _build_headers(stream, target_url)

    async with make_client(timeout=30.0) as client:
        resp = await client.get(target_url, headers=headers)
        if resp.status_code != 200:
            logger.error(f"m3u8 fetch failed: {resp.status_code}")
            raise HTTPException(status_code=502, detail="Failed to fetch stream manifest.")

        content = resp.text

        # Rewrite all segment and sub-manifest URLs to go through our proxy.
        # This is the key to blocking ads and redirects: the player only
        # ever requests URLs from our server, never from LookMovie's CDN
        # directly (so no ad injection, no redirect scripts).
        proxy_base = f"/api/stream/proxy/{stream_key}"

        # Rewrite relative URLs in the m3u8.
        lines = content.split("\n")
        rewritten = []
        for line in lines:
            line = line.strip()
            if not line:
                rewritten.append(line)
                continue
            if line.startswith("#"):
                # Rewrite URIs in EXT-X-KEY, EXT-X-MAP, etc.
                line = _rewrite_m3u8_directive(line, stream_key, target_url, stream)
                rewritten.append(line)
                continue
            # This is a segment or sub-manifest URL.
            if line.endswith(".m3u8") or line.endswith(".ts") or line.endswith(".mp4"):
                # Rewrite to go through our proxy.
                encoded_path = urllib.parse.quote(line, safe="")
                rewritten.append(f"{proxy_base}/{encoded_path}")
            else:
                rewritten.append(line)

        rewritten_content = "\n".join(rewritten)
        return Response(
            content=rewritten_content,
            media_type="application/vnd.apple.mpegurl",
            headers={
                "Cache-Control": "no-cache",
                "Access-Control-Allow-Origin": "*",
            },
        )


def _rewrite_m3u8_directive(
    line: str, stream_key: str, base_url: str, stream: dict
) -> str:
    """Rewrite URIs within #EXT-X-KEY, #EXT-X-MAP and other directives."""
    # Match URI="..." in the directive.
    uri_match = re.search(r'URI="([^"]+)"', line)
    if not uri_match:
        return line

    original_uri = uri_match.group(1)
    resolved = _resolve_url(base_url, original_uri)
    # Rewrite to go through our proxy.
    encoded = urllib.parse.quote(resolved, safe="")
    proxy_uri = f"/api/stream/proxy/{stream_key}/{encoded}"
    return line.replace(f'URI="{original_uri}"', f'URI="{proxy_uri}"')


async def _proxy_segment(target_url: str, stream: dict) -> StreamingResponse:
    """Stream a .ts segment or .mp4 through the server with the t_hash cookie."""
    headers = _build_headers(stream, target_url)

    async with make_client(timeout=60.0) as client:
        resp = await client.send(
            client.build_request("GET", target_url, headers=headers),
            stream=True,
        )
        if resp.status_code != 200:
            raise HTTPException(status_code=502, detail="Failed to fetch segment.")

        async def generate():
            try:
                async for chunk in resp.aiter_bytes(8192):
                    yield chunk
            finally:
                await resp.aclose()

        return StreamingResponse(
            generate(),
            media_type="video/mp2t",
            headers={
                "Cache-Control": "public, max-age=3600",
                "Access-Control-Allow-Origin": "*",
            },
        )


def _build_headers(stream: dict, referer_url: str) -> dict:
    """Build the headers for fetching from LookMovie's CDN."""
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0",
        "Referer": stream.get("referer", "https://www.lookmovie2.to/"),
    }
    t_hash = stream.get("hash", "")
    if t_hash:
        headers["Cookie"] = f"t_hash={t_hash}"
    return headers


# ──────────────────────────────────────────────────────────────────────────
#  Health check
# ──────────────────────────────────────────────────────────────────────────

@app.get("/api/health")
async def health():
    return {
        "status": "ok",
        "vpn": "enabled" if VPN_PROXY else "disabled (set PSIPHON_PROXY env var)",
        "extractor": "lookmovie",
        "adBlocking": True,
    }


# ──────────────────────────────────────────────────────────────────────────
#  Static frontend — serve the web app
# ──────────────────────────────────────────────────────────────────────────

WEB_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "web")

if os.path.isdir(WEB_DIR):
    app.mount("/", StaticFiles(directory=WEB_DIR, html=True), name="web")
    logger.info(f"Serving web frontend from {WEB_DIR}")
else:
    @app.get("/")
    async def index():
        return JSONResponse({
            "status": "ok",
            "message": "PiratesfilmCove Web API is running. Web frontend not found."
        })


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("PORT", 8000))
    uvicorn.run(app, host="0.0.0.0", port=port)
