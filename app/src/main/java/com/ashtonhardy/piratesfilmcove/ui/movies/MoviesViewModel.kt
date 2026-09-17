package com.ashtonhardy.piratesfilmcove.ui.movies

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

class MoviesViewModel : ViewModel() {
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
    private val _canLoadMorePopular = MutableStateFlow(true)
    val canLoadMorePopular: StateFlow<Boolean> = _canLoadMorePopular
    private val _canLoadMoreTopRated = MutableStateFlow(true)
    val canLoadMoreTopRated: StateFlow<Boolean> = _canLoadMoreTopRated
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

    init {
        viewModelScope.launch { _popular.value = repo.getPopularMovies(); refine(_popular) }
        viewModelScope.launch { _topRated.value = repo.getTopRatedMovies(); refine(_topRated) }
    }

    private suspend fun refine(row: MutableStateFlow<List<TmdbItem>>) {
        val ctx = AppContextHolder.context ?: return
        if (row.value.isEmpty()) return
        _filtering.value = true
        try { row.value = StreamAvailabilityChecker.filterAvailable(ctx, row.value) }
        finally { _filtering.value = false }
    }

    fun loadMore() = loadRow(true)
    fun loadMoreTopRated() = loadRow(false)

    private fun loadRow(popular: Boolean) = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            try {
                val next = if (popular) popularPage + 1 else topRatedPage + 1
                val raw = if (popular) repo.getPopularMovies(next) else repo.getTopRatedMovies(next)
                // Filtering and duplicate removal must never decide that the
                // catalog is finished. Only an empty raw API page is final.
                if (raw.isEmpty()) {
                    if (popular) _canLoadMorePopular.value = false else _canLoadMoreTopRated.value = false
                    return@withLock
                }
                val row = if (popular) _popular else _topRated
                val existing = row.value.map { it.id }.toSet()
                val fresh = raw.filter { it.id !in existing }
                val ctx = AppContextHolder.context
                val available = if (ctx != null) {
                    StreamAvailabilityChecker.filterAvailable(ctx, fresh)
                } else fresh
                row.value = row.value + available
                if (popular) {
                    popularPage = next
                    _canLoadMorePopular.value = true
                } else {
                    topRatedPage = next
                    _canLoadMoreTopRated.value = true
                }
            } catch (_: Exception) {
                // Retryable failure: preserve the affordance instead of
                // permanently removing Load More from the row.
                if (popular) _canLoadMorePopular.value = true else _canLoadMoreTopRated.value = true
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun appContext(): android.content.Context? = AppContextHolder.context
}
