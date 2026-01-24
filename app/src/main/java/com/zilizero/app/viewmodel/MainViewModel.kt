package com.zilizero.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zilizero.app.repository.BiliRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// Simple UI State
sealed class HomeUiState {
    object Loading : HomeUiState()
    data class Success(val videos: List<VideoCardModel>) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}

// Data model for UI - Added bvid and cid for navigation
data class VideoCardModel(
    val bvid: String,
    val cid: Long,
    val title: String,
    val coverUrl: String,
    val upName: String,
    val playCount: String
)

class MainViewModel(
    private val repository: BiliRepository = BiliRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadRecommendations()
    }

    private fun loadRecommendations() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            try {
                val feedItems = repository.getRecommendFeed()
                
                val videos = feedItems.map { item ->
                    VideoCardModel(
                        bvid = item.bvid,
                        cid = item.cid,
                        title = item.title,
                        coverUrl = item.pic,
                        upName = item.owner.name,
                        playCount = formatCount(item.stat.view)
                    )
                }
                _uiState.value = HomeUiState.Success(videos)

            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Unknown Error")
            }
        }
    }

    private fun formatCount(count: Int): String {
        return if (count >= 10000) {
            "${String.format("%.1f", count / 10000.0)}万"
        } else {
            count.toString()
        }
    }
}
