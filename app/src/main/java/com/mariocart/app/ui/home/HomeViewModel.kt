package com.mariocart.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.repository.ContentRepository
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

    private var trendingPage = 1
    private var nowPlayingPage = 1
    private var popularTVPage = 1
    private var topRatedPage = 1
    private var popularMoviesPage = 1

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    init { loadAll() }

    private fun loadAll() {
        viewModelScope.launch {
            val trending = repo.getTrending()
            _heroItems.value = trending.filter { it.backdropPath != null }.take(8)
            _trending.value = trending.filter { it.isMovie }.take(15)
        }
        viewModelScope.launch { _nowPlaying.value = repo.getNowPlaying() }
        viewModelScope.launch { _popularTV.value = repo.getPopularTV() }
        viewModelScope.launch { _topRated.value = repo.getTopRatedMovies() }
        viewModelScope.launch { _popularMovies.value = repo.getPopularMovies() }
    }

    fun loadMoreTrending() = viewModelScope.launch {
        loadMutex.withLock {
            if (_isLoadingMore.value) return@withLock
            _isLoadingMore.value = true
            trendingPage++
            val existing = _trending.value.map { it.id }.toSet()
            val more = repo.getTrending(trendingPage).filter { it.isMovie && it.id !in existing }
            _trending.value = _trending.value + more
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
            _nowPlaying.value = _nowPlaying.value + more
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
            _popularTV.value = _popularTV.value + more
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
            _topRated.value = _topRated.value + more
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
            _popularMovies.value = _popularMovies.value + more
            _isLoadingMore.value = false
        }
    }
}
