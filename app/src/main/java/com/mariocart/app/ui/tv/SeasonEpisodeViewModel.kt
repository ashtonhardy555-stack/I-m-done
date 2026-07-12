package com.mariocart.app.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mariocart.app.data.model.TvSeason
import com.mariocart.app.data.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel backing [SeasonEpisodePicker].
 *
 * Loads the season list for a TV show from TMDB
 * (ContentRepository.getTvDetails) and tracks the currently selected season.
 *
 * Mirrors the data the LookMovie Kodi addon extracts from `show_storage`
 * (the `seasons` array), but sourced from TMDB so it works before any
 * scraping round-trip.
 */
class SeasonEpisodeViewModel : ViewModel() {

    private val repo = ContentRepository()

    private val _seasons = MutableStateFlow<List<TvSeason>>(emptyList())
    val seasons: StateFlow<List<TvSeason>> = _seasons

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun load(tvId: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val details = repo.getTvDetails(tvId)
                // Skip season 0 (specials) unless it's the only one.
                val all = details?.seasons ?: emptyList()
                val filtered = all.filter { it.seasonNumber > 0 }.ifEmpty { all }
                _seasons.value = filtered
                _selectedSeason.value = filtered.firstOrNull()?.seasonNumber ?: 1
            } catch (e: Exception) {
                _seasons.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _selectedSeason.value = seasonNumber
    }
}
