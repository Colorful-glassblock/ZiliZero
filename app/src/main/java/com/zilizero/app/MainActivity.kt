package com.zilizero.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.tv.material3.Surface
import com.zilizero.app.ui.home.HomeScreen
import com.zilizero.app.ui.player.PlayerScreen
import com.zilizero.app.ui.theme.ZiliZeroTheme

sealed class Screen {
    object Home : Screen()
    data class Player(val bvid: String, val cid: Long) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

            ZiliZeroTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (val screen = currentScreen) {
                        is Screen.Home -> {
                            HomeScreen(onVideoClick = { bvid, cid ->
                                currentScreen = Screen.Player(bvid, cid)
                            })
                        }
                        is Screen.Player -> {
                            BackHandler {
                                currentScreen = Screen.Home
                            }
                            PlayerScreen(
                                bvid = screen.bvid,
                                cid = screen.cid,
                                onBackPressed = { currentScreen = Screen.Home }
                            )
                        }
                    }
                }
            }
        }
    }
}