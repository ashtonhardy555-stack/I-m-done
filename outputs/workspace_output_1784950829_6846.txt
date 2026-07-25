package com.ashtonhardy.piratesfilmcove.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.repository.ContentRepository
import com.ashtonhardy.piratesfilmcove.data.server.StreamAvailabilityChecker
import com.ashtonhardy.piratesfilmcove.ui.browse.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TvViewModel : ViewModel() {

    private val repo = ContentRepository()
    private val loadMutex = Mutex()

    private val _popular = MutableStateFlow<List<TmdbItem>>(emptyList())
    val popular: StateFlow<List<TmdbItem>> = _popular

    private val _topRated = MutableStateFlow<List<TmdbItem>>(emptyList())
    val topRated: StateFlow<List<TmdbItem>> = _topRated

    private var popularPage = 1
    private var topRatedPage = 1
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    // -- canLoadMore flags -----------------------------------------------
    // Each row exposes a flag that the UI uses to show/hide its Load More
    // button.  TMDB returns ~20 items per page; when a loadMore() fetch
    // returns fewer than `pageSize` items (or only duplicates), we flip the
    // flag to false so the button disappears once the catalog is exhausted.
    private val pageSize = 20

    private val _canLoadMorePopular = MutableStateFlow(true)
    val canLoadMorePopular: StateFlow<Boolean> = _canLoadMorePopular

    private val _canLoadMoreTopRated = MutableStateFlow(true)
    val canLoadMoreTopRated: StateFlow<Boolean> = _canLoadMoreTopRated

    /** True while filtering a row down to only-streamable titles. */
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

    init { load() }

    private fun load() {
        viewModelScope.launch {
            _popular.value = repo.getPopularTV()
            refineRow(_popular, _canLoadMorePopular)
        }
        viewModelScope.launch {
            _topRated.value = repo.getTopRatedTV()
            refineRow(_topRated, _canLoadMoreTopRated)
        }
    }

    /**
     * Refines a row's StateFlow down to only-streamable titles. Shows the raw
     * results immediately (already set by the caller) so the row isn't empty,
     * then probes availability and replaces the list with the filtered subset.
     * Titles with no playable source are hidden.
     *
     * If the filtered result is very short (fewer than 6 streamable titles)
     * but TMDB still has more pages, auto-loads the next page so the row
     * always has a reasonable number of cards and the Load More button is
     * visible.
     */
    private suspend fun refineRow(
        row: MutableStateFlow<List<TmdbItem>>,
        canLoadMoreFlag: MutableStateFlow<Boolean>
    ) {
        val ctx = appContext() ?: return
        val raw = row.value
        if (raw.isEmpty()) return
        _filtering.value = true
        try {
            val available = StreamAvailabilityChecker.filterAvailable(ctx, raw)
            row.value = available
            // If the first page filtered down to very few streamable titles
            // but TMDB still has more pages, auto-load the next page so the
            // user always sees a reasonable number of cards + the Load More
            // button.
            if (canLoadMoreFlag.value && available.size < 6) {
                autoLoadNextPage(row, canLoadMoreFlag)
            }
        } finally {
            _filtering.value = false
        }
    }

    /**
     * Auto-loads the next page of a row and appends the streamable subset.
     * Called when the first page filters down to too few items.
     */
    private suspend fun autoLoadNextPage(
        row: MutableStateFlow<List<TmdbItem>>,
        canLoadMoreFlag: MutableStateFlow<Boolean>
    ) {
        try {
            val ctx = appContext() ?: return
            val isPopular = row === _popular
            val nextPage = if (isPopular) popularPage + 1 else topRatedPage + 1
            val raw = if (isPopular) {
                repo.getPopularTV(nextPage)
            } else {
                repo.getTopRatedTV(nextPage)
            }
            if (raw.size < pageSize) {
                canLoadMoreFlag.value = false
            }
            val existing = row.value.map { it.id }.toSet()
            val fresh = raw.filter { it.id !in existing }
            if (fresh.isEmpty()) {
                canLoadMoreFlag.value = false
                return
            }
            val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, fresh)
            if (isPopular) popularPage = nextPage else topRatedPage = nextPage
            row.value = row.value + availableMore
        } catch (e: Exception) {
            canLoadMoreFlag.value = false
        }
    }

    fun loadMore() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            if (!_canLoadMorePopular.value) return@withLock
            _isLoadingMore.value = true
            popularPage++
            val existing = _popular.value.map { it.id }.toSet()
            val more = repo.getPopularTV(popularPage).filter { it.id !in existing }
            // Append the raw batch immediately, then filter the new batch.
            _popular.value = _popular.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _popular.value = _popular.value.filter { it.id !in moreIds } + availableMore
            }
            _isLoadingMore.value = false
            // End-of-catalog: TMDB sent fewer than a full page (or every
            // item was a duplicate), so there's nothing more to load.
            if (more.size < pageSize) {
                _canLoadMorePopular.value = false
            }
        }
    }

    /**
     * Loads the next page of top-rated TV shows and appends the new (deduped)
     * titles to the Top Rated Shows row, then filters the batch down to only
     * streamable titles — mirroring [loadMore] for the Popular row.
     */
    fun loadMoreTopRated() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            if (!_canLoadMoreTopRated.value) return@withLock
            _isLoadingMore.value = true
            topRatedPage++
            val existing = _topRated.value.map { it.id }.toSet()
            val more = repo.getTopRatedTV(topRatedPage).filter { it.id !in existing }
            _topRated.value = _topRated.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _topRated.value = _topRated.value.filter { it.id !in moreIds } + availableMore
            }
            _isLoadingMore.value = false
            // End-of-catalog: TMDB sent fewer than a full page (or every
            // item was a duplicate), so there's nothing more to load.
            if (more.size < pageSize) {
                _canLoadMoreTopRated.value = false
            }
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}
