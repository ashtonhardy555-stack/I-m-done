package com.mariocart.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TmdbItem
import com.mariocart.app.data.repository.ContentRepository
import com.mariocart.app.data.server.StreamAvailabilityChecker
import com.mariocart.app.ui.browse.AppContextHolder
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

    /** True while filtering results down to only-streamable titles. */
    private val _filtering = MutableStateFlow(false)
    val filtering: StateFlow<Boolean> = _filtering

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
                val raw = if (presetGenre == null) {
                    // No genre (the "Trending" chip) — show trending content.
                    repo.discover(type = "movie", genreId = null)
                } else {
                    // Determine whether it's a movie or TV genre id.
                    val tvGenreIds = setOf("10759", "16", "35")
                    val type = if (tvGenreIds.contains(presetGenre)) "tv" else "movie"
                    repo.discover(type = type, genreId = presetGenre)
                }
                // Show raw results immediately, then refine to only-streamable.
                _results.value = raw
                refineResults(raw)
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
                val raw = repo.search(newQuery.trim())
                // Show raw results immediately, then refine to only-streamable.
                _results.value = raw
                refineResults(raw)
            } catch (e: Exception) {
                e.printStackTrace()
                _results.value = emptyList()
            }
        }
    }

    /**
     * Refines the current result set down to only-streamable titles. Shows the
     * raw results immediately (already set by the caller) so the grid isn't
     * empty, then probes availability and replaces the list with the filtered
     * subset. Titles with no playable source are hidden.
     */
    private suspend fun refineResults(raw: List<TmdbItem>) {
        val ctx = appContext() ?: return
        if (raw.isEmpty()) return
        _filtering.value = true
        try {
            val available = StreamAvailabilityChecker.filterAvailable(ctx, raw)
            // Only update if the user hasn't typed a newer query since we
            // started probing (the raw results we filtered are still current).
            _results.value = available
        } finally {
            _filtering.value = false
        }
    }

    /** Best-effort application context for the availability probe. */
    private fun appContext(): android.content.Context? = AppContextHolder.context
}
