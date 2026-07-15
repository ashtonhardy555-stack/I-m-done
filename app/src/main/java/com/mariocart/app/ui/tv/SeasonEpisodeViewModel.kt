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
 *
 * ## Speed: in-memory season cache
 * TV shows load noticeably slower than movies because tapping a show opens
 * this picker, which has to wait on a `getTvDetails` network round-trip
 * before it can render anything (movies go straight to the player). To close
 * that gap we cache every show's season list in [seasonCache] for the
 * lifetime of the process. When the user re-opens a show they've already
 * viewed, the seasons appear **instantly** (no spinner) and a background
 * refresh silently updates them if TMDB changed. The first-ever open of a
 * show still needs one fetch, but subsequent opens are as fast as tapping a
 * movie.
 */
class SeasonEpisodeViewModel : ViewModel() {

    private val repo = ContentRepository()

    /**
     * Process-wide cache: TMDB tv id -> season list. Survives across picker
     * opens (and across view-model recreation, since it lives on the
     * companion) so re-opening a show is instant. This is the single biggest
     * win for "shows load as fast as movies".
     */
    private val _seasons = MutableStateFlow<List<TvSeason>>(emptyList())
    val seasons: StateFlow<List<TvSeason>> = _seasons

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /**
     * True when the current seasons came from the cache (so the UI knows it
     * can render immediately and the spinner should only cover a genuine
     * first-time fetch, not a background refresh).
     */
    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache

    private var loadedTvId: Int? = null

    fun load(tvId: Int) {
        // If we're already showing data for this exact show, do nothing
        // (e.g. recomposition). Avoids a redundant fetch + spinner flash.
        if (loadedTvId == tvId && _seasons.value.isNotEmpty()) return

        // Instant path: serve cached seasons immediately and refresh quietly.
        val cached = seasonCache[tvId]
        if (cached != null) {
            _seasons.value = cached
            _selectedSeason.value = cached.firstOrNull()?.seasonNumber ?: 1
            _fromCache.value = true
            loadedTvId = tvId
            // Background refresh — no spinner, just update if data changed.
            fetchSeasons(tvId, showLoading = false)
            return
        }

        // First-ever open of this show: show the spinner while we fetch.
        _fromCache.value = false
        fetchSeasons(tvId, showLoading = true)
    }

    private fun fetchSeasons(tvId: Int, showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                val details = repo.getTvDetails(tvId)
                // Skip season 0 (specials) unless it's the only one.
                val all = details?.seasons ?: emptyList()
                val filtered = all.filter { it.seasonNumber > 0 }.ifEmpty { all }
                // Only commit if this fetch is still the current show (the
                // user may have backed out and opened a different show).
                if (loadedTvId == null || loadedTvId == tvId) {
                    _seasons.value = filtered
                    seasonCache[tvId] = filtered
                    loadedTvId = tvId
                    // Don't override a user-selected season on a background
                    // refresh; only reset it on a first load.
                    if (_selectedSeason.value !in filtered.map { it.seasonNumber }) {
                        _selectedSeason.value = filtered.firstOrNull()?.seasonNumber ?: 1
                    }
                }
            } catch (e: Exception) {
                // On a background refresh failure, keep whatever is shown.
                if (showLoading) _seasons.value = emptyList()
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    fun selectSeason(seasonNumber: Int) {
        _selectedSeason.value = seasonNumber
    }

    companion object {
        // Lives on the companion so it outlives a single ViewModel instance
        // (ViewModels are scoped to the picker, which is destroyed on back).
        private val seasonCache = mutableMapOf<Int, List<TvSeason>>()
    }
}
