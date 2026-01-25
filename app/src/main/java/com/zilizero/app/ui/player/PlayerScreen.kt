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
import master.flame.danmaku.ui.widget.DanmakuSurfaceView

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
            setDuplicateMergingEnabled(true) // ENABLED for performance (TV 4K fillrate)
            setScrollSpeedFactor(1.2f)
            setScaleTextSize(1.5f) // Larger text for TV
            // setCacheStuffer(SimpleTextCacheStuffer(), null) // REMOVED: Revert to default renderer for debugging
        }
    }

    // Create DanmakuView instance to be managed by Compose and Listeners
    val danmakuView = remember {
        DanmakuSurfaceView(context).apply {
            setZOrderOnTop(true) // FORCE ON TOP: Places this Surface strictly ABOVE the window
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT) // Ensure transparency
            
            // DEBUG: Visual confirmation - REMOVED RED BACKGROUND
            // this.setBackgroundColor(android.graphics.Color.parseColor("#88FF0000")) 

            setCallback(object : DrawHandler.Callback {
                override fun prepared() {
                    android.util.Log.e("ZiliZero_Danmaku", "SurfaceView Prepared & Showing")
                    start()
                }
                override fun updateTimer(timer: DanmakuTimer?) {}
                override fun danmakuShown(danmaku: BaseDanmaku?) {}
                override fun drawingFinished() {}
            })
        }
    }

    LaunchedEffect(bvid, cid) {
        viewModel.initializePlayer()
        viewModel.loadVideo(bvid, cid)
    }

    // Bind Player Events to DanmakuView (Robust Sync)
    val player = viewModel.player
    if (player != null) {
        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        danmakuView.resume()
                    } else {
                        danmakuView.pause()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    // Sync on Seek
                    if (danmakuView.isPrepared) {
                        danmakuView.seekTo(newPosition.contentPositionMs)
                    }
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
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
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            this.player = viewModel.player
                            useController = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.player = null }
                )
                
                // Danmaku Layer
                if (danmakuParser != null) {
                    androidx.compose.runtime.key(danmakuParser) {
                        AndroidView(
                            factory = {
                                danmakuView.apply {
                                    prepare(danmakuParser, danmakuContext)
                                    show()
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
