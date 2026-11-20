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

    /** 供界面判断「是否已全选」 */
    fun visibleItems(): List<MediaItem> = currentItems()

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
        // 已经在多选模式里时，长按等同于切换，方便连续勾选
        if (state.selection is Selection.ItemSelection) {
            toggleItemSelection(item)
        } else {
            state = state.copy(selection = Selection.ItemSelection(listOf(item)))
        }
    }

    /**
     * 全屏预览里长按：退出预览并选中这个文件，让底部操作条接管。
     * 预览是盖在最上层的，不退出的话操作条会被挡住。
     */
    fun longPressInViewer(item: MediaItem) {
        state = state.copy(viewer = null, selection = Selection.ItemSelection(listOf(item)))
    }

    /**
     * 选择模式下点缩略图：把该文件从选中集合里加进来或去掉。
     * 全部取消时自动退出选择模式。
     */
    fun toggleItemSelection(item: MediaItem) {
        val current = state.selection
        if (current !is Selection.ItemSelection) {
            state = state.copy(selection = Selection.ItemSelection(listOf(item)))
            return
        }
        val remaining = if (current.items.any { it.id == item.id }) {
            current.items.filterNot { it.id == item.id }
        } else {
            current.items + item
        }
        state = state.copy(
            selection = if (remaining.isEmpty()) null else Selection.ItemSelection(remaining)
        )
    }

    /** 全选当前列表（全部 / 视频 / 某个相册）里的所有文件 */
    fun selectAllVisible() {
        val items = currentItems()
        if (items.isEmpty()) return
        state = state.copy(selection = Selection.ItemSelection(items))
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

    fun executeResize(targetWidth: Int, overwrite: Boolean = false) {
        val sheet = state.sheet as? Sheet.Resize ?: return
        if (targetWidth <= 0) {
            notify("请输入合法的宽度")
            return
        }
        val images = sheet.items.filter { !it.isVideo }
        if (images.isEmpty()) {
            notify("没有可处理的图片")
            return
        }
        state = state.copy(sheet = Sheet.None, selection = null)

        // 覆盖原文件要改写别人的媒体条目，Android 11+ 必须先拿到系统授权
        if (overwrite && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { MediaMutator.writeRequest(appContext, images.map { it.uri }) }
                .onSuccess {
                    state = state.copy(
                        consent = ConsentRequest(it, PendingAction.Resize(images, targetWidth, true))
                    )
                }
                .onFailure { notify("无法发起修改请求：${it.message ?: "未知错误"}") }
            return
        }

        viewModelScope.launch { performResize(images, targetWidth, overwrite) }
    }

    private suspend fun performResize(
        items: List<MediaItem>,
        targetWidth: Int,
        overwrite: Boolean,
    ) {
        var success = 0
        var failed = 0
        var clamped = 0
        var firstError: String? = null
        var relocated = 0
        var relocatedTo: String? = null

        items.forEachIndexed { index, item ->
            state = state.copy(busy = "正在修改分辨率 ${index + 1}/${items.size}")
            // 一律不放大原图：图片比目标宽度还小时按原尺寸输出
            val effective = if (item.width > 0) minOf(targetWidth, item.width) else targetWidth
            runCatching { ImageResizer.resize(appContext, item, effective, overwrite) }
                .onSuccess { outcome ->
                    success++
                    if (outcome.clamped) clamped++
                    // 原目录（如 Download）不允许写图片，新文件被挪到了 Pictures 下
                    outcome.relocatedTo?.let {
                        relocated++
                        if (relocatedTo == null) relocatedTo = it
                    }
                }
                .onFailure { error ->
                    failed++
                    if (firstError == null) {
                        firstError = error.message?.takeIf { it.isNotBlank() }
                            ?: error.javaClass.simpleName
                    }
                    Log.w(TAG, "改分辨率失败: ${item.displayName}", error)
                }
        }

        state = state.copy(busy = null)
        reload(clearSelection = true)
        notify(buildResizeMessage(success, failed, clamped, overwrite, firstError, relocated, relocatedTo))
    }

    /**
     * 结果提示。
     *
     * 以前失败一律说「请检查存储权限」，但真正的失败原因可能是内存不足、
     * 图片损坏等，误导性很强。这里把第一条真实原因带出来。
     */
    private fun buildResizeMessage(
        success: Int,
        failed: Int,
        clamped: Int,
        overwrite: Boolean,
        firstError: String?,
        relocated: Int = 0,
        relocatedTo: String? = null,
    ): String {
        val suffix = if (overwrite) "已覆盖原文件" else "已生成新文件（+gai）"
        return when {
            success == 0 && failed > 0 -> "处理失败：${firstError ?: "未知错误"}"
            failed == 0 && success > 0 -> buildString {
                append("完成 $success 张，")
                append(suffix)
                if (clamped > 0) append("，其中 $clamped 张原图更小未放大")
                if (relocated > 0) append("，$relocated 张原目录不可写，已存到 $relocatedTo")
            }

            success > 0 -> "完成 $success 张，失败 $failed 张（${firstError ?: "未知错误"}）"
            else -> "没有需要处理的文件"
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
            is PendingAction.Resize -> viewModelScope.launch {
                performResize(action.items, action.width, action.overwrite)
            }
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
