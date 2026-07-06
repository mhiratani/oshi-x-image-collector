package com.hilamalu.oshixcollector.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.hilamalu.oshixcollector.R
import com.hilamalu.oshixcollector.data.db.MediaAssetEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListScreen(viewModel: MediaViewModel = viewModel()) {
    val media by viewModel.media.collectAsState()
    val isFaceOnly by viewModel.isFaceOnly.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_media)) },
                actions = {
                    FilterChip(
                        selected = isFaceOnly,
                        onClick = { viewModel.setFaceOnly(!isFaceOnly) },
                        label = { Text(stringResource(R.string.media_face_only_filter)) },
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.media_refresh)) },
                icon = {
                    if (viewModel.isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.padding(2.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
                onClick = { viewModel.refresh() }
            )
        }
    ) { innerPadding ->
        if (media.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.padding(bottom = 8.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Text(stringResource(if (isFaceOnly) R.string.media_empty_face_only else R.string.media_empty))
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(media, key = MediaAssetEntity::mediaKey) { asset -> MediaTile(asset) }
            }
        }
    }
}

@Composable
private fun MediaTile(asset: MediaAssetEntity) {
    AsyncImage(
        model = asset.localImagePath ?: asset.xCdnUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
    )
}
