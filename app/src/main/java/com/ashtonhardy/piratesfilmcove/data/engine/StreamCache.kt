package com.ashtonhardy.piratesfilmcove.data.engine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * StreamCache \u2014 a tiny, in-memory cache of resolved LookMovie streams keyed
 * by a stable content identity, with an optional JSON persistence file so the
 * "Kodi-like engine" can warm-start after a process restart.
 *
 * Each entry stores the resolved `.m3u8`/`.mp4` URL, the headers ExoPlayer
 * must send (notably the `t_hash` cookie), the provider name, and an expiry
 * timestamp. LookMovie's security API hands back an `expires` value, so we
 * honour it; if we don't have one we fall back to a conservative TTL.
 *
 * This is what lets the [KodiEngine] deliver an *instant* play for a title it
 * already resolved in the background \u2014 the player never has to wait for the
 * search \u2192 storage \u2192 security-API round trip on a cache hit.
 */
class StreamCache private constructor(private val context: Context) {

    private val TAG = "StreamCache"

    /** A resolved stream ready to hand to ExoPlayer. */
    data class Entry(
        val url: String,
        val headers: Map<String, String>,
        val providerName: String,
        /** Epoch millis after which this entry is considered stale. */
        val expiresAt: Long
    ) {
        fun isFresh(now: Long = System.currentTimeMillis()): Boolean = now < expiresAt
    }

    // contentKey -> Entry. ConcurrentHashMap because the engine resolves from a
    // background coroutine while the player reads from the main thread.
    private val mem = ConcurrentHashMap<String, Entry>()

    /** Build the stable cache key for a piece of content. */
    fun key(
        title: String,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): String = buildString {
        append(title.trim().lowercase())
        append('|').append(if (isMovie) "movie" else "tv")
        if (!isMovie) append('|').append(season).append('x').append(episode)
    }

    /** Returns a still-fresh entry for the key, or null. */
    fun get(key: String): Entry? {
        val e = mem[key] ?: return null
        if (!e.isFresh()) {
            Log.d(TAG, "evict stale entry for $key")
            mem.remove(key)
            return null
        }
        return e
    }

    /** Convenience: get by content identity. */
    fun get(
        title: String,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ): Entry? = get(key(title, isMovie, season, episode))

    /** Store a freshly resolved stream. */
    fun put(
        title: String,
        isMovie: Boolean,
        season: Int,
        episode: Int,
        url: String,
        headers: Map<String, String>,
        providerName: String,
        ttlMillis: Long = DEFAULT_TTL_MS
    ) {
        val k = key(title, isMovie, season, episode)
        mem[k] = Entry(url, headers, providerName, System.currentTimeMillis() + ttlMillis)
        // Opportunistic persistence \u2014 never block the resolver on disk IO.
        runCatching { persist() }
    }

    /** Drop a single entry (e.g. the player reported it 404'd). */
    fun invalidate(
        title: String,
        isMovie: Boolean,
        season: Int,
        episode: Int
    ) {
        mem.remove(key(title, isMovie, season, episode))
        runCatching { persist() }
    }

    /** Wipe everything. */
    fun clear() {
        mem.clear()
        runCatching { context.deleteSerializableCacheFile() }
    }

    /** Number of cached entries (for the engine's debug notification). */
    fun size(): Int = mem.size

    // \u2500\u2500 persistence \u2500\u2500

    private fun persist() {
        val arr = JSONArray()
        for (e in mem.values) {
            val h = JSONObject()
            e.headers.forEach { (k, v) -> h.put(k, v) }
            arr.put(JSONObject().apply {
                put("url", e.url)
                put("headers", h)
                put("provider", e.providerName)
                put("expiresAt", e.expiresAt)
            })
        }
        val obj = JSONObject().apply {
            // Store keys alongside values so we can rebuild the map on load.
            val keyed = JSONObject()
            for (k in mem.keys) {
                mem[k]?.let { e ->
                    val h = JSONObject()
                    e.headers.forEach { (hk, hv) -> h.put(hk, hv) }
                    keyed.put(k, JSONObject().apply {
                        put("url", e.url)
                        put("headers", h)
                        put("provider", e.providerName)
                        put("expiresAt", e.expiresAt)
                    })
                }
            }
            put("entries", keyed)
        }
        context.cacheFile().writeText(obj.toString())
    }

    private fun load() {
        val f = context.cacheFile()
        if (!f.exists()) return
        runCatching {
            val obj = JSONObject(f.readText())
            val entries = obj.optJSONObject("entries") ?: return
            val now = System.currentTimeMillis()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val e = entries.optJSONObject(k) ?: continue
                val expiresAt = e.optLong("expiresAt")
                if (expiresAt <= now) continue // drop stale on load
                val h = mutableMapOf<String, String>()
                val hObj = e.optJSONObject("headers")
                if (hObj != null) {
                    val hk = hObj.keys()
                    while (hk.hasNext()) {
                        val name = hk.next()
                        h[name] = hObj.optString(name)
                    }
                }
                mem[k] = Entry(
                    url = e.optString("url"),
                    headers = h,
                    providerName = e.optString("provider"),
                    expiresAt = expiresAt
                )
            }
            Log.d(TAG, "loaded ${mem.size} cached streams from disk")
        }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
    }

    private fun Context.cacheFile() =
        java.io.File(cacheDir, "kodi_engine_stream_cache.json")

    private fun Context.deleteSerializableCacheFile() {
        runCatching { cacheFile().delete() }
    }

    companion object {
        /** Conservative default TTL: 20 minutes. LookMovie tokens can be
         *  short-lived, so we don't keep them around for hours. */
        private const val DEFAULT_TTL_MS = 20L * 60 * 1000

        @Volatile private var INSTANCE: StreamCache? = null

        fun init(context: Context): StreamCache {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: StreamCache(context.applicationContext).also {
                    INSTANCE = it
                    it.load()
                }
            }
        }

        fun get(): StreamCache =
            INSTANCE ?: error("StreamCache.init() must be called from PiratesfilmCoveApplication.onCreate()")
    }
}
