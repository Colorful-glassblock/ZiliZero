package com.zilizero.app.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.zilizero.app.viewmodel.PlayerUiState
import com.zilizero.app.viewmodel.PlayerViewModel
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer
import master.flame.danmaku.ui.widget.DanmakuView

import androidx.activity.compose.BackHandler

@Composable
fun PlayerScreen(
    bvid: String,
    cid: Long,
    onBackPressed: () -> Unit,
    viewModel: PlayerViewModel = viewModel()
) {
    // Handle system back press
    BackHandler(onBack = onBackPressed)

    val uiState by viewModel.uiState.collectAsState()
    val danmakuParser by viewModel.danmakuParser.collectAsState()
    val context = LocalContext.current
    
    // Create DanmakuContext with TV optimizations
    val danmakuContext = remember {
        DanmakuContext.create().apply {
            // setDanmakuStyle(IDanmakus.DANMAKU_STYLE_STROKEN, 2.0f) // Removed due to compilation error
            setDuplicateMergingEnabled(false)
            setScrollSpeedFactor(1.2f)
            setScaleTextSize(1.5f) // Larger text for TV
            setCacheStuffer(SimpleTextCacheStuffer(), null) // Simple cache for performance
            // setMaximumVisibleSize(0) // Removed due to compilation error
        }
    }

    LaunchedEffect(bvid, cid) {
        viewModel.initializePlayer()
        viewModel.loadVideo(bvid, cid)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (val state = uiState) {
            is PlayerUiState.Loading -> {
                Text(
                    text = "Buffering...",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
            is PlayerUiState.Error -> {
                Text(
                    text = "Error: ${state.message}",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Red
                )
            }
            is PlayerUiState.Ready -> {
                // Video Player
                AndroidView(
                    factory = {
                        PlayerView(context).apply {
                            // Video should be below Danmaku
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            player = viewModel.player
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.player = null }
                )
                
                // Danmaku Layer - Switched to DanmakuView (Standard View) to ensure visibility over SurfaceView
                if (danmakuParser != null) {
                    androidx.compose.runtime.key(danmakuParser) {
                        AndroidView(
                            factory = {
                                DanmakuView(context).apply {
                                    // Standard View doesn't need Z-Order config, it naturally overlays SurfaceView
                                    
                                    // DEBUG: Check if view is visible
                                    // this.setBackgroundColor(android.graphics.Color.parseColor("#33FF0000")) 

                                    setCallback(object : DrawHandler.Callback {
                                        override fun prepared() {
                                            start()
                                            show()
                                        }
                                        override fun updateTimer(timer: DanmakuTimer?) {}
                                        override fun danmakuShown(danmaku: BaseDanmaku?) {}
                                        override fun drawingFinished() {}
                                    })
                                    prepare(danmakuParser, danmakuContext)
                                }
                            },
                            update = { view ->
                                val player = viewModel.player
                                if (player != null) {
                                    if (player.isPlaying) {
                                        if (view.isPaused) view.resume()
                                        val diff = view.currentTime - player.currentPosition
                                        if (kotlin.math.abs(diff) > 1000) {
                                            view.seekTo(player.currentPosition)
                                        }
                                    } else {
                                        if (!view.isPaused) view.pause()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { it.release() }
                        )
                    }
                }
            }
        }
    }

    // Handle back press to release player early or navigate back
    DisposableEffect(Unit) {
        onDispose {
            viewModel.releasePlayer()
        }
    }
}
