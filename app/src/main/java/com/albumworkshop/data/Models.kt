package com.albumworkshop.data

import android.net.Uri

/**
 * 一条媒体记录（图片或视频），字段全部来自 MediaStore。
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val bucketId: Long,
    val bucketName: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val size: Long,
    val dateModified: Long,
    /** Android 10+ 才有，形如 "DCIM/Camera/" */
    val relativePath: String? = null,
    /** DATA 列，Android 9 及以下用来定位真实路径 */
    val absolutePath: String? = null,
    val durationMs: Long = 0L,
    val isVideo: Boolean = false,
) {

    /** 带点的扩展名，如 ".jpg"；没有则返回空串 */
    val extension: String
        get() {
            val dot = displayName.lastIndexOf('.')
            return if (dot > 0 && dot < displayName.length - 1) displayName.substring(dot) else ""
        }

    /** 不含扩展名的文件名 */
    val baseName: String
        get() = if (extension.isEmpty()) displayName else displayName.dropLast(extension.length)

    val resolutionText: String
        get() = if (width > 0 && height > 0) "$width × $height" else "未知尺寸"

    /**
     * 人类可读的文件大小：KB / MB / GB。
     *
     * 用 Double 而不是 Float 计算——视频动辄几个 G，Float 只有 7 位有效数字，
     * 换算时会把 2.4 GB 显示成 2.3 GB 这种误差。
     */
    fun displaySize(): String {
        if (size <= 0L) return "0 KB"
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format("%.2f GB", gb)
            mb >= 1.0 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }
}

/**
 * 一个相册（MediaStore 的 bucket）。
 */
data class Album(
    val id: Long,
    val name: String,
    val coverUri: Uri,
    val count: Int,
    val latestModified: Long,
)

/**
 * 一次查询拿到的全量媒体，避免重复扫 MediaStore。
 *
 * @param all 图片 + 视频，按修改时间倒序，供“全部”标签页与全屏预览使用
 */
data class MediaLibrary(
    val albums: List<Album>,
    val videos: List<MediaItem>,
    val all: List<MediaItem>,
)
