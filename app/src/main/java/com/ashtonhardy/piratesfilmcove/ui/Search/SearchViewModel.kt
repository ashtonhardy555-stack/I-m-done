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
     * The genre id currently being browsed (the preset / side-to-side bar
     * selection), or null when the user is doing a free-text search. Empty
     * string means "Trending" (no genre filter). Exposed so the screen can
     * highlight the active genre pill and show the category label.
     */
    private val _activeGenreId = MutableStateFlow<String?>(null)
    val activeGenreId: StateFlow<String?> = _activeGenreId

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

    // When a genre is preset (from the Home "Quick Browse" chips or the
    // side-to-side bar) the screen shows a discover feed for that genre
    // until the user types a real query.
    private var presetGenre: String? = null

    // Current page of results (1-based). Reset to 1 on every new query / genre.
    private var page = 1

    // The last committed query string (trimmed) so loadMore() knows what to
    // search for. Empty when in genre-preset mode.
    private var committedQuery: String = ""

    // Max pages to fetch so the grid shows all results that fit
    // without a Load More button.
    private val maxPages = 6

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
        _activeGenreId.value = genreId ?: ""
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                // Fetch ALL pages so the grid shows every title that fits
                // without a Load More button. Determine the media type,
                // then aggregate up to [maxPages] pages.
                if (presetGenre == null) {
                    val raw = loadAllPagesDiscover(type = "movie", genreId = null)
                    page = 1
                    _canLoadMore.value = false
                    _results.value = raw
                    refineResults(raw, replace = true)
                } else {
                    val tvGenreIds = setOf("10759", "16", "35")
                    val type = if (tvGenreIds.contains(presetGenre)) "tv" else "movie"
                    val raw = loadAllPagesDiscover(type = type, genreId = presetGenre)
                    page = 1
                    _canLoadMore.value = false
                    _results.value = raw
                    refineResults(raw, replace = true)
                }
            } catch (e: Exception) {
                _results.value = emptyList()
                _canLoadMore.value = false
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Switch the side-to-side genre bar to a new genre. Mirrors
     * [setInitialGenre] but is called when the user taps a genre pill while
     * already on the search screen. Clears any free-text query so the browse
     * feed takes over.
     */
    fun selectGenre(genreId: String?) {
        // Clear the text query so the browse feed is what's shown.
        _query.value = ""
        setInitialGenre(genreId)
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
        if (newQuery.isNotBlank()) {
            presetGenre = null
            _activeGenreId.value = null
        }

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
                // Fetch ALL pages for the query so the grid shows every
                // matching title without a Load More button.
                val raw = loadAllPagesSearch(trimmed)
                _canLoadMore.value = false
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
                    // Detect end of catalog based on the RAW page size, not
                    // the deduped/filtered count (dedup can shrink the batch
                    // below pageSize even when TMDB returned a full page).
                    val rawSize = raw.size
                    // Dedupe against already-loaded items.
                    val existing = _results.value.map { it.id }.toSet()
                    val fresh = raw.filter { it.id !in existing }
                    if (fresh.isEmpty()) {
                        // Page returned only duplicates, but keep the Load More
                        // button alive if the raw page was full — the next page
                        // may have new titles.
                        _canLoadMore.value = rawSize >= pageSize
                        return@withLock
                    }
                    // Append immediately so the grid grows, then refine the
                    // new batch down to only-streamable titles.
                    _results.value = _results.value + fresh
                    page = nextPage
                    refineResults(fresh, replace = false)
                    // End-of-catalog decision based on the RAW page size so the
                    // Load More button stays visible as long as TMDB has more
                    // pages, regardless of how many the availability filter kept.
                    _canLoadMore.value = rawSize >= pageSize
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
     * Fetches up to [maxPages] pages of a discover query, dedups by id,
     * and returns the aggregated list. Stops early when a page returns
     * fewer than a full page (end of catalog).
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
     * Fetches up to [maxPages] pages of a text search query, dedups by
     * id, and returns the aggregated list. Stops early when a page
     * returns fewer than a full page (end of catalog).
     */
    private suspend fun loadAllPagesSearch(query: String): List<TmdbItem> {
        val all = mutableListOf<TmdbItem>()
        val seen = mutableSetOf<Int>()
        for (p in 1..maxPages) {
            val items = runCatching { repo.search(query, page = p) }
                .getOrDefault(emptyList())
            if (items.isEmpty()) break
            for (item in items) if (seen.add(item.id)) all.add(item)
            if (items.size < pageSize) break
        }
        return all
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
