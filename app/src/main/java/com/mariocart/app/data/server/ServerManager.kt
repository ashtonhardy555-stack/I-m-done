package com.mariocart.app.data.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import org.json.JSONArray

/**
 * ServerManager — Loads the server list and tracks server health.
 *
 * ## Per-session health (in-memory)
 * Servers that succeed or fail for the *current* piece of content are
 * tracked in [goodServers] / [deadServers] so that [getOrderedServers]
 * can put known-good servers first and dead servers last.
 *
 * ## Cross-session success scores (persistent)
 * Every time a server successfully delivers a stream, its score is
 * incremented in [SharedPreferences].  On the next launch the list is
 * pre-sorted so that historically reliable servers are tried first,
 * before latency probing even begins.
 *
 * The combined ordering used by [getOrderedServers] is:
 *   1. Good this session  (sorted by persistent score desc)
 *   2. Untried this session (sorted by persistent score desc)
 *   3. Dead this session  (sorted by persistent score desc)
 *
 * Call [resetHealth] when the user opens a new title so per-session
 * state is cleared while persistent scores are preserved.
 */
object ServerManager {

    private const val TAG          = "ServerManager"
    private const val SERVERS_ASSET = "servers.json"
    private const val PREFS_NAME   = "server_scores"

    private var servers: List<StreamingServer> = emptyList()

    // Per-session health sets (cleared on resetHealth)
    private val deadServers = mutableSetOf<String>()
    private val goodServers = mutableSetOf<String>()

    // Persistent score store — loaded once in initialize()
    private var prefs: SharedPreferences? = null

    // ------------------------------------------------------------------ //
    //  Initialisation
    // ------------------------------------------------------------------ //

    /**
     * Loads servers from assets and opens the persistent score store.
     * Safe to call multiple times — subsequent calls are no-ops once loaded.
     */
    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (servers.isNotEmpty()) return

        try {
            val json = context.assets.open(SERVERS_ASSET).bufferedReader().use { it.readText() }
            val arr  = JSONArray(json)
            val list = mutableListOf<StreamingServer>()
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                val name    = obj.optString("name",    "").trim()
                val baseUrl = obj.optString("baseUrl", "").trim()
                if (name.isNotEmpty() && baseUrl.isNotEmpty()) {
                    list += StreamingServer(name, baseUrl)
                }
            }
            servers = list
            Log.d(TAG, "Loaded ${servers.size} servers from $SERVERS_ASSET")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $SERVERS_ASSET, using fallback: ${e.message}")
            servers = listOf(
                StreamingServer("VidLink",     "https://vidlink.pro"),
                StreamingServer("VidSrc.su",   "https://vidsrc.su"),
                StreamingServer("2Embed.cc",   "https://www.2embed.cc"),
                StreamingServer("2Embed.skin", "https://www.2embed.skin"),
                StreamingServer("VidSrc.to",   "https://vidsrc.to"),
                StreamingServer("Videasy",     "https://player.videasy.to"),
                StreamingServer("VidSrc.in",   "https://vidsrc.in"),
                StreamingServer("VidSrc.fyi",  "https://vidsrc.fyi")
            )
        }
    }

    // ------------------------------------------------------------------ //
    //  Ordered server list
    // ------------------------------------------------------------------ //

    /**
     * Returns the full server list ordered for best auto-selection:
     *
     *  1. Servers that already worked **this session** — sorted by
     *     persistent success score (most reliable first).
     *  2. Untried servers — sorted by persistent success score.
     *  3. Servers that failed **this session** — sorted by persistent
     *     success score (last-resort fallback).
     */
    fun getOrderedServers(): List<StreamingServer> {
        val good  = servers.filter { it.name in goodServers }
            .sortedByDescending { score(it.name) }
        val fresh = servers.filter { it.name !in goodServers && it.name !in deadServers }
            .sortedByDescending { score(it.name) }
        val dead  = servers.filter { it.name in deadServers }
            .sortedByDescending { score(it.name) }
        return good + fresh + dead
    }

    // ------------------------------------------------------------------ //
    //  Health tracking
    // ------------------------------------------------------------------ //

    /**
     * Call when a server successfully delivered a playable stream.
     * Increments the server's persistent success score.
     */
    fun markServerSuccess(name: String) {
        if (name.isBlank()) return
        goodServers += name
        deadServers -= name
        incrementScore(name)
        Log.d(TAG, "✅ Server OK: $name  (score=${score(name)})")
    }

    /**
     * Call when a server failed to deliver a playable stream.
     * Does NOT decrement the persistent score — a single failure may be
     * transient; only session-level ordering is affected.
     */
    fun markServerDead(name: String) {
        if (name.isBlank()) return
        deadServers += name
        goodServers -= name
        Log.d(TAG, "❌ Server dead: $name")
    }

    /**
     * Resets per-session health tracking.
     * Call this whenever the user opens a new title so dead/good sets
     * are cleared, but persistent scores are preserved.
     */
    fun resetHealth() {
        deadServers.clear()
        goodServers.clear()
        Log.d(TAG, "Session health reset.")
    }

    // ------------------------------------------------------------------ //
    //  Persistent scoring helpers
    // ------------------------------------------------------------------ //

    /** Returns the persistent success score for [name], defaulting to 0. */
    private fun score(name: String): Int =
        prefs?.getInt(name, 0) ?: 0

    /** Atomically increments the persistent success score for [name]. */
    private fun incrementScore(name: String) {
        val p = prefs ?: return
        p.edit().putInt(name, score(name) + 1).apply()
    }
}
