package com.takahashirinta.ncrust.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.model.SongDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SongViewModel : ViewModel() {
    private val _songDetail = MutableStateFlow<SongDetail?>(null)
    val songDetail: StateFlow<SongDetail?> = _songDetail

    private val _lyric = MutableStateFlow<String?>(null)
    val lyric: StateFlow<String?> = _lyric

    private val _translatedLyric = MutableStateFlow<String?>(null)
    val translatedLyric: StateFlow<String?> = _translatedLyric

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun loadSongDetail(songId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val c = Gson().toJson(listOf(mapOf("id" to songId, "v" to 0)))
                val detailResponse = RetrofitClient.api.getSongDetail(c)
                _songDetail.value = detailResponse.songs.firstOrNull()

                val lyricResponse = RetrofitClient.api.getLyric(id = songId)
                _lyric.value = lyricResponse.lrc?.lyric
                _translatedLyric.value = lyricResponse.tlyric?.lyric
            } catch (e: Exception) {
                // 静默处理
            } finally {
                _isLoading.value = false
            }
        }
    }
}
