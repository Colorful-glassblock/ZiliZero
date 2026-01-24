package com.zilizero.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.foundation.lazy.grid.TvGridCells
import androidx.tv.foundation.lazy.grid.TvLazyVerticalGrid
import androidx.tv.foundation.lazy.grid.items
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.zilizero.app.viewmodel.HomeUiState
import com.zilizero.app.viewmodel.MainViewModel
import com.zilizero.app.viewmodel.VideoCardModel

@Composable
fun HomeScreen(
    onVideoClick: (String, Long) -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    val uiState by mainViewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(27.dp)) { // Safety margin
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Text(
                    text = "Loading...",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            is HomeUiState.Error -> {
                Text(
                    text = "Error: ${state.message}",
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Red
                )
            }
            is HomeUiState.Success -> {
                VideoGrid(videos = state.videos, onVideoClick = onVideoClick)
            }
        }
    }
}

@Composable
fun VideoGrid(videos: List<VideoCardModel>, onVideoClick: (String, Long) -> Unit) {
    TvLazyVerticalGrid(
        columns = TvGridCells.Fixed(4),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items(videos) { video ->
            VideoCard(video = video, onClick = { onVideoClick(video.bvid, video.cid) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoCard(video: VideoCardModel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.width(240.dp).aspectRatio(16f/9f),
        scale = CardDefaults.scale(focusedScale = 1.1f),
        border = CardDefaults.border(focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)))
    ) {
        Column {
            AsyncImage(
                model = video.coverUrl,
                contentDescription = video.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Text(
                    text = video.upName,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Gray
                )
            }
        }
    }
}
