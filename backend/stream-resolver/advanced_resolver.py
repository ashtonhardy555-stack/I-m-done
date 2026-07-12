import httpx
import re
import urllib.parse
from typing import Optional, Dict, List

class AdvancedStreamResolver:
    """
    Advanced Stream Resolver with LookMovie priority.

    Mirrors the LookMovie Kodi addon (plugin.video.lookmovietomb) extraction:

      1. SEARCH  — LookMovie does NOT use TMDB ids; it uses its own internal
         ids. We search the site by the content title.
      2. STORAGE — Fetch the /view/ then /play/ page and pull the
         `movie_storage` / `show_storage` JS object to get hash + id + expires
         (and the seasons array for shows).
      3. SECURITY API — Call LookMovie's security endpoint with those params to
         receive the real HLS manifest URL.

    TMDB ids are passed in only so the resolver can keep a compatibility
    signature; the title (and year) drive the actual lookup.
    """

    UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
    BASES = ["https://www.lookmovie2.to", "https://lookmovie2.to"]

    def __init__(self):
        self.headers = {
            "User-Agent": self.UA,
            "Referer": "https://www.lookmovie2.to/",
        }

    # ------------------------------------------------------------------ #
    #  Public API                                                         #
    # ------------------------------------------------------------------ #

    async def get_clean_stream(
        self,
        tmdb_id: int,
        content_type: str = "movie",
        season: int = 1,
        episode: int = 1,
        title: Optional[str] = None,
        year: Optional[str] = None,
    ) -> Optional[Dict]:
        """Main resolver — LookMovie first (by title), matching the Kodi addon."""
        if title:
            result = await self.resolve_lookmovie(title, year, content_type, season, episode)
            if result:
                return result
        return None

    # ------------------------------------------------------------------ #
    #  LookMovie                                                          #
    # ------------------------------------------------------------------ #

    async def resolve_lookmovie(
        self,
        title: str,
        year: Optional[str],
        content_type: str = "movie",
        season: int = 1,
        episode: int = 1,
    ) -> Optional[Dict]:
        for base in self.BASES:
            try:
                async with httpx.AsyncClient(
                    headers=self.headers, timeout=15.0, follow_redirects=True
                ) as client:
                    # 1. Prime cookies.
                    await client.get(base)

                    # 2. Search by title.
                    lm_id = await self._search(client, base, title, content_type, year)
                    if not lm_id:
                        continue

                    # 3. Storage + security API.
                    if content_type == "tv":
                        url = await self._resolve_episode(client, base, lm_id, season, episode)
                    else:
                        url = await self._resolve_movie(client, base, lm_id)

                    if url:
                        return {"url": url, "serverId": "lookmovie_direct", "isDirect": True}
            except Exception as e:
                print(f"LookMovie error on {base}: {e}")
        return None

    # ------------------------------------------------------------------ #
    #  Search (by title)                                                  #
    # ------------------------------------------------------------------ #

    async def _search(
        self, client: httpx.AsyncClient, base: str, title: str,
        content_type: str, year: Optional[str],
    ) -> Optional[int]:
        path = "/shows/search/page/1?q=" if content_type == "tv" else "/movies/search/page/1?q="
        url = f"{base}{path}{urllib.parse.quote(title)}"
        resp = await client.get(url)
        html = resp.text

        # Parse movie-item blocks: each holds href="/movies/view/123" and a year.
        items = re.findall(r'<div\s+class="movie-item[^"]*"[^>]*>([\s\S]*?)(?=<div\s+class="movie-item|</div>\s*$)', html)
        if not items:
            # Fallback: just grab the first view href on the page.
            m = re.search(r'href="(/(?:movies|shows)/view/(\d+))"', html)
            return int(m.group(2)) if m else None

        best = None
        yr = (year or "")[:4]
        for block in items:
            m = re.search(r'href="(/(?:movies|shows)/view/(\d+))"', block)
            if not m:
                continue
            lm_id = int(m.group(2))
            if yr:
                found_year = re.search(r'year">(\d{4})<', block)
                if found_year and found_year.group(1) == yr:
                    return lm_id
            if best is None:
                best = lm_id
        return best

    # ------------------------------------------------------------------ #
    #  Movie storage → movie-access                                       #
    # ------------------------------------------------------------------ #

    async def _resolve_movie(
        self, client: httpx.AsyncClient, base: str, lm_id: int,
    ) -> Optional[str]:
        play_url = f"{base}/movies/play/{lm_id}"
        resp = await client.get(play_url)
        html = resp.text.replace('\\"', "'").replace("'", '"')

        m = re.search(r'movie_storage"\]\s*=\s*(\{[\s\S]*?\})', html, re.DOTALL)
        if not m:
            return None
        data = m.group(1)
        hash_m = re.search(r'hash\s*:\s*"([^"]+)"', data)
        id_m = re.search(r'id_movie\s*:\s*(\d+)', data)
        exp_m = re.search(r'expires\s*:\s*(\d+)', data)
        if not (hash_m and id_m and exp_m):
            return None

        return await self._call_security(
            client, f"{base}/api/v1/security/movie-access",
            {"id_movie": id_m.group(1), "hash": hash_m.group(1), "expires": exp_m.group(1)},
            play_url,
        )

    # ------------------------------------------------------------------ #
    #  Show storage → episode-access                                      #
    # ------------------------------------------------------------------ #

    async def _resolve_episode(
        self, client: httpx.AsyncClient, base: str, lm_id: int,
        season: int, episode: int,
    ) -> Optional[str]:
        play_url = f"{base}/shows/play/{lm_id}"
        resp = await client.get(play_url)
        html = resp.text.replace('\\"', "'").replace("'", '"')

        m = re.search(r'show_storage"\]\s*=\s*(\{[\s\S]*?\}\s*;)', html, re.DOTALL)
        if not m:
            return None
        data = m.group(1)
        hash_m = re.search(r'hash\s*:\s*"([^"]+)"', data)
        exp_m = re.search(r'expires\s*:\s*(\d+)', data)
        id_ep = self._find_episode_id(data, season, episode)
        if not (hash_m and exp_m and id_ep):
            return None

        return await self._call_security(
            client, f"{base}/api/v1/security/episode-access",
            {"id_episode": id_ep, "hash": hash_m.group(1), "expires": exp_m.group(1)},
            play_url,
        )

    def _find_episode_id(self, storage: str, season: int, episode: int) -> Optional[str]:
        m = re.search(r'seasons\s*:\s*(\[[\s\S]*?\])', storage, re.DOTALL)
        if not m:
            return None
        block = m.group(1)
        for obj in re.findall(r'\{([^{}]*)\}', block):
            s = re.search(r'season\s*:\s*"(\d+)"', obj)
            e = re.search(r'episode\s*:\s*"(\d+)"', obj)
            if s and e and int(s.group(1)) == season and int(e.group(1)) == episode:
                idm = re.search(r'id_episode\s*:\s*(\d+)', obj)
                if idm:
                    return idm.group(1)
        return None

    # ------------------------------------------------------------------ #
    #  Security API call                                                  #
    # ------------------------------------------------------------------ #

    async def _call_security(
        self, client: httpx.AsyncClient, api_url: str,
        params: Dict[str, str], referer: str,
    ) -> Optional[str]:
        hdrs = {
            "User-Agent": self.UA,
            "Referer": referer,
            "X-Requested-With": "XMLHttpRequest",
            "Accept": "application/json, text/javascript, */*; q=0.01",
        }
        resp = await client.get(api_url, params=params, headers=hdrs)
        body = resp.text
        sm = re.search(r'"streams"\s*:\s*\{([^}]*)\}', body)
        if not sm:
            return None
        um = re.search(r':\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)', sm.group(1))
        return um.group(1) if um else None
