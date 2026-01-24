package com.zilizero.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.zilizero.app.repository.BiliRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PlayerUiState {
    object Loading : PlayerUiState()
    object Ready : PlayerUiState()
    data class Error(val message: String) : PlayerUiState()
}

@UnstableApi
class PlayerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository: BiliRepository = BiliRepository()

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    
    // Danmaku State
    private val _danmakuParser = MutableStateFlow<com.zilizero.app.ui.player.DanmakuParser?>(null)
    val danmakuParser: StateFlow<com.zilizero.app.ui.player.DanmakuParser?> = _danmakuParser.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    fun loadVideo(bvid: String, cid: Long) {
        viewModelScope.launch {
            _uiState.value = PlayerUiState.Loading
            try {
                // Parallel fetch
                val dashDeferred = async { repository.getPlayUrl(bvid, cid) }
                val danmakuDeferred = async { repository.getDanmaku(cid) }
                
                val dash = dashDeferred.await()
                val danmakuReply = danmakuDeferred.await()
                
                // Init Danmaku Parser
                val parser = com.zilizero.app.ui.player.DanmakuParser()
                parser.load(com.zilizero.app.ui.player.DmDataSource(danmakuReply))
                _danmakuParser.value = parser
                
                // Select best video and audio streams
                val videoUrl = dash.video.firstOrNull()?.baseUrl ?: throw Exception("No video stream")
                val audioUrl = dash.audio.firstOrNull()?.baseUrl ?: throw Exception("No audio stream")

                preparePlayer(videoUrl, audioUrl)
                _uiState.value = PlayerUiState.Ready

            } catch (e: Exception) {
                _uiState.value = PlayerUiState.Error(e.message ?: "Unknown Error")
            }
        }
    }

    private fun preparePlayer(videoUrl: String, audioUrl: String) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))

        val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(videoUrl))
            
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(audioUrl))

        val mergingSource = MergingMediaSource(true, videoSource, audioSource)

        player.setMediaSource(mergingSource)
        player.prepare()
        player.playWhenReady = true
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
