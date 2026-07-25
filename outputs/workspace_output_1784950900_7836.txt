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
                val raw = repo.discover(
                    type = genre?.type ?: type,
                    genreId = genre?.id?.takeIf { it.isNotEmpty() },
                    page = 1
                )
                // Show the raw results immediately so the grid isn't empty
                // while we probe availability, then refine to only-streamable
                // titles. This keeps the UI responsive.
                _items.value = raw
                // End-of-catalog check on the first page.
                if (raw.size < pageSize) {
                    _canLoadMore.value = false
                }
                _filtering.value = true
                val ctx = appContext()
                if (ctx != null) {
                    val available = StreamAvailabilityChecker.filterAvailable(ctx, raw)
                    _items.value = available
                    // If the first page filtered down to very few streamable
                    // titles but TMDB still has more pages, auto-load the next
                    // page so the user always sees a reasonable grid + the
                    // "Show More" button. This fixes the "no load more button
                    // at the end" issue where aggressive availability filtering
                    // left the grid with only 2-3 cards and the user thought
                    // there was nothing more to load.
                    if (_canLoadMore.value && available.size < 6) {
                        autoLoadNextPage()
                    }
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
                var more: List<TmdbItem> = emptyList()
                try {
                    val genre = _selectedGenre.value
                    page++
                    val existing = _items.value.map { it.id }.toSet()
                    more = repo.discover(
                        type = genre?.type ?: "movie",
                        genreId = genre?.id?.takeIf { it.isNotEmpty() },
                        page = page
                    ).filter { it.id !in existing }
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
                    // End-of-catalog: TMDB sent fewer than a full page (or
                    // every item was a duplicate), so there's nothing more.
                    if (more.size < pageSize) {
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
