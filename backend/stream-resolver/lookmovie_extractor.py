"""
LookMovie web extractor using Playwright (headless Chromium) to bypass
Cloudflare's JS challenge — mirrors the Android app's LookMovieWebExtractor
and the Kodi addon (plugin.video.lookmovietomb) flow exactly:

  1. SEARCH — search LookMovie by title to get the internal id
  2. STORAGE — load /play/{id}, extract movie_storage/show_storage JS object
  3. SECURITY API — call the security endpoint with hash/id/expires
  4. PLAY — the first stream in the response is the .m3u8 URL

All requests go through the headless browser so Cloudflare's cf_clearance
cookie is earned and used. No ads, no redirects — we only extract the
direct stream URL.
"""

import re
import json
import urllib.parse
import logging
from typing import Optional, Dict, Tuple
from playwright.sync_api import sync_playwright, Browser, Page

logger = logging.getLogger(__name__)

UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
BASES = [
    "https://www.lookmovie2.to",
    "https://lookmovie2.to",
]
PAGE_TIMEOUT = 25000  # ms
CHALLENGE_GRACE = 12000  # ms — time for Cloudflare JS to auto-solve


class LookMovieResult:
    STREAM = "stream"
    ERROR = "error"


def extract_stream(
    title: str,
    year: Optional[str] = None,
    content_type: str = "movie",
    season: int = 1,
    episode: int = 1,
) -> Dict:
    """
    Extract a direct playable .m3u8 stream from LookMovie.

    Returns a dict with:
      success: bool
      url: str (the m3u8 URL) — only if success
      hash: str (the t_hash cookie value) — only if success
      referer: str — only if success
      base: str — only if success
      error: str — only if not success
    """
    for base in BASES:
        try:
            result = _run_flow(base, title, year, content_type, season, episode)
            if result and result.get("success"):
                return result
            logger.info(f"LookMovie: no stream on {base}, trying next mirror")
        except Exception as e:
            logger.warning(f"LookMovie flow failed on {base}: {e}")

    return {
        "success": False,
        "error": f'No stream found for "{title}" from LookMovie.',
    }


def _run_flow(
    base: str,
    title: str,
    year: Optional[str],
    content_type: str,
    season: int,
    episode: int,
) -> Optional[Dict]:
    """Run the full Kodi flow for one mirror inside a single browser page."""

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-blink-features=AutomationControlled",
                "--disable-dev-shm-usage",
            ],
        )
        context = browser.new_context(
            user_agent=UA,
            viewport={"width": 1280, "height": 720},
            ignore_https_errors=True,
        )
        page = context.new_page()

        # Block ads, redirects, popups — only allow the LookMovie domain and
        # its CDN. Everything else is aborted.
        def _route_handler(route):
            req_url = route.request.url
            req_type = route.request.resource_type

            # Allow the main page, scripts, xhr, and media from lookmovie2.to
            # and its CDN (which serves the .m3u8 and .ts segments).
            allowed_domains = [
                "lookmovie2.to",
                "lookmovie",
            ]
            is_allowed = any(d in req_url for d in allowed_domains)

            if not is_allowed:
                # Block everything from third-party domains (ads, trackers,
                # redirect scripts, popups).
                logger.debug(f"BLOCKED (ad/redirect): {req_url}")
                route.abort()
                return

            # Within LookMovie: block images, stylesheets, fonts (faster).
            if req_type in ("image", "stylesheet", "font"):
                route.abort()
                return

            route.continue_()

        page.route("**/*", _route_handler)

        try:
            # Step 1: Prime cookies + Cloudflare clearance.
            logger.info(f"LookMovie: loading base {base}")
            page.goto(base, timeout=PAGE_TIMEOUT, wait_until="domcontentloaded")
            # Give Cloudflare JS challenge time to clear.
            page.wait_for_timeout(6000)

            # Step 2: Search by title.
            search_path = (
                "/shows/search/page/1?q=" if content_type == "tv"
                else "/movies/search/page/1?q="
            )
            search_url = f"{base}{search_path}{urllib.parse.quote(title)}"
            logger.info(f"LookMovie: searching {search_url}")
            page.goto(search_url, timeout=PAGE_TIMEOUT, wait_until="domcontentloaded")
            page.wait_for_timeout(2000)

            lm_id = _parse_search_results(page, year)
            if not lm_id:
                logger.warning(f"LookMovie: no id found for '{title}'")
                return None

            logger.info(f"LookMovie: found id {lm_id} for '{title}'")

            # Step 3: Load the /play/{id} page and extract storage object.
            play_url = (
                f"{base}/shows/play/{lm_id}" if content_type == "tv"
                else f"{base}/movies/play/{lm_id}"
            )
            logger.info(f"LookMovie: loading play page {play_url}")
            page.goto(play_url, timeout=PAGE_TIMEOUT, wait_until="domcontentloaded")
            page.wait_for_timeout(2000)

            html = page.content()
            storage = _extract_storage_block(html, content_type)
            if not storage:
                logger.warning("LookMovie: no storage object on play page")
                return None

            hash_val = _extract_field(storage, "hash")
            expires = _extract_number(storage, "expires")
            if not hash_val or not expires:
                logger.warning("LookMovie: incomplete storage object")
                return None

            # Step 4: Call the security API from inside the page context (so
            # it carries the cf_clearance cookie).
            if content_type == "tv":
                id_episode = _find_episode_id(storage, season, episode)
                if not id_episode:
                    logger.warning(f"LookMovie: episode S{season}E{episode} not found")
                    return None
                api_path = "/api/v1/security/episode-access"
                params = {"id_episode": id_episode, "hash": hash_val, "expires": expires}
            else:
                id_movie = _extract_number(storage, "id_movie")
                if not id_movie:
                    logger.warning("LookMovie: no id_movie in storage")
                    return None
                api_path = "/api/v1/security/movie-access"
                params = {"id_movie": id_movie, "hash": hash_val, "expires": expires}

            api_url = f"{base}{api_path}?{urllib.parse.urlencode(params)}"

            logger.info(f"LookMovie: calling security API {api_url}")

            # Use fetch() from inside the page to carry the Cloudflare cookie.
            response_body = page.evaluate(
                """async (apiUrl) => {
                    try {
                        const resp = await fetch(apiUrl, {
                            method: "GET",
                            headers: {
                                "X-Requested-With": "XMLHttpRequest",
                                "Accept": "application/json, text/javascript, */*; q=0.01"
                            },
                            credentials: "include"
                        });
                        return await resp.text();
                    } catch(e) {
                        return "ERROR:" + e.message;
                    }
                }""",
                api_url,
            )

            if not response_body or response_body.startswith("ERROR:"):
                logger.warning(f"LookMovie: security API failed: {response_body}")
                return None

            stream_url = _parse_security_api_json(response_body, base)
            if not stream_url:
                logger.warning("LookMovie: no stream URL in security response")
                return None

            logger.info(f"LookMovie: ✅ stream found: {stream_url}")

            return {
                "success": True,
                "url": stream_url,
                "hash": hash_val,
                "referer": f"{base}/",
                "base": base,
            }

        finally:
            context.close()
            browser.close()


