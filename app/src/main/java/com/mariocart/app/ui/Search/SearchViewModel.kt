package com.mariocart.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.repository.ContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {

    private val repo = ContentRepository()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<TmdbItem>>(emptyList())
    val results: StateFlow<List<TmdbItem>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var searchJob: Job? = null

    // When a genre is preset (from the Home "Quick Browse" chips) the screen
    // shows a discover feed for that genre until the user types a real query.
    private var presetGenre: String? = null

    /**
     * Preload the screen with a genre browse instead of an empty search box.
     * @param genreId TMDB genre id, or null/empty for trending.
     */
    fun setInitialGenre(genreId: String?) {
        presetGenre = genreId?.takeIf { it.isNotBlank() }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                if (presetGenre == null) {
                    // No genre (the "Trending" chip) — show trending content.
                    _results.value = repo.discover(type = "movie", genreId = null)
                } else {
                    // Determine whether it's a movie or TV genre id.
                    val tvGenreIds = setOf("10759", "16", "35")
                    val type = if (tvGenreIds.contains(presetGenre)) "tv" else "movie"
                    _results.value = repo.discover(type = type, genreId = presetGenre)
                }
            } catch (e: Exception) {
                _results.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        // Once the user starts typing, drop the genre preset.
        if (newQuery.isNotBlank()) presetGenre = null

        if (newQuery.length < 2) {
            _results.value = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(700)
            try {
                val items = repo.search(newQuery.trim())
                _results.value = items
            } catch (e: Exception) {
                e.printStackTrace()
                _results.value = emptyList()
            }
        }
    }
}
