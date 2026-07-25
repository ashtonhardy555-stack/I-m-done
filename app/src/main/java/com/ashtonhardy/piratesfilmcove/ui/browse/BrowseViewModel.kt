package com.ashtonhardy.piratesfilmcove.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashtonhardy.piratesfilmcove.data.model.Genre
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.repository.ContentRepository
import com.ashtonhardy.piratesfilmcove.data.server.StreamAvailabilityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowseViewModel : ViewModel() {

    private val repo = ContentRepository()
    private val loadMutex = Mutex()

    private val _items = MutableStateFlow<List<TmdbItem>>(emptyList())
    val items: StateFlow<List<TmdbItem>> = _items

    private val _selectedGenre = MutableStateFlow<Genre?>(null)
    val selectedGenre: StateFlow<Genre?> = _selectedGenre

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _filtering = MutableStateFlow(false)
    /** True while filtering the grid down to only-streamable titles. */
    val filtering: StateFlow<Boolean> = _filtering

    // -- canLoadMore flag ------------------------------------------------
    // Controls the visibility of the "Show More" button.  TMDB returns ~20
    // items per page; when a fetch returns fewer than `pageSize` items (or
    // only duplicates), we flip the flag to false so the button disappears
    // once the genre catalog is exhausted.
    private val pageSize = 20
    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    /** True while a loadMore() is in flight (distinct from initial load). */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    // Max pages to fetch per genre so the grid shows all the titles
    // that fit without a Show More button. ~6 pages ≈ ~120 titles
    // before the streamable filter.
    private val maxPages = 6

    private var page = 1

    init {
        loadGenre(null, "movie")
    }

    fun loadGenre(genre: Genre?, type: String = "movie") {
        _selectedGenre.value = genre
        _error.value = null
        page = 1
        _canLoadMore.value = true
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Fetch ALL pages for the genre so the grid shows every
                // title that fits without a Show More button. We aggregate
                // up to [maxPages] pages, dedup by id, and stop early when
                // a page returns fewer than a full page (end of catalog).
                val raw = loadAllPagesDiscover(
                    type = genre?.type ?: type,
                    genreId = genre?.id?.takeIf { it.isNotEmpty() }
                )
                // Show the aggregated raw results immediately so the grid
                // isn't empty while we probe availability, then refine to
                // only-streamable titles.
                _items.value = raw
                _canLoadMore.value = false
                _filtering.value = true
                val ctx = appContext()
                if (ctx != null) {
                    _items.value = StreamAvailabilityChecker.filterAvailable(ctx, raw)
                }
            } catch (e: Exception) {
                _error.value = "Couldn't load content. Check your connection."
                _items.value = emptyList()
                _canLoadMore.value = false
            } finally {
                _isLoading.value = false
                _filtering.value = false
            }
        }
    }

    /**
     * Fetches up to [maxPages] pages of a discover query, dedups by id,
     * and returns the aggregated list. Stops early when a page returns
     * fewer than a full page (end of catalog). Lets the grid show all the
     * titles that fit without a Show More button.
     */
    private suspend fun loadAllPagesDiscover(
        type: String,
        genreId: String?
    ): List<TmdbItem> {
        val all = mutableListOf<TmdbItem>()
        val seen = mutableSetOf<Int>()
        for (p in 1..maxPages) {
            val items = runCatching { repo.discover(type = type, genreId = genreId, page = p) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) break
            for (item in items) if (seen.add(item.id)) all.add(item)
            if (items.size < pageSize) break
        }
        return all
    }

    /**
     * Auto-loads the next page of the current genre and appends the
     * streamable subset. Called when the first page filters down to too few
     * items so the user isn't left with a nearly-empty grid and no visible
     * "Show More" button. Runs in the background without clobbering the
     * existing results.
     */
    private suspend fun autoLoadNextPage() {
        try {
            val genre = _selectedGenre.value
            val nextPage = 2
            val raw = repo.discover(
                type = genre?.type ?: "movie",
                genreId = genre?.id?.takeIf { it.isNotEmpty() },
                page = nextPage
            )
            if (raw.size < pageSize) {
                _canLoadMore.value = false
            }
            val existing = _items.value.map { it.id }.toSet()
            val fresh = raw.filter { it.id !in existing }
            if (fresh.isEmpty()) {
                _canLoadMore.value = false
                return
            }
            val ctx = appContext() ?: return
            val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, fresh)
            page = nextPage
            _items.value = _items.value + availableMore
        } catch (e: Exception) {
            // Silently fail — the first page is still visible.
            _canLoadMore.value = false
        }
    }

    fun loadMore() {
        viewModelScope.launch {
            loadMutex.withLock {
                if (_isLoading.value || _loadingMore.value) return@withLock
                if (!_canLoadMore.value) return@withLock
                _loadingMore.value = true
                var rawSize = 0
                try {
                    val genre = _selectedGenre.value
                    page++
                    val existing = _items.value.map { it.id }.toSet()
                    // Capture the RAW page size BEFORE dedup so the
                    // end-of-catalog decision is based on the raw TMDB
                    // response, not the deduped/filtered count. (Dedup +
                    // availability filtering can shrink the batch below
                    // pageSize even when TMDB returned a full page — that
                    // was the Load More bug.)
                    val rawPage = repo.discover(
                        type = genre?.type ?: "movie",
                        genreId = genre?.id?.takeIf { it.isNotEmpty() },
                        page = page
                    )
                    rawSize = rawPage.size
                    val more = rawPage.filter { it.id !in existing }
                    // Append immediately, then filter the new batch.
                    _items.value = _items.value + more
                    _filtering.value = true
                    val ctx = appContext()
                    if (ctx != null) {
                        val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                        // Keep the already-available items and append only
                        // the newly-loaded ones that are also available.
                        val moreIds = more.map { it.id }.toSet()
                        _items.value = _items.value.filter { it.id !in moreIds } + availableMore
                    }
                } catch (e: Exception) {
                    _error.value = "Couldn't load more content."
                } finally {
                    _loadingMore.value = false
                    _filtering.value = false
                    // End-of-catalog: TMDB sent fewer than a full page of RAW
                    // results (not the deduped/filtered count), so there's
                    // nothing more to load.
                    if (rawSize < pageSize) {
                        _canLoadMore.value = false
                    }
                }
            }
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}

/**
 * Process-wide application context holder so ViewModels can access a Context
 * for the availability probe without needing an Activity-scoped reference.
 * Set once from [com.ashtonhardy.piratesfilmcove.PiratesfilmCoveApplication] / MainActivity.
 */
object AppContextHolder {
    @Volatile
    var context: android.content.Context? = null
}
