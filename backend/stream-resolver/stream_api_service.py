import asyncio
import httpx
from fastapi import FastAPI, Query, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List, Optional, Dict
import time
import logging
from advanced_resolver import AdvancedStreamResolver

logging.basicConfig(level=logging.INFO)
resolver = AdvancedStreamResolver()
logger = logging.getLogger(__name__)

app = FastAPI(title="I'm Done Streaming API", version="6.2")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

class StreamServer(BaseModel):
    id: str
    name: str
    type: str
    baseUrl: str
    status: str = "unknown"

class ServersResponse(BaseModel):
    success: bool
    servers: List[StreamServer]
    total: int

class EmbedResponse(BaseModel):
    success: bool
    embedUrl: Optional[str] = None
    serverId: Optional[str] = None
    error: Optional[str] = None

class StreamResponse(BaseModel):
    success: bool
    url: Optional[str] = None
    serverId: Optional[str] = None
    contentType: Optional[str] = None
    isDirect: Optional[bool] = False
    headers: Optional[Dict[str, str]] = None
    challengeUrl: Optional[str] = None
    error: Optional[str] = None

class HealthResponse(BaseModel):
    status: str

# Expanded list of working servers for 2026 (verified and tested)
SERVERS = [
    {"id": "vidsrc_embed_ru", "name": "VidSrc (vidsrc-embed.ru)", "baseUrl": "https://vidsrc-embed.ru/embed", "type": "movie"},
    {"id": "vsembed_ru", "name": "VidSrc (vsembed.ru)", "baseUrl": "https://vsembed.ru/embed", "type": "movie"},
    {"id": "vsembed_su", "name": "VidSrc (vsembed.su)", "baseUrl": "https://vsembed.su/embed", "type": "movie"},
    {"id": "vidlink", "name": "VidLink", "baseUrl": "https://vidlink.pro", "type": "movie"},
    {"id": "vidsrc_to", "name": "VidSrc.to", "baseUrl": "https://vidsrc.to/embed", "type": "movie"},
    {"id": "2embed", "name": "2Embed.cc", "baseUrl": "https://www.2embed.cc/embed", "type": "movie"},
    {"id": "vidsrc2", "name": "VidSrc2.to", "baseUrl": "https://vidsrc2.to/embed", "type": "movie"},
    {"id": "smashy", "name": "SmashyStream", "baseUrl": "https://smashystream.com/embed", "type": "movie"},
    {"id": "autoembed", "name": "AutoEmbed", "baseUrl": "https://autoembed.cc/embed", "type": "movie"},
    {"id": "embedsu", "name": "Embed.su", "baseUrl": "https://embed.su/embed", "type": "movie"}
]

@app.get("/health", response_model=HealthResponse)
async def health():
    return HealthResponse(status="ok")

@app.get("/api/servers", response_model=ServersResponse)
async def get_servers():
    # In a real app, we could run a background task to update status
    return {
        "success": True,
        "servers": [StreamServer(**s) for s in SERVERS],
        "total": len(SERVERS)
    }

@app.get("/api/embed", response_model=EmbedResponse)
async def get_embed(
    serverId: str,
    tmdbId: int,
    type: str = "movie",
    season: Optional[int] = 1,
    episode: Optional[int] = 1
):
    """Generate embed URL for a movie or TV show."""
    server = next((s for s in SERVERS if s["id"] == serverId), None)
    if not server:
        return {"success": False, "error": "Server not found"}
    
    base = server["baseUrl"]
    
    # Build URL based on server type and content type
    if serverId == "vidlink":
        # VidLink format: https://vidlink.pro/movie/tmdbId or https://vidlink.pro/tv/tmdbId/season/episode
        if type == "movie":
            url = f"{base}/movie/{tmdbId}"
        else:
            url = f"{base}/tv/{tmdbId}/{season or 1}/{episode or 1}"
    else:
        # VidSrc and similar format: https://vidsrc-embed.ru/embed/movie/tmdbId or /embed/tv/tmdbId/season/episode
        if type == "movie":
            url = f"{base}/movie/{tmdbId}"
        else:
            url = f"{base}/tv/{tmdbId}/{season or 1}/{episode or 1}"
    
    return {"success": True, "embedUrl": url, "serverId": serverId}

async def check_server_health(client: httpx.AsyncClient, server: Dict) -> bool:
    """Check if a server's base URL is responsive."""
    try:
        resp = await client.head(server["baseUrl"], timeout=5.0, follow_redirects=True)
        return resp.status_code < 400
    except Exception:
        return False

async def extract_stream(client: httpx.AsyncClient, server: Dict, tmdbId: int, type: str, season: int, episode: int) -> Optional[Dict]:
    """
    Attempt to find a direct playable stream by constructing the embed URL.
    Returns the embed URL if the server is responsive.
    """
    is_up = await check_server_health(client, server)
    if is_up:
        # Construct the embed URL based on server type
        base = server["baseUrl"]
        if server["id"] == "vidlink":
            if type == "movie":
                embed_url = f"{base}/movie/{tmdbId}"
            else:
                embed_url = f"{base}/tv/{tmdbId}/{season}/{episode}"
        else:
            if type == "movie":
                embed_url = f"{base}/movie/{tmdbId}"
            else:
                embed_url = f"{base}/tv/{tmdbId}/{season}/{episode}"
        
        return {
            "url": embed_url,
            "serverId": server["id"],
            "contentType": "text/html"
        }
    return None

@app.get("/api/stream", response_model=StreamResponse)
async def get_stream(
    tmdbId: int,
    type: str = "movie",
    season: Optional[int] = 1,
    episode: Optional[int] = 1,
    title: Optional[str] = None,
    year: Optional[str] = None
):
    """
    Auto-detect working servers by probing them in parallel, 
    bypassing ads and redirects using the advanced resolver.

    LookMovie (the advanced resolver) searches by title, not TMDB id, so the
    title/year query params are forwarded to the resolver to match the
    LookMovie Kodi addon behaviour exactly.
    """
    # 1. Try advanced resolver first for a clean experience
    clean_result = await resolver.get_clean_stream(
        tmdbId, type, season or 1, episode or 1, title=title, year=year
    )
    if clean_result:
        logger.info(f"Clean stream detected via {clean_result['serverId']}")
        return {
            "success": True,
            "url": clean_result["url"],
            "serverId": clean_result["serverId"],
            "contentType": "video/mp4" if clean_result.get("isDirect") else "text/html",
            "isDirect": clean_result.get("isDirect", False),
            "challengeUrl": clean_result.get("challengeUrl")
        }

    # 2. Fallback to standard probing if advanced resolution fails
    async with httpx.AsyncClient(timeout=10.0) as client:
        priority_servers = [s for s in SERVERS if s["id"] in ["vidsrc_embed_ru", "vidlink", "vsembed_ru", "vidsrc_to"]]
        other_servers = [s for s in SERVERS if s["id"] not in ["vidsrc_embed_ru", "vidlink", "vsembed_ru", "vidsrc_to"]]
        
        all_to_try = priority_servers + other_servers
        tasks = [extract_stream(client, s, tmdbId, type, season or 1, episode or 1) for s in all_to_try]
        
        for completed in asyncio.as_completed(tasks):
            result = await completed
            if result:
                logger.info(f"Auto-detected fallback stream via {result['serverId']}")
                return {
                    "success": True,
                    "url": result["url"],
                    "serverId": result["serverId"],
                    "contentType": result["contentType"],
                    "isDirect": result.get("isDirect", False),
                    "challengeUrl": result.get("challengeUrl")
                }
    
    return {"success": False, "error": "No working servers detected at this time."}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
