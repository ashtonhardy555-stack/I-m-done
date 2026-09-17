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
    val filtering: StateFlow<Boolean> = _filtering

    // Keep this flag about catalog paging, never stream availability. A page
    // can contain many valid TMDB titles that the current providers cannot
    // resolve; that must not hide the paging control.
    private val _canLoadMore = MutableStateFlow(true)
    val canLoadMore: StateFlow<Boolean> = _canLoadMore

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
                _items.value = raw
                // Do not infer exhaustion from a filtered or short first page.
                // The next request is the authoritative end-of-catalog check.
                _canLoadMore.value = raw.isNotEmpty()

                val ctx = appContext()
                if (ctx != null && raw.isNotEmpty()) {
                    _filtering.value = true
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

    fun loadMore() {
        viewModelScope.launch {
            loadMutex.withLock {
                if (_isLoading.value || _loadingMore.value || !_canLoadMore.value) return@withLock
                _loadingMore.value = true
                _filtering.value = true
                try {
                    val genre = _selectedGenre.value
                    val nextPage = page + 1
                    val existing = _items.value.map { it.id }.toSet()
                    val rawPage = repo.discover(
                        type = genre?.type ?: "movie",
                        genreId = genre?.id?.takeIf { it.isNotEmpty() },
                        page = nextPage
                    )

                    // Only an empty raw TMDB page proves that the catalog is
                    // exhausted. Do not use the filtered count or deduped count:
                    // both caused Load More to disappear prematurely.
                    if (rawPage.isEmpty()) {
                        _canLoadMore.value = false
                        return@withLock
                    }

                    val fresh = rawPage.filter { it.id !in existing }
                    val ctx = appContext()
                    val available = if (ctx != null) {
                        StreamAvailabilityChecker.filterAvailable(ctx, fresh)
                    } else {
                        fresh
                    }
                    _items.value = _items.value + available
                    page = nextPage
                    // Keep the button present after short/filtered pages. The
                    // following empty response ends paging without hiding
                    // titles that are still available on later pages.
                    _canLoadMore.value = true
                } catch (e: Exception) {
                    _error.value = "Couldn't load more content."
                    // A transient request failure must not permanently remove
                    // the button; the user can retry on Android TV.
                    _canLoadMore.value = true
                } finally {
                    _loadingMore.value = false
                    _filtering.value = false
                }
            }
        }
    }

    private fun appContext(): android.content.Context? = AppContextHolder.context
}

object AppContextHolder {
    @Volatile
    var context: android.content.Context? = null
}
