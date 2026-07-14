package com.mariocart.app.data.server

import android.content.Context
import android.util.Log
import com.mariocart.app.data.model.TmdbItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * StreamAvailabilityChecker — a *fast, lightweight* probe that decides whether
 * a piece of content has at least one working stream, so the browse/discover
 * grid can hide titles that have no playable source.
 *
 * ## Why not just run the full WebView extraction for every title?
 * The full [EmbedExtractor] spins up an off-screen WebView per provider and
 * waits up to 18s — far too slow to run across a 20-item grid. Instead this
 * checker does a **plain HTTP HEAD/GET probe** against the *embed page* of the
 * cleanest providers. If the embed page loads (HTTP 200) and returns HTML
 * (not a Cloudflare challenge or 404), we treat the title as "available": the
 * provider acknowledges the TMDB id and will serve a player. This is the same
 * signal a browser sees before the JS player initialises.
 *
 * ## Accuracy trade-off
 * A 200 on the embed page is not a 100% guarantee the underlying video file is
 * playable, but empirically the clean providers (VidLink, 2Embed) return 404
 * for unknown TMDB ids and 200 for known ones, so the probe is a good proxy.
 * Titles that pass the probe but fail at full-extraction time still fall
 * through to the error screen in [PlayerActivity] with a Retry button, so the
 * user is never stuck.
 *
 * ## Caching
 * Results are cached per (tmdbId, contentType) for the app lifetime so paging
 * through the grid doesn't re-probe the same title. The cache is an
 * in-memory [ConcurrentHashMap].
 *
 * ## Concurrency
 * [filterAvailable] probes titles in parallel (bounded by the number of
 * titles) so a full grid resolves in roughly one round-trip, not N.
 */
object StreamAvailabilityChecker {

    private const val TAG = "StreamAvail"
    private const val PROBE_TIMEOUT_S = 6L

    /** Max providers to probe per title (keeps it fast). */
    private const val MAX_PROVIDERS_PER_TITLE = 3

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Cached results: tmdbId|contentType -> available?
     * Persists for the app lifetime (cleared only by [clearCache]).
     */
    private val cache = ConcurrentHashMap<String, Boolean>()

    private fun cacheKey(item: TmdbItem): String = "${item.id}|${item.contentType}"

    /**
     * Returns true if at least one clean provider's embed page for [item]
     * responds with HTTP 200 and non-challenge HTML.
     *
     * Uses the cached result if available.
     */
    suspend fun isAvailable(context: Context, item: TmdbItem): Boolean {
        val key = cacheKey(item)
        cache[key]?.let { return it }

        val result = probeItem(context, item)
        cache[key] = result
        return result
    }

    /**
     * Filters a list of items to only those with at least one working embed
     * page, probing in parallel. Items are returned in their original order.
     *
     * This is what the browse/discover grid calls before rendering.
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
    //  Probing
    // ------------------------------------------------------------------ //

    private suspend fun probeItem(context: Context, item: TmdbItem): Boolean {
        ServerManager.initialize(context)
        // Probe only the cleanest (tier 1-2) providers — tier 3 are
        // Cloudflare-challenged and will 403 on plain HTTP, giving false
        // negatives. They're still tried at full-extraction time.
        val providers = ServerManager.allServers()
            .filter { it.tier <= 2 }
            .sortedBy { it.tier }
            .take(MAX_PROVIDERS_PER_TITLE)

        if (providers.isEmpty()) {
            // No clean providers configured — don't hide everything.
            return true
        }

        for (p in providers) {
            val url = p.urlFor(item.contentType, item.id, 1, 1)
            if (probeUrl(url)) {
                Log.d(TAG, "✓ ${item.displayTitle} available via ${p.name}")
                return true
            }
        }
        Log.d(TAG, "✗ ${item.displayTitle} (${item.id}) no clean embed responded")
        return false
    }

    /**
     * Returns true if [url] responds with HTTP 200 and HTML that is not a
     * Cloudflare challenge page.
     */
    private fun probeUrl(url: String): Boolean {
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
            false
        }
    }

    /** Clears the availability cache (e.g. when the server list is refreshed). */
    fun clearCache() {
        cache.clear()
    }
}
