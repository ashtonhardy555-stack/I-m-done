#!/usr/bin/env python3
"""
server_health_check.py — auto-updating server reliability checker for the
"I'm Done" Android streaming app.

Runs on GitHub Actions (daily cron). For every server in working-servers.json
it probes the movie + TV embed URL for a small set of well-known TMDB titles,
records how many responded 2xx within a timeout, and writes an updated
working-servers.json with fresh `reliability` scores and `enabled` flags.
The app fetches this file at runtime so dead servers are auto-disabled and
new reliability scores flow to every install without an app update.

Probe strategy (fast, no JS execution):
  - Build the embed URL from the server's movie/tv URL template.
  - GET with a desktop User-Agent + Referer, follow redirects, 12s timeout.
  - A server "responds" if HTTP status is in 200..299 OR 403 (Cloudflare
    challenge — the host is alive, just JS-gated, which the app's WebView
    extractor handles). 404/5xx/DNS/timeout = down.
  - reliability = round(100 * responses / probes) blended with the previous
    score (exponential moving average) so one bad day doesn't kill a good
    server and one good day doesn't fully resurrect a dead one.

Usage:
  python3 scripts/server_health_check.py [--input working-servers.json] [--output working-servers.json] [--timeout 12]

Exits 0 always (so the workflow commit step runs regardless of how many
servers are down — we want to record the state, not fail the build).
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Tuple

import urllib.request
import urllib.error
import ssl

# ── Test titles (popular, stable TMDB ids) ──────────────────────────────────
# Movies: The Green Mile (497), Interstellar (157336), The Matrix (603),
#         The Dark Knight (155), Spider-Man: NWH (634649)
# TV:     Breaking Bad (1396), Game of Thrones (1399), The Rookie (60625? ->
#         use 60625 only if it resolves; we also use Rick and Morty 60625)
TEST_MOVIES = [
    ("497", "The Green Mile"),
    ("157336", "Interstellar"),
    ("603", "The Matrix"),
    ("155", "The Dark Knight"),
]
TEST_TV = [
    ("1396", "Breaking Bad", 1, 1),
    ("1399", "Game of Thrones", 1, 1),
]

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
)
SSL_CTX = ssl.create_default_context()
SSL_CTX.check_hostname = False
SSL_CTX.verify_mode = ssl.CERT_NONE


def build_url(template: str, base: str, tmdb: str, season: int = 1, episode: int = 1) -> str:
    """Expand {base}/{id}/{season}/{episode} placeholders."""
    return (
        template
        .replace("{base}", base.rstrip("/"))
        .replace("{id}", tmdb)
        .replace("{season}", str(season))
        .replace("{episode}", str(episode))
    )


def probe(url: str, referer: str, timeout: int) -> Tuple[bool, int, str]:
    """Return (alive, status_code, reason)."""
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": referer,
    }, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=SSL_CTX) as resp:
            code = resp.getcode()
            # Read a little so the connection closes cleanly.
            resp.read(2048)
            # 2xx or 403 (Cloudflare challenge = host alive, JS-gated) = alive.
            alive = 200 <= code < 300 or code == 403
            return alive, code, "ok"
    except urllib.error.HTTPError as e:
        code = e.code
        alive = 200 <= code < 300 or code == 403
        return alive, code, f"HTTP {code}"
    except urllib.error.URLError as e:
        return False, 0, f"URLError: {e.reason}"
    except Exception as e:
        return False, 0, f"{type(e).__name__}: {e}"


def health_check_server(server: Dict[str, Any], timeout: int) -> Dict[str, Any]:
    """Probe one server across all test titles. Returns updated server dict."""
    sid = server.get("id", "?")
    name = server.get("name", sid)
    base = server.get("baseUrl", "")
    movie_tpl = server.get("movieUrlTemplate", "")
    tv_tpl = server.get("tvUrlTemplate", "")
    referer = base.rstrip("/") + "/" if base else "https://vidstorm.ru/"

    probes: List[Tuple[str, str]] = []  # (label, url)
    for tmdb, title in TEST_MOVIES:
        if movie_tpl and "{id}" in movie_tpl:
            probes.append((f"movie:{title}", build_url(movie_tpl, base, tmdb)))
    for tmdb, title, s, e in TEST_TV:
        if tv_tpl and "{id}" in tv_tpl:
            probes.append((f"tv:{title}", build_url(tv_tpl, base, tmdb, s, e)))

    if not probes:
        # No usable templates — can't probe, keep prior reliability.
        server["_probe_notes"] = "no usable URL templates"
        return server

    hits = 0
    notes = []
    for label, url in probes:
        alive, code, reason = probe(url, referer, timeout)
        tag = "OK" if alive else "DOWN"
        notes.append(f"{label}:{tag}({code})")
        if alive:
            hits += 1

    raw_ratio = hits / len(probes) if probes else 0.0
    # Exponential moving average: blend today's ratio with the prior score.
    prior = float(server.get("reliability", 50))
    new_score = round(0.6 * (raw_ratio * 100) + 0.4 * prior)
    # Clamp.
    new_score = max(0, min(100, new_score))

    server["reliability"] = new_score
    # Disable a server only if it has been consistently dead (score <= 10).
    # A single bad probe day shouldn't kill it — the EMA handles gradual decay.
    if new_score <= 10:
        server["enabled"] = False
    else:
        # Re-enable if it recovered above the disable threshold.
        server["enabled"] = True

    server["_probe_notes"] = f"{hits}/{len(probes)} alive | " + " ".join(notes)
    print(f"  [{sid:>16}] {name:<20} score={new_score:>3}  {hits}/{len(probes)}  {' '.join(notes)}")
    return server


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", default="working-servers.json")
    ap.add_argument("--output", default="working-servers.json")
    ap.add_argument("--timeout", type=int, default=12)
    ap.add_argument("--max-workers", type=int, default=8)
    args = ap.parse_args()

    with open(args.input, "r", encoding="utf-8") as f:
        data = json.load(f)

    servers = data.get("servers", [])
    print(f"=== Server health check: {len(servers)} servers, timeout={args.timeout}s ===")
    print(f"=== Test movies: {[t[1] for t in TEST_MOVIES]} ===")
    print(f"=== Test TV: {[t[1] for t in TEST_TV]} ===\n")

    t0 = time.time()
    results: List[Dict[str, Any]] = []
    # Probe servers in parallel for speed.
    with ThreadPoolExecutor(max_workers=args.max_workers) as pool:
        future_map = {
            pool.submit(health_check_server, dict(s), args.timeout): s.get("id", "?")
            for s in servers
        }
        for fut in as_completed(future_map):
            try:
                results.append(fut.result())
            except Exception as e:
                sid = future_map[fut]
                print(f"  [ERROR] server {sid}: {e}")

    # Re-order results to match original server order (stable).
    order = {s.get("id"): i for i, s in enumerate(servers)}
    results.sort(key=lambda s: order.get(s.get("id"), 9999))

    # Strip probe notes from the written file (keep the file clean).
    clean = []
    for s in results:
        s.pop("_probe_notes", None)
        clean.append(s)

    data["servers"] = clean
    data["version"] = data.get("version", 1)
    data["updated"] = dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")

    enabled = sum(1 for s in clean if s.get("enabled"))
    elapsed = time.time() - t0
    print(f"\n=== Done in {elapsed:.1f}s. {enabled}/{len(clean)} servers enabled. ===")
    print(f"=== Wrote {args.output} (version {data['version']}, updated {data['updated']}) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
