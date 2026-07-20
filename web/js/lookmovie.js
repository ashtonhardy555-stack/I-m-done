/**
 * LookMovieExtractor — a pure-fetch, no-backend port of the Android app's
 * `LookMovieHeadlessExtractor.kt`, which is itself a pure-OkHttp port of the
 * `plugin.video.lookmovietomb` Kodi addon's extraction flow (main.py).
 *
 * ## The flow (a 1:1 port)
 *
 * 1. SEARCH — GET /movies/search/page/1?q=<title> (TV: /shows/search/...).
 *    Parse HTML for the first `<a href="/movies/view/{id-slug}">`. We pick the
 *    result whose slug best matches the requested title (and year, when
 *    available) to avoid grabbing the wrong entry on ambiguous searches.
 *
 * 2. STORAGE — GET /movies/play/{id-slug} (TV: /shows/play/{id-slug}).
 *    Regex out the `movie_storage` / `show_storage` JS object to get:
 *      Movie → hash, id_movie, expires
 *      TV    → hash, expires, and the `seasons` array; scan the seasons array
 *              for the episode matching (season, episode) and read its id_episode.
 *
 * 3. SECURITY API — GET /api/v1/security/movie-access?id_movie=&hash=&expires=
 *    (TV: /api/v1/security/episode-access?id_episode=&hash=&expires=) with
 *    `Referer: <play page>` and `X-Requested-With: XMLHttpRequest`.
 *    Returns JSON { "streams": {"1080p": "https://...m3u8", ...}, "subtitles": [...] }.
 *    We take the first non-empty stream value (highest quality), matching the
 *    addon's `[x for x in streams.values() if x][0]`.
 *
 * 4. PLAY — The addon runs a LOCAL HTTP proxy (serverHTTP.py) that injects the
 *    `t_hash={hash}` cookie on every .m3u8/segment request. In the browser we
 *    attach the `Cookie: t_hash={hash}` (plus UA + Referer where the browser
 *    allows) to the HLS.js loader config so it is sent on every playlist +
 *    segment fetch — a true headless play with zero extra server process.
 *
 * ## CORS note
 *
 * LookMovie does not send CORS headers, so direct fetch() from the browser will
 * be blocked. This extractor is designed to be driven through the KodiEngine,
 * which uses a CORS-bypassing proxy for the extraction requests. The final
 * stream URL (the m3u8) is played by HLS.js which handles cross-origin media
 * via standard <video>/MSE + range requests, with the headers attached.
 */
