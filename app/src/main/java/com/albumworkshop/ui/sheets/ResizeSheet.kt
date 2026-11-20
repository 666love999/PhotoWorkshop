package com.albumworkshop.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.albumworkshop.data.MediaItem
import com.albumworkshop.media.ImageResizer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 修改分辨率面板：从底部升起，占屏幕 90% 高度。
 * 默认宽度是原图的 50%，高度按同比例自动计算。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResizeSheet(
    targets: List<MediaItem>,
    onDismiss: () -> Unit,
    onConfirm: (Int, Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val first = targets.first()

    // MediaStore 的宽高是「存储方向」的，旋转图按 EXIF 摆正后要交换宽高
    var originalSize by remember(first.uri) {
        mutableStateOf(
            if (first.width > 0 && first.height > 0) first.width to first.height else 1080 to 1920
        )
    }
    var widthText by remember(first.uri) {
        mutableStateOf((originalSize.first / 2).coerceAtLeast(16).toString())
    }
    var userEdited by remember(first.uri) { mutableStateOf(false) }
    // 默认保留原图，覆盖属于不可逆操作，要用户主动打开
    var overwrite by remember(first.uri) { mutableStateOf(false) }

    LaunchedEffect(first.uri) {
        val real = runCatching { ImageResizer.displaySize(context, first) }.getOrNull()
        if (real != null && real.first > 0 && real.second > 0) {
            originalSize = real
            if (!userEdited) widthText = (real.first / 2).coerceAtLeast(16).toString()
        }
    }

    val originalWidth = originalSize.first
    val originalHeight = originalSize.second
    val ratio = originalHeight.toFloat() / originalWidth

    val targetWidth = widthText.toIntOrNull()
    val isValid = targetWidth != null && targetWidth in 16..30000
    val targetHeight = if (isValid) max(1, (targetWidth!! * ratio).roundToInt()) else null
    val keyboard = LocalSoftwareKeyboardController.current

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
            Text(
                text = "修改分辨率",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (targets.size == 1) {
                    "${first.displayName} · ${first.displaySize()}"
                } else {
                    "共 ${targets.size} 张图片，将统一使用该宽度"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            // 原始 / 输出尺寸对照
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SizeBlock(
                        label = "原始尺寸",
                        value = "$originalWidth × $originalHeight",
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(14.dp))
                    SizeBlock(
                        label = "输出尺寸",
                        value = targetHeight?.let { "$targetWidth × $it" } ?: "—",
                        highlight = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = widthText,
                onValueChange = { input ->
                    if (input.length <= 5 && input.all { it.isDigit() }) {
                        widthText = input
                        userEdited = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("宽度") },
                suffix = { Text("px") },
                singleLine = true,
                isError = !isValid,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    keyboard?.hide()
                    if (isValid) onConfirm(targetWidth!!, overwrite)
                }),
                supportingText = if (isValid) {
                    null
                } else {
                    { Text("请输入 16 – 30000 之间的宽度") }
                },
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 75, 100).forEach { percent ->
                    val value = max(1, originalWidth * percent / 100)
                    AssistChip(
                        onClick = { widthText = value.toString() },
                        label = { Text("$percent%") },
                        shape = RoundedCornerShape(50),
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 覆盖原文件开关
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (overwrite) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "覆盖原文件",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (overwrite) {
                                "直接改写原图，文件名不变，此操作无法恢复"
                            } else {
                                "关闭时生成新文件，原图保留不动"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (overwrite) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (overwrite) {
                        "将直接改写原图内容，文件名保持 ${first.displayName} 不变。目标宽度大于原图时按原尺寸输出，不会放大。"
                    } else {
                        "新文件名加后缀 gai（如 ${first.baseName}gai${first.extension}），保存在原目录，原文件保留不动。Download 等系统不允许写入的目录会存到 Pictures 下。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    keyboard?.hide()
                    if (isValid) onConfirm(targetWidth!!, overwrite)
                },
                enabled = isValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("确认修改", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun SizeBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
