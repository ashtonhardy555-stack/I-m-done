/**
 * Piratesfilm Cove Web — Frontend Application
 *
 * Features:
 *   - Browse trending/popular movies & TV shows (via TMDB API proxy)
 *   - Search for content
 *   - View detailed info (cast, overview, season/episode picker for TV)
 *   - Play content via the LookMovie engine (server-side extraction)
 *   - All streams are proxied through the server with VPN + ad blocking
 *   - Uses hls.js for .m3u8 playback
 */

// ──────────────────────────────────────────────────────────────────────────
//  State
// ──────────────────────────────────────────────────────────────────────────

const API_BASE = ""; // same origin
const IMG_BASE = "https://image.tmdb.org/t/p";

let currentHero = null;
let currentModalItem = null;
let currentSeason = 1;
let currentEpisode = 1;
let hls = null;
let searchTimer = null;

// ──────────────────────────────────────────────────────────────────────────
//  Initialization
// ──────────────────────────────────────────────────────────────────────────

document.addEventListener("DOMContentLoaded", () => {
    loadHome();
    setupNavbarScroll();
    checkVPNStatus();
});

function setupNavbarScroll() {
    window.addEventListener("scroll", () => {
        const navbar = document.getElementById("navbar");
        if (window.scrollY > 50) {
            navbar.classList.add("scrolled");
        } else {
            navbar.classList.remove("scrolled");
        }
    });
}

async function checkVPNStatus() {
    try {
        const resp = await fetch(`${API_BASE}/api/health`);
        const data = await resp.json();
        const badge = document.getElementById("vpnBadge");
        if (data.status === "ok") {
            const vpnText = data.vpn === "enabled" ? "VPN Protected" : "Server-Side";
            badge.querySelector("span").textContent =
                `${vpnText} · Ad-Free · LookMovie Engine`;
        }
    } catch (e) {
        // Badge stays as-is
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Navigation
// ──────────────────────────────────────────────────────────────────────────

function navigateTo(page) {
    // Update nav links
    document.querySelectorAll(".nav-link").forEach(link => {
        link.classList.toggle("active", link.dataset.page === page);
    });

    // Show the right page
    document.querySelectorAll(".page").forEach(p => p.style.display = "none");
    const pageEl = document.getElementById(`page-${page}`);
    if (pageEl) pageEl.style.display = "block";

    if (page === "home") loadHome();
    else if (page === "movies") loadMovies();
    else if (page === "tv") loadTV();

    window.scrollTo(0, 0);
}

// ──────────────────────────────────────────────────────────────────────────
//  Data Loading
// ──────────────────────────────────────────────────────────────────────────

async function loadHome() {
    // Load hero (trending movie)
    await loadHero();

    // Load rows in parallel
    loadTrendingMovies();
    loadTrendingTV();
    loadPopularMovies();
    loadPopularTV();
}

async function loadHero() {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/trending?media_type=movie&time_window=week`);
        const data = await resp.json();
        if (data.results && data.results.length > 0) {
            currentHero = data.results[0];
            renderHero(currentHero);
        }
    } catch (e) {
        console.error("Failed to load hero:", e);
    }
}

function renderHero(item) {
    document.getElementById("heroBg").style.backgroundImage =
        `url("${IMG_BASE}/original${item.backdrop_path || item.poster_path}")`;
    document.getElementById("heroTitle").textContent = item.title || item.name;
    document.getElementById("heroOverview").textContent = item.overview || "";

    const meta = document.getElementById("heroMeta");
    const year = (item.release_date || item.first_air_date || "").slice(0, 4);
    const rating = item.vote_average ? Math.round(item.vote_average * 10) / 10 : null;
    meta.innerHTML = "";
    if (year) meta.innerHTML += `<span>${year}</span>`;
    if (rating) meta.innerHTML += `<span class="badge">${rating}</span>`;
    if (item.original_language) meta.innerHTML += `<span>${item.original_language.toUpperCase()}</span>`;
}

async function loadTrendingMovies() {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/trending?media_type=movie&time_window=week`);
        const data = await resp.json();
        renderRow("row-trending-movies", data.results, "movie");
    } catch (e) {
        console.error("Failed to load trending movies:", e);
    }
}

