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
 * Probes a list of servers for a *specific* piece of content (movie or TV episode)
 * and returns them sorted by who responds the fastest.
 *
 * This is called once per video open so the player immediately loads the best
 * server for that exact title rather than relying solely on the generic health-check
 * ordering from [ServerManager].
 */
object ServerTester {

    private const val TAG = "ServerTester"
    private const val PROBE_TIMEOUT_MS = 8_000L   // per-server probe timeout
    private const val MAX_PARALLEL     = 10        // how many servers to probe at once

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * Probes all [servers] for the given content and returns a reordered list
     * where responsive servers come first, maintaining their relative order among
     * ties.  Unresponsive servers are moved to the end (never removed — the user
     * can still select them manually).
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

        // Probe servers in batches of MAX_PARALLEL to avoid opening hundreds of
        // connections simultaneously.
        val results = mutableListOf<Pair<StreamingServer, Long?>>() // server → response ms (null = failed)

        servers.chunked(MAX_PARALLEL).forEach { batch ->
            val batchResults = coroutineScope {
                batch.map { server ->
                    async {
                        val url = if (type == "movie") server.movieUrl(tmdbId)
                                  else server.tvUrl(tmdbId, season, episode)
                        val ms = withTimeoutOrNull(PROBE_TIMEOUT_MS) { probeUrl(url) }
                        server to ms
                    }
                }.map { it.await() }
            }
            results.addAll(batchResults)
        }

        // Sort: working servers first (by response time), then failed ones
        val (working, failed) = results.partition { (_, ms) -> ms != null }
        val sorted = working.sortedBy { (_, ms) -> ms!! }.map { (s, _) -> s }
        val failedServers = failed.map { (s, _) -> s }

        Log.d(TAG, "Probe complete: ${sorted.size} working, ${failedServers.size} failed")
        sorted + failedServers
    }

    /**
     * Issues a lightweight GET request (range: first 512 bytes) to [url] and
     * returns how long it took in milliseconds, or null if the server didn't
     * respond or returned a hard error.
     *
     * We use GET instead of HEAD because many embed servers reject HEAD with
     * 403/405, which would incorrectly mark them as dead.
     * Requesting only the first 512 bytes keeps bandwidth usage minimal.
     */
    private fun probeUrl(url: String): Long? {
        return try {
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url(url)
                .get()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                // Ask for only the first 512 bytes to keep probe traffic tiny
                .header("Range", "bytes=0-511")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            // Consume and discard body to release the connection
            response.body?.bytes()
            response.close()

            val elapsed = System.currentTimeMillis() - start

            // Accept 200 (full), 206 (partial — range accepted), and 301/302 redirects
            // that resolved to a 2xx after followRedirects.
            // Reject 404 (content not found) and 5xx (server error).
            // Treat 403 as "alive but blocked" — still usable, just slower to try.
            when (code) {
                in 200..299 -> elapsed
                403         -> elapsed + 5_000L  // penalise but don't exclude
                404, 410    -> null               // content definitely not there
                in 500..599 -> null               // server error
                else        -> elapsed            // 301/302 already followed; other codes — try anyway
            }
        } catch (e: Exception) {
            Log.d(TAG, "Probe failed for $url: ${e.message}")
            null
        }
    }
}
