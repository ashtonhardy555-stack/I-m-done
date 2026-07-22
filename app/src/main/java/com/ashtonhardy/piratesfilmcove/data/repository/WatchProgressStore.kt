package com.ashtonhardy.piratesfilmcove.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ashtonhardy.piratesfilmcove.data.model.WatchProgress
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe persistent store for [WatchProgress] records.
 *
 * Backed by a single SharedPreferences file (`watch_progress`) that holds one
 * JSON string (`items`) — a serialized list of every watch-progress record.
 * Gson (already on the classpath via the Retrofit converter) handles the
 * (de)serialization.
 *
 * All public methods are safe to call from any thread: a ReentrantReadWriteLock
 * guards the in-memory cache, and SharedPreferences commits are synchronous
 * (`commit()`) so a process death mid-write never loses the final flush from
 * the player Activity's onDestroy.
 *
 * Usage:
 *   WatchProgressStore.init(context)          // once at app start
 *   WatchProgressStore.upsert(progress)       // while playing
 *   WatchProgressStore.markCompleted(key)     // on STATE_ENDED
 *   WatchProgressStore.activeItems()          // continue-watching row
 *   WatchProgressStore.allWatchedIds()        // for recommendations (exclude)
 */
object WatchProgressStore {

    private const val TAG = "WatchProgressStore"
    private const val PREFS_NAME = "watch_progress"
    private const val KEY_ITEMS = "items"

    private val gson = Gson()
    private val lock = ReentrantReadWriteLock()

    // In-memory mirror of the persisted list so reads don't hit disk + parse
    // JSON on every call. Mutated only under the write lock and flushed to
    // SharedPreferences immediately after every mutation.
    private var cache: MutableList<WatchProgress> = mutableListOf()

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Must be called once at app start (e.g. from PiratesfilmCoveApplication or the
     * first Activity onCreate) so the store has a Context to read/write with.
     * Safe to call repeatedly; only the first call loads from disk.
     */
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        loadFromDisk(sp)
    }

    private fun loadFromDisk(sp: SharedPreferences) {
        lock.write {
            val json = sp.getString(KEY_ITEMS, null)
            cache = if (json.isNullOrBlank()) {
                mutableListOf()
            } else {
                runCatching {
                    val type = object : TypeToken<List<WatchProgress>>() {}.type
                    gson.fromJson<List<WatchProgress>>(json, type).toMutableList()
                }.getOrElse {
                    Log.e(TAG, "Failed to parse watch progress JSON", it)
                    mutableListOf()
                }
            }
        }
    }

    private fun persist() {
        val sp = prefs ?: return
        val snapshot = lock.read { cache.toList() }
        val json = gson.toJson(snapshot)
        // commit() (synchronous) so the final flush from onDestroy is durable
        // before the process dies.
        sp.edit().putString(KEY_ITEMS, json).commit()
    }

    /**
     * Inserts or updates the record matching [progress.key]. If a record with
     * the same key exists it is replaced (position/duration grow); otherwise
     * the new record is appended. The record's [WatchProgress.timestamp] is
     * bumped to "now" so the continue-watching row sorts by most-recent.
     */
    fun upsert(progress: WatchProgress) {
        if (prefs == null) {
            Log.w(TAG, "upsert called before init — ignoring")
            return
        }
        lock.write {
            val stamped = progress.copy(timestamp = System.currentTimeMillis())
            val idx = cache.indexOfFirst { it.key == stamped.key }
            if (idx >= 0) cache[idx] = stamped else cache.add(stamped)
        }
        persist()
    }

    /**
     * Marks the record with [key] as completed (STATE_ENDED). A completed
     * record is kept on disk (so recommendations can still learn from it) but
     * [activeItems] excludes it, which removes it from the Continue Watching
     * row — exactly the behaviour the user asked for: "removes that movie or
     * show once you fully finish it".
     */
    fun markCompleted(key: String) {
        if (prefs == null) return
        lock.write {
            val idx = cache.indexOfFirst { it.key == key }
            if (idx >= 0) {
                cache[idx] = cache[idx].copy(
                    completed = true,
                    positionMs = cache[idx].durationMs.coerceAtLeast(cache[idx].positionMs),
                    timestamp = System.currentTimeMillis()
                )
            }
        }
        persist()
    }

    /**
     * All records that should appear in the Continue Watching row: incomplete
     * titles the user hasn't basically finished, sorted most-recently-watched
     * first. This is the source of truth for HomeViewModel's continue row.
     */
    fun activeItems(): List<WatchProgress> =
        lock.read { cache.filter { it.isActive }.sortedByDescending { it.timestamp } }

    /**
     * Every record (completed or not), most-recent first. Used by the
     * recommendation engine to learn the user's tastes from their full watch
     * history, not just the unfinished titles.
     */
    fun allItems(): List<WatchProgress> =
        lock.read { cache.sortedByDescending { it.timestamp } }

    /**
     * The set of TMDB ids the user has ever watched (movie or TV). Used to
     * exclude already-seen titles from the Recommended row so we never
     * recommend something the user just finished.
     */
    fun allWatchedIds(): Set<Int> =
        lock.read { cache.map { it.tmdbId }.toSet() }

    /**
     * Fetches a single record by key (used by the player to look up a resume
     * position, though currently the resume position is passed via Intent).
     */
    fun get(key: String): WatchProgress? =
        lock.read { cache.firstOrNull { it.key == key } }

    /**
     * Removes completed records older than [maxAgeMs] to keep the store from
     * growing unbounded over months of use. Called opportunistically. Default
     * keeps completed records for 30 days (enough for recommendations to
     * learn from recent watches) then prunes.
     */
    fun pruneOldCompleted(maxAgeMs: Long = 30L * 24 * 60 * 60 * 1000) {
        if (prefs == null) return
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var changed = false
        lock.write {
            val before = cache.size
            cache = cache.filterNot { it.completed && it.timestamp < cutoff }.toMutableList()
            changed = cache.size != before
        }
        if (changed) persist()
    }
}
