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

    private val _results = MutableStateFlow<List<TmdbItem>>(emptyList())
    val results: StateFlow<List<TmdbItem>> = _results

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _searchType = MutableStateFlow("multi")
    val searchType: StateFlow<String> = _searchType

    private val _yearFilter = MutableStateFlow<String?>(null)
    val yearFilter: StateFlow<String?> = _yearFilter

    private var searchJob: Job? = null

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
        debounceSearch()
    }

    fun updateType(type: String) {
        _searchType.value = type
        if (_query.value.isNotBlank()) performSearch()
    }

    fun updateYear(year: String?) {
        _yearFilter.value = year
        if (_query.value.isNotBlank()) performSearch()
    }

    private fun debounceSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            performSearch()
        }
    }

    private fun performSearch() {
        val q = _query.value.trim()
        if (q.isBlank()) {
            _results.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            var items = repo.search(q, _searchType.value, _yearFilter.value)
            val yf = _yearFilter.value
            if (yf != null) {
                items = items.filter { item ->
                    val y = item.year
                    y.isEmpty() || y >= yf
                }
            }
            _results.value = items
            _isLoading.value = false
        }
    }
}
