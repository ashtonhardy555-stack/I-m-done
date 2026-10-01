package com.ashtonhardy.piratesfilmcove.data.engine

import android.content.Context
import android.util.Log
import com.ashtonhardy.piratesfilmcove.data.server.CinejoyExtractor
import com.ashtonhardy.piratesfilmcove.data.server.KissKhExtractor
import com.ashtonhardy.piratesfilmcove.data.server.LookMovieHeadlessExtractor
import com.ashtonhardy.piratesfilmcove.data.server.MeowTvExtractor
import com.ashtonhardy.piratesfilmcove.data.server.NoTorrentExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VidFastExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VidLinkExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VidStormExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VidSyncExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VidSrcExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VideasyExtractor
import com.ashtonhardy.piratesfilmcove.data.server.VixSrcExtractor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
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
        /** TMDB id — needed by the TMDB-keyed headless addons (VidLink,
         *  VidFast, Cinejoy, MeowTV, Videasy, VixSrc, VidSync, VidStorm,
         *  KissKH, NoTorrent) to build their request URLs. May be 0 if the
         *  caller only has title/year (e.g. a pre-resolve from a browse
         *  screen that doesn't have the TMDB id handy). LookMovieTomb (the
         *  primary addon) resolves by title and doesn't need it. */
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

    // -- Generic TMDB-id addon adapter --------------------------------------
    // Every headless extractor in data/server shares the exact same shape:
    //   suspend fun extract(tmdbId, contentType, season, episode): Result
    // with Result.Stream(url, headers, providerName) / Result.Error(message).
    // This adapter turns ANY such extractor into a KodiEngine [Addon], so we
    // can register as many working sources as we like -- each one runs the
    // source's own site/API headlessly (pure OkHttp, no WebView, no Kodi
    // runtime), exactly like the reference LookMovieTomb addon.
    private fun tmdbAddon(
        addonId: String,
        label: String,
        fn: suspend (tmdbId: Int, contentType: String, season: Int, episode: Int) -> AddonResult
    ): Addon = object : Addon {
        override val id: String = addonId
        override suspend fun resolve(req: ResolveRequest): AddonResult {
            if (req.tmdbId <= 0) return AddonResult.Error("$label: no tmdbId")
            return try {
                fn(req.tmdbId, req.contentType, req.season, req.episode)
            } catch (e: Exception) {
                AddonResult.Error("$label: ${e.message}")
            }
        }
    }

    // ── Working headless addons (each pulls from its own source, no WebView) ──
    // These are the live sources that resolve real, playable streams. They are
    // consulted in the order listed in `addons` below.

    private val vidLinkAddon = tmdbAddon("vidlink", "VidLink") { id, ct, s, e ->
        when (val r = VidLinkExtractor.extract(id, ct, s, e)) {
            is VidLinkExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VidLink" })
            is VidLinkExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val vidFastAddon = tmdbAddon("vidfast", "VidFast") { id, ct, s, e ->
        when (val r = VidFastExtractor.extract(id, ct, s, e)) {
            is VidFastExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VidFast" })
            is VidFastExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val meowTvAddon = tmdbAddon("meowtv", "MeowTV") { id, ct, s, e ->
        when (val r = MeowTvExtractor.extract(id, ct, s, e)) {
            is MeowTvExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "MeowTV" })
            is MeowTvExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val videasyAddon = tmdbAddon("videasy", "Videasy") { id, ct, s, e ->
        when (val r = VideasyExtractor.extract(id, ct, s, e)) {
            is VideasyExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "Videasy" })
            is VideasyExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val vixSrcAddon = tmdbAddon("vixsrc", "VixSrc") { id, ct, s, e ->
        when (val r = VixSrcExtractor.extract(id, ct, s, e)) {
            is VixSrcExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VixSrc" })
            is VixSrcExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val vidSyncAddon = tmdbAddon("vidsync", "VidSync") { id, ct, s, e ->
        when (val r = VidSyncExtractor.extract(id, ct, s, e)) {
            is VidSyncExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VidSync" })
            is VidSyncExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val vidStormAddon = tmdbAddon("vidstorm", "VidStorm") { id, ct, s, e ->
        when (val r = VidStormExtractor.extract(id, ct, s, e)) {
            is VidStormExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VidStorm" })
            is VidStormExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val kissKhAddon = tmdbAddon("kisskh", "KissKH") { id, ct, s, e ->
        when (val r = KissKhExtractor.extract(id, ct, s, e)) {
            is KissKhExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "KissKH" })
            is KissKhExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    private val noTorrentAddon = tmdbAddon("notorrent", "NoTorrent") { id, ct, s, e ->
        when (val r = NoTorrentExtractor.extract(id, ct, s, e)) {
            is NoTorrentExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "NoTorrent" })
            is NoTorrentExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    // -- Cinejoy addon (TMDB-id direct API, verified full-length up to 4K) --
    private val cinejoyAddon = tmdbAddon("cinejoy", "Cinejoy") { id, ct, s, e ->
        when (val r = CinejoyExtractor.extract(id, ct, s, e)) {
            is CinejoyExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "Cinejoy" })
            is CinejoyExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    // VidSrc (vidsrc.me) -- a classic website-scraper addon: it loads the
    // site's /embed page headlessly and pulls the stream out of the markup,
    // exactly the same "run the site in the engine" approach as LookMovieTomb.
    private val vidSrcAddon = tmdbAddon("vidsrc", "VidSrc") { id, ct, s, e ->
        when (val r = VidSrcExtractor.extract(id, ct, s, e)) {
            is VidSrcExtractor.Result.Stream -> AddonResult.Stream(r.url, r.headers, r.providerName.ifBlank { "VidSrc" })
            is VidSrcExtractor.Result.Error -> AddonResult.Error(r.message)
        }
    }

    /** Addons consulted, in priority order. LookMovieTomb first (the reference
     *  headless addon), then the other working headless sources. Every one of
     *  these runs in-process with pure OkHttp -- no WebView, no Kodi runtime. */
    private val addons = mutableListOf<Addon>(
        lookmovieAddon,
        vidLinkAddon,
        vidFastAddon,
        cinejoyAddon,
        meowTvAddon,
        videasyAddon,
        vixSrcAddon,
        vidSyncAddon,
        vidStormAddon,
        kissKhAddon,
        noTorrentAddon,
        vidSrcAddon
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
                Log.d(TAG, "background resolve (racing ${addons.size} headless addons): '${req.title}' S${req.season}E${req.episode} (movie=${req.isMovie})")
                // Race every addon in parallel; the FIRST playable Stream wins
                // and the rest are cancelled. This is the "addon runs headlessly
                // in the engine" behaviour, but it never waits on a slow addon
                // before a fast one can answer -- so playback starts quickly.
                val winner = raceAddons(req)
                if (winner != null) {
                    cache.put(
                        title = req.title,
                        isMovie = req.isMovie,
                        season = req.season,
                        episode = req.episode,
                        url = winner.url,
                        headers = winner.headers,
                        providerName = winner.providerName
                    )
                    Log.i(TAG, "\u2705 resolved '${req.title}' via ${winner.providerName}: ${winner.url}")
                    if (emitOnComplete) {
                        _resolved.tryEmit(
                            ResolvedStream(winner.url, winner.headers, winner.providerName, fromCache = false)
                        )
                    }
                } else {
                    Log.d(TAG, "all addons missed for '${req.title}'")
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

    /**
     * Run every addon concurrently and return the first [AddonResult.Stream].
     * Losers are cancelled the moment a winner is found. Returns null if no
     * addon produced a stream within [RACE_TIMEOUT_MS].
     *
     * This is what makes the engine fast: instead of trying addons one-by-one
     * (waiting on each timeout), all working sources are queried at once and
     * the fastest one wins.
     */
    private suspend fun raceAddons(req: ResolveRequest): AddonResult.Stream? = coroutineScope {
        val first = CompletableDeferred<AddonResult.Stream>()
        val jobs = addons.map { addon ->
            launch {
                val r = try {
                    addon.resolve(req)
                } catch (e: Exception) {
                    AddonResult.Error("${addon.id}: ${e.message}")
                }
                when (r) {
                    is AddonResult.Stream -> first.complete(r)
                    is AddonResult.Error -> Log.d(TAG, "addon ${addon.id} miss for '${req.title}': ${r.message}")
                }
            }
        }
        try {
            withTimeoutOrNull(RACE_TIMEOUT_MS) { first.await() }
        } finally {
            jobs.forEach { runCatching { it.cancel() } }
        }
    }

    companion object {
        /** Max concurrent background pre-resolves. Keeps the engine polite. */
        private const val MAX_CONCURRENT_PRE = 3

        /** How long the addon race may run before giving up (no winner). */
        private const val RACE_TIMEOUT_MS = 12_000L

        @Volatile private var INSTANCE: KodiEngine? = null

        fun init(context: Context): KodiEngine {
            // Ensure the cache is ready first.
            StreamCache.init(context)
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: KodiEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): KodiEngine =
            INSTANCE ?: error("KodiEngine.init() must be called from PiratesfilmCoveApplication.onCreate()")
    }
}
