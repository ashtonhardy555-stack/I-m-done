import httpx
import re
import base64
import json
from bs4 import BeautifulSoup
from typing import Optional, Dict

class AdvancedStreamResolver:
    """
    Advanced resolver to bypass ads, redirects, and extract direct video sources.
    """
    
    def __init__(self):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": "https://vidsrc.to/",
            "Accept-Language": "en-US,en;q=0.9",
        }

    async def resolve_vidsrc_to(self, tmdb_id: str, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Resolve vidsrc.to direct stream."""
        # Vidsrc.to often has mirrors that provide direct links
        api_urls = [
            f"https://vidsrc.to/api/source/{content_type}/{tmdb_id}",
            f"https://vidsrc.net/api/source/{content_type}/{tmdb_id}"
        ]
        if content_type == "tv":
            api_urls = [
                f"https://vidsrc.to/api/source/tv/{tmdb_id}/{season}/{episode}",
                f"https://vidsrc.net/api/source/tv/{tmdb_id}/{season}/{episode}"
            ]
            
        for api_url in api_urls:
            try:
                async with httpx.AsyncClient(headers=self.headers, follow_redirects=True, timeout=5.0) as client:
                    resp = await client.get(api_url)
                    if resp.status_code == 200:
                        data = resp.json()
                        file_url = data.get("url") or data.get("file")
                        if file_url and (".m3u8" in file_url or ".mp4" in file_url):
                            return {
                                "url": file_url,
                                "serverId": "vidsrc_to_direct",
                                "isDirect": True
                            }
            except Exception:
                continue

        # Fallback to embed
        return {
            "url": f"https://vidsrc.to/embed/{content_type}/{tmdb_id}" + (f"/{season}/{episode}" if content_type == "tv" else ""),
            "serverId": "vidsrc_to_embed",
            "isDirect": False
        }

    async def resolve_vidlink(self, tmdb_id: str, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Resolve vidlink.pro direct stream by attempting to find the actual .m3u8 file."""
        # Try multiple known API endpoints for VidLink in 2026
        endpoints = [
            f"https://vidlink.pro/api/source/{content_type}/{tmdb_id}",
            f"https://vidlink.pro/api/b/{content_type}/{tmdb_id}"
        ]
        if content_type == "tv":
            endpoints = [
                f"https://vidlink.pro/api/source/tv/{tmdb_id}/{season}/{episode}",
                f"https://vidlink.pro/api/b/tv/{tmdb_id}/{season}/{episode}"
            ]
            
        for api_url in endpoints:
            try:
                async with httpx.AsyncClient(headers=self.headers, follow_redirects=True, timeout=10.0) as client:
                    resp = await client.get(api_url)
                    if resp.status_code == 200:
                        data = resp.json()
                        # Check various common JSON keys for the stream URL
                        stream_url = data.get("stream") or data.get("url") or data.get("file")
                        
                        # Some APIs return a list of sources
                        if not stream_url and "sources" in data:
                            sources = data["sources"]
                            if isinstance(sources, list) and len(sources) > 0:
                                stream_url = sources[0].get("file") or sources[0].get("url")

                        if stream_url and (".m3u8" in stream_url or ".mp4" in stream_url):
                            return {
                                "url": stream_url,
                                "serverId": "vidlink_direct",
                                "isDirect": True
                            }
            except Exception:
                continue
        
        # Fallback to verified clean embed
        return {
            "url": f"https://vidlink.pro/movie/{tmdb_id}" if content_type == "movie" else f"https://vidlink.pro/tv/{tmdb_id}/{season}/{episode}",
            "serverId": "vidlink_embed",
            "isDirect": False
        }

    async def resolve_vidsrc_embed_ru(self, tmdb_id: str, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Resolve vidsrc-embed.ru direct stream."""
        base_url = f"https://vidsrc-embed.ru/embed/{content_type}/{tmdb_id}"
        if content_type == "tv":
            base_url += f"/{season}/{episode}"
            
        try:
            return {
                "url": base_url,
                "serverId": "vidsrc_embed_ru",
                "isDirect": False
            }
        except Exception:
            pass
        return None

    async def resolve_vidsrc_me(self, tmdb_id: str, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Resolve vidsrc.me direct stream."""
        api_url = f"https://vidsrc.me/api/source/{content_type}/{tmdb_id}"
        if content_type == "tv":
            api_url += f"/{season}/{episode}"
            
        try:
            async with httpx.AsyncClient(headers=self.headers, follow_redirects=True, timeout=10.0) as client:
                resp = await client.get(api_url)
                if resp.status_code == 200:
                    data = resp.json()
                    if "url" in data and data["url"].endswith(".m3u8"):
                        return {
                            "url": data["url"],
                            "serverId": "vidsrc_me_direct",
                            "isDirect": True
                        }
        except Exception:
            pass
        return None

    async def resolve_autoembed(self, tmdb_id: str, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Resolve autoembed.cc direct stream."""
        api_url = f"https://autoembed.cc/api/v2/{content_type}/{tmdb_id}"
        if content_type == "tv":
            api_url += f"/{season}/{episode}"
            
        try:
            async with httpx.AsyncClient(headers=self.headers, follow_redirects=True, timeout=10.0) as client:
                resp = await client.get(api_url)
                if resp.status_code == 200:
                    data = resp.json()
                    # AutoEmbed often provides a direct file link in its JSON
                    file_url = data.get("file") or data.get("url")
                    if file_url and (".m3u8" in file_url or ".mp4" in file_url):
                        return {
                            "url": file_url,
                            "serverId": "autoembed_direct",
                            "isDirect": True
                        }
        except Exception:
            pass
        return None

    async def get_clean_stream(self, tmdb_id: int, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Try all resolvers and return the cleanest working one, prioritizing direct links."""
        tmdb_str = str(tmdb_id)
        
        # Check for global challenges first (example: Vidsrc.to often triggers a challenge)
        try:
            async with httpx.AsyncClient(headers=self.headers, follow_redirects=True, timeout=5.0) as client:
                # Probe a common endpoint to see if we're being challenged
                probe_url = f"https://vidsrc.to/embed/movie/{tmdb_id}"
                resp = await client.get(probe_url)
                url_str = str(resp.url).lower()
                content = resp.text.lower()
                
                # Refined: Ignore common ad/clickbait domains
                is_clickbait = any(x in url_str for x in ["click", "ads", "pop", "redirect", "bet", "game"])
                
                if not is_clickbait and (
                   any(x in url_str for x in ["verify", "captcha", "checkpoint", "challenge"]) or \
                   any(x in content for x in ["captcha", "robot", "verify you are human", "cf-challenge"]) or \
                   resp.status_code == 403):
                    return {
                        "url": str(resp.url),
                        "serverId": "vidsrc_to_challenge",
                        "isDirect": False,
                        "challengeUrl": str(resp.url)
                    }
        except Exception:
            pass

        resolvers = [
            self.resolve_vidlink,
            self.resolve_autoembed,
            self.resolve_vidsrc_me,
            self.resolve_vidsrc_to,
            self.resolve_vidsrc_embed_ru
        ]
        
        results = []
        for resolver in resolvers:
            result = await resolver(tmdb_str, content_type, season, episode)
            if result:
                # Check if this specific result is a challenge
                if result.get("challengeUrl"):
                    return result
                
                if result.get("isDirect", False):
                    return result
                results.append(result)
        
        return results[0] if results else None
