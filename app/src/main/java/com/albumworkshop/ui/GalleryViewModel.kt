package com.albumworkshop.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.albumworkshop.data.Album
import com.albumworkshop.data.MediaItem
import com.albumworkshop.data.MediaStoreRepository
import com.albumworkshop.data.TagStore
import com.albumworkshop.media.ImageResizer
import com.albumworkshop.media.MediaMutator
import kotlinx.coroutines.launch

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaStoreRepository(application)
    val tagStore = TagStore(application)

    private val appContext: Application get() = getApplication()

    var state by mutableStateOf(GalleryUiState())
        private set

    init {
        refresh()
    }

    // ------------------------------------------------------------ 列表 / 导航

    fun refresh() {
        viewModelScope.launch {
            if (state.allMedia.isEmpty()) {
                state = state.copy(loading = true)
            }
            reload(clearSelection = false)
        }
    }

    fun selectTab(index: Int) {
        if (state.tab != index) state = state.copy(tab = index, selection = null)
    }

    fun openAlbum(album: Album) {
        state = state.copy(openAlbum = album, albumItems = emptyList(), loadingAlbum = true, selection = null)
        viewModelScope.launch {
            val items = repository.loadImages(album.id)
            if (state.openAlbum?.id == album.id) {
                state = state.copy(albumItems = items, loadingAlbum = false)
            }
        }
    }

    fun closeAlbum() {
        state = state.copy(openAlbum = null, albumItems = emptyList(), selection = null)
    }

    private suspend fun reload(clearSelection: Boolean) {
        val library = repository.loadLibrary()
        val albums = library.albums
        val openId = state.openAlbum?.id
        val items = if (openId != null) repository.loadImages(openId) else emptyList()
        val albumStillExists = openId != null && albums.any { it.id == openId }
        state = state.copy(
            loading = false,
            loadingAlbum = false,
            albums = albums,
            videos = library.videos,
            allMedia = library.all,
            openAlbum = if (albumStillExists) albums.first { it.id == openId } else null,
            albumItems = if (albumStillExists) items else emptyList(),
            selection = if (clearSelection) null else state.selection,
        )
    }

    // ------------------------------------------------------------ 全屏预览

    /** 点开当前列表里的第 [index] 个文件 */
    fun openViewer(index: Int) {
        val items = currentItems()
        if (index !in items.indices) return
        state = state.copy(viewer = ViewerState(items, index), selection = null)
    }

    fun closeViewer() {
        state = state.copy(viewer = null)
    }

    /** 当前屏幕上正在展示的那组文件，保证预览顺序与网格一致 */
    private fun currentItems(): List<MediaItem> = when {
        state.openAlbum != null -> state.albumItems
        state.tab == ALL_TAB -> state.allMedia
        state.tab == VIDEO_TAB -> state.videos
        else -> emptyList()
    }

    // ------------------------------------------------------------ 长按选中

    fun longPressAlbum(album: Album) {
        viewModelScope.launch {
            val cached = if (state.openAlbum?.id == album.id) state.albumItems else emptyList()
            val items = cached.ifEmpty { repository.loadImages(album.id) }
            if (items.isEmpty()) {
                notify("这个相册里没有可编辑的照片")
                return@launch
            }
            state = state.copy(selection = Selection.AlbumSelection(album, items))
        }
    }

    fun longPressItem(item: MediaItem) {
        state = state.copy(selection = Selection.ItemSelection(listOf(item)))
    }

    fun dismissSelection() {
        state = state.copy(selection = null)
    }

    // ------------------------------------------------------------ 面板

    fun openResizeSheet() {
        val selection = state.selection ?: return
        val images = selection.items.filter { !it.isVideo }
        if (images.isEmpty()) {
            notify("视频不支持修改分辨率")
            return
        }
        state = state.copy(sheet = Sheet.Resize(images))
    }

    fun openRenameSheet() {
        val selection = state.selection ?: return
        if (selection.items.isEmpty()) return
        state = state.copy(sheet = Sheet.Rename(selection.items))
    }

    fun closeSheet() {
        state = state.copy(sheet = Sheet.None)
    }

    // ------------------------------------------------------------ 修改分辨率

    fun executeResize(targetWidth: Int) {
        val sheet = state.sheet as? Sheet.Resize ?: return
        if (targetWidth <= 0) {
            notify("请输入合法的宽度")
            return
        }
        val targets = sheet.items
        state = state.copy(sheet = Sheet.None, selection = null)
        viewModelScope.launch {
            var success = 0
            var failed = 0
            targets.forEachIndexed { index, item ->
                state = state.copy(busy = "正在修改分辨率 ${index + 1}/${targets.size}")
                // 批量处理时不放大原图，避免小图被拉大
                val width = if (targets.size > 1 && item.width > 0) {
                    minOf(targetWidth, item.width)
                } else {
                    targetWidth
                }
                runCatching { ImageResizer.resize(appContext, item, width) }
                    .onSuccess { success++ }
                    .onFailure {
                        failed++
                        Log.w(TAG, "改分辨率失败: ${item.displayName}", it)
                    }
            }
            state = state.copy(busy = null)
            reload(clearSelection = true)
            notify(
                when {
                    failed == 0 -> "已生成 $success 张新图片（文件名 + gai）"
                    success == 0 -> "处理失败，请检查存储权限"
                    else -> "完成 $success 张，失败 $failed 张"
                }
            )
        }
    }

    // ------------------------------------------------------------ 重命名

    fun executeRename(suffixes: List<String>) {
        val sheet = state.sheet as? Sheet.Rename ?: return
        val suffix = suffixes.joinToString("").trim()
        if (suffix.isEmpty()) {
            notify("请先选择要追加的标签")
            return
        }
        val plan = sheet.items.mapNotNull { item ->
            val newBase = MediaMutator.sanitizeFileName(item.baseName + suffix)
            if (newBase.isEmpty() || newBase == item.baseName) {
                null
            } else {
                RenamePlan(item.uri, newBase + item.extension)
            }
        }
        if (plan.isEmpty()) {
            notify("文件名没有变化")
            return
        }
        state = state.copy(sheet = Sheet.None, selection = null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { MediaMutator.writeRequest(appContext, plan.map { it.uri }) }
                .onSuccess { state = state.copy(consent = ConsentRequest(it, PendingAction.Rename(plan))) }
                .onFailure { notify("无法发起重命名请求：${it.message ?: "未知错误"}") }
        } else {
            viewModelScope.launch { performRename(plan) }
        }
    }

    private suspend fun performRename(plan: List<RenamePlan>) {
        state = state.copy(busy = "正在重命名…")
        val result = runCatching { MediaMutator.rename(appContext, plan.map { it.uri to it.newName }) }
        state = state.copy(busy = null)
        result.onSuccess { (success, failed) ->
            reload(clearSelection = true)
            notify(if (failed == 0) "已重命名 $success 项" else "已重命名 $success 项，$failed 项失败")
        }.onFailure { error ->
            if (error is MediaMutator.ConsentRequired) {
                state = state.copy(consent = ConsentRequest(error.intentSender, PendingAction.Rename(plan)))
            } else {
                notify("重命名失败：${error.message ?: "未知错误"}")
            }
        }
    }

    // ------------------------------------------------------------ 删除

    fun requestDelete() {
        val selection = state.selection ?: return
        if (selection.items.isEmpty()) return
        state = state.copy(confirmDelete = selection.items)
    }

    fun dismissDelete() {
        state = state.copy(confirmDelete = null)
    }

    fun confirmDelete() {
        val items = state.confirmDelete ?: return
        state = state.copy(confirmDelete = null, selection = null)
        val uris = items.map { it.uri }
        if (uris.isEmpty()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { MediaMutator.deleteRequest(appContext, uris) }
                .onSuccess { state = state.copy(consent = ConsentRequest(it, PendingAction.Delete(uris))) }
                .onFailure { notify("无法发起删除请求：${it.message ?: "未知错误"}") }
        } else {
            viewModelScope.launch { performDelete(uris) }
        }
    }

    private suspend fun performDelete(uris: List<Uri>) {
        state = state.copy(busy = "正在删除…")
        val result = runCatching { MediaMutator.delete(appContext, uris) }
        state = state.copy(busy = null)
        result.onSuccess { (success, failed) ->
            reload(clearSelection = true)
            notify(if (failed == 0) "已删除 $success 项" else "已删除 $success 项，$failed 项失败")
        }.onFailure { error ->
            if (error is MediaMutator.ConsentRequired) {
                state = state.copy(consent = ConsentRequest(error.intentSender, PendingAction.Delete(uris)))
            } else {
                notify("删除失败：${error.message ?: "未知错误"}")
            }
        }
    }

    // ------------------------------------------------------------ 系统授权回调

    fun onConsentResult(granted: Boolean) {
        val consent = state.consent ?: return
        state = state.copy(consent = null)
        if (!granted) {
            notify("已取消系统授权")
            return
        }
        when (val action = consent.action) {
            is PendingAction.Delete -> viewModelScope.launch { performDelete(action.uris) }
            is PendingAction.Rename -> viewModelScope.launch { performRename(action.plan) }
        }
    }

    // ------------------------------------------------------------ 标签

    fun addTag(text: String): Boolean {
        val tag = tagStore.add(text)
        if (tag == null) {
            notify("标签为空或已存在")
            return false
        }
        return true
    }

    fun updateTag(id: String, text: String): Boolean {
        val ok = tagStore.update(id, text)
        if (!ok) notify("标签为空或已存在")
        return ok
    }

    fun deleteTag(id: String) {
        tagStore.remove(id)
    }

    // ------------------------------------------------------------ 提示

    fun notify(text: String) {
        state = state.copy(message = UiMessage(System.nanoTime(), text))
    }

    fun consumeMessage(id: Long) {
        if (state.message?.id == id) state = state.copy(message = null)
    }

    private companion object {
        const val TAG = "GalleryViewModel"

        /** 标签页下标：0 全部、1 相册、2 视频 */
        const val ALL_TAB = 0
        const val VIDEO_TAB = 2
    }
}
