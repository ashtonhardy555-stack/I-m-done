package com.mariocart.app.data.server

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RemoteServerListFetcher — keeps the server list *up to date* without an app
 * update.
 *
 * On startup the app calls [fetchRemoteServers]. It downloads
 * `working-servers.json` from the raw GitHub URL (the same file you can edit
 * and push to instantly update every install), parses it, caches it to
 * private storage so the next launch is instant, and returns the fresh list.
 *
 * If the network request fails it falls back to the cached copy from the last
 * successful fetch; if there is no cache it falls back to the [servers.json]
 * asset bundled inside the APK. The app therefore always has a usable list.
 *
 * The remote URL points at the `main` branch of the I-m-done repo so editing
 * [working-servers.json] and merging to main is all that's needed to push new
 * servers / disable dead ones / reorder reliability.
 */
object RemoteServerListFetcher {

    private const val TAG = "RemoteServerList"

    /** The auto-update source. Edit `working-servers.json` + push to main. */
    private const val REMOTE_URL =
        "https://raw.githubusercontent.com/ashtonhardy555-stack/I-m-done/main/working-servers.json"

    /** Cached copy filename in app private storage. */
    private const val CACHE_FILE = "remote_servers_cache.json"

    /** Max staleness before a fetch is forced (24 h). */
    private const val MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000L

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /** Shape of the remote / asset JSON. */
    data class ServerList(
        val version: Int = 1,
        val updated: String? = null,
        @SerializedName("servers")
        val servers: List<ServerConfig> = emptyList()
    )

    // In-memory copy so repeated reads don't hit disk.
    @Volatile
    private var cached: List<ServerConfig>? = null

    /** Clears the in-memory cache so the next read re-fetches/re-reads. */
    fun clearMemoryCache() {
        cached = null
    }

    /**
     * Returns the best-available server list, newest first:
     *   1. A fresh remote fetch (network).
     *   2. The on-disk cache from the last successful fetch.
     *   3. The bundled [servers.json] asset.
     *
     * Pass [forceRemote] = true to skip the freshness check and always hit
     * the network (e.g. when the user taps "refresh servers").
     */
    fun getServers(context: Context, forceRemote: Boolean = false): List<ServerConfig> {
        cached?.let { if (!forceRemote) return it }

        // 1. Try a fresh remote fetch (unless we recently cached to disk).
        if (forceRemote || !cacheIsFresh(context)) {
            val remote = fetchFromNetwork()
            if (remote.isNotEmpty()) {
                cached = remote
                saveCache(context, remote)
                Log.i(TAG, "✅ Loaded ${remote.size} servers from remote (${REMOTE_URL})")
                return remote
            }
        }

        // 2. On-disk cache from a previous run.
        val disk = readCache(context)
        if (disk.isNotEmpty()) {
            cached = disk
            Log.i(TAG, "📂 Using cached server list (${disk.size} servers)")
            return disk
        }

        // 3. Bundled asset fallback.
        val asset = readAsset(context)
        cached = asset
        Log.i(TAG, "📦 Using bundled asset server list (${asset.size} servers)")
        return asset
    }

    /** Force a network fetch right now, returning the result (may be empty). */
    private fun fetchFromNetwork(): List<ServerConfig> {
        return try {
            val request = Request.Builder()
                .url(REMOTE_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) I-m-Done/ServerList")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Remote fetch failed: HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                val list = Gson().fromJson(body, ServerList::class.java)
                list.servers.filter { it.enabled && it.id.isNotBlank() }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Remote fetch error: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Remote parse error: ${e.message}")
            emptyList()
        }
    }

    // ── disk cache ──────────────────────────────────────────────────── //

    private fun cacheFile(context: Context) =
        java.io.File(context.filesDir, CACHE_FILE)

    private fun cacheIsFresh(context: Context): Boolean {
        val f = cacheFile(context)
        if (!f.exists()) return false
        val age = System.currentTimeMillis() - f.lastModified()
        return age < MAX_CACHE_AGE_MS
    }

    private fun saveCache(context: Context, servers: List<ServerConfig>) {
        try {
            val list = ServerList(servers = servers)
            val json = Gson().toJson(list)
            cacheFile(context).writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache: ${e.message}")
        }
    }

    private fun readCache(context: Context): List<ServerConfig> {
        return try {
            val f = cacheFile(context)
            if (!f.exists()) return emptyList()
            val list = Gson().fromJson(f.readText(), ServerList::class.java)
            list.servers.filter { it.enabled }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── bundled asset fallback ──────────────────────────────────────── //

    private fun readAsset(context: Context): List<ServerConfig> {
        return try {
            val json = context.assets.open("servers.json").bufferedReader().use { it.readText() }
            val list = Gson().fromJson(json, ServerList::class.java)
            list.servers.filter { it.enabled }
        } catch (e: Exception) {
            Log.e(TAG, "Asset read failed: ${e.message}")
            // Last-ditch hardcoded fallback so the app is never empty.
            listOf(
                ServerConfig("vidlink", "VidLink", "https://vidlink.pro",
                    "{base}/movie/{id}", "{base}/tv/{id}/{season}/{episode}", 1, 95),
                ServerConfig("2embed_cc", "2Embed.cc", "https://www.2embed.cc",
                    "{base}/embed/{id}", "{base}/embed/tv/{id}/{season}/{episode}", 1, 88),
                ServerConfig("vidsrc_me", "VidSrc.me", "https://vidsrc.me",
                    "{base}/embed/movie/{id}", "{base}/embed/tv/{id}/{season}/{episode}", 2, 78)
            )
        }
    }
}
