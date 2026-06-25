import httpx
import re
from typing import Optional, Dict

class AdvancedStreamResolver:
    """
    Advanced Stream Resolver - LookMovie Priority (2026)
    """

    def __init__(self):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer": "https://www.lookmovie2.to/",
            "Accept-Language": "en-US,en;q=0.9",
        }

    # ====================== LOOKMOVIE (HIGHEST PRIORITY) ======================
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

                # Captcha / Thread Defence
                if '>Thread Defence' in html or 'recaptcha' in html.lower() or 'challenge' in html.lower():
                    return {
                        "url": str(resp.url),
                        "serverId": "lookmovie_captcha",
                        "isDirect": False,
                        "challengeUrl": str(resp.url)
                    }

                # Extract security data
                if content_type == "movie":
                    dt_match = re.search(r'movie_storage"\]\s*=\s*({.*?})', html, re.DOTALL)
                    api_url = f"{base}/api/v1/security/movie-access"
                    id_key = "id_movie"
                else:
                    dt_match = re.search(r'show_storage"\]\s*=\s*({.*?};\\n\s+)', html, re.DOTALL)
                    api_url = f"{base}/api/v1/security/episode-access"
                    id_key = "id_episode"

                if dt_match:
                    data = dt_match.group(1)
                    hash_match = re.search(r'hash\s*:\s*"([^"]+)"', data)
                    id_match = re.search(rf'{id_key}\s*:\s*(\d+)', data)
                    expires_match = re.search(r'expires\s*:\s*(\d+)', data)

                    if hash_match and id_match:
                        params = {
                            id_key: id_match.group(1),
                            "hash": hash_match.group(1),
                            "expires": expires_match.group(1) if expires_match else ""
                        }
                        access_resp = await client.get(api_url, params=params)
                        if access_resp.status_code == 200:
                            streams = access_resp.json
