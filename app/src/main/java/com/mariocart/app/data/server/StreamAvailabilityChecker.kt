package com.mariocart.app.data.server

import android.content.Context
import android.util.Log
import com.mariocart.app.BuildConfig
import com.mariocart.app.data.api.ApiClient
import com.mariocart.app.data.cache.StreamAvailabilityCache
import com.mariocart.app.data.model.TmdbItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * StreamAvailabilityChecker — a *fast, lightweight* probe that decides whether
 * a piece of content has at least one working stream, so the browse/discover
 * grid can hide titles that have no playable source.
 *
 * ## Two-tier probing (v2)
 *
 *  1. **Persistent good-cache first.** Before any network probe we consult
 *     [StreamAvailabilityCache.knownGoodProvider]. If a title has a
 *     *fresh* (≤14-day) good record it means the user (or this checker)
 *     successfully played it before — show it immediately, no probe needed.
 *     This is the fastest, most reliable signal: "it played before, it'll
 *     play again".
 *
 *  2. **NoTorrent direct-stream probe (primary).** NoTorrent
 *     (`addon-osvh.onrender.com`) is the fastest, most reliable 2026 source —
 *     it returns 302→206 `video/mp4` in ~280-400 ms. We resolve TMDB→IMDb
 *     (one ~150 ms round-trip) and query the addon. If it returns a
 *     **non-empty `streams` array**, the title is available — this is a
 *     *positive* "actually playable" signal, not just "the page loaded".
 *     This catches titles the embed-probe misses and avoids the false
 *     positives the embed-probe produces (a 200 page that never plays).
 *
 *  3. **Embed-page probe (fallback).** For titles NoTorrent doesn't cover
 *     we fall back to the original lightweight embed-page probe against the
 *     cleanest (tier 1-2) providers. A 200 HTML page that isn't a Cloudflare
 *     challenge is treated as "available".
 *
 * ## Never hide on a network error
 *
 * Every probe can fail for transient reasons (a CDN hiccup, rate-limit, the
 * user's connection dropped). Hiding content because a probe *errored* would
 * make popular titles vanish at random. So the rule is:
 *
 *   - A title is **hidden** only when we have **positive evidence** it has no
 *     source: NoTorrent returned `{"streams": []}` (or 404) **and** every
 *     embed provider returned a non-200 / challenge / 404.
 *   - If *every* probe **errored** (threw an exception, timed out), the title
 *     is **shown** — we don't have evidence it's unplayable, only that we
 *     couldn't check right now.
 *
 * This keeps the grid populated on flaky connections while still hiding the
 * genuinely sourceless titles.
 *
 * ## Caching
 *
 * In-memory per-app-lifetime cache (`ConcurrentHashMap`) keyed by
 * `tmdbId|contentType`, so paging through the grid doesn't re-probe the same
 * title. Good results are also promoted into the persistent
 * [StreamAvailabilityCache] so they survive app restarts.
 *
 * ## Concurrency
 *
 * [filterAvailable] probes titles in parallel (bounded by the number of
 * titles) so a full grid resolves in roughly one round-trip, not N.
 */
object StreamAvailabilityChecker {

    private const val TAG = "StreamAvail"
    private const val PROBE_TIMEOUT_S = 6L

    /** NoTorrent Stremio addon base — the fastest, most-reliable 2026 source. */
    private const val NOTORRENT_BASE = "https://addon-osvh.onrender.com"

    /** Max embed providers to probe per title (keeps it fast). */
    private const val MAX_PROVIDERS_PER_TITLE = 3

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Cached results: `tmdbId|contentType` -> TriState.
     *
     *  - `true`  → confirmed available (show)
     *  - `false` → confirmed no source (hide)
     *  - `null`  → probed but inconclusive (all probes errored) → show
     *
     * Persists for the app lifetime (cleared only by [clearCache]).
     */
    private val cache = ConcurrentHashMap<String, Boolean>()

    private fun cacheKey(item: TmdbItem): String = "${item.id}|${item.contentType}"

    /**
     * Returns true if the item should be **shown** (i.e. it is, or is likely,
     * playable). Returns false only when we have positive evidence it has no
     * playable source.
     *
     * Uses the cached result if available; otherwise probes.
     */
    suspend fun isAvailable(context: Context, item: TmdbItem): Boolean {
        val key = cacheKey(item)
        cache[key]?.let { return it }

        val result = probeItem(context, item)
        cache[key] = result
        return result
    }

    /**
     * Filters a list of items to only those that should be shown, probing in
     * parallel. Items are returned in their original order.
     *
     * This is what every content grid (Home, Movies, TV, Search, Browse) calls
     * before rendering, so titles with no playable source never appear.
     */
    suspend fun filterAvailable(
        context: Context,
        items: List<TmdbItem>
    ): List<TmdbItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        ServerManager.initialize(context)
        coroutineScope {
            val results = items.map { item ->
                async { item to isAvailable(context, item) }
            }.map { it.await() }
            results.filter { it.second }.map { it.first }
        }
    }

    // ------------------------------------------------------------------ //
    //  Probing                                                           //
    // ------------------------------------------------------------------ //

    private suspend fun probeItem(context: Context, item: TmdbItem): Boolean {
        ServerManager.initialize(context)

        // 1) Persistent good-cache: played before → show immediately.
        //    season/episode default to 0 (movies) / 1,1 (tv) for the
        //    availability check — we only care "does ANY source exist".
        val season = if (item.isMovie) 0 else 1
        val episode = if (item.isMovie) 0 else 1
        val knownGood = StreamAvailabilityCache.knownGoodProvider(
            item.id, item.contentType, season, episode
        )
        if (knownGood != null) {
            Log.d(TAG, "✓ ${item.displayTitle} known-good ($knownGood) — show")
            return true
        }

        // 2) NoTorrent direct-stream probe (primary positive signal).
        val nt = probeNoTorrent(item)
        if (nt == true) {
            Log.d(TAG, "✓ ${item.displayTitle} NoTorrent has streams — show")
            // Promote to the persistent cache so future screens skip probing.
            StreamAvailabilityCache.recordSuccess(
                item.id, item.contentType, season, episode, "NoTorrent"
            )
            return true
        }

        // 3) Embed-page probe (fallback for titles NoTorrent doesn't cover).
        val embed = probeEmbeds(context, item)
        if (embed == true) {
            Log.d(TAG, "✓ ${item.displayTitle} embed page responded — show")
            return true
        }

        // 4) Decision: hide ONLY when both probes returned a definitive
        //    "no source" (false). If either was inconclusive (null — all
        //    probes errored), show the title — we lack positive evidence
        //    it's unplayable.
        if (nt == false && embed == false) {
            Log.d(TAG, "✗ ${item.displayTitle} (${item.id}) no source anywhere — hide")
            return false
        }
        // At least one probe was inconclusive → don't hide on a network glitch.
        Log.d(TAG, "? ${item.displayTitle} probes inconclusive — show (safe default)")
        return true
    }

    // ------------------------------------------------------------------ //
    //  NoTorrent direct-stream probe                                     //
    // ------------------------------------------------------------------ //

    /**
     * Probes the NoTorrent Stremio addon for [item].
     *
     * @return `true`  if the addon returned a non-empty `streams` array
     *         (the title is playable);
     *         `false` if the addon returned `{"streams": []}` or a 404
     *         (definitive "no source");
     *         `null` if the probe errored / timed out (inconclusive).
     */
    private suspend fun probeNoTorrent(item: TmdbItem): Boolean? =
        withContext(Dispatchers.IO) {
            val isTv = !item.isMovie
            // Resolve TMDB → IMDb (the addon keys off IMDb ids).
            val imdbId = try {
                resolveImdbId(item.id, isTv)
            } catch (e: Exception) {
                Log.w(TAG, "NoTorrent probe: TMDB→IMDb failed for ${item.id}: ${e.message}")
                return@withContext null  // inconclusive
            }
            if (imdbId.isNullOrBlank()) {
                // No IMDb id → can't query NoTorrent. Not a "no source" verdict;
                // the embed probe still gets a chance.
                return@withContext null
            }

            val season = if (isTv) 1 else 0
            val episode = if (isTv) 1 else 0
            val addonUrl = if (isTv) {
                "$NOTORRENT_BASE/stream/series/$imdbId:$season:$episode.json"
            } else {
                "$NOTORRENT_BASE/stream/movie/$imdbId.json"
            }

            try {
                val request = Request.Builder()
                    .url(addonUrl)
                    .header("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 404) return@withContext false  // definitive: no source
                    if (!resp.isSuccessful) return@withContext null  // 5xx etc → inconclusive
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val streams = json.optJSONArray("streams")
                    val count = streams?.length() ?: 0
                    // Non-empty streams array → playable. Empty → no source.
                    count > 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "NoTorrent probe errored for ${item.id}: ${e.message}")
                null  // inconclusive
            }
        }

    /** Resolve a TMDB id → IMDb id via TMDB's external_ids append. */
    private suspend fun resolveImdbId(tmdbId: Int, isTv: Boolean): String? {
        val key = BuildConfig.TMDB_API_KEY
        val resp = if (isTv) {
            ApiClient.tmdbApi.getTvExternalIds(tmdbId, key)
        } else {
            ApiClient.tmdbApi.getMovieExternalIds(tmdbId, key)
        }
        return resp.imdbId
    }

    // ------------------------------------------------------------------ //
    //  Embed-page probe (fallback)                                       //
    // ------------------------------------------------------------------ //

    /**
     * Probes the cleanest (tier 1-2) embed providers for [item].
     *
     * @return `true`  if at least one embed page responded 200 + non-challenge;
     *         `false` if every probed provider returned non-200 / challenge;
     *         `null` if every probe **errored** (inconclusive).
     */
    private suspend fun probeEmbeds(context: Context, item: TmdbItem): Boolean? =
        withContext(Dispatchers.IO) {
            // Probe only the cleanest (tier 1-2) providers — tier 3 are
            // Cloudflare-challenged and will 403 on plain HTTP, giving false
            // negatives. They're still tried at full-extraction time.
            val providers = ServerManager.allServers()
                .filter { it.tier <= 2 }
                .sortedBy { it.tier }
                .take(MAX_PROVIDERS_PER_TITLE)

            if (providers.isEmpty()) {
                // No clean providers configured — don't hide everything.
                return@withContext null
            }

            var anyError = false
            for (p in providers) {
                val url = p.urlFor(item.contentType, item.id, 1, 1)
                when (probeUrl(url)) {
                    true -> return@withContext true
                    false -> { /* try next provider */ }
                    null -> { anyError = true }
                }
            }
            // If at least one probe errored (vs cleanly returning false),
            // treat as inconclusive so we don't hide on a network glitch.
            if (anyError) null else false
        }

    /**
     * Returns `true` if [url] responds with HTTP 200 and HTML that is not a
     * Cloudflare challenge page; `false` if it responds with a definitive
     * non-200 / challenge (no source); `null` if the request errored
     * (inconclusive — network issue).
     */
    private fun probeUrl(url: String): Boolean? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ct = resp.header("Content-Type") ?: ""
                if (!ct.contains("text/html") && !ct.contains("application/xhtml")) {
                    return false
                }
                // Read a chunk of the body to detect Cloudflare challenge pages.
                val source = resp.body?.source() ?: return false
                source.request(2048)
                val preview = source.buffer.snapshot().utf8().lowercase()
                // Cloudflare "just a moment" / challenge pages are not real
                // embed pages — treat as unavailable for probing purposes.
                if (preview.contains("just a moment") ||
                    preview.contains("cf-challenge") ||
                    preview.contains("attention required") ||
                    preview.contains("enable javascript and cookies")
                ) {
                    return false
                }
                true
            }
        } catch (e: Exception) {
            null  // inconclusive — network error
        }
    }

    /** Clears the availability cache (e.g. when the server list is refreshed). */
    fun clearCache() {
        cache.clear()
    }
}
