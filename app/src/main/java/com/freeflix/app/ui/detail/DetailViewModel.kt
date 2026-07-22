package com.freeflix.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeflix.app.data.model.MovieDetail
import com.freeflix.app.data.model.TmdbItem
import com.freeflix.app.data.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel backing the Netflix-style [DetailScreen] for **movies only**.
 *
 * TV shows use [com.freeflix.app.ui.tv.SeasonEpisodePicker] + its own
 * [com.freeflix.app.ui.tv.SeasonEpisodeViewModel], which embeds the show
 * detail hero directly in the season/episode browser.
 *
 * Loads:
 *  • Movie detail (runtime, genres, tagline, overview, backdrop).
 *  • A "More Like This" row scoped to the movie's genres.
 */
class DetailViewModel : ViewModel() {

    private val repo = ContentRepository()

    private val _movieDetail = MutableStateFlow<MovieDetail?>(null)
    val movieDetail: StateFlow<MovieDetail?> = _movieDetail.asStateFlow()

    private val _similar = MutableStateFlow<List<TmdbItem>>(emptyList())
    val similar: StateFlow<List<TmdbItem>> = _similar.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loadedId: Int? = null

    /**
     * Load detail for a movie. [item] is the lightweight TmdbItem the user
     * tapped (gives us an instant title/backdrop to show while the full
     * detail loads).
     */
    fun load(item: TmdbItem) {
        if (loadedId == item.id) return
        loadedId = item.id

        _isLoading.value = true
        _movieDetail.value = null
        _similar.value = emptyList()

        viewModelScope.launch {
            val detail = repo.getMovieDetail(item.id)
            _movieDetail.value = detail
            _isLoading.value = false
            // "More Like This" — use the detail's genre ids if available,
            // otherwise fall back to the list item's genre_ids.
            val genres = detail?.genreIds ?: item.genreIds
            if (genres.isNotEmpty()) {
                _similar.value = repo.getSimilarMovies(genres, item.id)
            }
        }
    }
}
