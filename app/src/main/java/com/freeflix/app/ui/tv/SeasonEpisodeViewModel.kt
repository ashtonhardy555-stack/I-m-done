package com.freeflix.app.ui.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freeflix.app.data.model.TmdbItem
import com.freeflix.app.data.model.TvEpisode
import com.freeflix.app.data.model.TvSeason
import com.freeflix.app.data.model.TvShowDetail
import com.freeflix.app.data.repository.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel backing [SeasonEpisodePicker].
 *
 * Loads the season list for a TV show from TMDB
 * (ContentRepository.getTvDetails) and tracks the currently selected season.
 *
 * ## Netflix-style detail + episode list
 * In addition to seasons, this now also fetches the full [TvShowDetail]
 * (overview, backdrop, genres, episode runtime) so the picker can show a
 * Netflix-style detail hero at the top, and fetches the full episode list
 * for the selected season (each episode with its thumbnail still + overview)
 * so the episode list can show thumbnails as buttons with descriptions below.
 *
 * ## Speed: in-memory caches
 * Season lists AND episode lists are cached process-wide so re-opening a
 * show or re-selecting a season is instant.
 */
class SeasonEpisodeViewModel : ViewModel() {

    private val repo = ContentRepository()

    private val _seasons = MutableStateFlow<List<TvSeason>>(emptyList())
    val seasons: StateFlow<List<TvSeason>> = _seasons.asStateFlow()

    private val _selectedSeason = MutableStateFlow(1)
    val selectedSeason: StateFlow<Int> = _selectedSeason.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Full show detail (overview, backdrop, genres, runtime) for the hero. */
    private val _showDetail = MutableStateFlow<TvShowDetail?>(null)
    val showDetail: StateFlow<TvShowDetail?> = _showDetail.asStateFlow()

    /** Episodes for the selected season, each with still + overview. */
    private val _episodes = MutableStateFlow<List<TvEpisode>>(emptyList())
    val episodes: StateFlow<List<TvEpisode>> = _episodes.asStateFlow()

    private val _isLoadingEpisodes = MutableStateFlow(false)
    val isLoadingEpisodes: StateFlow<Boolean> = _isLoadingEpisodes.asStateFlow()

    /** "More Like This" row for the show. */
    private val _similar = MutableStateFlow<List<TmdbItem>>(emptyList())
    val similar: StateFlow<List<TmdbItem>> = _similar.asStateFlow()

    /**
     * True when the current seasons came from the cache (so the UI knows it
     * can render immediately and the spinner should only cover a genuine
     * first-time fetch, not a background refresh).
     */
    private val _fromCache = MutableStateFlow(false)
    val fromCache: StateFlow<Boolean> = _fromCache.asStateFlow()

    private var loadedTvId: Int? = null

    fun load(tvId: Int) {
        // If we're already showing data for this exact show, do nothing.
        if (loadedTvId == tvId && _seasons.value.isNotEmpty()) return

        // SWITCHING SHOWS: fully wipe the previous show's state so the user
        // never sees show A's seasons / episodes / hero detail / "More Like
        // This" lingering while show B loads. Without this reset the
        // activity-scoped ViewModel keeps the old show's data on screen
        // (the hero showed the previous show, the episode list kept the old
        // episodes, etc.) whenever you opened a different show.
        if (loadedTvId != null && loadedTvId != tvId) {
            _seasons.value = emptyList()
            _episodes.value = emptyList()
            _showDetail.value = null
            _similar.value = emptyList()
            _selectedSeason.value = 1
        }

        // Instant path: serve cached seasons immediately and refresh quietly.
        val cached = seasonCache[tvId]
        if (cached != null) {
            _seasons.value = cached
            _selectedSeason.value = cached.firstOrNull()?.seasonNumber ?: 1
            _fromCache.value = true
            loadedTvId = tvId
            // Background refresh — no spinner.
            fetchSeasons(tvId, showLoading = false)
            fetchShowDetail(tvId)
            return
        }

        // First-ever open of this show: show the spinner while we fetch.
        _fromCache.value = false
        loadedTvId = tvId
        fetchSeasons(tvId, showLoading = true)
        fetchShowDetail(tvId)
    }

    private fun fetchSeasons(tvId: Int, showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _isLoading.value = true
            try {
                val details = repo.getTvDetails(tvId)
                // Skip season 0 (specials) unless it's the only one.
                val all = details?.seasons ?: emptyList()
                val filtered = all.filter { it.seasonNumber > 0 }.ifEmpty { all }
                if (loadedTvId == null || loadedTvId == tvId) {
                    _seasons.value = filtered
                    seasonCache[tvId] = filtered
                    loadedTvId = tvId
                    if (_selectedSeason.value !in filtered.map { it.seasonNumber }) {
                        _selectedSeason.value = filtered.firstOrNull()?.seasonNumber ?: 1
                    }
                    // Load episodes for the selected season immediately.
                    if (filtered.isNotEmpty()) {
                        loadEpisodes(tvId, _selectedSeason.value)
                    }
                }
            } catch (e: Exception) {
                if (showLoading) _seasons.value = emptyList()
            } finally {
                if (showLoading) _isLoading.value = false
            }
        }
    }

    /** Fetch the full show detail (overview, backdrop, genres) for the hero. */
    private fun fetchShowDetail(tvId: Int) {
        viewModelScope.launch {
            try {
                val detail = repo.getTvShowDetail(tvId)
                _showDetail.value = detail
                // "More Like This"
                val genres = detail?.genreIds ?: emptyList()
                if (genres.isNotEmpty()) {
                    _similar.value = repo.getSimilarTV(genres, tvId)
                }
            } catch (e: Exception) {
                // Non-fatal — the hero falls back to the lightweight item.
            }
        }
    }

    /** Switch the selected season and fetch its episode list. */
    fun selectSeason(tvId: Int, seasonNumber: Int) {
        if (_selectedSeason.value == seasonNumber && _episodes.value.isNotEmpty()) return
        _selectedSeason.value = seasonNumber
        loadEpisodes(tvId, seasonNumber)
    }

    private fun loadEpisodes(tvId: Int, seasonNumber: Int) {
        val cacheKey = tvId to seasonNumber
        episodeCache[cacheKey]?.let { cached ->
            _episodes.value = cached
            return
        }
        _isLoadingEpisodes.value = true
        viewModelScope.launch {
            try {
                val seasonDetail = repo.getSeasonDetail(tvId, seasonNumber)
                val eps = seasonDetail?.episodes ?: emptyList()
                episodeCache[cacheKey] = eps
                if (_selectedSeason.value == seasonNumber) {
                    _episodes.value = eps
                }
            } catch (e: Exception) {
                if (_selectedSeason.value == seasonNumber) {
                    _episodes.value = emptyList()
                }
            } finally {
                _isLoadingEpisodes.value = false
            }
        }
    }

    companion object {
        private val seasonCache = mutableMapOf<Int, List<TvSeason>>()
        // (tvId, seasonNumber) -> episode list with stills + overviews.
        private val episodeCache = mutableMapOf<Pair<Int, Int>, List<TvEpisode>>()
    }
}
