import re
import requests
import json
from typing import Dict

class LookMovieResolver:
    def __init__(self):
        self.session = requests.Session()
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://www.lookmovie2.to/",
        }

    def resolve(self, tmdb_id: int, content_type: str = "movie", season: int = 1, episode: int = 1) -> Dict:
        base = "https://www.lookmovie2.to"
        try:
            if content_type.lower() == "movie":
                play_url = f"{base}/movies/play/{tmdb_id}"
            else:
                play_url = f"{base}/shows/play/{tmdb_id}/{season}/{episode}"

            resp = self.session.get(play_url, headers=self.headers, timeout=20)
            html = resp.text

            if any(x in html.lower() for x in ['thread defence', 'recaptcha', 'challenge']):
                return {"url": str(resp.url), "isDirect": False, "needsCaptcha": True, "server": "LookMovie"}

            # Extract streams
            if content_type.lower() == "movie":
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
                    access = self.session.get(api_url, params=params, headers=self.headers)
                    streams = access.json().get("streams", {})
                    if streams:
                        direct_url = list(streams.values())[0]
                        return {"url": direct_url, "isDirect": True, "server": "LookMovie"}

            return {"url": play_url, "isDirect": False, "server": "LookMovie"}

        except Exception as e:
            return {"error": str(e), "url": "", "isDirect": False}
