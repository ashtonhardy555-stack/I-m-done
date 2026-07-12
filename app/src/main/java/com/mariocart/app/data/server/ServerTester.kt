package com.mariocart.app.data.server

import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ServerTester
 *
 * Probes a list of servers for a *specific* piece of content (movie or TV
 * episode) and returns them sorted by who responds the fastest.
 *
 * This is called once per video open so the player immediately loads the best
 * server for that exact title rather than relying solely on the generic
 * health-check ordering from [ServerManager].  Probing in parallel (instead of
 * trying servers one-by-one) is what removes the "laggy / slow start" feeling.
 */
object ServerTester {

    private const val TAG = "ServerTester"
    private const val PROBE_TIMEOUT_MS = 6_000L   // per-server probe timeout
    private const val MAX_PARALLEL = 10           // how many servers to probe at once

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * Probes all [servers] for the given content and returns a reordered list
     * where responsive servers come first, sorted by response time.  Dead
     * servers are moved to the end (never removed — the user can still select
     * them manually).
     *
     * @param servers  Full server list from [ServerManager.getOrderedServers]
     * @param tmdbId   TMDB ID of the movie / TV show
     * @param type     "movie" or "tv"
     * @param season   Season number (TV only)
     * @param episode  Episode number (TV only)
     */
    suspend fun rankForContent(
        servers: List<StreamingServer>,
        tmdbId: Int,
        type: String,
        season: Int = 1,
        episode: Int = 1
    ): List<StreamingServer> = withContext(Dispatchers.IO) {

        val results = mutableListOf<Pair<StreamingServer, Long?>>() // server -> response ms (null = failed)

        // Probe in batches to avoid opening hundreds of connections at once.
        servers.chunked(MAX_PARALLEL).forEach { batch ->
            val batchResults = coroutineScope {
                batch.map { server ->
                    async {
                        val url = if (type == "movie") server.movieUrl(tmdbId)
                                  else server.tvUrl(tmdbId, season, episode)
                        server to probeUrl(url)
                    }
                }.map { it.await() }
            }
            results.addAll(batchResults)
        }

        val (working, failed) = results.partition { (_, ms) -> ms != null }
        val sorted = working.sortedBy { (_, ms) -> ms!! }.map { (s, _) -> s }
        val failedServers = failed.map { (s, _) -> s }

        Log.d(TAG, "Probe complete: ${sorted.size} working, ${failedServers.size} failed")
        sorted + failedServers
    }

    /**
     * Issues a lightweight HEAD request to [url] and returns how long it took
     * in milliseconds, or null if the server didn't respond or returned an
     * error.  A fast HEAD probe is much cheaper than a full GET and keeps the
     * "find best server" step snappy.
     */
    private suspend fun probeUrl(url: String): Long? = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        try {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url(url)
                .head()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            val elapsed = System.currentTimeMillis() - start
            // Accept 200-499: even a 403/404 means the server is alive.
            if (code in 200..499) elapsed else null
        } catch (e: Exception) {
            Log.d(TAG, "Probe failed for $url: ${e.message}")
            null
        }
    }
}
