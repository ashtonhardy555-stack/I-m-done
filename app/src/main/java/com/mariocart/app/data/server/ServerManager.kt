package com.mariocart.app.data.server

import android.content.Context
import android.util.Log
import com.mariocart.app.data.model.StreamingServer
import org.json.JSONArray
import java.io.IOException

/**
 * Manages the list of streaming servers loaded from assets/servers.json.
 *
 * Tracks per-session server health so that servers that have already failed
 * for the current piece of content are deprioritised in subsequent attempts.
 */
object ServerManager {

    private const val TAG = "ServerManager"
    private const val SERVERS_ASSET = "servers.json"

    private var servers: List<StreamingServer> = emptyList()
    private val deadServers  = mutableSetOf<String>()
    private val goodServers  = mutableSetOf<String>()

    /**
     * Loads the server list from `assets/servers.json`.
     * Safe to call multiple times — subsequent calls are no-ops once loaded.
     */
    fun initialize(context: Context) {
        if (servers.isNotEmpty()) return
        try {
            val json = context.assets.open(SERVERS_ASSET).bufferedReader().use { it.readText() }
            val arr  = JSONArray(json)
            val list = mutableListOf<StreamingServer>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name    = obj.optString("name",    "").trim()
                val baseUrl = obj.optString("baseUrl", "").trim()
                if (name.isNotEmpty() && baseUrl.isNotEmpty()) {
                    list += StreamingServer(name, baseUrl)
                }
            }
            servers = list
            Log.d(TAG, "Loaded ${servers.size} servers from $SERVERS_ASSET")
        } catch (e: IOException) {
            Log.e(TAG, "Could not open $SERVERS_ASSET: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $SERVERS_ASSET: ${e.message}")
        }
    }

    /**
     * Returns the server list ordered so that:
     *  1. Previously successful servers come first (in original order).
     *  2. Untried servers come next (in original order).
     *  3. Dead / failed servers come last.
     */
    fun getOrderedServers(): List<StreamingServer> {
        val good  = servers.filter { it.name in goodServers  }
        val fresh = servers.filter { it.name !in goodServers && it.name !in deadServers }
        val dead  = servers.filter { it.name in deadServers  }
        return good + fresh + dead
    }

    /** Call when a server successfully delivered a playable stream. */
    fun markServerSuccess(name: String) {
        if (name.isBlank()) return
        goodServers += name
        deadServers -= name
        Log.d(TAG, "✅ Server OK: $name")
    }

    /** Call when a server failed to deliver a playable stream. */
    fun markServerDead(name: String) {
        if (name.isBlank()) return
        deadServers += name
        goodServers -= name
        Log.d(TAG, "❌ Server dead: $name")
    }

    /** Resets health tracking (e.g. when the user opens a new title). */
    fun resetHealth() {
        deadServers.clear()
        goodServers.clear()
    }
}
