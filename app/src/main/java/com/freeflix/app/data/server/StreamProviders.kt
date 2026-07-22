package com.freeflix.app.data.server

import android.content.Context
import android.util.Log

/**
 * StreamProviders — central access point for the **auto-updating** list of
 * embed stream providers.
 *
 * The actual provider roster lives in `working-servers.json` (the remote
 * auto-update source) and is fetched by [RemoteServerListFetcher] with a
 * three-tier fallback (remote → disk cache → bundled asset). This object is a
 * thin in-memory cache on top of that fetcher so callers don't repeatedly hit
 * disk/network.
 *
 * Each provider is a [ServerConfig] carrying its own URL **templates**, so new
 * providers can be added to the remote JSON (and pushed to the `main` branch)
 * without an app update — every install picks them up on the next launch.
 *
 * Ordering / health tracking / user-selected-server lives in [ServerManager];
 * this object only loads and caches the raw list.
 */
object StreamProviders {

    private const val TAG = "StreamProviders"

    /** In-memory cache so repeated reads are instant. */
    @Volatile
    private var servers: List<ServerConfig>? = null

    /**
     * Returns the loaded server list, fetching it on first call.
     *
     * Safe to call from any thread. The first call may perform a network
     * request (bounded by [RemoteServerListFetcher]'s 8s/12s timeouts); the
     * result is cached so subsequent calls are instant.
     *
     * The list is already filtered to `enabled == true` providers and ordered
     * by tier then reliability (clean, high-reliability providers first).
     */
    @Synchronized
    fun get(context: Context): List<ServerConfig> {
        servers?.let { return it }
        val loaded = RemoteServerListFetcher.getServers(context)
        // Sort by tier (asc) then reliability (desc) — best providers first.
        val ordered = loaded.sortedWith(
            compareBy<ServerConfig> { it.tier }
                .thenByDescending { it.reliability }
        )
        servers = ordered
        Log.i(TAG, "Loaded ${ordered.size} providers (${ordered.joinToString { "${it.name}(${it.reliability}%)" }})")
        return ordered
    }

    /**
     * Returns the cached list if already loaded, otherwise an empty list.
     * Use this from the UI thread when you only want already-available data.
     */
    fun cached(): List<ServerConfig> = servers ?: emptyList()

    /**
     * Clears the in-memory cache so the next [get] call re-fetches. Call after
     * the user taps "refresh servers" or edits the remote list.
     */
    fun clearCache() {
        servers = null
    }
}
