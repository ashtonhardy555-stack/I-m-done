package com.mariocart.app.ui.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.repository.ContentRepository
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

    init { load() }

    private fun load() {
        viewModelScope.launch { _popular.value = repo.getPopularMovies() }
        viewModelScope.launch { _nowPlaying.value = repo.getNowPlaying() }
        viewModelScope.launch { _topRated.value = repo.getTopRatedMovies() }
    }

    fun loadMore() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            popularPage++
            val existing = _popular.value.map { it.id }.toSet()
            val more = repo.getPopularMovies(popularPage).filter { it.id !in existing }
            _popular.value = _popular.value + more
            _isLoadingMore.value = false
        }
    }
}