# ──────────────────────────────────────────────────────────────────────────
#  Parsing helpers (mirror the Kotlin/Python regexes exactly)
# ──────────────────────────────────────────────────────────────────────────

def _parse_search_results(page: Page, year: Optional[str]) -> Optional[int]:
    """Extract LookMovie's internal id from the search page DOM."""
    raw = page.evaluate(
        """() => {
            try {
                var out = [];
                var blocks = document.querySelectorAll('div.movie-item, div[class*="movie-item"], div[class*="show-item"]');
                blocks.forEach(function(b){
                    var a = b.querySelector('a[href*="/movies/view/"], a[href*="/shows/view/"]');
                    if (!a) return;
                    var href = a.getAttribute('href') || '';
                    var m = href.match(/\\/(?:movies|shows)\\/view\\/(\\d+)/);
                    if (!m) return;
                    var yEl = b.querySelector('[class*="year"]');
                    var yr = yEl ? (yEl.textContent || '').trim() : '';
                    out.push(m[1] + '|' + yr);
                });
                if (out.length === 0) {
                    var as = document.querySelectorAll('a[href*="/movies/view/"], a[href*="/shows/view/"]');
                    as.forEach(function(a){
                        var m = (a.getAttribute('href')||'').match(/\\/(?:movies|shows)\\/view\\/(\\d+)/);
                        if (m) out.push(m[1] + '|');
                    });
                }
                return out.join(',');
            } catch(e){ return ''; }
        }"""
    )

    if not raw:
        return None

    yr = (year or "")[:4] if year else None
    best_id = None
    for entry in raw.split(","):
        parts = entry.split("|")
        try:
            lm_id = int(parts[0].strip())
        except (ValueError, IndexError):
            continue
        result_year = parts[1].strip()[:4] if len(parts) > 1 else ""
        if yr:
            if result_year == yr:
                return lm_id
        if best_id is None:
            best_id = lm_id

    return best_id


def _extract_storage_block(html: str, content_type: str) -> Optional[str]:
    """Extract the movie_storage/show_storage JS object from the page HTML."""
    if not html:
        return None
    normalized = html.replace('\\"', "'").replace("'", '"')
    key = "show_storage" if content_type == "tv" else "movie_storage"
    m = re.search(rf'{key}"\]\s*=\s*(\{{[\s\S]*?\}})', normalized)
    return m.group(1) if m else None


def _extract_field(text: str, key: str) -> Optional[str]:
    m = re.search(rf'{key}\s*:\s*"([^"]+)"', text)
    return m.group(1) if m else None


def _extract_number(text: str, key: str) -> Optional[str]:
    m = re.search(rf'{key}\s*:\s*(\d+)', text)
    return m.group(1) if m else None


def _find_episode_id(storage: str, season: int, episode: int) -> Optional[str]:
    """Find the id_episode for the given season/episode in the storage object."""
    m = re.search(r'seasons\s*:\s*(\[[\s\S]*?\])', storage)
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


def _parse_security_api_json(body: str, base: str) -> Optional[str]:
    """Parse the security API response to extract the stream URL."""
    # Try JSON parsing first.
    try:
        data = json.loads(body)
        streams = data.get("streams", {})
        for key, stream_url in streams.items():
            if stream_url and _is_playable(stream_url):
                return stream_url if stream_url.startswith("http") else f"{base}{stream_url}"
        return None
    except (json.JSONDecodeError, ValueError):
        pass

    # Fallback: regex.
    sm = re.search(r'"streams"\s*:\s*\{([^}]*)\}', body)
    if not sm:
        return None
    um = re.search(r':\s*"(https?://[^"]+\.(?:m3u8|mp4)[^"]*)', sm.group(1))
    if um:
        url = um.group(1)
        return url if url.startswith("http") else f"{base}{url}"
    return None


def _is_playable(url: str) -> bool:
    c = url.lower()
    return c.startswith("http") and (".m3u8" in c or ".mp4" in c)
