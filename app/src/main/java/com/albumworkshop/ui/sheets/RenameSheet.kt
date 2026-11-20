package com.albumworkshop.ui.sheets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.albumworkshop.data.MediaItem
import com.albumworkshop.data.RenameTag
import com.albumworkshop.media.MediaMutator

/**
 * 重命名面板：从底部升起，占屏幕 90% 高度。
 * 上方是标签仓库（点击追加、长按编辑/删除），最下方是新建标签输入框。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RenameSheet(
    targets: List<MediaItem>,
    tags: List<RenameTag>,
    onAddTag: (String) -> Boolean,
    onUpdateTag: (String, String) -> Boolean,
    onDeleteTag: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val first = targets.first()

    val selectedIds = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var editingTag by remember { mutableStateOf<RenameTag?>(null) }

    val selectedTexts = selectedIds.mapNotNull { id -> tags.firstOrNull { it.id == id }?.text }
    val suffix = selectedTexts.joinToString("")
    val previewName = MediaMutator.sanitizeFileName(first.baseName + suffix) + first.extension

    val submitTag: () -> Unit = {
        val text = input.trim()
        if (text.isNotEmpty() && onAddTag(text)) {
            input = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .imePadding()
                .padding(horizontal = 22.dp)
        ) {
            Text(text = "重命名", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (targets.size == 1) first.displayName else "共 ${targets.size} 个文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(16.dp))

            // 结果预览
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        text = "新文件名",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = previewName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (targets.size > 1) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "每个文件都在原文件名后追加相同后缀",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "我的标签",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (selectedTexts.isEmpty()) "点击追加后缀" else "已选 ${selectedTexts.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedTexts.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (tags.isEmpty()) {
                    Text(
                        text = "还没有标签，在下方输入文字后点「添加」，标签会永久保存并全局可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            TagChip(
                                text = tag.text,
                                selected = selectedIds.contains(tag.id),
                                onClick = {
                                    if (selectedIds.contains(tag.id)) {
                                        selectedIds.remove(tag.id)
                                    } else {
                                        selectedIds.add(tag.id)
                                    }
                                },
                                onLongClick = { editingTag = tag },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "长按标签可以修改文字或删除",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(24) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入新标签") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitTag() }),
                    trailingIcon = {
                        if (input.isNotBlank()) {
                            androidx.compose.material3.IconButton(onClick = { submitTag() }) {
                                Icon(Icons.Rounded.Add, contentDescription = "添加标签")
                            }
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { submitTag() },
                    enabled = input.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.height(56.dp),
                ) {
                    Text("添加")
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { onConfirm(selectedTexts) },
                enabled = selectedTexts.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("确认重命名", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    val tagBeingEdited = editingTag
    if (tagBeingEdited != null) {
        TagEditDialog(
            tag = tagBeingEdited,
            onDismiss = { editingTag = null },
            onSave = { text ->
                if (onUpdateTag(tagBeingEdited.id, text)) editingTag = null
            },
            onDelete = {
                selectedIds.remove(tagBeingEdited.id)
                onDeleteTag(tagBeingEdited.id)
                editingTag = null
            },
        )
    }
}

/**
 * 标签胶囊。
 *
 * 选中只改变颜色：不加图标、不改字重、不改内外边距，
 * 所以宽度与位置在选中前后完全一致，FlowRow 不会因此重新换行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        // 边框始终存在，只换颜色，避免描边出现/消失带来的视觉跳动
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun TagEditDialog(
    tag: RenameTag,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(tag.id) { mutableStateOf(tag.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("编辑标签") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(24) },
                    singleLine = true,
                    label = { Text("标签文字") },
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "删除只会从全局标签列表移除，已经改好的文件名不受影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        },
    )
}
