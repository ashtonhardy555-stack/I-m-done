/**
 * KodiEngine — a faithful JS port of the Android app's `KodiEngine.kt`.
 *
 * This is the user's "Kodi-like engine running in the background". It is a
 * headless engine that runs the LookMovie addon flow (search → storage →
 * security API → .m3u8) in the browser, so it can:
 *
 *  • resolve on demand — when the Player asks for a stream, the engine
 *    returns a cached one instantly (if fresh) or kicks off a fresh resolve
 *    in the background and delivers the result.
 *  • pre-resolve — browse/home screens can ask the engine to warm up the next
 *    likely title so playback starts instantly when the user hits play. This
 *    is the "Kodi addon running in the background" behaviour: it is always
 *    working ahead of the user.
 *  • cache — resolved streams go into an in-memory + localStorage cache so a
 *    replay or a quick back-and-forth never re-hits the network.
 *
 * The actual extraction is delegated to the existing, proven
 * `LookMovieExtractor` — a pure-fetch port of the addon — so we do NOT
 * duplicate the addon logic. The engine is the *orchestration* layer; the
 * extractor is the *addon* layer. Only the LookMovie addon is plugged in —
 * all other servers (VidLink, VidSrc, 2Embed, NoTorrent, etc.) are excluded
 * per the user's request.
 *
 * ## Concurrency
 * Resolves are de-duplicated: if two callers ask for the same title at once,
 * only one network resolve runs and both observe the result. Pre-resolve
 * jobs are best-effort and never block an on-demand resolve.
 */
