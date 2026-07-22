package com.freeflix.app.data.engine

import android.content.Context
import android.util.Log
import com.freeflix.app.data.server.AnnasCinemaExtractor
import com.freeflix.app.data.server.LookMovieHeadlessExtractor
import com.freeflix.app.data.server.NovaStreamExtractor
import com.freeflix.app.data.server.NuvioStreamsExtractor
import com.freeflix.app.data.server.SmashStreamsExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * KodiEngine \u2014 the user's "Kodi-like engine running in the background".
 *
 * This is a headless, **no-WebView, no-Kodi-runtime** engine that runs the
 * `plugin.video.lookmovietomb` addon flow (search \u2192 storage \u2192 security
 * API \u2192 `.m3u8`) in a background coroutine scope. It is started by
 * [KodiEngineService] (a foreground Service) and lives for the lifetime of the
 * app process, so it can:
 *
 *  \u2022 **resolve on demand** \u2014 when PlayerActivity asks for a stream, the
 *    engine returns a cached one instantly (if fresh) or kicks off a fresh
 *    resolve in the background and delivers the result.
 *  \u2022 **pre-resolve** \u2014 browse/home screens can ask the engine to warm up
 *    the next likely title so playback starts instantly when the user hits
 *    play. This is the "Kodi addon running in the background" behaviour: it is
 *    always working ahead of the user.
 *  \u2022 **cache** \u2014 resolved streams go into [StreamCache] (memory + disk) so
 *    a replay or a quick back-and-forth never re-hits the network.
 *
 * The actual extraction is delegated to the existing, proven
 * [LookMovieHeadlessExtractor] \u2014 a pure-OkHttp port of the addon \u2014 so we do
 * NOT duplicate the addon logic or introduce a WebView. The engine is the
 * *orchestration* layer; the extractor is the *addon* layer. Other addon
 * flows can be plugged in later by implementing the [Addon] interface.
 *
 * ## Concurrency
 * Resolves are de-duplicated: if two callers ask for the same title at once,
 * only one network resolve runs and both observe the result. Pre-resolve
 * jobs are best-effort and never block an on-demand resolve.
 */
class KodiEngine private constructor(private val context: Context) {

    private val TAG = "KodiEngine"

    /** A pluggable addon flow. LookMovie is the first/only one for now. */
    interface Addon {
        val id: String
        suspend fun resolve(req: ResolveRequest): AddonResult
    }

    /** What the engine resolves. */
    data class ResolveRequest(
        val title: String,
        val year: String?,
        val isMovie: Boolean,
        val season: Int,
        val episode: Int,
        /** TMDB id — needed by Stremio-style addons (NoTorrent, SmashStreams,
         *  NuvioStreams, AnnasCinema, NovaStream) to resolve IMDb ids. May be
         *  0 if the caller only has title/year (e.g. a pre-resolve from a
         *  browse screen that doesn't have the TMDB id handy). LookMovie
         *  (the primary addon) doesn't need it. */
        val tmdbId: Int = 0
    ) {
        /** Convenience: "movie" or "tv" for Stremio addon calls. */
        val contentType: String get() = if (isMovie) "movie" else "tv"
    }

    /** A successfully resolved stream. */
    data class ResolvedStream(
        val url: String,
        val headers: Map<String, String>,
        val providerName: String,
        val fromCache: Boolean
    )

    /** Engine-level result for a resolve. */
    sealed class Result {
        data class Stream(val stream: ResolvedStream) : Result()
        data class Error(val message: String) : Result()
        /** No cached stream yet; a background resolve has been started. The
         *  caller should call [resolve] again (or subscribe) shortly. */
        data class Pending(val job: Job) : Result()
    }

    sealed class AddonResult {
        data class Stream(
            val url: String,
            val headers: Map<String, String>,
            val providerName: String
        ) : AddonResult()
        data class Error(val message: String) : AddonResult()
    }

