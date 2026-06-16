"""
Stream API Service
==================

A FastAPI backend service that handles video stream extraction from multiple
servers, optimized for long-duration videos.

Fixes:
1. Updated API endpoints (VidLink, Videasy, etc.) to match current production.
2. Fixed the response contract to match PlayerActivity expectations (nested headers).
3. Added fallback_iframe support.
4. Added support for encrypted/dynamic source IDs.
"""

from fastapi import FastAPI, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List, Dict
import httpx
import asyncio
import re
import json
import logging
from datetime import datetime
from functools import lru_cache

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────────────────────
# Data Models
# ─────────────────────────────────────────────────────────────────────────────

class StreamingServer(BaseModel):
    name: str
    baseUrl: str
    
    def movie_url(self, tmdb_id: int) -> str:
        return f"{self.baseUrl}/movie/{tmdb_id}"
    
    def tv_url(self, tmdb_id: int, season: int, episode: int) -> str:
        return f"{self.baseUrl}/tv/{tmdb_id}/{season}/{episode}"


class VideoSource(BaseModel):
    url: str
    format: str  # "hls" or "mp4"
    server: str
    quality: Optional[str] = "1080p"
    headers: Dict[str, str] = {}


class StreamResponse(BaseModel):
    success: bool
    stream: Optional[VideoSource] = None
    fallback_iframe: Optional[str] = None
    error: Optional[str] = None
    server_tried: int = 0
    time_ms: float = 0


# ─────────────────────────────────────────────────────────────────────────────
# Server Configuration
# ─────────────────────────────────────────────────────────────────────────────

SERVERS = [
    StreamingServer(name="VidLink", baseUrl="https://vidlink.pro"),
    StreamingServer(name="Videasy", baseUrl="https://player.videasy.to"),
    StreamingServer(name="VidSrc.to", baseUrl="https://vidsrc.to/embed"),
    StreamingServer(name="VidSrc.me", baseUrl="https://vidsrcme.ru/embed"),
    StreamingServer(name="AutoEmbed", baseUrl="https://autoembed.cc/embed"),
    StreamingServer(name="SuperEmbed", baseUrl="https://superembed.stream/embed"),
    StreamingServer(name="Embed.su", baseUrl="https://embed.su/embed"),
]

# ─────────────────────────────────────────────────────────────────────────────
# FastAPI App Setup
# ─────────────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="Stream API Service",
    description="Video stream extraction service for I-m-done",
    version="1.1.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ─────────────────────────────────────────────────────────────────────────────
# HTTP Client Setup
# ─────────────────────────────────────────────────────────────────────────────

USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

@lru_cache(maxsize=1)
def get_http_client() -> httpx.AsyncClient:
    return httpx.AsyncClient(
        timeout=15.0,
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT}
    )

# ─────────────────────────────────────────────────────────────────────────────
# Extraction Logic
# ─────────────────────────────────────────────────────────────────────────────

async def extract_vidlink(tmdb_id: int, is_movie: bool, season: int = 1, episode: int = 1) -> Optional[VideoSource]:
    """VidLink extraction (handles their dynamic/encrypted IDs by fetching the page first)."""
    base = "https://vidlink.pro"
    path = f"/movie/{tmdb_id}" if is_movie else f"/tv/{tmdb_id}/{season}/{episode}"
    url = base + path
    
    client = get_http_client()
    try:
        # Step 1: Get the page to find the dynamic API URL
        resp = await client.get(url)
        if resp.status_code != 200: return None
        
        # Look for the API call pattern in the page or just use the known pattern if possible
        # Currently VidLink uses a hash in the URL: /api/b/movie/[HASH]
        # For a robust fix, we'd parse the JS, but we can try to find it in the HTML
        match = re.search(r'https://vidlink\.pro/api/b/(movie|tv)/[a-zA-Z0-9]+', resp.text)
        api_url = match.group(0) if match else None
        
        if not api_url:
            # Fallback to a guess or standard pattern if hash is stable for session
            return None

        # Step 2: Call the actual API
        api_resp = await client.get(api_url, headers={"Referer": url})
        data = api_resp.json()
        
        if "stream" in data and "playlist" in data["stream"]:
            video_url = data["stream"]["playlist"]
            # If the backend returns its own headers, use them, otherwise use defaults
            resp_headers = data["stream"].get("headers", {})
            return VideoSource(
                url=video_url,
                format="hls",
                server="VidLink",
                headers={
                    "referer": resp_headers.get("referer", "https://megacloud.live/"),
                    "origin": resp_headers.get("origin", "https://megacloud.live")
                }
            )
    except Exception as e:
        logger.debug(f"VidLink failed: {e}")
    return None

async def extract_videasy(tmdb_id: int, is_movie: bool, season: int = 1, episode: int = 1) -> Optional[VideoSource]:
    """Videasy extraction."""
    client = get_http_client()
    try:
        # Videasy uses a search-based API now
        # We need the title/year which we don't have here, so we'd usually fetch from TMDB first
        # But we can try the direct embed page scraping as fallback
        url = f"https://player.videasy.to/movie/{tmdb_id}" if is_movie else f"https://player.videasy.to/tv/{tmdb_id}/{season}/{episode}"
        resp = await client.get(url)
        
        # Look for .m3u8 in the page source
        match = re.search(r'(https?://[^\s"\'<>()\]]+\.m3u8(?:\?[^\s"\'<>()\]]*)?)', resp.text)
        if match:
            return VideoSource(
                url=match.group(1),
                format="hls",
                server="Videasy",
                headers={"Referer": url}
            )
    except Exception as e:
        logger.debug(f"Videasy failed: {e}")
    return None

# ─────────────────────────────────────────────────────────────────────────────
# Main API Endpoint
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/api/stream")
async def get_stream(
    tmdb_id: int,
    content_type: str = "movie",
    season: int = 1,
    episode: int = 1
) -> StreamResponse:
    start_time = datetime.now()
    is_movie = content_type == "movie"
    
    # Try VidLink first (best quality for long videos)
    stream = await extract_vidlink(tmdb_id, is_movie, season, episode)
    if not stream:
        # Try Videasy
        stream = await extract_videasy(tmdb_id, is_movie, season, episode)
        
    elapsed = (datetime.now() - start_time).total_seconds() * 1000
    
    if stream:
        return StreamResponse(
            success=True,
            stream=stream,
            server_tried=2,
            time_ms=elapsed
        )
    
    # Fallback Iframe
    fallback = f"https://vidsrc.to/embed/movie/{tmdb_id}" if is_movie else f"https://vidsrc.to/embed/tv/{tmdb_id}/{season}/{episode}"
    
    return StreamResponse(
        success=False,
        fallback_iframe=fallback,
        error="Direct stream extraction failed. Use fallback iframe.",
        server_tried=2,
        time_ms=elapsed
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
