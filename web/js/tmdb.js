/**
 * TMDB client — a JS port of the Android app's `TmdbApi.kt` + `ContentRepository.kt`.
 *
 * Uses the same TMDB api key as the app and the same endpoints (trending, now
 * playing, popular, top rated, discover, search, movie/tv detail, season
 * detail). All browse/search calls pass include_adult=false, matching the app's
 * guard that keeps adult/pornographic titles out of every feed.
 *
 * Image URLs use the same TMDB image CDN sizes the app uses:
 *   poster  → w185 (small, fast) / w342 for detail hero
 *   backdrop → w780 / w1280 for hero
 */
const TMDB = (() => {
  const API_KEY = "a15c24c2a5c00487b179f5d4b53b72b0";
  const BASE = "https://api.themoviedb.org/3";
  const IMG = "https://image.tmdb.org/t/p";

  function posterUrl(path, size = "w185") {
    return path ? `${IMG}/${size}${path}` : null;
  }
  function backdropUrl(path, size = "w780") {
    return path ? `${IMG}/${size}${path}` : null;
  }
  function stillUrl(path, size = "w300") {
    return path ? `${IMG}/${size}${path}` : null;
  }

  async function api(path, params = {}) {
    const url = new URL(`${BASE}/${path}`);
    url.searchParams.set("api_key", API_KEY);
    url.searchParams.set("language", "en-US");
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== "") url.searchParams.set(k, v);
    }
    const resp = await fetch(url.toString());
    if (!resp.ok) throw new Error(`TMDB ${resp.status}`);
    return resp.json();
  }

  // ── normalize a TMDB item (movies use title, tv uses name) ──
  function norm(item) {
    if (!item) return null;
    const isMovie = item.title != null || item.media_type === "movie";
    return {
      id: item.id,
      title: item.title || item.name || "Unknown",
      overview: item.overview,
      posterPath: item.poster_path,
      backdropPath: item.backdrop_path,
      voteAverage: item.vote_average || 0,
      releaseDate: item.release_date || item.first_air_date,
      originalLanguage: item.original_language,
      mediaType: isMovie ? "movie" : "tv",
      genreIds: item.genre_ids || [],
      posterUrl: posterUrl(item.poster_path),
      backdropUrl: backdropUrl(item.backdrop_path, "w1280"),
      get year() { return (this.releaseDate || "").slice(0, 4); },
      get ratingText() { return this.voteAverage > 0 ? this.voteAverage.toFixed(1) : ""; },
    };
  }

  // ── Browse feeds ──
  async function trending() {
    const r = await api("trending/all/week", { region: "US", page: 1 });
    return r.results.map(norm).filter(Boolean);
  }
  async function nowPlaying() {
    const r = await api("movie/now_playing", { region: "US", page: 1 });
    return r.results.map(norm).filter(Boolean);
  }
  async function popularMovies(page = 1) {
    const r = await api("movie/popular", { region: "US", page });
    return r.results.map(norm).filter(Boolean);
  }
  async function topRatedMovies(page = 1) {
    const r = await api("movie/top_rated", { region: "US", page });
    return r.results.map(norm).filter(Boolean);
  }
  async function popularTV(page = 1) {
    const r = await api("tv/popular", { page });
    return r.results.map(norm).filter(Boolean);
  }
  async function airingToday() {
    const r = await api("tv/airing_today", { page: 1 });
    return r.results.map(norm).filter(Boolean);
  }
  async function topRatedTV(page = 1) {
    const r = await api("tv/top_rated", { page });
    return r.results.map(norm).filter(Boolean);
  }
  async function discover(type, genreId, page = 1) {
    const r = await api(`discover/${type}`, {
      with_genres: genreId || undefined,
      sort_by: "popularity.desc",
      with_original_language: "en",
      include_adult: "false",
      page,
    });
    return r.results.map(norm).filter(Boolean);
  }

  // ── Search ──
  async function search(type, query, page = 1) {
    const r = await api(`search/${type}`, {
      query, include_adult: "false", page,
    });
    // multi-search returns mixed media_type; filter to movie/tv only
    return r.results
      .map(norm)
      .filter(it => it && (it.mediaType === "movie" || it.mediaType === "tv"));
  }

  // ── Detail ──
  async function movieDetail(id) {
    const d = await api(`movie/${id}`);
    return {
      ...norm(d),
      runtime: d.runtime,
      tagline: d.tagline,
      genres: (d.genres || []).map(g => ({ id: g.id, name: g.name })),
      status: d.status,
      genreIds: (d.genres || []).map(g => g.id),
    };
  }
  async function tvDetail(id) {
    const d = await api(`tv/${id}`);
    return {
      ...norm(d),
      episodeRunTime: d.episode_run_time,
      tagline: d.tagline,
      genres: (d.genres || []).map(g => ({ id: g.id, name: g.name })),
      status: d.status,
      numberOfSeasons: d.number_of_seasons,
      seasons: (d.seasons || []).map(s => ({
        seasonNumber: s.season_number,
        episodeCount: s.episode_count,
        name: s.name,
        posterPath: s.poster_path,
      })),
      genreIds: (d.genres || []).map(g => g.id),
    };
  }
  async function seasonDetail(tvId, seasonNumber) {
    const d = await api(`tv/${tvId}/season/${seasonNumber}`);
    return {
      seasonNumber: d.season_number,
      episodes: (d.episodes || []).map(e => ({
        episodeNumber: e.episode_number,
        name: e.name,
        overview: e.overview,
        stillPath: e.still_path,
        runtime: e.runtime,
      })),
    };
  }

  // ── Similar / More Like This ──
  async function similarMovies(genreIds, excludeId) {
    if (!genreIds || !genreIds.length) return [];
    const r = await api("discover/movie", {
      with_genres: genreIds.slice(0, 2).join(","),
      sort_by: "popularity.desc",
      with_original_language: "en",
      include_adult: "false",
      page: 1,
    });
    return r.results.map(norm).filter(it => it && it.id !== excludeId).slice(0, 20);
  }

  // ── Genres (for browse chips) ──
  let genreCache = { movie: null, tv: null };
  async function genres(type) {
    if (genreCache[type]) return genreCache[type];
    const r = await api(`genre/${type}/list`);
    genreCache[type] = r.genres;
    return r.genres;
  }

  return {
    posterUrl, backdropUrl, stillUrl,
    trending, nowPlaying, popularMovies, topRatedMovies,
    popularTV, airingToday, topRatedTV, discover,
    search, movieDetail, tvDetail, seasonDetail, similarMovies, genres,
  };
})();