    // \u2500\u2500 The LookMovie addon adapter \u2014 wraps the existing extractor \u2500\u2500
    private val lookmovieAddon = object : Addon {
        override val id = "lookmovietomb"
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            val r = LookMovieHeadlessExtractor.extract(
                title = req.title,
                year = req.year,
                isMovie = req.isMovie,
                season = req.season,
                episode = req.episode
            )
            return when (r) {
                is LookMovieHeadlessExtractor.Result.Stream -> AddonResult.Stream(
                    url = r.url,
                    headers = r.headers,
                    providerName = r.providerName.ifBlank { "LookMovie" }
                )
                is LookMovieHeadlessExtractor.Result.Error -> AddonResult.Error(r.message)
            }
        }
    }

    // -- Stremio-style addon adapters --
    // These use the TMDB id (from ResolveRequest.tmdbId) to resolve a direct
    // playable stream URL via their respective Stremio addon APIs. They are
    // headless (pure OkHttp, no WebView) -- the same approach as
    // LookMovieHeadlessExtractor. They only run when tmdbId is available.

    private val smashStreamsAddon = object : Addon {
        override val id = "smashstreams"
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            if (req.tmdbId <= 0) return AddonResult.Error("SmashStreams: no tmdbId")
            val r = SmashStreamsExtractor.extract(req.tmdbId, req.contentType, req.season, req.episode)
            return when (r) {
                is SmashStreamsExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "SmashStreams" })
                is SmashStreamsExtractor.Result.Error -> AddonResult.Error(r.message)
            }
        }
    }

    private val nuvioStreamsAddon = object : Addon {
        override val id = "nuviostreams"
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            if (req.tmdbId <= 0) return AddonResult.Error("NuvioStreams: no tmdbId")
            val r = NuvioStreamsExtractor.extract(req.tmdbId, req.contentType, req.season, req.episode)
            return when (r) {
                is NuvioStreamsExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "NuvioStreams" })
                is NuvioStreamsExtractor.Result.Error -> AddonResult.Error(r.message)
            }
        }
    }

    private val annasCinemaAddon = object : Addon {
        override val id = "annascinema"
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            if (req.tmdbId <= 0) return AddonResult.Error("AnnasCinema: no tmdbId")
            val r = AnnasCinemaExtractor.extract(req.tmdbId, req.contentType, req.season, req.episode)
            return when (r) {
                is AnnasCinemaExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "AnnasCinema" })
                is AnnasCinemaExtractor.Result.Error -> AddonResult.Error(r.message)
            }
        }
    }

    private val novaStreamAddon = object : Addon {
        override val id = "novastream"
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            if (req.tmdbId <= 0) return AddonResult.Error("NovaStream: no tmdbId")
            val r = NovaStreamExtractor.extract(req.tmdbId, req.contentType, req.season, req.episode)
            return when (r) {
                is NovaStreamExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "NovaStream" })
                is NovaStreamExtractor.Result.Error -> AddonResult.Error(r.message)
            }
        }
    }

    /** Addons consulted, in priority order. LookMovie first (the reference
     *  headless extractor and the engine's primary addon), then the
     *  Stremio-style addons that need TMDB ids. */
    private val addons = mutableListOf<Addon>(
        lookmovieAddon,
        smashStreamsAddon,
        nuvioStreamsAddon,
        annasCinemaAddon,
        novaStreamAddon
    )

    // \u2500\u2500 scope \u2500\u2500
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = StreamCache.get()

    /** Per-key single-flight: in-flight resolve jobs so we don't double-fetch. */
    private val inFlight = ConcurrentHashMap<String, Job>()
    private val inFlightMutex = Mutex()

    /** Emits a [ResolvedStream] whenever a background (pre-)resolve completes.
     *  PlayerActivity can collect this to auto-start playback if it's waiting. */
    private val _resolved = MutableSharedFlow<ResolvedStream>(extraBufferCapacity = 16)
    val resolved: SharedFlow<ResolvedStream> = _resolved.asSharedFlow()

    @Volatile var isRunning = false
        private set

    /** Start the engine. Idempotent. */
    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Kodi-like engine started (headless, no WebView). Addons: ${addons.map { it.id }}")
    }

    /** Stop the engine and cancel all in-flight resolves. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        scope.cancel()
        inFlight.values.forEach { runCatching { it.cancel() } }
        inFlight.clear()
        Log.i(TAG, "engine stopped")
    }

    // \u2500\u2500 Public API \u2500\u2500

    /**
     * Resolve a stream for [req]. If a fresh cached entry exists it is returned
     * synchronously (fromCache=true). Otherwise a background resolve is
     * started and [Result.Pending] is returned; the caller may await the job
     * via [awaitResolve] or subscribe to [resolved].
     *
     * This is what PlayerActivity calls first \u2014 a cache hit means instant
     * playback with zero extractor race.
     */
    suspend fun resolve(req: ResolveRequest): Result {
        // 1. Cache hit?
        cache.get(req.title, req.isMovie, req.season, req.episode)?.let { e ->
            Log.d(TAG, "cache HIT for '${req.title}' via ${e.providerName}")
            return Result.Stream(
                ResolvedStream(e.url, e.headers, e.providerName, fromCache = true)
            )
        }
        // 2. On-demand resolve (single-flight).
        val job = startResolveIfNeeded(req, emitOnComplete = true)
        return Result.Pending(job)
    }

    /**
     * Resolve and wait for the result (with a timeout). Use this when the
     * caller wants the actual stream, not just the job \u2014 e.g. PlayerActivity's
     * "ask the engine first, with a short budget" step before the parallel
     * extractor race.
     */
    suspend fun awaitResolve(
        req: ResolveRequest,
        timeoutMillis: Long = 7_000L
    ): ResolvedStream? {
        cache.get(req.title, req.isMovie, req.season, req.episode)?.let { e ->
            return ResolvedStream(e.url, e.headers, e.providerName, fromCache = true)
        }
        val job = startResolveIfNeeded(req, emitOnComplete = true)
        return withTimeoutOrNull(timeoutMillis) {
            job.join()
            cache.get(req.title, req.isMovie, req.season, req.episode)?.let { e ->
                ResolvedStream(e.url, e.headers, e.providerName, fromCache = false)
            }
        }
    }

    /**
     * Pre-resolve a title in the background \u2014 the "Kodi addon working ahead"
     * behaviour. Browse/home screens call this for the next likely title
     * (e.g. the focused row item). Fire-and-forget; never throws.
     */
    fun preResolve(req: ResolveRequest) {
        if (!isRunning) return
        if (cache.get(req.title, req.isMovie, req.season, req.episode) != null) return
        startResolveIfNeeded(req, emitOnComplete = true)
    }

    /**
     * Pre-resolve several titles at once (e.g. the visible row). Best-effort,
     * bounded concurrency. Used by Home/Browse to warm the engine.
     */
    fun preResolveAll(requests: List<ResolveRequest>) {
        if (!isRunning || requests.isEmpty()) return
        scope.launch {
            // Resolve them concurrently but cap the fan-out to avoid hammering
            // LookMovie (and to be a polite background citizen).
            requests.distinctBy { cacheKey(it) }.chunked(MAX_CONCURRENT_PRE).forEach { batch ->
                batch.map { req ->
                    async {
                        if (cache.get(req.title, req.isMovie, req.season, req.episode) == null) {
                            startResolveIfNeeded(req, emitOnComplete = true).join()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    /** Drop a cached entry \u2014 e.g. the player reported the URL 404'd. */
    fun invalidate(req: ResolveRequest) {
        cache.invalidate(req.title, req.isMovie, req.season, req.episode)
    }

    /** Number of streams currently cached \u2014 surfaced in the service notification. */
    fun cacheSize(): Int = cache.size()

    // \u2500\u2500 internals \u2500\u2500

    private fun cacheKey(req: ResolveRequest): String =
        cache.key(req.title, req.isMovie, req.season, req.episode)

    /**
     * Returns the in-flight job for [req], starting one if none exists. The
     * job runs the addon flow (LookMovie), stores the result in the cache on
     * success, and (optionally) emits to [resolved].
     */
    private fun startResolveIfNeeded(req: ResolveRequest, emitOnComplete: Boolean): Job {
        val k = cacheKey(req)
        inFlight[k]?.let { if (it.isActive) return it }
        val job = scope.launch {
            try {
                Log.d(TAG, "background resolve: '${req.title}' S${req.season}E${req.episode} (movie=${req.isMovie})")
                // Run addons in order; first Stream wins. LookMovie is first
                // (the primary headless addon), then the Stremio-style addons.
                var lastErr: String? = null
                for (addon in addons) {
                    val r = addon.resolve(req)
                    when (r) {
                        is AddonResult.Stream -> {
                            cache.put(
                                title = req.title,
                                isMovie = req.isMovie,
                                season = req.season,
                                episode = req.episode,
                                url = r.url,
                                headers = r.headers,
                                providerName = r.providerName
                            )
                            Log.i(TAG, "\u2705 resolved '${req.title}' via ${addon.id}: ${r.url}")
                            if (emitOnComplete) {
                                _resolved.tryEmit(
                                    ResolvedStream(r.url, r.headers, r.providerName, fromCache = false)
                                )
                            }
                            return@launch
                        }
                        is AddonResult.Error -> {
                            lastErr = r.message
                            Log.d(TAG, "addon ${addon.id} miss for '${req.title}': ${r.message}")
                        }
                    }
                }
                if (lastErr != null) {
                    Log.d(TAG, "all addons missed for '${req.title}': $lastErr")
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolve failed for '${req.title}': ${e.message}")
            } finally {
                inFlight.remove(k)
            }
        }
        inFlight[k] = job
        return job
    }

    companion object {
        /** Max concurrent background pre-resolves. Keeps the engine polite. */
        private const val MAX_CONCURRENT_PRE = 3

        @Volatile private var INSTANCE: KodiEngine? = null

        fun init(context: Context): KodiEngine {
            // Ensure the cache is ready first.
            StreamCache.init(context)
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KodiEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): KodiEngine =
            INSTANCE ?: error("KodiEngine.init() must be called from FreeflixApplication.onCreate()")
    }
}
