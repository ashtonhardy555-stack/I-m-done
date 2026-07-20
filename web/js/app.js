/**
 * app.js — the SPA controller for "I'm Done — Web Edition".
 *
 * Routes (hash-based):
 *   #/            Home (trending hero + rows)
 *   #/movies      Movies browse (popular / top rated / genre chips)
 *   #/tv          TV browse
 *   #/search      Search results
 *   #/movie/:id   Movie detail
 *   #/tv/:id      TV detail (season/episode picker)
 *   #/play/...    Player (LookMovie via KodiEngine)
 *
 * The player uses ONLY the KodiEngine → LookMovieExtractor path (the "Kodi
 * addon running in the background"). No other servers are wired in.
 */
(() => {

  const app = document.getElementById("app");
  let currentRoute = null;

  // ── Routing ──
  function router() {
    KodiEngine.start();
    // location.hash is like "#/search?q=batman" — split route from query first.
    const raw = location.hash.slice(1) || "/";
    const [routePart, queryPart] = raw.split("?");
    const parts = routePart.split("/").filter(Boolean); // e.g. ["movie","123"]
    const query = new URLSearchParams(queryPart || "");
    scroll(0, 0);
    if (parts.length === 0) return renderHome();
    if (parts[0] === "movies" && !parts[1]) return renderMovies(null);
    if (parts[0] === "movies" && parts[1]) return renderMovies(parts[1]);
    if (parts[0] === "tv" && !parts[1]) return renderTV(null);
    if (parts[0] === "tv" && parts[1] && /^\d+$/.test(parts[1])) return renderTvDetail(parts[1]);
    if (parts[0] === "search") return renderSearch(query.get("q") || "");
    if (parts[0] === "movie" && parts[1]) return renderMovieDetail(parts[1]);
    renderHome();
  }

  window.addEventListener("hashchange", router);

  // ── Helpers ──
  function el(tag, cls, html) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function loading(msg = "Loading…") {
    app.innerHTML = `<div class="loading"><div class="spinner"></div><div>${msg}</div></div>`;
  }

  function navBar(active) {
    const links = [
      ["#//", "Home", "home"],
      ["#/movies", "Movies", "movies"],
      ["#/tv", "TV Shows", "tv"],
    ];
    return `
      <nav class="topnav" id="topnav">
        <a class="brand" href="#/">I'M DONE</a>
        <div class="nav-links">
          ${links.map(([href, label, key]) =>
            `<a href="${href}" class="${active === key ? "active" : ""}">${label}</a>`
          ).join("")}
        </div>
        <div class="search-box">
          <span class="icon">⌕</span>
          <input id="nav-search" type="text" placeholder="Titles, people, genres" autocomplete="off" />
        </div>
      </nav>`;
  }

  function wireNavSearch() {
    const input = document.getElementById("nav-search");
    if (!input) return;
    let timer;
    input.addEventListener("input", (e) => {
      clearTimeout(timer);
      const q = e.target.value.trim();
      timer = setTimeout(() => {
        if (q) location.hash = `/search?q=${encodeURIComponent(q)}`;
      }, 450);
    });
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        const q = input.value.trim();
        if (q) location.hash = `/search?q=${encodeURIComponent(q)}`;
      }
    });
    // restore current query into the box on search page
    const m = location.hash.match(/q=([^&]+)/);
    if (m) input.value = decodeURIComponent(m[1]);
  }

  function wireScroll() {
    const nav = document.getElementById("topnav");
    if (!nav) return;
    const onScroll = () => nav.classList.toggle("scrolled", scrollY > 40);
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  function footer() {
    return `<footer class="footer">
      <div class="engine-note">Kodi Engine · LookMovie Extractor (headless, no WebView)</div>
      <div>Web edition of the I'm Done app. Streams resolved via the LookMovie addon flow
      (search → storage → security API → HLS) running in the background KodiEngine.
      Catalog data from TMDB.</div>
    </footer>`;
  }

  // ── Card builders ──
  function posterCard(item) {
    const wp = item.mediaType === "movie"
      ? WatchProgress.get(item.id, "movie")
      : WatchProgress.get(item.id, "tv", 1, 1);
    const prog = wp && wp.durationMs > 0 ? (wp.positionMs / wp.durationMs) * 100 : 0;
    const poster = item.posterUrl
      ? `<img class="poster" src="${item.posterUrl}" alt="${esc(item.title)}" loading="lazy" onerror="this.style.opacity=0.1">`
      : `<div class="poster" style="display:flex;align-items:center;justify-content:center;font-size:0.7rem;color:var(--muted);text-align:center;padding:0.5rem">${esc(item.title)}</div>`;
    const cw = wp ? `<div class="cw-badge">${item.mediaType === "tv" ? `S${wp.season}·E${wp.episode}` : "Resume"}</div>` : "";
    const bar = wp && prog > 0
      ? `<div class="progress-bar"><div class="fill" style="width:${prog}%"></div></div>`
      : "";
    const card = el("div", "card", `
      <div style="position:relative">
        ${cw}
        ${poster}
      </div>
      ${bar}
      <div class="card-title">${esc(item.title)}</div>
    `);
    card.addEventListener("click", () => {
      location.hash = `/${item.mediaType}/${item.id}`;
    });
    return card;
  }

  function row(title, items) {
    if (!items || !items.length) return "";
    const section = el("div", "row");
    section.appendChild(el("div", "row-title", title));
    const track = el("div", "row-track");
    items.forEach(it => track.appendChild(posterCard(it)));
    section.appendChild(track);
    return section;
  }

  function esc(s) {
    return (s || "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  // ── Home ──
  async function renderHome() {
    currentRoute = "home";
    app.innerHTML = navBar("home") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Loading home…</div></div>`;
    try {
      const [trending, nowPlay, popMov, topMov, popTv, airing, topTv, cw] = await Promise.all([
        TMDB.trending(), TMDB.nowPlaying(), TMDB.popularMovies(),
        TMDB.topRatedMovies(), TMDB.popularTV(), TMDB.airingToday(),
        TMDB.topRatedTV(), Promise.resolve(WatchProgress.active()),
      ]);

      // hero = top trending with a backdrop
      const hero = trending.find(t => t.backdropPath) || trending[0];

      page.innerHTML = `
        <section class="hero">
          <div class="hero-bg" style="background-image:url('${hero.backdropUrl || hero.posterUrl}')"></div>
          <div class="hero-grad"></div>
          <div class="hero-content">
            <h1 class="hero-title">${esc(hero.title)}</h1>
            <div class="hero-meta">
              ${hero.ratingText ? `<span class="rating">★ ${hero.ratingText}</span>` : ""}
              ${hero.year ? `<span>${hero.year}</span>` : ""}
              <span>${hero.mediaType === "tv" ? "TV Series" : "Movie"}</span>
            </div>
            <p class="hero-overview">${esc(hero.overview || "")}</p>
            <div class="hero-actions">
              <button class="btn btn-play" id="hero-play">▶ Play</button>
              <button class="btn btn-info" id="hero-info">ⓘ More Info</button>
            </div>
          </div>
        </section>
        <div id="rows"></div>
        ${footer()}
      `;

      document.getElementById("hero-play").addEventListener("click", () => playItem(hero, 1, 1));
      document.getElementById("hero-info").addEventListener("click", () => {
        location.hash = `/${hero.mediaType}/${hero.id}`;
      });

      const rows = document.getElementById("rows");
      if (cw.length) rows.appendChild(row("Continue Watching", cw.map(w => ({
        id: w.tmdbId, title: w.title, mediaType: w.contentType,
        posterUrl: TMDB.posterUrl(w.posterPath),
        backdropUrl: TMDB.backdropUrl(w.backdropPath, "w1280"),
        _wp: w,
      })).map(item => {
        // attach resume click that jumps straight to the right episode
        const card = posterCard(item);
        card.addEventListener("click", (e) => {
          e.stopPropagation();
          playItem({ id: item.id, title: item.title, mediaType: item.mediaType, year: w => w },
            item._wp.season, item._wp.episode, item._wp.positionMs);
        }, { capture: true });
        return card;
      })));

      rows.appendChild(row("Trending Now", trending));
      rows.appendChild(row("Now Playing in Theaters", nowPlay));
      rows.appendChild(row("Popular Movies", popMov));
      rows.appendChild(row("Popular on TV", popTv));
      rows.appendChild(row("Top Rated Movies", topMov));
      rows.appendChild(row("Airing Today", airing));
      rows.appendChild(row("Top Rated TV Shows", topTv));

      // pre-resolve the first few trending titles in the background
      // (the "Kodi addon working ahead" behaviour)
      KodiEngine.preResolveAll(trending.slice(0, 4).map(t => ({
        title: t.title, year: t.year, isMovie: t.mediaType === "movie",
        season: 1, episode: 1,
      })));
    } catch (e) {
      page.innerHTML = `<div class="empty">Failed to load home: ${esc(e.message)}<br><button class="btn btn-red" onclick="location.reload()">Retry</button></div>`;
    }
  }

  // ── Movies browse ──
  async function renderMovies(genreId) {
    currentRoute = "movies";
    app.innerHTML = navBar("movies") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Loading movies…</div></div>`;
    try {
      const genres = await TMDB.genres("movie");
      const active = genreId || (genres[0] && genres[0].id);
      const [items] = await Promise.all([
        TMDB.discover("movie", active),
      ]);

      page.innerHTML = `
        <h1 class="section-h">Movies</h1>
        <div class="chips" id="chips"></div>
        <div class="grid" id="grid"></div>
        ${footer()}
      `;
      const chips = document.getElementById("chips");
      genres.forEach(g => {
        const c = el("button", "chip" + (String(g.id) === String(active) ? " active" : ""), g.name);
        c.addEventListener("click", () => { location.hash = `/movies/${g.id}`; });
        chips.appendChild(c);
      });
      const grid = document.getElementById("grid");
      items.forEach(it => grid.appendChild(posterCard(it)));
    } catch (e) {
      document.getElementById("page").innerHTML = `<div class="empty">Failed to load movies: ${esc(e.message)}</div>`;
    }
  }

  // ── TV browse ──
  async function renderTV(genreId) {
    currentRoute = "tv";
    app.innerHTML = navBar("tv") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Loading TV shows…</div></div>`;
    try {
      const genres = await TMDB.genres("tv");
      const active = genreId || (genres[0] && genres[0].id);
      const items = await TMDB.discover("tv", active);

      page.innerHTML = `
        <h1 class="section-h">TV Shows</h1>
        <div class="chips" id="chips"></div>
        <div class="grid" id="grid"></div>
        ${footer()}
      `;
      const chips = document.getElementById("chips");
      genres.forEach(g => {
        const c = el("button", "chip" + (String(g.id) === String(active) ? " active" : ""), g.name);
        c.addEventListener("click", () => { location.hash = `/tv/${g.id}`; });
        chips.appendChild(c);
      });
      const grid = document.getElementById("grid");
      items.forEach(it => grid.appendChild(posterCard(it)));
    } catch (e) {
      document.getElementById("page").innerHTML = `<div class="empty">Failed to load TV shows: ${esc(e.message)}</div>`;
    }
  }

  // ── Search ──
  async function renderSearch(q) {
    currentRoute = "search";
    app.innerHTML = navBar("") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    if (!q) {
      page.innerHTML = `<h1 class="section-h">Search</h1><div class="empty">Type in the search box above to find movies and TV shows.</div>${footer()}`;
      return;
    }
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Searching for "${esc(q)}"…</div></div>`;
    try {
      const items = await TMDB.search("multi", q);
      page.innerHTML = `<h1 class="section-h">Results for "${esc(q)}"</h1>` +
        (items.length ? `<div class="grid" id="grid"></div>` : `<div class="empty">No results found.</div>`) +
        footer();
      if (items.length) {
        const grid = document.getElementById("grid");
        items.forEach(it => grid.appendChild(posterCard(it)));
      }
    } catch (e) {
      page.innerHTML = `<div class="empty">Search failed: ${esc(e.message)}</div>`;
    }
  }

  // ── Movie detail ──
  async function renderMovieDetail(id) {
    currentRoute = "movie";
    app.innerHTML = navBar("") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Loading details…</div></div>`;
    try {
      const d = await TMDB.movieDetail(id);
      // similarMovies needs genre ids — fetch after detail
      const sim = d.genreIds && d.genreIds.length ? await TMDB.similarMovies(d.genreIds, id) : [];
      renderMovieDetailContent(page, d, sim);
    } catch (e) {
      page.innerHTML = `<div class="empty">Failed to load: ${esc(e.message)}</div>`;
    }
  }

  function renderMovieDetailContent(page, d, similar) {
    const runtime = d.runtime ? `${d.runtime}m` : "";
    page.innerHTML = `
      <section class="detail">
        <div class="detail-hero">
          <div class="bg" style="background-image:url('${d.backdropUrl || d.posterUrl}')"></div>
          <div class="grad"></div>
          <div class="content">
            <h1 class="detail-title">${esc(d.title)}</h1>
            <div class="detail-meta">
              ${d.ratingText ? `<span class="rating">★ ${d.ratingText}</span>` : ""}
              ${d.year ? `<span>${d.year}</span>` : ""}
              ${runtime ? `<span>${runtime}</span>` : ""}
              <span class="badge">Movie</span>
            </div>
            ${d.tagline ? `<div class="detail-tagline">${esc(d.tagline)}</div>` : ""}
            <p class="detail-overview">${esc(d.overview || "")}</p>
            ${d.genres && d.genres.length ? `<div class="detail-genres">${d.genres.map(g => `<span class="g">${esc(g.name)}</span>`).join("")}</div>` : ""}
            <div class="hero-actions">
              <button class="btn btn-play" id="play-btn">▶ Play</button>
            </div>
          </div>
        </div>
        <div class="detail-body" id="similar-host"></div>
        ${footer()}
      </section>
    `;
    document.getElementById("play-btn").addEventListener("click", () => playItem(d, 1, 1));
    const host = document.getElementById("similar-host");
    if (similar && similar.length) host.appendChild(row("More Like This", similar));

    // pre-resolve this movie in the background so Play is instant
    KodiEngine.preResolve({
      title: d.title, year: d.year, isMovie: true, season: 1, episode: 1,
    });
  }

  // ── TV detail (season/episode picker) ──
  async function renderTvDetail(id) {
    currentRoute = "tv";
    app.innerHTML = navBar("") + `<div class="page" id="page"></div>`;
    wireNavSearch(); wireScroll();
    const page = document.getElementById("page");
    page.innerHTML = `<div class="loading"><div class="spinner"></div><div>Loading details…</div></div>`;
    try {
      const d = await TMDB.tvDetail(id);
      const realSeasons = (d.seasons || []).filter(s => s.seasonNumber > 0);
      const firstSeason = realSeasons[0] ? realSeasons[0].seasonNumber : 1;

      page.innerHTML = `
        <section class="detail">
          <div class="detail-hero">
            <div class="bg" style="background-image:url('${d.backdropUrl || d.posterUrl}')"></div>
            <div class="grad"></div>
            <div class="content">
              <h1 class="detail-title">${esc(d.title)}</h1>
              <div class="detail-meta">
                ${d.ratingText ? `<span class="rating">★ ${d.ratingText}</span>` : ""}
                ${d.year ? `<span>${d.year}</span>` : ""}
                ${d.numberOfSeasons ? `<span>${d.numberOfSeasons} Season${d.numberOfSeasons > 1 ? "s" : ""}</span>` : ""}
                <span class="badge">TV Series</span>
              </div>
              ${d.tagline ? `<div class="detail-tagline">${esc(d.tagline)}</div>` : ""}
              <p class="detail-overview">${esc(d.overview || "")}</p>
              ${d.genres && d.genres.length ? `<div class="detail-genres">${d.genres.map(g => `<span class="g">${esc(g.name)}</span>`).join("")}</div>` : ""}
            </div>
          </div>
          <div class="detail-body">
            <div class="season-picker">
              <select class="season-select" id="season-select">
                ${realSeasons.map(s => `<option value="${s.seasonNumber}">${s.name || "Season " + s.seasonNumber}</option>`).join("")}
              </select>
              <div class="episode-list" id="ep-list"></div>
            </div>
          </div>
          <div class="detail-body" id="similar-host"></div>
          ${footer()}
        </section>
      `;

      const sel = document.getElementById("season-select");
      sel.value = String(firstSeason);
      async function loadSeason(sn) {
        const epList = document.getElementById("ep-list");
        epList.innerHTML = `<div class="loading" style="min-height:200px"><div class="spinner"></div></div>`;
        try {
          const season = await TMDB.seasonDetail(id, sn);
          epList.innerHTML = "";
          season.episodes.forEach(ep => {
            const wp = WatchProgress.get(Number(id), "tv", sn, ep.episodeNumber);
            const prog = wp && wp.durationMs > 0 ? (wp.positionMs / wp.durationMs) * 100 : 0;
            const still = ep.stillPath ? TMDB.stillUrl(ep.stillPath) : (d.backdropUrl || d.posterUrl);
            const epEl = el("div", "episode", `
              <div class="ep-still" style="background-image:url('${still}')">
                <div class="play-icon">▶</div>
              </div>
              <div class="ep-info">
                <div class="ep-num">Episode ${ep.episodeNumber}</div>
                <div class="ep-name">${esc(ep.name || "")}</div>
                <div class="ep-overview">${esc(ep.overview || "No description available.")}</div>
                ${prog > 0 ? `<div class="ep-prog"><div class="fill" style="width:${prog}%"></div></div>` : ""}
              </div>
            `);
            epEl.addEventListener("click", () => playItem(
              { id: Number(id), title: d.title, mediaType: "tv", year: d.year, backdropPath: d.backdropPath },
              sn, ep.episodeNumber
            ));
            epList.appendChild(epEl);
          });
          // pre-resolve the first episode of this season
          if (season.episodes[0]) {
            KodiEngine.preResolve({
              title: d.title, year: d.year, isMovie: false,
              season: sn, episode: season.episodes[0].episodeNumber,
            });
          }
        } catch (e) {
          epList.innerHTML = `<div class="empty">Failed to load episodes: ${esc(e.message)}</div>`;
        }
      }
      sel.addEventListener("change", () => loadSeason(Number(sel.value)));
      loadSeason(firstSeason);

      // similar
      if (d.genreIds && d.genreIds.length) {
        const sim = await TMDB.discover("tv", d.genreIds[0]);
        const filtered = sim.filter(it => it.id !== d.id).slice(0, 20);
        if (filtered.length) document.getElementById("similar-host").appendChild(row("More Like This", filtered));
      }
    } catch (e) {
      document.getElementById("page").innerHTML = `<div class="empty">Failed to load: ${esc(e.message)}</div>`;
    }
  }

  // ── Player ──
  // Uses ONLY KodiEngine → LookMovieExtractor. No other servers.
  async function playItem(item, season, episode, resumeMs) {
    season = season || 1; episode = episode || 1;
    const isMovie = (item.mediaType || "movie") === "movie";
    const req = {
      title: item.title, year: item.year, isMovie,
      season, episode,
    };

    app.innerHTML = `
      <div class="player-screen" id="pscreen">
        <div class="player-top">
          <button class="back-btn" id="pback">← Back</button>
          <div class="player-title">${esc(item.title)}${isMovie ? "" : ` · S${season}E${episode}`}</div>
          <div class="provider-badge">LookMovie · Kodi Engine</div>
        </div>
        <video id="video" controls playsinline></video>
        <div class="player-loading" id="pload">
          <div class="pl-bg" style="background-image:url('${item.backdropUrl || item.posterUrl || ""}')"></div>
          <div class="pl-content">
            <div class="pl-title">${esc(item.title)}</div>
            <div class="spinner"></div>
            <div class="pl-status" id="pstatus">Asking the Kodi engine…</div>
            <div class="pl-engine">KODI ENGINE · LOOKMOVIE EXTRACTOR</div>
          </div>
        </div>
      </div>
    `;

    const screen = document.getElementById("pscreen");
    const video = document.getElementById("video");
    const loadEl = document.getElementById("pload");
    const statusEl = document.getElementById("pstatus");
    document.getElementById("pback").addEventListener("click", () => history.back());

    // show/hide controls on mouse move
    let hideTimer;
    function showControls() {
      screen.classList.add("show-controls");
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => screen.classList.remove("show-controls"), 3000);
    }
    screen.addEventListener("mousemove", showControls);
    screen.addEventListener("click", showControls);

    function setStatus(msg) { if (statusEl) statusEl.textContent = msg; }

    let hls = null;
    let resolved = null;

    // Subscribe to background-resolved streams (in case a pre-resolve finishes)
    const unsub = KodiEngine.onResolved((stream) => {
      if (!resolved && streamKeyMatch(stream, req)) {
        resolved = stream;
        play(stream);
      }
    });

    // Ask the engine — cache hit returns instantly, otherwise it resolves.
    setStatus("Resolving via LookMovie (search → storage → security)…");
    const result = KodiEngine.resolve(req);
    if (!result.pending) {
      resolved = result.stream;
      play(result.stream);
    } else {
      setStatus("Kodi engine is working in the background…");
      const stream = await KodiEngine.awaitResolve(req, 20000);
      if (!resolved) {
        if (stream) { resolved = stream; play(stream); }
        else {
          setStatus("LookMovie couldn't resolve this title. It may be blocked by reCAPTCHA right now.");
          setTimeout(() => { if (!resolved) history.back(); }, 4000);
        }
      }
    }

    function streamKeyMatch(stream, r) {
      // basic guard — the engine only emits for LookMovie resolves
      return stream && stream.providerName === "LookMovie";
    }

    function play(stream) {
      unsub();
      if (!stream || !stream.url) {
        setStatus("No stream available.");
        return;
      }
      setStatus("Stream found! Loading player…");
      const url = stream.url;

      if (Hls.isSupported()) {
        hls = new Hls({
          enableWorker: true,
          lowLatencyMode: false,
          // Attach the t_hash cookie + referer on every playlist + segment
          // fetch — replaces the Kodi addon's local proxy (serverHTTP.py).
          xhrSetup: (xhr, reqUrl) => {
            try {
              if (stream.headers) {
                // Browsers forbid setting Cookie/User-Agent/Referer on
                // cross-origin XHR; the ones we CAN set go here. The m3u8
                // stream from LookMovie's CDN generally plays without the
                // cookie for the manifest/segments (the cookie gates the
                // security API, which already returned the URL).
                if (stream.headers["Origin"]) xhr.setRequestHeader("Origin", stream.headers["Origin"]);
              }
            } catch (e) {}
          },
        });
        hls.loadSource(url);
        hls.attachMedia(video);
        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          loadEl.style.display = "none";
          video.play().catch(() => {});
          if (resumeMs) try { video.currentTime = resumeMs / 1000; } catch (e) {}
        });
        hls.on(Hls.Events.ERROR, (event, data) => {
          if (data.fatal) {
            switch (data.type) {
              case Hls.ErrorTypes.NETWORK_ERROR:
                setStatus("Network error — retrying…");
                hls.startLoad();
                break;
              case Hls.ErrorTypes.MEDIA_ERROR:
                setStatus("Media error — recovering…");
                hls.recoverMediaError();
                break;
              default:
                setStatus("Playback error. This stream may be unavailable.");
                hls.destroy();
                break;
            }
          }
        });
      } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
        // Safari native HLS
        video.src = url;
        video.addEventListener("loadedmetadata", () => {
          loadEl.style.display = "none";
          video.play().catch(() => {});
          if (resumeMs) try { video.currentTime = resumeMs / 1000; } catch (e) {}
        });
      } else {
        setStatus("HLS not supported in this browser.");
      }

      // ── Watch progress tracking (port of WatchProgressStore) ──
      let lastFlush = 0;
      video.addEventListener("timeupdate", () => {
        const now = Date.now();
        if (now - lastFlush < 3000) return; // flush every 3s like the app
        lastFlush = now;
        if (video.duration > 0 && video.currentTime > 0) {
          WatchProgress.upsert({
            key: WatchProgress.makeKey(isMovie ? "movie" : "tv", item.id, season, episode),
            tmdbId: item.id,
            contentType: isMovie ? "movie" : "tv",
            positionMs: Math.floor(video.currentTime * 1000),
            durationMs: Math.floor(video.duration * 1000),
            season, episode,
            title: item.title,
            year: item.year,
            posterPath: item.posterPath,
            backdropPath: item.backdropPath,
            timestamp: now,
            completed: false,
          });
        }
      });
      video.addEventListener("ended", () => {
        WatchProgress.markCompleted(item.id, isMovie ? "movie" : "tv", season, episode);
      });
    }

    // cleanup on navigation away
    const cleanup = () => {
      unsub();
      if (hls) hls.destroy();
    };
    window.addEventListener("hashchange", cleanup, { once: true });
  }

  // ── Boot ──
  router();
})();
