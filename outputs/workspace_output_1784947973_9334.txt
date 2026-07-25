package com.ashtonhardy.piratesfilmcove.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.model.WatchProgress
import com.ashtonhardy.piratesfilmcove.data.repository.ContentRepository
import com.ashtonhardy.piratesfilmcove.data.repository.WatchProgressStore
import com.ashtonhardy.piratesfilmcove.data.server.StreamAvailabilityChecker
import com.ashtonhardy.piratesfilmcove.ui.browse.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HomeViewModel : ViewModel() {

    private val repo = ContentRepository()
    private val loadMutex = Mutex()

    private val _heroItems = MutableStateFlow<List<TmdbItem>>(emptyList())
    val heroItems: StateFlow<List<TmdbItem>> = _heroItems

    private val _trending = MutableStateFlow<List<TmdbItem>>(emptyList())
    val trending: StateFlow<List<TmdbItem>> = _trending

    private val _nowPlaying = MutableStateFlow<List<TmdbItem>>(emptyList())
    val nowPlaying: StateFlow<List<TmdbItem>> = _nowPlaying

    private val _popularTV = MutableStateFlow<List<TmdbItem>>(emptyList())
    val popularTV: StateFlow<List<TmdbItem>> = _popularTV

    private val _topRated = MutableStateFlow<List<TmdbItem>>(emptyList())
    val topRated: StateFlow<List<TmdbItem>> = _topRated

    private val _popularMovies = MutableStateFlow<List<TmdbItem>>(emptyList())
    val popularMovies: StateFlow<List<TmdbItem>> = _popularMovies

    // ── Continue Watching + Recommended ──────────────────────────── //
    // continueWatching: TmdbItem-shaped view of the user's unfinished
    // titles (from WatchProgressStore.activeItems), most-recent first. Each
    // item carries its resume position + progress fraction via a side map so
    // the Continue Watching card can draw the red progress bar.
    private val _continueWatching = MutableStateFlow<List<TmdbItem>>(emptyList())
    val continueWatching: StateFlow<List<TmdbItem>> = _continueWatching

    // Maps "contentType_tmdbId[_S_s_E_e]" → WatchProgress so the UI can look
    // up the progress fraction + resume position for each card.
    private val _progressMap = MutableStateFlow<Map<String, WatchProgress>>(emptyMap())
    val progressMap: StateFlow<Map<String, WatchProgress>> = _progressMap

    // recommended: titles discovered from the genres of what the user has
    // watched, excluding already-watched titles. Empty until the user has a
    // watch history.
    private val _recommended = MutableStateFlow<List<TmdbItem>>(emptyList())
    val recommended: StateFlow<List<TmdbItem>> = _recommended

    private var trendingPage = 1
    private var nowPlayingPage = 1
    private var popularTVPage = 1
    private var topRatedPage = 1
    private var popularMoviesPage = 1
    private var recommendedPage = 1
    // The genre query used by the Recommended row so loadMoreRecommended()
    // can fetch the next page of the same discover feed.
    private var recommendedGenreQuery: String? = null

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** True while filtering a row down to only-streamable titles. */
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

    // ── canLoadMore flags (per row) ───────────────────────────────────── //
    // Each flag is true while there are more pages available to load via the
    // row's loadMoreX() function. Set true on initial load (TMDB returns ~20
    // items per page, so page 1 always implies more), then set false once a
    // loadMore() returns fewer than a full page or only duplicates — that
    // means we've hit the end of the catalog and the "Load More" button
    // should disappear.
    private val _canLoadMoreTrending = MutableStateFlow(true)
    val canLoadMoreTrending: StateFlow<Boolean> = _canLoadMoreTrending

    private val _canLoadMoreNowPlaying = MutableStateFlow(true)
    val canLoadMoreNowPlaying: StateFlow<Boolean> = _canLoadMoreNowPlaying

    private val _canLoadMorePopularTV = MutableStateFlow(true)
    val canLoadMorePopularTV: StateFlow<Boolean> = _canLoadMorePopularTV

    private val _canLoadMoreTopRated = MutableStateFlow(true)
    val canLoadMoreTopRated: StateFlow<Boolean> = _canLoadMoreTopRated

    private val _canLoadMorePopularMovies = MutableStateFlow(true)
    val canLoadMorePopularMovies: StateFlow<Boolean> = _canLoadMorePopularMovies

    private val _canLoadMoreRecommended = MutableStateFlow(false)
    val canLoadMoreRecommended: StateFlow<Boolean> = _canLoadMoreRecommended

    // TMDB returns ~20 results per page. Used to detect end-of-catalog.
    private val pageSize = 20

    init { loadAll() }

    private fun loadAll() {
        // Continue watching is local (no network) so it loads instantly and
        // appears at the top of the Home screen before anything else.
        loadContinueWatching()
        viewModelScope.launch {
            val trending = repo.getTrending()
            _heroItems.value = trending.filter { it.backdropPath != null }.take(8)
            _trending.value = trending.filter { it.isMovie }.take(15)
            // Refine the trending row to only-streamable titles.
            refineRow(_trending)
        }
        viewModelScope.launch {
            _nowPlaying.value = repo.getNowPlaying()
            refineRow(_nowPlaying)
        }
        viewModelScope.launch {
            _popularTV.value = repo.getPopularTV()
            refineRow(_popularTV)
        }
        viewModelScope.launch {
            _topRated.value = repo.getTopRatedMovies()
            refineRow(_topRated)
        }
        viewModelScope.launch {
            _popularMovies.value = repo.getPopularMovies()
            refineRow(_popularMovies)
        }
        // Recommendations depend on the watch history (local) + a TMDB
        // discover call, so it loads after continue watching is available.
        viewModelScope.launch {
            loadRecommended()
        }
    }

    /**
     * Populates the Continue Watching row from the persistent watch-progress
     * store. Each [WatchProgress] is converted to a [TmdbItem] so the existing
     * ContentRow / card rendering works unchanged; the progress fraction +
     * resume position are exposed via [progressMap] so the card can draw the
     * red progress bar and the click handler can resume from the saved spot.
     */
    private fun loadContinueWatching() {
        val active = WatchProgressStore.activeItems()
        _progressMap.value = active.associateBy { it.key }
        _continueWatching.value = active.map { it.toTmdbItem() }
    }

    /**
     * Refreshes Continue Watching from the store. Called when the Home screen
     * resumes (the user may have just watched something → the row should
     * update without a full reload).
     */
    fun refreshContinueWatching() {
        loadContinueWatching()
        viewModelScope.launch { loadRecommended() }
    }

    /**
     * Builds the "Recommended for You" row from the user's watch history.
     *
     * Strategy:
     *  1. Take every watched title (completed or not) from WatchProgressStore.
     *  2. For each, fetch its TMDB detail to get genre ids (the lightweight
     *     TmdbItem from the store doesn't carry genres).
     *  3. Aggregate the genres, weighted by recency (most-recent watches
     *     contribute more), and pick the top genres.
     *  4. Discover movies + TV scoped to those genres, exclude already-watched
     *     ids, filter to streamable titles, and merge into one row.
     *
     * If the user has no watch history yet the row stays empty (it only
     * appears once they've watched something — matching "recommends movies
     * based off of what you watch").
     */
    private suspend fun loadRecommended() {
        val watched = WatchProgressStore.allItems()
        if (watched.isEmpty()) {
            _recommended.value = emptyList()
            return
        }
        val excludeIds = WatchProgressStore.allWatchedIds()

        // Aggregate genre ids from watched titles, weighting by recency so a
        // genre the user watches a lot lately ranks higher. We fetch detail
        // for each watched title to get its genre ids (the stored WatchProgress
        // doesn't carry them).
        val genreWeights = mutableMapOf<Int, Int>()
        watched.take(12).forEachIndexed { idx, wp ->
            // Fetch detail to get genre ids (the stored WatchProgress doesn't
            // carry them). Each branch resolves genreIds on its own concrete
            // type so the compiler doesn't have to unify the two detail types.
            val genres: List<Int> = if (wp.contentType.equals("tv", ignoreCase = true)) {
                repo.getTvShowDetail(wp.tmdbId)?.genreIds ?: emptyList()
            } else {
                repo.getMovieDetail(wp.tmdbId)?.genreIds ?: emptyList()
            }
            // More-recent watches (lower idx) weigh more.
            val weight = (12 - idx).coerceAtLeast(1)
            genres.forEach { g -> genreWeights[g] = (genreWeights[g] ?: 0) + weight }
        }
        val topGenres = genreWeights.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        if (topGenres.isEmpty()) {
            _recommended.value = emptyList()
            return
        }

        // Discover movies + TV for the top genres, excluding already-watched.
        val genreQuery = topGenres.joinToString(",")
        recommendedGenreQuery = genreQuery
        recommendedPage = 1
        val ctx = appContext()
        val movies = runCatching {
            repo.discover("movie", genreId = genreQuery, sortBy = "popularity.desc")
                .filter { it.id !in excludeIds }
        }.getOrDefault(emptyList())
        val tv = runCatching {
            repo.discover("tv", genreId = genreQuery, sortBy = "popularity.desc")
                .filter { it.id !in excludeIds }
        }.getOrDefault(emptyList())

        // Merge movies + TV, interleave so the row is a mix of both, take ~18.
        val merged = mutableListOf<TmdbItem>()
        val maxLen = maxOf(movies.size, tv.size)
        for (i in 0 until maxLen) {
            if (i < movies.size) merged.add(movies[i])
            if (i < tv.size) merged.add(tv[i])
            if (merged.size >= 18) break
        }

        // Filter to only streamable titles so the row never surfaces a title
        // that can't actually play.
        val filtered = if (ctx != null) {
            runCatching { StreamAvailabilityChecker.filterAvailable(ctx, merged) }
                .getOrDefault(merged)
        } else merged

        _recommended.value = filtered.take(18)
        // There's more to load if either the movie or TV discover returned a
        // full page — the merged row was capped at 18, but the discover feeds
        // each return ~20 so there are almost certainly more pages.
        _canLoadMoreRecommended.value = movies.size >= pageSize || tv.size >= pageSize
    }

    /**
     * Loads the next page of recommended movies + TV for the same genre query
     * computed by [loadRecommended], appends the new (deduped) titles to the
     * Recommended row, and filters them to only-streamable titles — mirroring
     * the other loadMore* functions.
     */
    fun loadMoreRecommended() = viewModelScope.launch {
        val gq = recommendedGenreQuery ?: return@launch
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            recommendedPage++
            val existing = _recommended.value.map { it.id }.toSet()
            val excludeIds = WatchProgressStore.allWatchedIds()
            val moreMovies = runCatching {
                repo.discover("movie", genreId = gq, sortBy = "popularity.desc", page = recommendedPage)
                    .filter { it.id !in existing && it.id !in excludeIds }
            }.getOrDefault(emptyList())
            val moreTv = runCatching {
                repo.discover("tv", genreId = gq, sortBy = "popularity.desc", page = recommendedPage)
                    .filter { it.id !in existing && it.id !in excludeIds }
            }.getOrDefault(emptyList())
            // Merge + interleave the new batch.
            val merged = mutableListOf<TmdbItem>()
            val maxLen = maxOf(moreMovies.size, moreTv.size)
            for (i in 0 until maxLen) {
                if (i < moreMovies.size) merged.add(moreMovies[i])
                if (i < moreTv.size) merged.add(moreTv[i])
            }
            if (merged.isEmpty()) {
                _canLoadMoreRecommended.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            // Append raw, then filter the new batch to only-streamable titles.
            _recommended.value = _recommended.value + merged
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = runCatching {
                    StreamAvailabilityChecker.filterAvailable(ctx, merged)
                }.getOrDefault(merged)
                val moreIds = merged.map { it.id }.toSet()
                _recommended.value = _recommended.value.filter { it.id !in moreIds } + availableMore
            }
            // End of catalog if both feeds returned less than a full page.
            _canLoadMoreRecommended.value =
                moreMovies.size >= pageSize || moreTv.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    /**
     * Refines a row's StateFlow down to only-streamable titles. Shows the raw
     * results immediately (already set by the caller) so the row isn't empty,
     * then probes availability and replaces the list with the filtered subset.
     * Titles with no playable source are hidden.
     */
    private suspend fun refineRow(row: MutableStateFlow<List<TmdbItem>>) {
        val ctx = appContext() ?: return
        val raw = row.value
        if (raw.isEmpty()) return
        _filtering.value = true
        try {
            val available = StreamAvailabilityChecker.filterAvailable(ctx, raw)
            // Only replace if we still have something to show; never wipe a
            // row to empty on a probe glitch (filterAvailable returns the
            // safe-default-shown set, so this is belt-and-braces).
            row.value = available
        } finally {
            _filtering.value = false
        }
    }

    fun loadMoreTrending() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            trendingPage++
            val existing = _trending.value.map { it.id }.toSet()
            val more = repo.getTrending(trendingPage).filter { it.isMovie && it.id !in existing }
            if (more.isEmpty()) {
                // Page returned only duplicates / non-movies — end of catalog.
                _canLoadMoreTrending.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            // Append the raw batch immediately, then filter the new batch.
            _trending.value = _trending.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _trending.value = _trending.value.filter { it.id !in moreIds } + availableMore
            }
            // End of catalog if the raw batch was smaller than a full page.
            _canLoadMoreTrending.value = more.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    fun loadMoreNowPlaying() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            nowPlayingPage++
            val existing = _nowPlaying.value.map { it.id }.toSet()
            val more = repo.getNowPlaying(nowPlayingPage).filter { it.id !in existing }
            if (more.isEmpty()) {
                _canLoadMoreNowPlaying.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            _nowPlaying.value = _nowPlaying.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _nowPlaying.value = _nowPlaying.value.filter { it.id !in moreIds } + availableMore
            }
            _canLoadMoreNowPlaying.value = more.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    fun loadMorePopularTV() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            popularTVPage++
            val existing = _popularTV.value.map { it.id }.toSet()
            val more = repo.getPopularTV(popularTVPage).filter { it.id !in existing }
            if (more.isEmpty()) {
                _canLoadMorePopularTV.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            _popularTV.value = _popularTV.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _popularTV.value = _popularTV.value.filter { it.id !in moreIds } + availableMore
            }
            _canLoadMorePopularTV.value = more.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    fun loadMoreTopRated() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            topRatedPage++
            val existing = _topRated.value.map { it.id }.toSet()
            val more = repo.getTopRatedMovies(topRatedPage).filter { it.id !in existing }
            if (more.isEmpty()) {
                _canLoadMoreTopRated.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            _topRated.value = _topRated.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _topRated.value = _topRated.value.filter { it.id !in moreIds } + availableMore
            }
            _canLoadMoreTopRated.value = more.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    fun loadMorePopularMovies() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            popularMoviesPage++
            val existing = _popularMovies.value.map { it.id }.toSet()
            val more = repo.getPopularMovies(popularMoviesPage).filter { it.id !in existing }
            if (more.isEmpty()) {
                _canLoadMorePopularMovies.value = false
                _isLoadingMore.value = false
                return@withLock
            }
            _popularMovies.value = _popularMovies.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _popularMovies.value = _popularMovies.value.filter { it.id !in moreIds } + availableMore
            }
            _canLoadMorePopularMovies.value = more.size >= pageSize
            _isLoadingMore.value = false
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}

/**
 * Converts a [WatchProgress] record into a [TmdbItem] so it can flow through
 * the existing ContentRow / ContentCard rendering without special-casing.
 *
 * The TmdbItem is reconstructed from the metadata captured when the player
 * saved the progress (title, poster/backdrop paths, content type). The
 * `mediaType` is set so [TmdbItem.isMovie] resolves correctly. Genre ids are
 * unknown at this layer (they're fetched on-demand by the recommendation
 * engine) so they're left empty — the card doesn't need them.
 */
private fun WatchProgress.toTmdbItem(): TmdbItem = TmdbItem(
    id = tmdbId,
    title = if (contentType.equals("tv", ignoreCase = true)) null else title,
    name = if (contentType.equals("tv", ignoreCase = true)) title else null,
    posterPath = posterPath,
    backdropPath = backdropPath,
    releaseDate = if (!contentType.equals("tv", ignoreCase = true)) (year?.let { "$it-01-01" }) else null,
    firstAirDate = if (contentType.equals("tv", ignoreCase = true)) (year?.let { "$it-01-01" }) else null,
    mediaType = contentType
)