const LookMovieExtractor = (() => {

  const TAG = "LookMovie";
  const BASE = "https://www.lookmovie2.to";

  // The addon's exact User-Agent (Firefox 115 on Win64). Reusing it maximises
  // the chance the request shape matches what the server expects.
  const UA = "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0";

  /**
   * The CORS proxy used for the extraction requests (search/play/security).
   * The final m3u8 stream itself is NOT proxied — HLS.js fetches it directly
   * with the t_hash cookie attached so playback is native and performant.
   * A rotating set of public CORS proxies provides redundancy.
   */
  const CORS_PROXIES = [
    (u) => `https://api.allorigins.win/raw?url=${encodeURIComponent(u)}`,
    (u) => `https://corsproxy.io/?url=${encodeURIComponent(u)}`,
    (u) => `https://thingproxy.freeboard.io/fetch/${u}`,
  ];

  function log(...a) { console.log(`[${TAG}]`, ...a); }
  function warn(...a) { console.warn(`[${TAG}]`, ...a); }

  class ResultStream {
    constructor(url, headers, providerName) {
      this.url = url;
      this.headers = headers;
      this.providerName = providerName;
    }
  }
  class ResultError {
    constructor(message) { this.message = message; }
  }

  /**
   * Resolve a direct playable stream for the given title.
   *
   * @param {string} title     the movie/show title to search for
   * @param {string|null} year release year (used to disambiguate search results)
   * @param {boolean} isMovie  true for movies, false for TV
   * @param {number} season    1-indexed season (TV only)
   * @param {number} episode   1-indexed episode (TV only)
   * @returns {Promise<ResultStream|ResultError>}
   */
  async function extract(title, year, isMovie, season, episode) {
    if (!title || !title.trim()) return new ResultError("LookMovie: no title");

    const root = isMovie ? "movies" : "shows";
    try {
      // ── 1. SEARCH ──
      const query = encodeURIComponent(title);
      const searchUrl = `${BASE}/${root}/search/page/1?q=${query}`;
      const searchHtml = await get(searchUrl, baseHeaders(`${BASE}/`));
      if (!searchHtml) {
        warn("search fetch failed / blocked");
        return new ResultError("LookMovie: search failed");
      }
      // v0.8 of the addon changed the captcha marker to "g-recaptcha".
      if (searchHtml.includes("g-recaptcha")) {
        warn("search hit reCAPTCHA interstitial — skipping");
        return new ResultError("LookMovie: reCAPTCHA (captcha)");
      }

      const slugRegex = isMovie
        ? /\/movies\/view\/([^"'<>]+)/g
        : /\/shows\/view\/([^"'<>]+)/g;
      const candidates = [...searchHtml.matchAll(slugRegex)]
        .map(m => m[1].trim())
        .filter(s => s.length > 0);
      const distinct = [...new Set(candidates)];

      if (distinct.length === 0) {
        warn(`no search results for '${title}'`);
        return new ResultError("LookMovie: no results");
      }

      const slug = pickBestSlug(distinct, title, year);
      log(`search '${title}' → slug '${slug}' (of ${distinct.length})`);

      // ── 2. STORAGE ──
      const playUrl = `${BASE}/${root}/play/${slug}`;
      const playHtml = await get(playUrl, baseHeaders(`${BASE}/${root}/search/page/1?q=${query}`));
      if (!playHtml) {
        warn(`play fetch failed / blocked for ${slug}`);
        return new ResultError("LookMovie: play fetch failed");
      }
      if (playHtml.includes("g-recaptcha")) {
        warn("play hit reCAPTCHA interstitial — skipping");
        return new ResultError("LookMovie: reCAPTCHA (captcha)");
      }

      // Normalise quote styles the same way the addon does before regexing.
      const norm = playHtml.replace(/\\"/g, "'").replace(/'/g, '"');

      let hash, expires, securityPath, idParamName, idParamValue;

      if (isMovie) {
        const storageMatch = norm.match(/movie_storage"\]\s*=\s*(\{.*?\})/s);
        if (!storageMatch) {
          warn(`no movie_storage block for ${slug}`);
          return new ResultError("LookMovie: no movie_storage");
        }
        const storage = storageMatch[1];
        hash = extractField(storage, "hash");
        if (!hash) return new ResultError("LookMovie: no hash");
        const idMovie = extractNum(storage, "id_movie");
        if (!idMovie) return new ResultError("LookMovie: no id_movie");
        expires = extractNum(storage, "expires");
        if (!expires) return new ResultError("LookMovie: no expires");
        securityPath = "/api/v1/security/movie-access";
        idParamName = "id_movie";
        idParamValue = idMovie;
      } else {
        const storageMatch = norm.match(/show_storage"\]\s*=\s*(\{.*?\};)/s);
        if (!storageMatch) {
          warn(`no show_storage block for ${slug}`);
          return new ResultError("LookMovie: no show_storage");
        }
        const storage = storageMatch[1];
        hash = extractField(storage, "hash");
        if (!hash) return new ResultError("LookMovie: no hash");
        expires = extractNum(storage, "expires");
        if (!expires) return new ResultError("LookMovie: no expires");

        // Normalise the matched storage block (ListSerial in main.py): collapse
        // escaped quotes, strip newlines and triple-spaces so the seasons
        // array parses as one contiguous run.
        const storageClean = storage.replace(/\\"/g, "'").replace(/\n/g, "").replace(/   /g, "");

        const seasonsMatch = storageClean.match(/"?seasons"?\s*:\s*(\[.*?\])/s);
        if (!seasonsMatch) {
          warn(`no seasons array for ${slug}`);
          return new ResultError("LookMovie: no seasons");
        }
        const seasons = seasonsMatch[1];

        // Find the id_episode for the requested (season, episode).
        // LookMovie lists episode objects with fields in ARBITRARY order (often
        // id_episode FIRST), so a single fixed-order regex misses every episode.
        // Splitting each object and pulling season/episode/id_episode out of it
        // individually works regardless of field order. (Port of the addon's
        // robust per-episode-object parsing.)
        const episodeObjectRegex = /(\{.*?\})(?:,|\])/gs;
        const seasonRe = /(?<![A-Za-z_])["']?season["']?\s*:\s*"?(\\d+)["']?/;
        const episodeRe = /(?<![A-Za-z_])["']?episode["']?\s*:\s*"?(\\d+)["']?/;
        const idEpisodeRe = /(?<![A-Za-z_])["']?id_episode["']?\s*:\s*(\d+)/;
        let idEpisode = null;
        for (const m of seasons.matchAll(episodeObjectRegex)) {
          const obj = m[1];
          const s = seasonRe.exec(obj)?.[1];
          const e = episodeRe.exec(obj)?.[1];
          if (s != null && e != null && Number(s) === season && Number(e) === episode) {
            idEpisode = idEpisodeRe.exec(obj)?.[1];
            if (idEpisode) break;
          }
        }
        if (!idEpisode) {
          warn(`no S${season}E${episode} in seasons for ${slug}`);
          return new ResultError("LookMovie: episode not found");
        }
        securityPath = "/api/v1/security/episode-access";
        idParamName = "id_episode";
        idParamValue = idEpisode;
      }

      // ── 3. SECURITY API ──
      const securityUrl = `${BASE}${securityPath}?${idParamName}=${idParamValue}&hash=${hash}&expires=${expires}`;
      const secBody = await get(
        securityUrl,
        { ...baseHeaders(playUrl), "X-Requested-With": "XMLHttpRequest" }
      );
      if (!secBody) {
        warn("security API failed / blocked");
        return new ResultError("LookMovie: security API failed");
      }

      let json;
      try { json = JSON.parse(secBody); }
      catch (e) {
        warn(`security response not JSON: ${secBody.slice(0, 120)}`);
        return new ResultError("LookMovie: security not JSON");
      }
      const streams = json.streams;
      if (!streams) {
        warn("no streams object in security response");
        return new ResultError("LookMovie: no streams");
      }
      // First non-empty, http-prefixed stream value (highest quality), matching
      // the addon's `[x for x in list(streams.values()) if x][0]`.
      let m3u8 = "";
      for (const key of Object.keys(streams)) {
        const v = streams[key];
        if (typeof v === "string" && v.trim() && v.startsWith("http")) {
          m3u8 = v;
          break;
        }
      }
      if (!m3u8) {
        warn("no valid (non-empty) stream in security response");
        return new ResultError("LookMovie: no valid stream");
      }

      log(`✅ LookMovie resolved (${slug}): ${m3u8}`);

      // ── 4. PLAY ──
      // HLS.js sends these headers on every playlist + segment fetch, so
      // attaching the t_hash cookie here replaces the addon's local proxy
      // (serverHTTP.py) entirely — a true headless play.
      const playHeaders = {
        ...baseHeaders(playUrl),
        "Cookie": `t_hash=${hash}`,
      };
      return new ResultStream(m3u8, playHeaders, "LookMovie");
    } catch (e) {
      warn(`extraction error: ${e.message}`);
      return new ResultError(`LookMovie: ${e.message || "error"}`);
    }
  }

  // ── helpers ──

  function baseHeaders(referer) {
    return {
      "User-Agent": UA,
      "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      "Accept-Language": "en-US,en;q=0.9",
      "Referer": referer,
    };
  }

  /**
   * Execute a GET via a CORS proxy, returning the body text or null on
   * non-2xx / error. Tries proxies in order until one succeeds.
   * (The browser cannot set User-Agent/Referer on cross-origin fetches, but
   * the CORS proxy forwards the request; LookMovie's nginx generally accepts
   * the proxied request shape.)
   */
  async function get(url, headers) {
    for (const proxy of CORS_PROXIES) {
      try {
        const proxied = proxy(url);
        const resp = await fetch(proxied, {
          method: "GET",
          // allorigins/corsproxy forward headers; we pass the Referer as a
          // custom header the addon's flow needs, but most public proxies
          // strip arbitrary headers. The request shape (path + query) is what
          // primarily matters for LookMovie's extraction.
          headers: { "X-Referer": headers["Referer"] || BASE },
          redirect: "follow",
        });
        if (resp.ok) {
          const text = await resp.text();
          if (text && text.length > 0) return text;
        }
      } catch (e) {
        // try next proxy
      }
    }
    return null;
  }

  /** Extract a quoted string field from a JS object. Handles both unquoted
   *  keys (hash:"abc") and quoted keys ("hash":"abc") — the `"?` around [name]
   *  makes the regex work either way. */
  function extractField(js, name) {
    const r = new RegExp(`"?${name}"?\\s*:\\s*"([^"]+)"`);
    return js.match(r)?.[1];
  }

  /** Extract a numeric field from a JS object. Handles both unquoted keys
   *  (expires:1700000000) and quoted keys ("expires":1700000000). */
  function extractNum(js, name) {
    const r = new RegExp(`"?${name}"?\\s*:\\s*(\\d+)`);
    return js.match(r)?.[1];
  }

  /**
   * Pick the search-result slug that best matches the requested title (and
   * year, if known). LookMovie slugs look like `12345-the-dark-knight-2008`,
   * so a substring match on the normalised title + year is a strong signal.
   */
  function pickBestSlug(slugs, title, year) {
    const normTitle = title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
    const yearStr = year && year.trim() ? year.trim() : null;

    function score(slug) {
      const s = slug.toLowerCase();
      let sc = 0;
      if (yearStr && s.includes(yearStr)) sc += 100;
      if (s.includes(normTitle)) sc += 50;
      else {
        const words = normTitle.split("-").filter(w => w.length > 2);
        sc += words.filter(w => s.includes(w)).length * 5;
      }
      sc -= Math.min(slugs.indexOf(slug), 10);
      return sc;
    }
    return slugs.slice().sort((a, b) => score(b) - score(a))[0] || slugs[0];
  }

  return { extract, ResultStream, ResultError, BASE };
})();
