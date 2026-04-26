package com.takahashirinta.ncrust.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.network.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _songs = MutableStateFlow<List<SongItem>>(emptyList())
    val songs: StateFlow<List<SongItem>> = _songs

    private val _albums = MutableStateFlow<List<AlbumSearchItem>>(emptyList())
    val albums: StateFlow<List<AlbumSearchItem>> = _albums

    private val _artists = MutableStateFlow<List<ArtistSearchItem>>(emptyList())
    val artists: StateFlow<List<ArtistSearchItem>> = _artists

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentType = MutableStateFlow(1)
    val currentType: StateFlow<Int> = _currentType

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(500)
            if (newQuery.isBlank()) {
                clearResults()
                return@launch
            }
            searchByType(_currentType.value)
        }
    }

    fun onTypeChanged(type: Int) {
        _currentType.value = type
        if (_query.value.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                searchByType(type)
            }
        }
    }

    private suspend fun searchByType(type: Int) {
        _isLoading.value = true
        _error.value = null
        try {
            when (type) {
                1 -> {
                    val response = RetrofitClient.api.search(keyword = _query.value, type = 1)
                    _songs.value = response.result?.songs ?: emptyList()
                    _albums.value = emptyList()
                    _artists.value = emptyList()
                }
                10 -> {
                    val response = RetrofitClient.api.searchAlbum(keyword = _query.value, type = 10)
                    _albums.value = response.result?.albums ?: emptyList()
                    _songs.value = emptyList()
                    _artists.value = emptyList()
                }
                100 -> {
                    val response = RetrofitClient.api.searchArtist(keyword = _query.value, type = 100)
                    _artists.value = response.result?.artists ?: emptyList()
                    _songs.value = emptyList()
                    _albums.value = emptyList()
                }
            }
        } catch (e: Exception) {
            _error.value = "搜索失败: ${e.message}"
            clearResults()
        } finally {
            _isLoading.value = false
        }
    }

    fun clearQuery() {
        _query.value = ""
        clearResults()
    }

    private fun clearResults() {
        _songs.value = emptyList()
        _albums.value = emptyList()
        _artists.value = emptyList()
    }
}