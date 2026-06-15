package com.mariocart.app.data.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import com.mariocart.app.data.repository.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Manages the list of streaming servers.
 *
 * Strategy:
 *  1. Start with the builtin list from [ContentRepository].
 *  2. Run a fast parallel connectivity check — remove servers that don't respond at all.
 *  3. Servers that have *actually played a video* (via [markServerSuccess]) are sorted first.
 *  4. Servers that keep failing (via [markServerDead]) are sorted last.
 *
 * The only real test of whether a server can play a video is to load it in
 * a WebView and wait for <video>.play() — that happens in PlayerActivity.
 * This class just provides a smart initial ordering so the player reaches a
 * working server faster.
 */
object ServerManager {

    private const val TAG              = "ServerManager"
    private const val PREFS_NAME       = "server_prefs"
    private const val KEY_SUCCESS      = "success_count_"   // prefix + serverName
    private const val KEY_DEAD         = "dead_servers"
    private const val KEY_LAST_CHECK   = "last_check"
    private const val CHECK_INTERVAL   = 30 * 60 * 1000L    // 30 min
    private const val PROBE_TIMEOUT    = 5_000L             // 5 s per server

    private val client = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    private val mutex  = Mutex()
    private var prefs: SharedPreferences? = null

    val isChecking     = MutableStateFlow(false)
    val statusMessage  = MutableStateFlow("")

    // The ordered, filtered server list — updated by initialize()
    private val _servers = MutableStateFlow<List<StreamingServer>>(emptyList())
    val liveServers: StateFlow<List<StreamingServer>> = _servers

    // ── Public API ────────────────────────────────────────────────────────────

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Call once on app start. Runs a connectivity check and orders servers by
     * past success. Safe to call multiple times — re-runs only every 30 min.
     */
    suspend fun initialize(context: Context) {
        init(context)
        mutex.withLock {
            val last = prefs?.getLong(KEY_LAST_CHECK, 0L) ?: 0L
            if (_servers.value.isNotEmpty() &&
                System.currentTimeMillis() - last < CHECK_INTERVAL) return
        }

        isChecking.value = true
        statusMessage.value = "Checking servers…"

        try {
            val allBuiltin = ContentRepository().streamingServers
            val dead       = loadDeadServers()

            // Quick connectivity check: discard servers that won't even load the embed page
            statusMessage.value = "Testing ${allBuiltin.size} servers…"
            val alive = connectivityCheck(allBuiltin, dead)

            // Sort alive servers: highest success count first
            val ordered = sortBySuccess(alive)

            // Append dead / unreachable at the end so the user can still pick them
            val unreachable = allBuiltin.filter { s -> ordered.none { it.name == s.name } }

            _servers.value = ordered + unreachable
            prefs?.edit()?.putLong(KEY_LAST_CHECK, System.currentTimeMillis())?.apply()

            statusMessage.value = "${ordered.size} servers ready"
            Log.d(TAG, "Ready: ${ordered.size} reachable, ${unreachable.size} unreachable")
        } catch (e: Exception) {
            Log.e(TAG, "Initialize failed, using builtin list", e)
            if (_servers.value.isEmpty()) _servers.value = ContentRepository().streamingServers
            statusMessage.value = "Using default servers"
        } finally {
            isChecking.value = false
        }
    }

    /**
     * Call this when a server **actually plays video** in the WebView/ExoPlayer.
     * Increments its success score so it appears earlier next time.
     */
    fun markServerSuccess(serverName: String) {
        if (serverName.isBlank()) return
        val key    = KEY_SUCCESS + serverName
        val old    = prefs?.getInt(key, 0) ?: 0
        prefs?.edit()?.putInt(key, old + 1)?.apply()

        // Also move it to the front of the in-memory list for this session
        val current = _servers.value.toMutableList()
        val idx     = current.indexOfFirst { it.name == serverName }
        if (idx > 0) {
            val s = current.removeAt(idx)
            current.add(0, s)
            _servers.value = current
        }
        Log.d(TAG, "Success: $serverName (score=${old + 1})")
    }

    /**
     * Call when a server fails with an HTTP error or network error.
     * Moves it to the end of the in-memory list. Does NOT persist — servers get
     * another chance next session (sites recover).
     */
    fun markServerDead(serverName: String) {
        if (serverName.isBlank()) return
        val current = _servers.value.toMutableList()
        val idx     = current.indexOfFirst { it.name == serverName }
        if (idx >= 0) {
            val s = current.removeAt(idx)
            current.add(s)
            _servers.value = current
        }
        Log.d(TAG, "Dead (this session): $serverName")
    }

    /**
     * Returns the current ordered server list, or the builtin list if not yet
     * initialized (safe to call before [initialize] finishes).
     */
    fun getOrderedServers(): List<StreamingServer> {
        val live = _servers.value
        return if (live.isNotEmpty()) live else sortBySuccess(ContentRepository().streamingServers)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Parallel connectivity check.  Uses HEAD requests — a failed response
     * means the server is completely down.  A 200/3xx means the page loads
     * (doesn't guarantee video plays — that's proven by the WebView in PlayerActivity).
     */
    private suspend fun connectivityCheck(
        servers: List<StreamingServer>,
        dead: Set<String>
    ): List<StreamingServer> = withContext(Dispatchers.IO) {

        // Use Fight Club (550) — virtually every server has it
        val TEST_ID = 550
        coroutineScope {
            servers.map { server ->
                async {
                    // Skip previously persisted dead servers (but still include at end)
                    if (server.name in dead) return@async null
                    val url = server.movieUrl(TEST_ID)
                    val ok  = probe(url)
                    if (ok) server else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun probe(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        val resp = client.newCall(req).execute()
        val code = resp.code
        resp.close()
        // 200–399 = the page is there (redirect is fine); 404/5xx = skip
        code in 200..399
    } catch (_: Exception) { false }

    private fun sortBySuccess(servers: List<StreamingServer>): List<StreamingServer> {
        val p = prefs ?: return servers
        return servers.sortedByDescending { p.getInt(KEY_SUCCESS + it.name, 0) }
    }

    private fun loadDeadServers(): Set<String> =
        prefs?.getStringSet(KEY_DEAD, emptySet()) ?: emptySet()
}
