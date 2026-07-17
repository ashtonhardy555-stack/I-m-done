package com.mariocart.app.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.repository.ContentRepository
import com.mariocart.app.data.server.StreamAvailabilityChecker
import com.mariocart.app.ui.browse.AppContextHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MoviesViewModel : ViewModel() {

    private val repo = ContentRepository()
    private val loadMutex = Mutex()

    private val _popular = MutableStateFlow<List<TmdbItem>>(emptyList())
    val popular: StateFlow<List<TmdbItem>> = _popular

    private val _nowPlaying = MutableStateFlow<List<TmdbItem>>(emptyList())
    val nowPlaying: StateFlow<List<TmdbItem>> = _nowPlaying

    private val _topRated = MutableStateFlow<List<TmdbItem>>(emptyList())
    val topRated: StateFlow<List<TmdbItem>> = _topRated

    private var popularPage = 1
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    /** True while filtering a row down to only-streamable titles. */
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

    init { load() }

    private fun load() {
        viewModelScope.launch {
            _popular.value = repo.getPopularMovies()
            refineRow(_popular)
        }
        viewModelScope.launch {
            _nowPlaying.value = repo.getNowPlaying()
            refineRow(_nowPlaying)
        }
        viewModelScope.launch {
            _topRated.value = repo.getTopRatedMovies()
            refineRow(_topRated)
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
            row.value = available
        } finally {
            _filtering.value = false
        }
    }

    fun loadMore() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            popularPage++
            val existing = _popular.value.map { it.id }.toSet()
            val more = repo.getPopularMovies(popularPage).filter { it.id !in existing }
            // Append the raw batch immediately, then filter the new batch.
            _popular.value = _popular.value + more
            val ctx = appContext()
            if (ctx != null) {
                val availableMore = StreamAvailabilityChecker.filterAvailable(ctx, more)
                val moreIds = more.map { it.id }.toSet()
                _popular.value = _popular.value.filter { it.id !in moreIds } + availableMore
            }
            _isLoadingMore.value = false
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}