const KodiEngine = (() => {

  const TAG = "KodiEngine";
  const CACHE_PREFIX = "kodi_cache:";
  const CACHE_TTL_MS = 1000 * 60 * 45; // 45 min — streams can expire
  const MAX_CONCURRENT_PRE = 3;

  function log(...a) { console.log(`[${TAG}]`, ...a); }

  // ── The LookMovie addon adapter — wraps the existing extractor ──
  const lookmovieAddon = {
    id: "lookmovietomb",
    async resolve(req) {
      const r = await LookMovieExtractor.extract(
        req.title, req.year, req.isMovie, req.season, req.episode
      );
      if (r instanceof LookMovieExtractor.ResultStream) {
        return {
          kind: "stream",
          url: r.url,
          headers: r.headers,
          providerName: r.providerName || "LookMovie",
        };
      }
      return { kind: "error", message: r.message };
    },
  };

  /** Addons consulted, in priority order. Only LookMovie is plugged in. */
  const addons = [lookmovieAddon];

  // ── In-flight single-flight: de-duplicate concurrent resolves for same key ──
  const inFlight = new Map(); // key -> Promise<ResolvedStream|null>

  // ── Resolved event subscribers (Player can listen for background results) ──
  const resolvedListeners = new Set();

  let running = false;

  function start() {
    if (running) return;
    running = true;
    log(`Kodi-like engine started (headless, no WebView). Addons: ${addons.map(a => a.id)}`);
  }
  function stop() {
    running = false;
    inFlight.clear();
    log("engine stopped");
  }

  // ── Cache (memory + localStorage) ──

  function cacheKey(title, isMovie, season, episode) {
    return `${CACHE_PREFIX}${isMovie ? "movie" : "tv"}::${title.toLowerCase()}::S${season}E${episode}`;
  }

  function cacheGet(title, isMovie, season, episode) {
    const k = cacheKey(title, isMovie, season, episode);
    // memory
    const mem = memCache.get(k);
    if (mem && Date.now() - mem.ts < CACHE_TTL_MS) return mem.entry;
    // localStorage
    try {
      const raw = localStorage.getItem(k);
      if (raw) {
        const parsed = JSON.parse(raw);
        if (Date.now() - parsed.ts < CACHE_TTL_MS) {
          memCache.set(k, parsed);
          return parsed.entry;
        }
        localStorage.removeItem(k);
      }
    } catch (e) {}
    return null;
  }

  function cachePut(title, isMovie, season, episode, url, headers, providerName) {
    const k = cacheKey(title, isMovie, season, episode);
    const entry = { url, headers, providerName };
    const wrapped = { entry, ts: Date.now() };
    memCache.set(k, wrapped);
    try {
      localStorage.setItem(k, JSON.stringify(wrapped));
    } catch (e) { /* quota — ignore */ }
  }

  function cacheInvalidate(title, isMovie, season, episode) {
    const k = cacheKey(title, isMovie, season, episode);
    memCache.delete(k);
    try { localStorage.removeItem(k); } catch (e) {}
  }

  function cacheSize() {
    let n = 0;
    try {
      for (let i = 0; i < localStorage.length; i++) {
        if (localStorage.key(i).startsWith(CACHE_PREFIX)) n++;
      }
    } catch (e) {}
    return n;
  }

  const memCache = new Map();

  // ── Public API ──

  /**
   * Resolve a stream for req. If a fresh cached entry exists it is returned
   * synchronously (fromCache=true). Otherwise a background resolve is started
   * and { pending: true } is returned; the caller may await resolvePromise or
   * subscribe to onResolved.
   */
  function resolve(req) {
    const cached = cacheGet(req.title, req.isMovie, req.season, req.episode);
    if (cached) {
      log(`cache HIT for '${req.title}' via ${cached.providerName}`);
      return { pending: false, stream: { ...cached, fromCache: true } };
    }
    const promise = startResolveIfNeeded(req, true);
    return { pending: true, resolvePromise: promise };
  }

  /**
   * Resolve and wait for the result (with a timeout). Use this when the caller
   * wants the actual stream, not just the promise — e.g. the Player's
   * "ask the engine first, with a short budget" step.
   */
  async function awaitResolve(req, timeoutMs = 12000) {
    const cached = cacheGet(req.title, req.isMovie, req.season, req.episode);
    if (cached) return { ...cached, fromCache: true };
    const promise = startResolveIfNeeded(req, true);
    return Promise.race([
      promise,
      new Promise(resolve => setTimeout(() => resolve(null), timeoutMs)),
    ]);
  }

  /**
   * Pre-resolve a title in the background — the "Kodi addon working ahead"
   * behaviour. Browse/home screens call this for the next likely title.
   * Fire-and-forget; never throws.
   */
  function preResolve(req) {
    if (!running) return;
    if (cacheGet(req.title, req.isMovie, req.season, req.episode)) return;
    startResolveIfNeeded(req, true);
  }

  /** Pre-resolve several titles at once (e.g. the visible row). Best-effort,
   *  bounded concurrency. Used by Home/Browse to warm the engine. */
  function preResolveAll(requests) {
    if (!running || !requests.length) return;
    const distinct = [];
    const seen = new Set();
    for (const r of requests) {
      const k = cacheKey(r.title, r.isMovie, r.season, r.episode);
      if (!seen.has(k)) { seen.add(k); distinct.push(r); }
    }
    // chunked fan-out to avoid hammering LookMovie
    let i = 0;
    function next() {
      const batch = distinct.slice(i, i + MAX_CONCURRENT_PRE);
      i += MAX_CONCURRENT_PRE;
      if (!batch.length) return;
      Promise.all(batch.map(async (req) => {
        if (!cacheGet(req.title, req.isMovie, req.season, req.episode)) {
          await startResolveIfNeeded(req, true);
        }
      })).then(next);
    }
    next();
  }

  /** Drop a cached entry — e.g. the player reported the URL 404'd. */
  function invalidate(req) {
    cacheInvalidate(req.title, req.isMovie, req.season, req.episode);
  }

  /** Subscribe to background-resolved streams. Returns an unsubscribe fn. */
  function onResolved(cb) {
    resolvedListeners.add(cb);
    return () => resolvedListeners.delete(cb);
  }

  // ── internals ──

  /**
   * Returns the in-flight promise for req, starting one if none exists. The
   * promise runs the addon flow (LookMovie), stores the result in the cache on
   * success, and (optionally) emits to listeners.
   */
  function startResolveIfNeeded(req, emitOnComplete) {
    const k = cacheKey(req.title, req.isMovie, req.season, req.episode);
    const existing = inFlight.get(k);
    if (existing) return existing;

    const promise = (async () => {
      try {
        log(`background resolve: '${req.title}' S${req.season}E${req.episode} (movie=${req.isMovie})`);
        let lastErr = null;
        for (const addon of addons) {
          const r = await addon.resolve(req);
          if (r.kind === "stream") {
            cachePut(req.title, req.isMovie, req.season, req.episode,
              r.url, r.headers, r.providerName);
            log(`✅ resolved '${req.title}' via ${addon.id}: ${r.url}`);
            const resolved = { url: r.url, headers: r.headers, providerName: r.providerName, fromCache: false };
            if (emitOnComplete) {
              resolvedListeners.forEach(cb => { try { cb(resolved); } catch (e) {} });
            }
            return resolved;
          } else {
            lastErr = r.message;
            log(`addon ${addon.id} miss for '${req.title}': ${r.message}`);
          }
        }
        if (lastErr) log(`all addons missed for '${req.title}': ${lastErr}`);
        return null;
      } catch (e) {
        log(`resolve failed for '${req.title}': ${e.message}`);
        return null;
      } finally {
        inFlight.delete(k);
      }
    })();

    inFlight.set(k, promise);
    return promise;
  }

  return {
    start, stop,
    resolve, awaitResolve, preResolve, preResolveAll,
    invalidate, cacheSize, onResolved,
    isRunning: () => running,
  };
})();
