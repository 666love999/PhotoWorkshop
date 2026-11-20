package com.albumworkshop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.albumworkshop.data.Album
import com.albumworkshop.data.MediaItem
import com.albumworkshop.ui.components.MediaThumbnail

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    onTabSelected: (Int) -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onLongPressAlbum: (Album) -> Unit,
    onOpenItem: (Int) -> Unit,
    onLongPressItem: (MediaItem) -> Unit,
    onToggleItem: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
) {
    // 处于「文件多选」模式时，单击缩略图变成切换选中，而不是进预览
    val itemSelection = state.selection as? Selection.ItemSelection
    val selecting = itemSelection != null
    val selectedIds = remember(itemSelection) {
        itemSelection?.items?.mapTo(HashSet()) { it.id } ?: emptySet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        GalleryHeader(
            albumCount = state.albums.size,
            photoCount = state.albums.sumOf { it.count },
            videoCount = state.videos.size,
            onRefresh = onRefresh,
        )

        SegmentedTabs(
            titles = listOf("全部", "相册", "视频"),
            selected = state.tab,
            onSelected = onTabSelected,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )

        Text(
            text = "单击可全屏预览，左右滑动切换文件 · 长按可修改分辨率、重命名、删除",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 2.dp),
        )

        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState()

                // 相册
                state.tab == 1 -> {
                    if (state.albums.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.PhotoLibrary,
                            title = "还没有照片",
                            description = "系统相册里暂时读不到照片",
                        )
                    } else {
                        AlbumGrid(
                            albums = state.albums,
                            onOpen = onOpenAlbum,
                            onLongPress = onLongPressAlbum,
                        )
                    }
                }

                // 视频
                state.tab == 2 -> {
                    if (state.videos.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.Videocam,
                            title = "还没有视频",
                            description = "系统相册里暂时读不到视频",
                        )
                    } else {
                        VideoGrid(
                            videos = state.videos,
                            selecting = selecting,
                            selectedIds = selectedIds,
                            onOpen = onOpenItem,
                            onLongPress = onLongPressItem,
                            onToggle = onToggleItem,
                        )
                    }
                }

                // 全部（图片 + 视频，混合按时间倒序）
                else -> {
                    if (state.allMedia.isEmpty()) {
                        EmptyState(
                            icon = Icons.Rounded.PhotoLibrary,
                            title = "还没有文件",
                            description = "系统相册里暂时读不到照片或视频",
                        )
                    } else {
                        AllMediaGrid(
                            items = state.allMedia,
                            selecting = selecting,
                            selectedIds = selectedIds,
                            onOpen = onOpenItem,
                            onLongPress = onLongPressItem,
                            onToggle = onToggleItem,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryHeader(
    albumCount: Int,
    photoCount: Int,
    videoCount: Int,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "相册工坊",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "$albumCount 个相册 · $photoCount 张照片 · $videoCount 个视频",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
        }
    }
}

@Composable
private fun SegmentedTabs(
    titles: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        titles.forEachIndexed { index, title ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelected(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGrid(
    albums: List<Album>,
    onOpen: (Album) -> Unit,
    onLongPress: (Album) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items = albums, key = { it.id }) { album ->
            val haptic = LocalHapticFeedback.current
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(
                            onClick = { onOpen(album) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress(album)
                            },
                        )
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(album.coverUri)
                            .crossfade(200)
                            .build(),
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        shape = RoundedCornerShape(50),
                        color = Color.Black.copy(alpha = 0.42f),
                    ) {
                        Text(
                            text = "${album.count}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${album.count} 张照片",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** “全部”标签页：图片与视频混排，顺序与全屏预览一致 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AllMediaGrid(
    items: List<MediaItem>,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onOpen: (Int) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    onToggle: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(items = items, key = { _, item -> item.id }) { index, item ->
            MediaThumbnail(
                item = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = 10.dp,
                showDuration = item.isVideo,
                selected = selecting && item.id in selectedIds,
                onClick = { if (selecting) onToggle(item) else onOpen(index) },
                onLongClick = { onLongPress(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<MediaItem>,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onOpen: (Int) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    onToggle: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(items = videos, key = { _, video -> video.id }) { index, video ->
            MediaThumbnail(
                item = video,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = 12.dp,
                showDuration = true,
                selected = selecting && video.id in selectedIds,
                onClick = { if (selecting) onToggle(video) else onOpen(index) },
                onLongClick = { onLongPress(video) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    album: Album,
    items: List<MediaItem>,
    loading: Boolean,
    selecting: Boolean,
    selectedIds: Set<Long>,
    onBack: () -> Unit,
    onOpenItem: (Int) -> Unit,
    onLongPressItem: (MediaItem) -> Unit,
    onToggleItem: (MediaItem) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${items.size} 张照片 · 单击预览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        Box(Modifier.fillMaxSize()) {
            when {
                loading && items.isEmpty() -> LoadingState()
                items.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "相册是空的",
                    description = "这个相册里暂时没有照片",
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(items = items, key = { _, item -> item.id }) { index, image ->
                        MediaThumbnail(
                            item = image,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            corner = 8.dp,
                            selected = selecting && image.id in selectedIds,
                            onClick = { if (selecting) onToggleItem(image) else onOpenItem(index) },
                            onLongClick = { onLongPressItem(image) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(46.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
