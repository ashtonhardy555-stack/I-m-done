package com.ashtonhardy.piratesfilmcove.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ashtonhardy.piratesfilmcove.data.model.TmdbItem
import com.ashtonhardy.piratesfilmcove.data.repository.ContentRepository
import com.ashtonhardy.piratesfilmcove.data.server.StreamAvailabilityChecker
import com.ashtonhardy.piratesfilmcove.ui.browse.AppContextHolder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SearchViewModel : ViewModel() {

    private val repo = ContentRepository()
    private val loadMutex = Mutex()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<TmdbItem>>(emptyList())
    val results: StateFlow<List<TmdbItem>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** True while filtering results down to only-streamable titles. */
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

    /**
     * True when there are more pages available to load via [loadMore]. Set
     * false when the last page returned fewer than a full page of results so
     * the "Load More" button hides once we've exhausted the catalog.
     */
    private val _canLoadMore = MutableStateFlow(false)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

    /** True while a [loadMore] request is in flight (distinct from initial load). */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private var searchJob: Job? = null

    // When a genre is preset (from the Home "Quick Browse" chips) the screen
    // shows a discover feed for that genre until the user types a real query.
    private var presetGenre: String? = null

    // Current page of results (1-based). Reset to 1 on every new query / genre.
    private var page = 1

    // The last committed query string (trimmed) so loadMore() knows what to
    // search for. Empty when in genre-preset mode.
    private var committedQuery: String = ""

    // TMDB returns ~20 results per page. Used to detect end-of-catalog.
    private val pageSize = 20

    /**
     * Preload the screen with a genre browse instead of an empty search box.
     * @param genreId TMDB genre id, or null/empty for trending.
     */
    fun setInitialGenre(genreId: String?) {
        presetGenre = genreId?.takeIf { it.isNotBlank() }
        committedQuery = ""
        page = 1
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val raw = if (presetGenre == null) {
                    // No genre (the "Trending" chip) - show trending content.
                    repo.discover(type = "movie", genreId = null, page = 1)
                } else {
                    // Determine whether it's a movie or TV genre id.
                    val tvGenreIds = setOf("10759", "16", "35")
                    val type = if (tvGenreIds.contains(presetGenre)) "tv" else "movie"
                    repo.discover(type = type, genreId = presetGenre, page = 1)
                }
                page = 1
                _canLoadMore.value = raw.size >= pageSize
                // Show raw results immediately, then refine to only-streamable.
                _results.value = raw
                refineResults(raw, replace = true)
            } catch (e: Exception) {
                _results.value = emptyList()
                _canLoadMore.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateQuery(newQuery: String) {
        // Guard against spurious re-issues: if the query hasn't actually
        // changed, don't cancel the in-flight search job and don't re-run
        // the debounce. This fixes the "searches disappear if you sit on
        // the keypad too long" issue — the TV Leanback IME can send
        // repeated onValueChange callbacks with the same value (e.g. when
        // the IME auto-dismisses or re-composes), which previously
        // cancelled the running search job and either re-debounced forever
        // or cleared results.
        if (newQuery == _query.value) return
        _query.value = newQuery
        // Once the user starts typing, drop the genre preset.
        if (newQuery.isNotBlank()) presetGenre = null

        if (newQuery.length < 2) {
            _results.value = emptyList()
            _canLoadMore.value = false
            committedQuery = ""
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(700)
            try {
                val trimmed = newQuery.trim()
                committedQuery = trimmed
                page = 1
                val raw = repo.search(trimmed, page = 1)
                _canLoadMore.value = raw.size >= pageSize
                // Show raw results immediately, then refine to only-streamable.
                _results.value = raw
                refineResults(raw, replace = true)
            } catch (e: Exception) {
                e.printStackTrace()
                _results.value = emptyList()
                _canLoadMore.value = false
            }
        }
    }

    /**
     * Loads the next page of results and appends them. Works for both
     * free-text search (uses [committedQuery]) and the genre-preset discover
     * feed (uses [presetGenre]). Dedupes against already-loaded ids. After
     * appending the raw page, it probes stream availability for the new batch
     * and keeps only the streamable ones (matching the initial-load behaviour).
     */
    fun loadMore() {
        // Nothing to page if there's no committed query and no preset genre.
        if (committedQuery.isBlank() && presetGenre == null) return
        viewModelScope.launch {
            loadMutex.withLock {
                if (_loadingMore.value || !_canLoadMore.value) return@withLock
                _loadingMore.value = true
                try {
                    val nextPage = page + 1
                    val raw = if (committedQuery.isNotBlank()) {
                        repo.search(committedQuery, page = nextPage)
                    } else {
                        val tvGenreIds = setOf("10759", "16", "35")
                        val type = if (tvGenreIds.contains(presetGenre)) "tv" else "movie"
                        repo.discover(type = type, genreId = presetGenre, page = nextPage)
                    }
                    // Detect end of catalog.
                    _canLoadMore.value = raw.size >= pageSize
                    // Dedupe against already-loaded items.
                    val existing = _results.value.map { it.id }.toSet()
                    val fresh = raw.filter { it.id !in existing }
                    if (fresh.isEmpty()) {
                        // Page returned only duplicates - stop paging.
                        _canLoadMore.value = false
                        return@withLock
                    }
                    // Append immediately so the grid grows, then refine the
                    // new batch down to only-streamable titles.
                    _results.value = _results.value + fresh
                    page = nextPage
                    refineResults(fresh, replace = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                    _canLoadMore.value = false
                } finally {
                    _loadingMore.value = false
                }
            }
        }
    }

    /**
     * Refines results down to only-streamable titles.
     *
     * @param replace When true (initial load / new query), the entire result
     *                list is replaced with the filtered subset. When false
     *                (loadMore), only the newly-loaded [raw] batch is filtered
     *                and appended; existing items are kept as-is.
     */
    private suspend fun refineResults(raw: List<TmdbItem>, replace: Boolean) {
        val ctx = appContext() ?: return
        if (raw.isEmpty()) return
        _filtering.value = true
        try {
            val available = StreamAvailabilityChecker.filterAvailable(ctx, raw)
            if (replace) {
                // Only update if the user hasn't typed a newer query since we
                // started probing (the raw results we filtered are still current).
                _results.value = available
            } else {
                // loadMore: drop the just-appended raw batch and append only
                // its streamable subset, preserving everything loaded before.
                val freshIds = raw.map { it.id }.toSet()
                _results.value = _results.value.filter { it.id !in freshIds } + available
            }
        } finally {
            _filtering.value = false
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}
