package com.albumworkshop.ui

import android.content.IntentSender
import android.net.Uri
import com.albumworkshop.data.Album
import com.albumworkshop.data.MediaItem

/** 当前被长按选中的对象：单个文件，或一个相册（批量） */
sealed interface Selection {

    val items: List<MediaItem>
    val title: String

    data class AlbumSelection(
        val album: Album,
        override val items: List<MediaItem>,
    ) : Selection {
        override val title: String get() = album.name
    }

    data class ItemSelection(
        override val items: List<MediaItem>,
    ) : Selection {
        override val title: String get() = items.firstOrNull()?.displayName ?: ""
    }
}

/** 从底部弹出的面板 */
sealed interface Sheet {
    object None : Sheet
    data class Resize(val items: List<MediaItem>) : Sheet
    data class Rename(val items: List<MediaItem>) : Sheet
}

data class RenamePlan(val uri: Uri, val newName: String)

sealed interface PendingAction {
    data class Delete(val uris: List<Uri>) : PendingAction
    data class Rename(val plan: List<RenamePlan>) : PendingAction

    /** 覆盖原文件在 Android 11+ 需要系统授权，授权后再执行 */
    data class Resize(
        val items: List<MediaItem>,
        val width: Int,
        val overwrite: Boolean,
    ) : PendingAction
}

data class ConsentRequest(val intentSender: IntentSender, val action: PendingAction)

/** 全屏预览：当前这组文件 + 起始下标 */
data class ViewerState(val items: List<MediaItem>, val index: Int)

data class UiMessage(val id: Long, val text: String)

data class GalleryUiState(
    val loading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val videos: List<MediaItem> = emptyList(),
    /** “全部”标签页：图片 + 视频，按修改时间倒序 */
    val allMedia: List<MediaItem> = emptyList(),
    val openAlbum: Album? = null,
    val albumItems: List<MediaItem> = emptyList(),
    val loadingAlbum: Boolean = false,
    val selection: Selection? = null,
    val sheet: Sheet = Sheet.None,
    val confirmDelete: List<MediaItem>? = null,
    val viewer: ViewerState? = null,
    val busy: String? = null,
    val message: UiMessage? = null,
    val consent: ConsentRequest? = null,
    /** 0 = 全部，1 = 相册，2 = 视频 */
    val tab: Int = 0,
)
