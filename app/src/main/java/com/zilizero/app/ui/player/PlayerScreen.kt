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

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    bvid: String,
    cid: Long,
    onBackPressed: () -> Unit,
    viewModel: PlayerViewModel = viewModel()
) {
    BackHandler(onBack = onBackPressed)

    val uiState by viewModel.uiState.collectAsState()
    val danmakuParser by viewModel.danmakuParser.collectAsState()
    val context = LocalContext.current
    
    // BBLL-Style DanmakuContext Configuration
    val danmakuContext = remember {
        DanmakuContext.create().apply {
            // Set thick stroke style for TV visibility
            // setDanmakuStyle(IDanmakus.DANMAKU_STYLE_STROKEN, 3.0f) // REMOVED: Compilation error, using default.
            setDuplicateMergingEnabled(true)
            setScrollSpeedFactor(1.2f)
            setScaleTextSize(1.5f)
            setCacheStuffer(SimpleTextCacheStuffer(), null)
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
                // 1. Video Layer (Bottom)
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
                
                // 2. Danmaku Layer (Top)
                if (danmakuParser != null) {
                    androidx.compose.runtime.key(danmakuParser) {
                        AndroidView(
                            factory = {
                                DanmakuSurfaceView(context).apply {
                                    layoutParams = android.view.ViewGroup.LayoutParams(
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    // CRITICAL: SurfaceView layering
                                    setZOrderMediaOverlay(true) 
                                    holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                                    
                                    setCallback(object : DrawHandler.Callback {
                                        override fun prepared() {
                                            post {
                                                val pos = viewModel.player?.currentPosition ?: 0
                                                android.util.Log.e("ZiliZero_Danmaku", "BBLL-Style Start at: $pos. Size: $width x $height")
                                                start(pos)
                                            }
                                        }
                                        override fun updateTimer(timer: DanmakuTimer?) {
                                            if (timer != null && timer.currMillisecond % 5000 < 50) {
                                                android.util.Log.e("ZiliZero_Danmaku", "Timer: ${timer.currMillisecond}")
                                            }
                                        }
                                        override fun danmakuShown(danmaku: BaseDanmaku?) {
                                            android.util.Log.e("ZiliZero_Danmaku", "SHOWN: ${danmaku?.text}")
                                        }
                                        override fun drawingFinished() {}
                                    })
                                    prepare(danmakuParser, danmakuContext)
                                }
                            },
                            update = { view ->
                                val player = viewModel.player
                                if (player != null && view.isPrepared) {
                                    if (player.isPlaying) {
                                        view.resume()
                                        // Sync if drift > 500ms
                                        val diff = view.currentTime - player.currentPosition
                                        if (kotlin.math.abs(diff) > 500) {
                                            view.seekTo(player.currentPosition)
                                        }
                                    } else {
                                        view.pause()
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

    DisposableEffect(Unit) {
        onDispose {
            viewModel.releasePlayer()
        }
    }
}
