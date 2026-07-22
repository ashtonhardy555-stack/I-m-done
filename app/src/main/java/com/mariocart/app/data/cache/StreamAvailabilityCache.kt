package com.mariocart.app.data.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * StreamAvailabilityCache — a persistent, per-title record of which streaming
 * PROVIDERS actually work for a given movie or show.
 *
 * ## Why this exists
 * The app races many providers (VidStorm, VidSrc, VidLink, VixSrc, NoTorrent,
 * + the embed servers) in parallel and lets ExoPlayer be the final arbiter of
 * playability. That guarantees EVERY resolvable title eventually plays, but it
 * also means the *first* attempt for a brand-new title may hand ExoPlayer a
 * dead URL (e.g. VidStorm's dead "Boron" worker for Interstellar) before
 * falling through to the provider that actually works (NoTorrent). That costs
 * the user ~4–8 s of "that source didn't play — trying another".
 *
 * This cache breaks that cold-start penalty: every time a provider successfully
 * plays a title we record it; every time ExoPlayer rejects a provider's URL for
 * a title we record that too. The next time the user opens the SAME title, the
 * player consults [knownGoodProvider] and leads with the provider that worked
 * before, and [knownBadProviders] lets the race skip providers we already know
 * are dead for this title — so playback starts on the first try.
 *
 * ## Key
 * The key is `tmdbId|contentType|season|episode` (season/episode are 0 for
 * movies). This granularity means a provider that works for S01E01 but not
 * S02E05 is tracked correctly per episode.
 *
 * ## Persistence
 * Records are stored as a single JSON file in the app's cache dir
 * (`stream_availability.json`). It is loaded lazily on first access and
 * rewritten (debounced via the mutex) on every update. A timestamp is kept per
 * entry so stale records (provider fixed a dead URL, or broke a working one)
 * eventually age out — a bad record older than [STALE_AFTER_MS] is ignored and
 * re-evaluated, and a good record is trusted up to [GOOD_TRUST_MS].
 *
 * ## Thread-safety
 * All mutations go through [saveMutex]; the in-memory map is a
 * `ConcurrentHashMap`-style guarded by the same mutex for simplicity.
 */
object StreamAvailabilityCache {

    private const val TAG = "StreamAvailCache"
    private const val FILE_NAME = "stream_availability.json"

    /** A "good" record is trusted for 14 days before we re-evaluate. */
    private const val GOOD_TRUST_MS = 14L * 24 * 60 * 60 * 1000
    /**
     * A "bad" record is re-evaluated after this long. Kept SHORT (2 hours)
     * deliberately: most provider failures are TRANSIENT (a CDN hiccup, a
     * rate-limit, a temporary 5xx). Marking a provider bad for days — as the
     * old 3-day window did — meant a single transient failure permanently
     * excluded a working server (e.g. VidStorm·Lithium) for the rest of the
     * week. The user reported "servers like lithium are the main ones I've
     * seen work" being removed; a 2-hour window lets transient blips age out
     * quickly while still short-circuiting genuinely-dead providers within a
     * viewing session.
     */
    private const val STALE_AFTER_MS = 2L * 60 * 60 * 1000  // 2 hours (was 3 days)

    // The race-provider keys whose health we track at the race level.
    // This now includes ALL direct-API extractors that participate in the
    // parallel race in PlayerActivity, so the cache can record per-title
    // good/bad results for every extractor (not just the original 5).
    private val RACE_PROVIDERS = setOf(
        "VidStorm", "VidSrc", "VidSrcNet", "VidLink", "VixSrc", "NoTorrent",
        "MeowTV", "Videasy", "KissKH", "VidSync", "LordFlix", "DahmerMovies",
        "TwoEmbed", "SuperEmbed", "VidSrcPro"
    )

    private var appContext: Context? = null
    private val saveMutex = Mutex()

    // key -> JSONObject { "good": "<provider>", "bad": { "<provider>": ts, ... }, "goodTs": ts }
    private val store = mutableMapOf<String, JSONObject>()
    @Volatile private var loaded = false

    /** Initialise with the application context (call once from Application). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Clears all in-memory + on-disk stream-availability records. Called from
     * the Settings screen's "Clear Stream Cache" action so the next play
     * re-probes every provider from scratch (useful if a provider changed
     * their URL scheme or the user wants a clean slate).
     */
    fun clearAll(context: Context) {
        appContext = context.applicationContext
        store.clear()
        loaded = true
        val cacheFile = File(context.cacheDir, "stream_availability.json")
        if (cacheFile.exists()) cacheFile.delete()
        Log.i(TAG, "Stream availability cache cleared")
    }

    private fun keyFor(
        tmdbId: Int, contentType: String, season: Int, episode: Int
    ): String = "$tmdbId|$contentType|$season|$episode"

    private fun ctx(): Context? = appContext

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        try {
            val c = ctx() ?: return
            val f = File(c.cacheDir, FILE_NAME)
            if (!f.exists()) return
            val text = f.readText()
            val root = JSONObject(text)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                store[k] = root.getJSONObject(k)
            }
            Log.d(TAG, "Loaded ${store.size} availability record(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load availability cache: ${e.message}")
        }
    }

    /** Returns the provider that is known to play this title (and is fresh),
     *  or null if we have no trusted good record yet. */
    fun knownGoodProvider(
        tmdbId: Int, contentType: String, season: Int = 0, episode: Int = 0
    ): String? {
        loadIfNeeded()
        val rec = store[keyFor(tmdbId, contentType, season, episode)] ?: return null
        val now = System.currentTimeMillis()
        val goodTs = rec.optLong("goodTs", 0L)
        val good = rec.optString("good", "")
        if (good.isBlank()) return null
        if (now - goodTs > GOOD_TRUST_MS) return null   // stale good record -> re-evaluate
        return good
    }

    /** Providers we have already seen fail for this title (and that are still
     *  considered freshly bad). The race uses this to short-circuit known-dead
     *  providers so a good one wins immediately. */
    fun knownBadProviders(
        tmdbId: Int, contentType: String, season: Int = 0, episode: Int = 0
    ): Set<String> {
        loadIfNeeded()
        val rec = store[keyFor(tmdbId, contentType, season, episode)] ?: return emptySet()
        val now = System.currentTimeMillis()
        val bad = rec.optJSONObject("bad") ?: return emptySet()
        val out = mutableSetOf<String>()
        val keys = bad.keys()
        while (keys.hasNext()) {
            val p = keys.next()
            val ts = bad.optLong(p, 0L)
            if (now - ts <= STALE_AFTER_MS) out += p
        }
        return out
    }

    /** Record that [provider] successfully played this title. */
    fun recordSuccess(
        tmdbId: Int, contentType: String, season: Int, episode: Int, provider: String
    ) {
        if (provider.isBlank()) return
        val canon = canonical(provider)
        if (canon == null) return
        try {
            loadIfNeeded()
            val k = keyFor(tmdbId, contentType, season, episode)
            val rec = store.getOrPut(k) { JSONObject() }
            rec.put("good", canon)
            rec.put("goodTs", System.currentTimeMillis())
            // A fresh success clears any prior "bad" mark for this provider.
            val bad = rec.optJSONObject("bad")
            if (bad != null && bad.has(canon)) {
                bad.remove(canon)
                rec.put("bad", bad)
            }
            scheduleSave()
            Log.d(TAG, "✓ $canon works for $k")
        } catch (e: Exception) {
            Log.w(TAG, "recordSuccess failed: ${e.message}")
        }
    }

    /** Record that ExoPlayer rejected [provider]'s URL for this title. */
    fun recordFailure(
        tmdbId: Int, contentType: String, season: Int, episode: Int, provider: String
    ) {
        if (provider.isBlank()) return
        val canon = canonical(provider) ?: return
        try {
            loadIfNeeded()
            val k = keyFor(tmdbId, contentType, season, episode)
            val rec = store.getOrPut(k) { JSONObject() }
            var bad = rec.optJSONObject("bad")
            if (bad == null) {
                bad = JSONObject()
                rec.put("bad", bad)
            }
            bad.put(canon, System.currentTimeMillis())
            rec.put("bad", bad)
            // If we just marked the previously-known-good provider as bad,
            // clear the good mark so we re-evaluate from scratch.
            if (rec.optString("good", "") == canon) {
                rec.remove("good")
                rec.remove("goodTs")
            }
            scheduleSave()
            Log.d(TAG, "✗ $canon failed for $k")
        } catch (e: Exception) {
            Log.w(TAG, "recordFailure failed: ${e.message}")
        }
    }

    /**
     * Map a deliveringServerName to the canonical cache key, or null if it's
     * not a race provider.
     *
     * IMPORTANT: this preserves the FULL sub-server name. A VidStorm stream
     * is delivered as "VidStorm·Lithium", "VidStorm·Hydrogen", "VidStorm·Boron",
     * etc. — each element-named sub-server is a DIFFERENT upstream source.
     * The old implementation stripped the "·Lithium" suffix and collapsed
     * every sub-server to a single "VidStorm" key, so if ONE sub-server
     * (e.g. Boron) failed once, recordFailure marked "VidStorm" bad and
     * knownBadProviders then excluded ALL VidStorm sub-servers — including
     * Lithium, which the user confirmed is "the main one I've seen work".
     *
     * Now each sub-server is tracked independently: "VidStorm·Lithium" stays
     * "VidStorm·Lithium", so a Boron failure never touches Lithium.
     */
    private fun canonical(provider: String): String? {
        val n = provider.trim()
        if (n.isBlank()) return null
        // The base provider name is everything before the "·" separator.
        val base = n.substringBefore("·").trim()
        return if (base in RACE_PROVIDERS) n else null
    }

    /** The set of race providers (used by the player to seed its exclusion set). */
    fun raceProviders(): Set<String> = RACE_PROVIDERS

    @Volatile private var savePending = false
    private fun scheduleSave() {
        savePending = true
        // Coalesce rapid writes; persist on a background coroutine.
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            saveMutex.withLock {
                if (!savePending) return@withLock
                savePending = false
                persistNow()
            }
        }
    }

    private fun persistNow() {
        try {
            val c = ctx() ?: return
            val root = JSONObject()
            for ((k, v) in store) root.put(k, v)
            val f = File(c.cacheDir, FILE_NAME)
            f.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "persist failed: ${e.message}")
        }
    }

    /** Force a flush (e.g. on app background). */
    suspend fun flush() = saveMutex.withLock { persistNow() }
}
