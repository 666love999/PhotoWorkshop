package com.albumworkshop.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.albumworkshop.data.formatFileSize
import com.albumworkshop.ui.components.BusyOverlay
import com.albumworkshop.ui.components.DeleteConfirmDialog
import com.albumworkshop.ui.components.PermissionScreen
import com.albumworkshop.ui.components.SelectionActionBar
import com.albumworkshop.ui.sheets.RenameSheet
import com.albumworkshop.ui.sheets.ResizeSheet
import com.albumworkshop.ui.viewer.MediaViewer

@Composable
fun RootScreen(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val state = viewModel.state

    var hasPermission by remember { mutableStateOf(hasMediaPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val granted = hasMediaPermission(context)
        hasPermission = granted
        if (granted) viewModel.refresh()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(requiredPermissions())
    }

    // 从系统设置返回时重新判断
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasMediaPermission(context)
                if (granted && !hasPermission) viewModel.refresh()
                hasPermission = granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 系统授权弹窗（重命名 / 删除）
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(state.consent) {
        val consent = state.consent
        if (consent != null) {
            runCatching {
                consentLauncher.launch(IntentSenderRequest.Builder(consent.intentSender).build())
            }.onFailure { viewModel.onConsentResult(false) }
        }
    }

    if (!hasPermission) {
        PermissionScreen(
            onRequest = { permissionLauncher.launch(requiredPermissions()) },
            onOpenSettings = { openAppSettings(context) },
        )
        return
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message.text)
            viewModel.consumeMessage(message.id)
        }
    }

    BackHandler(enabled = state.openAlbum != null) { viewModel.closeAlbum() }
    BackHandler(enabled = state.selection != null) { viewModel.dismissSelection() }
    BackHandler(enabled = state.sheet !is Sheet.None) { viewModel.closeSheet() }
    // 放在最后注册，保证预览打开时返回键先关预览
    BackHandler(enabled = state.viewer != null) { viewModel.closeViewer() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val itemSelection = state.selection as? Selection.ItemSelection
        val selecting = itemSelection != null
        val selectedIds = remember(itemSelection) {
            itemSelection?.items?.mapTo(HashSet()) { it.id } ?: emptySet()
        }

        val openAlbum = state.openAlbum
        if (openAlbum == null) {
            GalleryScreen(
                state = state,
                onTabSelected = viewModel::selectTab,
                onOpenAlbum = viewModel::openAlbum,
                onLongPressAlbum = viewModel::longPressAlbum,
                onOpenItem = viewModel::openViewer,
                onLongPressItem = viewModel::longPressItem,
                onToggleItem = viewModel::toggleItemSelection,
                onRefresh = viewModel::refresh,
            )
        } else {
            AlbumScreen(
                album = openAlbum,
                items = state.albumItems,
                loading = state.loadingAlbum,
                selecting = selecting,
                selectedIds = selectedIds,
                onBack = viewModel::closeAlbum,
                onOpenItem = viewModel::openViewer,
                onLongPressItem = viewModel::longPressItem,
                onToggleItem = viewModel::toggleItemSelection,
                onRefresh = viewModel::refresh,
            )
        }

        // 底部操作条：动画退出时保留上一次的内容
        val lastSelection = remember { mutableStateOf<Selection?>(null) }
        LaunchedEffect(state.selection) {
            if (state.selection != null) lastSelection.value = state.selection
        }
        AnimatedVisibility(
            visible = state.selection != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            lastSelection.value?.let { selection ->
                val multi = selection.items.size > 1
                val title = when {
                    selection is Selection.AlbumSelection -> selection.title
                    multi -> "已选 ${selection.items.size} 项"
                    else -> selection.items.first().displayName
                }
                val subtitle = if (multi) {
                    "共 ${selection.items.size} 个文件 · ${formatFileSize(selection.items.sumOf { it.size })}"
                } else {
                    val item = selection.items.first()
                    buildString {
                        append(item.resolutionText)
                        if (item.size > 0L) {
                            append("  ·  ")
                            append(item.displaySize())
                        }
                        if (item.modifiedText.isNotEmpty()) {
                            append("  ·  ")
                            append(item.modifiedText)
                        }
                    }
                }
                val totalVisible = viewModel.visibleItems().size
                val allSelected = selection is Selection.ItemSelection &&
                    totalVisible > 0 && selection.items.size >= totalVisible

                SelectionActionBar(
                    title = title,
                    subtitle = subtitle,
                    showResize = selection.items.any { !it.isVideo },
                    onResize = viewModel::openResizeSheet,
                    onRename = viewModel::openRenameSheet,
                    onDelete = viewModel::requestDelete,
                    onDismiss = viewModel::dismissSelection,
                    showSelectAll = selection is Selection.ItemSelection,
                    allSelected = allSelected,
                    onSelectAll = {
                        if (allSelected) viewModel.dismissSelection() else viewModel.selectAllVisible()
                    },
                )
            }
        }

        when (val sheet = state.sheet) {
            is Sheet.Resize -> ResizeSheet(
                targets = sheet.items,
                onDismiss = viewModel::closeSheet,
                onConfirm = { width, overwrite -> viewModel.executeResize(width, overwrite) },
            )

            is Sheet.Rename -> {
                val tags by viewModel.tagStore.tags.collectAsState()
                RenameSheet(
                    targets = sheet.items,
                    tags = tags,
                    onAddTag = viewModel::addTag,
                    onUpdateTag = viewModel::updateTag,
                    onDeleteTag = viewModel::deleteTag,
                    onDismiss = viewModel::closeSheet,
                    onConfirm = viewModel::executeRename,
                )
            }

            else -> Unit
        }

        val confirmDelete = state.confirmDelete
        if (confirmDelete != null) {
            DeleteConfirmDialog(
                items = confirmDelete,
                onDismiss = viewModel::dismissDelete,
                onConfirm = viewModel::confirmDelete,
            )
        }

        val busy = state.busy
        if (busy != null) BusyOverlay(text = busy)

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (state.selection != null) 196.dp else 28.dp),
        )

        // 全屏预览盖在最上层
        val viewer = state.viewer
        if (viewer != null) {
            MediaViewer(
                items = viewer.items,
                initialIndex = viewer.index,
                onClose = viewModel::closeViewer,
                onLongPress = viewModel::longPressInViewer,
            )
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
