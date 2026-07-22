package com.freeflix.app.data.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ServerManager — Loads the auto-updating server list, tracks server health,
 * and remembers which server the user selected.
 *
 * ## Auto-updating list
 * Servers come from [RemoteServerListFetcher] (remote JSON → disk cache →
 * bundled asset). The list is exposed as [ServerConfig] entries — each carries
 * its own URL templates so new providers can be added without an app update.
 *
 * ## Per-session health (in-memory)
 * Servers that succeed or fail for the *current* piece of content are tracked
 * in [goodServers] / [deadServers] so [getOrderedServers] puts known-good
 * servers first and dead servers last.
 *
 * ## Cross-session success scores (persistent)
 * Every time a server delivers a stream its score is incremented. On the next
 * launch the list is pre-sorted so historically reliable servers are tried
 * first.
 *
 * ## User-selected server
 * The user can pin a preferred server via the server picker. When set, that
 * server is tried **first**, ahead of everything else. A pinned server's id is
 * stored in SharedPreferences so it persists across launches.
 *
 * Call [resetHealth] when the user opens a new title.
 */
object ServerManager {

    private const val TAG = "ServerManager"
    private const val PREFS_NAME = "server_scores"
    private const val KEY_SELECTED = "selected_server_id"
    private const val KEY_AUTO = "auto_server"   // true = no manual pin

    private var servers: List<ServerConfig> = emptyList()

    // Per-session health keyed by server id (cleared on resetHealth).
    private val deadServers = mutableSetOf<String>()
    private val goodServers = mutableSetOf<String>()

    private var prefs: SharedPreferences? = null

    // ------------------------------------------------------------------ //
    //  Initialisation
    // ------------------------------------------------------------------ //

    /**
     * Loads the server list from the auto-updating source and opens the
     * persistent score store. Safe to call multiple times — a subsequent
     * call with [forceRefresh] re-fetches the list from RemoteServerListFetcher.
     */
    fun initialize(context: Context, forceRefresh: Boolean = false) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        if (forceRefresh) {
            StreamProviders.clearCache()
            RemoteServerListFetcher.clearMemoryCache()
        }
        if (servers.isEmpty() || forceRefresh) {
            servers = StreamProviders.get(context)
            Log.d(TAG, "Initialized with ${servers.size} servers")
        }
    }

    /** The raw server list (ordered by tier/reliability from StreamProviders). */
    fun allServers(): List<ServerConfig> = servers

    // ------------------------------------------------------------------ //
    //  User-selected server
    // ------------------------------------------------------------------ //

    /**
     * The id of the server the user pinned via the picker, or null for
     * "Auto" (let the manager order/score servers automatically).
     */
    fun getSelectedServerId(): String? {
        val p = prefs ?: return null
        if (p.getBoolean(KEY_AUTO, true)) return null
        return p.getString(KEY_SELECTED, null)
    }

    /** Sets the user-selected server by id, or null to revert to Auto. */
    fun setSelectedServerId(id: String?) {
        val p = prefs ?: return
        if (id == null) {
            p.edit().putBoolean(KEY_AUTO, true).remove(KEY_SELECTED).apply()
        } else {
            p.edit()
                .putBoolean(KEY_AUTO, false)
                .putString(KEY_SELECTED, id)
                .apply()
        }
        Log.d(TAG, "Selected server set to: ${id ?: "Auto"}")
    }

    /** The [ServerConfig] the user pinned, or null if Auto / not found. */
    fun getSelectedServer(): ServerConfig? {
        val id = getSelectedServerId() ?: return null
        return servers.firstOrNull { it.id == id }
    }

    // ------------------------------------------------------------------ //
    //  Ordered server list
    // ------------------------------------------------------------------ //

    /**
     * Returns the server list ordered for best auto-selection:
     *
     *  1. The user-selected server (if pinned) — always first.
     *  2. Servers that already worked **this session** — sorted by persistent
     *     success score (most reliable first).
     *  3. Untried servers — sorted by persistent success score, then by the
     *     remote reliability rating.
     *  4. Servers that failed **this session** — last-resort fallback.
     *
     * The user-selected server is NOT filtered out even if it failed this
     * session (the user chose it deliberately); it stays at the top.
     */
    fun getOrderedServers(): List<ServerConfig> {
        val selectedId = getSelectedServerId()
        val selected = selectedId?.let { id -> servers.firstOrNull { it.id == id } }

        val remaining = servers.filter { it.id != selectedId }
        val good = remaining.filter { it.id in goodServers }
            .sortedWith(scoreThenReliability())
        val fresh = remaining.filter { it.id !in goodServers && it.id !in deadServers }
            .sortedWith(scoreThenReliability())
        val dead = remaining.filter { it.id in deadServers }
            .sortedWith(scoreThenReliability())

        return listOfNotNull(selected) + good + fresh + dead
    }

    private fun scoreThenReliability(): Comparator<ServerConfig> =
        compareByDescending<ServerConfig> { score(it.id) }
            .thenByDescending { it.reliability }
            .thenBy { it.tier }

    // ------------------------------------------------------------------ //
    //  Health tracking
    // ------------------------------------------------------------------ //

    /** Call when a server successfully delivered a playable stream. */
    fun markServerSuccess(id: String) {
        if (id.isBlank()) return
        goodServers += id
        deadServers -= id
        incrementScore(id)
        Log.d(TAG, "✅ Server OK: $id  (score=${score(id)})")
    }

    /** Call when a server failed to deliver a playable stream. */
    fun markServerDead(id: String) {
        if (id.isBlank()) return
        deadServers += id
        goodServers -= id
        Log.d(TAG, "❌ Server dead: $id")
    }

    /** Resets per-session health tracking (call on new title). */
    fun resetHealth() {
        deadServers.clear()
        goodServers.clear()
        Log.d(TAG, "Session health reset.")
    }

    // ------------------------------------------------------------------ //
    //  Persistent scoring helpers
    // ------------------------------------------------------------------ //

    private fun score(id: String): Int = prefs?.getInt(id, 0) ?: 0

    private fun incrementScore(id: String) {
        val p = prefs ?: return
        p.edit().putInt(id, score(id) + 1).apply()
    }
}