async function loadTrendingTV() {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/trending?media_type=tv&time_window=week`);
        const data = await resp.json();
        renderRow("row-trending-tv", data.results, "tv");
    } catch (e) {
        console.error("Failed to load trending TV:", e);
    }
}

async function loadPopularMovies() {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/popular?media_type=movie`);
        const data = await resp.json();
        renderRow("row-popular-movies", data.results, "movie");
    } catch (e) {
        console.error("Failed to load popular movies:", e);
    }
}

async function loadPopularTV() {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/popular?media_type=tv`);
        const data = await resp.json();
        renderRow("row-popular-tv", data.results, "tv");
    } catch (e) {
        console.error("Failed to load popular TV:", e);
    }
}

async function loadMovies() {
    try {
        const [popular, topRated] = await Promise.all([
            fetch(`${API_BASE}/api/tmdb/popular?media_type=movie`).then(r => r.json()),
            fetch(`${API_BASE}/api/tmdb/tmdb_top_rated_placeholder`).then(r => r.json()).catch(() => null),
        ]);
        renderRow("movies-grid", popular.results, "movie");
        // Fallback to trending if top rated endpoint doesn't exist
        if (topRated && topRated.results) {
            renderRow("movies-top", topRated.results, "movie");
        } else {
            const trending = await fetch(`${API_BASE}/api/tmdb/trending?media_type=movie&time_window=day`).then(r => r.json());
            renderRow("movies-top", trending.results, "movie");
        }
    } catch (e) {
        console.error("Failed to load movies:", e);
    }
}

async function loadTV() {
    try {
        const [popular, topRated] = await Promise.all([
            fetch(`${API_BASE}/api/tmdb/popular?media_type=tv`).then(r => r.json()),
            fetch(`${API_BASE}/api/tmdb/tmdb_top_rated_placeholder`).then(r => r.json()).catch(() => null),
        ]);
        renderRow("tv-grid", popular.results, "tv");
        if (topRated && topRated.results) {
            renderRow("tv-top", topRated.results, "tv");
        } else {
            const trending = await fetch(`${API_BASE}/api/tmdb/trending?media_type=tv&time_window=day`).then(r => r.json());
            renderRow("tv-top", trending.results, "tv");
        }
    } catch (e) {
        console.error("Failed to load TV:", e);
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Rendering
// ──────────────────────────────────────────────────────────────────────────

function renderRow(elementId, items, mediaType) {
    const container = document.getElementById(elementId);
    if (!container) return;
    if (!items || items.length === 0) {
        container.innerHTML = '<div class="error-container"><div class="error-message">No results found.</div></div>';
        return;
    }
    container.innerHTML = items
        .filter(item => item.poster_path)
        .map(item => createCard(item, mediaType))
        .join("");
}

function createCard(item, mediaType) {
    const title = item.title || item.name;
    const year = (item.release_date || item.first_air_date || "").slice(0, 4);
    const poster = item.poster_path ? `${IMG_BASE}/w300${item.poster_path}` : "";
    const rating = item.vote_average ? Math.round(item.vote_average * 10) / 10 : null;

    return `
        <div class="content-card" onclick='showDetails(${JSON.stringify(item).replace(/'/g, "&#39;")}, "${mediaType}")'>
            <div class="card-image" style="background-image: url('${poster}')"></div>
            <div class="card-info">
                <div class="card-title">${escapeHtml(title)}</div>
                <div class="card-meta">${year}${mediaType === "tv" ? " · TV" : ""}</div>
                ${rating ? `<div class="card-rating">★ ${rating}</div>` : ""}
            </div>
        </div>
    `;
}

function escapeHtml(text) {
    const div = document.createElement("div");
    div.textContent = text;
    return div.innerHTML;
}

// ──────────────────────────────────────────────────────────────────────────
//  Search
// ──────────────────────────────────────────────────────────────────────────

function handleSearch(event) {
    clearTimeout(searchTimer);
    const query = event.target.value.trim();

    if (query.length < 2) {
        navigateTo("home");
        return;
    }

    // Switch to search page
    document.querySelectorAll(".page").forEach(p => p.style.display = "none");
    document.getElementById("page-search").style.display = "block";

    searchTimer = setTimeout(() => doSearch(query), 400);
}

async function doSearch(query) {
    const titleEl = document.getElementById("search-title");
    const gridEl = document.getElementById("search-grid");

    titleEl.textContent = `Search Results for "${query}"`;
    gridEl.innerHTML = '<div class="loading-container"><div class="spinner"></div></div>';

    try {
        // Search both movies and TV
        const [movies, tv] = await Promise.all([
            fetch(`${API_BASE}/api/tmdb/search?query=${encodeURIComponent(query)}&media_type=movie`).then(r => r.json()),
            fetch(`${API_BASE}/api/tmdb/search?query=${encodeURIComponent(query)}&media_type=tv`).then(r => r.json()),
        ]);

        const allResults = [
            ...(movies.results || []).map(m => ({...m, _type: "movie"})),
            ...(tv.results || []).map(t => ({...t, _type: "tv"})),
        ];

        if (allResults.length === 0) {
            gridEl.innerHTML = '<div class="error-container"><div class="error-icon">🔍</div><div class="error-title">No Results</div><div class="error-message">Try a different search term.</div></div>';
            return;
        }

        gridEl.innerHTML = allResults
            .filter(item => item.poster_path)
            .map(item => createCard(item, item._type))
            .join("");

        // Store the media type on each card's onclick
        gridEl.querySelectorAll(".content-card").forEach((card, i) => {
            card.onclick = () => showDetails(allResults[i], allResults[i]._type);
        });

    } catch (e) {
        console.error("Search failed:", e);
        gridEl.innerHTML = '<div class="error-container"><div class="error-icon">⚠</div><div class="error-title">Search Failed</div><div class="error-message">Could not perform search. Please try again.</div></div>';
    }
}

// ──────────────────────────────────────────────────────────────────────────
//  Modal (Detail View)
// ──────────────────────────────────────────────────────────────────────────

async function showDetails(item, mediaType) {
    currentModalItem = item;
    currentMediaType = mediaType;

    const overlay = document.getElementById("modalOverlay");
    const title = item.title || item.name;
    const year = (item.release_date || item.first_air_date || "").slice(0, 4);
    const backdrop = item.backdrop_path || item.poster_path;
    const rating = item.vote_average ? Math.round(item.vote_average * 10) / 10 : null;

    document.getElementById("modalImage").style.backgroundImage =
        `url("${IMG_BASE}/w780${backdrop}")`;
    document.getElementById("modalTitle").textContent = title;

    const metaEl = document.getElementById("modalMeta");
    metaEl.innerHTML = "";
    if (year) metaEl.innerHTML += `<span>${year}</span>`;
    if (rating) metaEl.innerHTML += `<span>★ ${rating}</span>`;
    if (item.original_language) metaEl.innerHTML += `<span>${item.original_language.toUpperCase()}</span>`;
    if (item.runtime) metaEl.innerHTML += `<span>${item.runtime} min</span>`;
    if (item.number_of_seasons) metaEl.innerHTML += `<span>${item.number_of_seasons} season${item.number_of_seasons > 1 ? "s" : ""}</span>`;

    document.getElementById("modalOverview").textContent = item.overview || "No overview available.";

    // Show/hide season section for TV
    const seasonSection = document.getElementById("seasonSection");
    if (mediaType === "tv") {
        seasonSection.style.display = "block";
        await loadSeasons(item.id);
    } else {
        seasonSection.style.display = "none";
    }

    // Fetch full details for more info
    fetchFullDetails(item.id, mediaType);

    overlay.style.display = "flex";
}

async function fetchFullDetails(tmdbId, mediaType) {
    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/details?media_type=${mediaType}&tmdb_id=${tmdbId}`);
        const data = await resp.json();

        // Update overview if we got a better one
        if (data.overview && data.overview.length > (currentModalItem.overview || "").length) {
            document.getElementById("modalOverview").textContent = data.overview;
        }

        // Update runtime
        if (data.runtime) {
            const meta = document.getElementById("modalMeta");
            if (!meta.innerHTML.includes("min")) {
                meta.innerHTML += `<span>${data.runtime} min</span>`;
            }
        }

        currentModalItem = data;
        currentModalItem._type = mediaType;
    } catch (e) {
        console.error("Failed to fetch full details:", e);
    }
}

