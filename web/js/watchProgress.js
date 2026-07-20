/**
 * WatchProgress — a JS port of the Android app's `WatchProgress.kt` +
 * `WatchProgressStore.kt`. Stores how far the user has watched a title in
 * localStorage, keyed by contentType + tmdbId (+ season + episode for TV),
 * so each episode of a TV show is tracked independently (Netflix-style
 * "Continue Watching").
 */
const WatchProgress = (() => {
  const KEY = "watch_progress_v1";
  const MAX = 30;

  function load() {
    try { return JSON.parse(localStorage.getItem(KEY) || "[]"); }
    catch (e) { return []; }
  }
  function save(list) {
    try { localStorage.setItem(KEY, JSON.stringify(list.slice(0, MAX * 2))); } catch (e) {}
  }

  function upsert(rec) {
    const list = load();
    const idx = list.findIndex(r => r.key === rec.key);
    if (idx >= 0) list[idx] = rec;
    else list.unshift(rec);
    // drop completed / keep most-recent active
    const filtered = list.filter(r => !r.completed && (r.positionMs / (r.durationMs || 1)) < 0.97);
    save(filtered);
  }

  function active() {
    return load()
      .filter(r => !r.completed && (r.durationMs > 0 ? (r.positionMs / r.durationMs) < 0.97 : true))
      .sort((a, b) => b.timestamp - a.timestamp)
      .slice(0, MAX);
  }

  function get(tmdbId, contentType, season = 1, episode = 1) {
    const key = contentType === "tv" ? `tv_${tmdbId}_S${season}_E${episode}` : `movie_${tmdbId}`;
    return load().find(r => r.key === key) || null;
  }

  function markCompleted(tmdbId, contentType, season = 1, episode = 1) {
    const list = load();
    const key = contentType === "tv" ? `tv_${tmdbId}_S${season}_E${episode}` : `movie_${tmdbId}`;
    const idx = list.findIndex(r => r.key === key);
    if (idx >= 0) { list[idx].completed = true; save(list); }
  }

  function clear() { try { localStorage.removeItem(KEY); } catch (e) {} }

  function makeKey(contentType, tmdbId, season, episode) {
    return contentType === "tv" ? `tv_${tmdbId}_S${season}_E${episode}` : `movie_${tmdbId}`;
  }

  return { upsert, active, get, markCompleted, clear, makeKey };
})();
