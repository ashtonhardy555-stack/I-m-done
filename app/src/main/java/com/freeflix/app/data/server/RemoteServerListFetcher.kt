package com.freeflix.app.data.server

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
 * On startup the app calls [fetchRemoteServers]. It downloads the server list
 * from one of several redundant raw GitHub URLs (see [REMOTE_URLS]), parses
 * it, caches it to private storage so the next launch is instant, and returns
 * the fresh list.
 *
 * If every network request fails it falls back to the cached copy from the
 * last successful fetch; if there is no cache it falls back to the
 * [servers.json] asset bundled inside the APK. The app therefore always has a
 * usable list.
 *
 * ## Why multiple remote URLs
 *
 * The primary source is the `working-servers.json` on this repo's `main`
 * branch, which a GitHub Actions workflow (`server-health-check.yml`)
 * auto-updates every day with fresh reliability scores and enabled flags.
 * Because the app cannot know in advance which branch/repo name will exist
 * in the future, it tries several redundant raw URLs in order — the first one
 * that returns a valid, non-empty server list wins. This means renaming the
 * repo, moving the file, or the daily workflow pushing to a different branch
 * all degrade gracefully instead of breaking every install.
 */
object RemoteServerListFetcher {

    private const val TAG = "RemoteServerList"

    /**
     * Ordered list of remote sources for the auto-updating server list.
     * The first URL that returns a valid JSON server list wins.
     *
     *  1. The `main` branch of this repo (auto-updated daily by the
     *     `server-health-check` GitHub Actions workflow).
     *  2. A `server-registry` branch of this repo (a dedicated long-lived
     *     branch the workflow can be repointed to without touching main).
     *  3. A jsDelivr CDN mirror of the same file (CDN-cached, survives brief
     *     raw.githubusercontent.com outages).
     *
     * Editing `working-servers.json` and merging to main is all that's needed
     * to push new servers / disable dead ones / reorder reliability — the
     * daily workflow does this automatically.
     */
    private val REMOTE_URLS = listOf(
        "https://raw.githubusercontent.com/ashtonhardy555-stack/I-m-done/main/working-servers.json",
        "https://raw.githubusercontent.com/ashtonhardy555-stack/I-m-done/server-registry/working-servers.json",
        "https://cdn.jsdelivr.net/gh/ashtonhardy555-stack/I-m-done@main/working-servers.json"
    )

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
                Log.i(TAG, "✅ Loaded ${remote.size} servers from remote")
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

    /**
     * Force a network fetch right now, trying each URL in [REMOTE_URLS] in
     * order. Returns the first non-empty server list, or empty if every URL
     * failed.
     */
    private fun fetchFromNetwork(): List<ServerConfig> {
        for (url in REMOTE_URLS) {
            val result = fetchOne(url)
            if (result.isNotEmpty()) {
                Log.i(TAG, "Remote source OK: $url")
                return result
            }
            Log.w(TAG, "Remote source empty/failed: $url")
        }
        return emptyList()
    }

    private fun fetchOne(remoteUrl: String): List<ServerConfig> {
        return try {
            val request = Request.Builder()
                .url(remoteUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) I-m-Done/ServerList")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Remote fetch $remoteUrl failed: HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string() ?: return emptyList()
                val list = Gson().fromJson(body, ServerList::class.java)
                list.servers.filter { it.enabled && it.id.isNotBlank() }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Remote fetch $remoteUrl error: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Remote parse $remoteUrl error: ${e.message}")
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
                ServerConfig("notorrent", "NoTorrent", "https://addon-osvh.onrender.com",
                    "{base}/stream/movie/{imdbId}.json",
                    "{base}/stream/series/{imdbId}:{season}:{episode}.json", 1, 99),
                ServerConfig("vidstorm", "VidStorm", "https://vidstorm.ru",
                    "{base}/movie/{id}", "{base}/tv/{id}/{season}/{episode}", 1, 97),
                ServerConfig("vidspark", "VidSpark", "https://vidspark.to",
                    "{base}/movie/{id}", "{base}/tv/{id}/{season}/{episode}", 1, 95),
                ServerConfig("vidlink", "VidLink", "https://vidlink.pro",
                    "{base}/movie/{id}", "{base}/tv/{id}/{season}/{episode}", 1, 94),
                ServerConfig("2embed_cc", "2Embed.cc", "https://www.2embed.cc",
                    "{base}/embed/{id}", "{base}/embedtv/{id}&s={season}&e={episode}", 1, 93),
                ServerConfig("2embed_skin", "2Embed.skin", "https://www.2embed.skin",
                    "{base}/embed/{id}", "{base}/embedtv/{id}&s={season}&e={episode}", 1, 92),
                ServerConfig("2embed_vesy", "2Embed·Vesy", "https://streamsrcs.2embed.cc",
                    "{base}/vesy?tmdb={id}", "{base}/vesy-tv?tmdb={id}&s={season}&e={episode}", 1, 91),
                ServerConfig("2embed_vcr", "2Embed·VCR", "https://streamsrcs.2embed.cc",
                    "{base}/vcr?tmdb={id}", "{base}/vcr-tv?tmdb={id}&s={season}&e={episode}", 1, 90),
                ServerConfig("2embed_xps", "2Embed·XPS", "https://streamsrcs.2embed.cc",
                    "{base}/xps?imdb=tt{id}", "{base}/xps-tv?imdb=tt{id}&s={season}&e={episode}", 1, 89),
                ServerConfig("videasy_net", "Videasy.net", "https://player.videasy.net",
                    "{base}/movie/{id}", "{base}/tv/{id}/{season}/{episode}", 1, 83),
                ServerConfig("multiembed", "MultiEmbed", "https://multiembed.mov",
                    "{base}/?video_id={id}&tmdb=1", "{base}/?video_id={id}&tmdb=1&s={season}&e={episode}", 2, 79),
                ServerConfig("vidsrc_me", "VidSrc.me", "https://vidsrc.me",
                    "{base}/embed/movie/{id}", "{base}/embed/tv/{id}/{season}/{episode}", 2, 60),
                ServerConfig("vidsrc_cc", "VidSrc.cc", "https://vidsrc.cc",
                    "{base}/v2/embed/movie/{id}", "{base}/v2/embed/tv/{id}/{season}/{episode}", 2, 60),
                ServerConfig("vidsrc_to", "VidSrc.to", "https://vidsrc.to",
                    "{base}/embed/movie/{id}", "{base}/embed/tv/{id}/{season}/{episode}", 2, 60)
            )
        }
    }
}