async function loadSeasons(tvId) {
    currentSeason = 1;
    currentEpisode = 1;

    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/season?tv_id=${tvId}&season_number=1`);
        const data = await resp.json();

        // Render season selector (if we know the number of seasons)
        const numSeasons = currentModalItem.number_of_seasons || 1;
        const seasonSelector = document.getElementById("seasonSelector");
        seasonSelector.innerHTML = "";
        for (let s = 1; s <= numSeasons; s++) {
            const btn = document.createElement("button");
            btn.className = `season-btn ${s === 1 ? "active" : ""}`;
            btn.textContent = `Season ${s}`;
            btn.onclick = () => selectSeason(tvId, s);
            seasonSelector.appendChild(btn);
        }

        renderEpisodes(data.episodes || []);
    } catch (e) {
        console.error("Failed to load seasons:", e);
        document.getElementById("episodeList").innerHTML =
            '<div class="error-container"><div class="error-message">Could not load episodes.</div></div>';
    }
}

async function selectSeason(tvId, seasonNum) {
    currentSeason = seasonNum;

    // Update active button
    document.querySelectorAll(".season-btn").forEach(btn => {
        btn.classList.remove("active");
    });
    event.target.classList.add("active");

    const episodeList = document.getElementById("episodeList");
    episodeList.innerHTML = '<div class="loading-container"><div class="spinner"></div></div>';

    try {
        const resp = await fetch(`${API_BASE}/api/tmdb/season?tv_id=${tvId}&season_number=${seasonNum}`);
        const data = await resp.json();
        renderEpisodes(data.episodes || []);
    } catch (e) {
        console.error("Failed to load season:", e);
    }
}

function renderEpisodes(episodes) {
    const list = document.getElementById("episodeList");
    if (episodes.length === 0) {
        list.innerHTML = '<div class="error-container"><div class="error-message">No episodes found for this season.</div></div>';
        return;
    }

    list.innerHTML = episodes.map(ep => `
        <div class="episode-item ${ep.episode_number === currentEpisode ? "active" : ""}"
             onclick="selectEpisode(${ep.episode_number}, '${escapeHtml(ep.name || "")}')">
            <div class="episode-number">E${String(ep.episode_number).padStart(2, "0")}</div>
            <div>
                <div class="episode-title">${escapeHtml(ep.name || `Episode ${ep.episode_number}`)}</div>
                ${ep.overview ? `<div class="episode-overview">${escapeHtml(ep.overview.slice(0, 100))}${ep.overview.length > 100 ? "..." : ""}</div>` : ""}
            </div>
        </div>
    `).join("");

    // Set click handlers
    list.querySelectorAll(".episode-item").forEach((item, i) => {
        item.onclick = () => {
            currentEpisode = episodes[i].episode_number;
            list.querySelectorAll(".episode-item").forEach(el => el.classList.remove("active"));
            item.classList.add("active");
        };
    });
}

function selectEpisode(epNum, epName) {
    currentEpisode = epNum;
}

function closeModal() {
    document.getElementById("modalOverlay").style.display = "none";
}

// ──────────────────────────────────────────────────────────────────────────
//  Playback — LookMovie Engine (server-side, VPN-protected, ad-free)
// ──────────────────────────────────────────────────────────────────────────

let currentMediaType = "movie";

async function playHero() {
    if (currentHero) {
        showDetails(currentHero, "movie");
    }
}

function showHeroInfo() {
    if (currentHero) {
        showDetails(currentHero, "movie");
    }
}

async function playFromModal() {
    if (!currentModalItem) return;

    const title = currentModalItem.title || currentModalItem.name;
    const year = (currentModalItem.release_date || currentModalItem.first_air_date || "").slice(0, 4);
    const mediaType = currentModalItem._type || currentMediaType;
    const season = currentSeason;
    const episode = currentEpisode;

    closeModal();
    openPlayer(title, year, mediaType, season, episode);
}

async function openPlayer(title, year, mediaType, season, episode) {
    // Show the player container with a loading state
    const container = document.getElementById("playerContainer");
    const video = document.getElementById("videoPlayer");
    const titleEl = document.getElementById("playerTitle");

    titleEl.textContent = `${title}${mediaType === "tv" ? ` S${season}E${episode}` : ""}`;
    container.style.display = "flex";

    // Show loading overlay
    showPlayerLoading(title);

    try {
        // Resolve the stream through our server (LookMovie engine)
        const resp = await fetch(`${API_BASE}/api/stream/resolve`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({
                title: title,
                year: year,
                content_type: mediaType === "tv" ? "tv" : "movie",
                season: season,
                episode: episode,
            }),
        });

        const data = await resp.json();

        if (!data.success) {
            showPlayerError(data.error || "No stream found for this title.");
            return;
        }

        // Play the proxied stream URL
        const proxyUrl = data.proxyUrl;
        playHLS(proxyUrl);

    } catch (e) {
        console.error("Stream resolution failed:", e);
        showPlayerError("Failed to resolve stream. Please try again.");
    }
}

function playHLS(url) {
    const video = document.getElementById("videoPlayer");

    // Clean up previous HLS instance
    if (hls) {
        hls.destroy();
        hls = null;
    }

    if (Hls.isSupported()) {
        hls = new Hls({
            // The proxy handles everything — no external requests.
            // All segment URLs are already rewritten to go through our server.
            enableWorker: true,
            lowLatencyMode: false,
        });

        hls.loadSource(url);
        hls.attachMedia(video);

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
            video.play();
            hidePlayerLoading();
        });

        hls.on(Hls.Events.ERROR, (event, data) => {
            console.error("HLS error:", data);
            if (data.fatal) {
                switch (data.type) {
                    case Hls.ErrorTypes.NETWORK_ERROR:
                        hls.startLoad();
                        break;
                    case Hls.ErrorTypes.MEDIA_ERROR:
                        hls.recoverMediaError();
                        break;
                    default:
                        showPlayerError("Playback error. The stream may have expired.");
                        break;
                }
            }
        });

    } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
        // Native HLS support (Safari, iOS)
        video.src = url;
        video.addEventListener("loadedmetadata", () => {
            video.play();
            hidePlayerLoading();
        });
    } else {
        showPlayerError("HLS playback is not supported in this browser.");
    }
}

function showPlayerLoading(title) {
    const video = document.getElementById("videoPlayer");
    // Add a loading overlay on top of the video
    let overlay = document.getElementById("playerLoadingOverlay");
    if (!overlay) {
        overlay = document.createElement("div");
        overlay.id = "playerLoadingOverlay";
        overlay.style.cssText = `
            position: absolute; top: 0; left: 0; right: 0; bottom: 0;
            display: flex; flex-direction: column;
            justify-content: center; align-items: center;
            background: #000; z-index: 5; color: white;
        `;
        overlay.innerHTML = `
            <div class="spinner" style="margin-bottom: 16px;"></div>
            <div style="font-size: 16px;">Resolving stream via LookMovie...</div>
            <div style="font-size: 12px; color: #b3b3b3; margin-top: 8px;">VPN-protected · Ad-free</div>
        `;
        document.getElementById("playerContainer").appendChild(overlay);
    }
    overlay.style.display = "flex";
}

function hidePlayerLoading() {
    const overlay = document.getElementById("playerLoadingOverlay");
    if (overlay) overlay.style.display = "none";
}

function showPlayerError(message) {
    let overlay = document.getElementById("playerLoadingOverlay");
    if (!overlay) {
        overlay = document.createElement("div");
        overlay.id = "playerLoadingOverlay";
        overlay.style.cssText = `
            position: absolute; top: 0; left: 0; right: 0; bottom: 0;
            display: flex; flex-direction: column;
            justify-content: center; align-items: center;
            background: #000; z-index: 5; color: white; padding: 32px; text-align: center;
        `;
        document.getElementById("playerContainer").appendChild(overlay);
    }
    overlay.style.display = "flex";
    overlay.innerHTML = `
        <div style="font-size: 48px; margin-bottom: 16px;">⚠</div>
        <div style="font-size: 20px; font-weight: bold; margin-bottom: 8px;">Playback Error</div>
        <div style="font-size: 14px; color: #b3b3b3; max-width: 400px;">${escapeHtml(message)}</div>
        <button onclick="closePlayer()" style="margin-top: 24px; background: #e50914; color: white; border: none; padding: 10px 24px; border-radius: 4px; cursor: pointer; font-size: 14px;">Go Back</button>
    `;
}

function closePlayer() {
    const container = document.getElementById("playerContainer");
    const video = document.getElementById("videoPlayer");

    if (hls) {
        hls.destroy();
        hls = null;
    }
    video.pause();
    video.removeAttribute("src");
    video.load();

    hidePlayerLoading();
    container.style.display = "none";
}

// ──────────────────────────────────────────────────────────────────────────
//  Auto-hide player header on mouse inactivity
// ──────────────────────────────────────────────────────────────────────────

let headerHideTimer = null;
document.addEventListener("mousemove", () => {
    if (document.getElementById("playerContainer").style.display !== "none") {
        const header = document.getElementById("playerHeader");
        header.classList.remove("hidden");
        clearTimeout(headerHideTimer);
        headerHideTimer = setTimeout(() => {
            header.classList.add("hidden");
        }, 3000);
    }
});
