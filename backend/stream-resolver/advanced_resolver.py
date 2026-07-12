import httpx
import re
from typing import Optional, Dict

class AdvancedStreamResolver:
    """
    Advanced Stream Resolver with LookMovie priority
    """

    def __init__(self):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://www.lookmovie2.to/",
        }

    async def resolve_lookmovie(self, tmdb_id: int, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        base = "https://www.lookmovie2.to"
        try:
            if content_type == "movie":
                play_url = f"{base}/movies/play/{tmdb_id}"
            else:
                play_url = f"{base}/shows/play/{tmdb_id}/{season}/{episode}"

            async with httpx.AsyncClient(headers=self.headers, timeout=15.0, follow_redirects=True) as client:
                resp = await client.get(play_url)
                html = resp.text

                if '>Thread Defence' in html or 'recaptcha' in html.lower():
                    return {"url": str(resp.url), "serverId": "lookmovie_captcha", "isDirect": False}

                # Extract streams
                if content_type == "movie":
                    dt = re.search(r'movie_storage"\]\s*=\s*({.*?})', html, re.DOTALL)
                    api_url = f"{base}/api/v1/security/movie-access"
                    id_key = "id_movie"
                else:
                    dt = re.search(r'show_storage"\]\s*=\s*({.*?};\\n\s+)', html, re.DOTALL)
                    api_url = f"{base}/api/v1/security/episode-access"
                    id_key = "id_episode"

                if dt:
                    data = dt.group(1)
                    hash_m = re.search(r'hash\s*:\s*"([^"]+)"', data)
                    id_m = re.search(rf'{id_key}\s*:\s*(\d+)', data)

                    if hash_m and id_m:
                        params = {id_key: id_m.group(1), "hash": hash_m.group(1)}
                        access = await client.get(api_url, params=params)
                        streams = access.json().get("streams", {})
                        if streams:
                            return {"url": list(streams.values())[0], "serverId": "lookmovie_direct", "isDirect": True}
        except Exception as e:
            print(f"LookMovie error: {e}")

        return {"url": play_url, "serverId": "lookmovie_embed", "isDirect": False}

    async def get_clean_stream(self, tmdb_id: int, content_type: str = "movie", season: int = 1, episode: int = 1) -> Optional[Dict]:
        """Main resolver - LookMovie first"""
        result = await self.resolve_lookmovie(tmdb_id, content_type, season, episode)
        if result:
            return result
        return None
