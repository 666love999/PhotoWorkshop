package com.albumworkshop.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.albumworkshop.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片改分辨率：按目标宽度等比缩放，生成「原文件名 + gai」的新文件，
 * 存放在原文件所在目录，原文件保持不变。
 */
object ImageResizer {

    private const val TAG = "ImageResizer"
    private const val JPEG_QUALITY = 95

    data class Outcome(
        val uri: Uri,
        val displayName: String,
        val width: Int,
        val height: Int,
    )

    suspend fun resize(context: Context, item: MediaItem, targetWidth: Int): Outcome =
        withContext(Dispatchers.IO) {
            require(targetWidth > 0) { "宽度必须大于 0" }
            val resolver = context.contentResolver

            // 1. 读原始尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("无法读取图片尺寸")

            // 2. EXIF 方向（很多相机照片是横向存储 + 旋转标记）
            val orientation = readOrientation(context, item.uri)

            // 3. 按采样率解码，避免大图 OOM
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IOException("图片解码失败")

            // 4. 摆正 -> 等比缩放
            val upright = applyOrientation(decoded, orientation)
            val targetHeight = max(1, (upright.height.toFloat() * targetWidth / upright.width).roundToInt())
            val scaled = if (upright.width == targetWidth) {
                upright
            } else {
                Bitmap.createScaledBitmap(upright, targetWidth, targetHeight, true)
            }

            // 5. 压缩到缓存目录
            val isPng = item.mimeType.equals("image/png", true)
            val extension = if (isPng) ".png" else ".jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val temp = File(context.cacheDir, "aw_resize_${System.currentTimeMillis()}$extension")
            try {
                temp.outputStream().use { out ->
                    val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    scaled.compress(format, JPEG_QUALITY, out)
                }
                if (!isPng) copyExif(context, item, temp)

                // 6. 写回原目录
                val newName = buildUniqueName(context, item, extension)
                val newUri = saveToMediaStore(context, item, temp, newName, mimeType)
                Outcome(newUri, newName, targetWidth, targetHeight)
            } finally {
                temp.delete()
            }
        }

    /**
     * 按 EXIF 摆正后的显示尺寸。MediaStore 里的 WIDTH/HEIGHT 是「存储方向」的，
     * 竖拍但横向存储的照片必须交换宽高，界面上显示的尺寸才和肉眼一致。
     */
    suspend fun displaySize(context: Context, item: MediaItem): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                item.width to item.height
            } else when (readOrientation(context, item.uri)) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE,
                ExifInterface.ORIENTATION_TRANSVERSE -> height to width

                else -> width to height
            }
        }

    // --------------------------------------------------------------- 文件落盘

    private fun saveToMediaStore(
        context: Context,
        item: MediaItem,
        source: File,
        displayName: String,
        mimeType: String,
    ): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.SIZE, source.length())
            if (isQ) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathOf(item))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                put(MediaStore.MediaColumns.DATA, File(directoryOf(item), displayName).absolutePath)
            }
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("无法在相册中创建新文件")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("无法写入新文件")
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        if (isQ) {
            runCatching {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
        }
        return uri
    }

    private fun relativePathOf(item: MediaItem): String {
        item.relativePath?.takeIf { it.isNotBlank() }?.let { return it }
        val absolute = item.absolutePath
        if (absolute != null) {
            val root = Environment.getExternalStorageDirectory().absolutePath
            if (absolute.startsWith(root)) {
                val dir = absolute.substring(0, absolute.lastIndexOf('/'))
                val rel = dir.removePrefix(root).trim('/')
                if (rel.isNotEmpty()) return "$rel/"
            }
        }
        return "Pictures/"
    }

    private fun directoryOf(item: MediaItem): String {
        item.absolutePath?.substringBeforeLast('/')?.takeIf { it.isNotBlank() }?.let { return it }
        val root = Environment.getExternalStorageDirectory().absolutePath
        return File(root, relativePathOf(item).trimEnd('/')).absolutePath
    }

    private fun buildUniqueName(context: Context, item: MediaItem, extension: String): String {
        val base = item.baseName.ifBlank { "image" }
        var candidate = "${base}gai$extension"
        var index = 2
        while (nameExists(context, item, candidate) && index < 1000) {
            candidate = "${base}gai_$index$extension"
            index++
        }
        return candidate
    }

    private fun nameExists(context: Context, item: MediaItem, displayName: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return runCatching {
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !item.relativePath.isNullOrBlank()) {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                args = arrayOf(displayName, item.relativePath)
            } else {
                val dir = directoryOf(item)
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.DATA}=?"
                args = arrayOf(displayName, "$dir/$displayName")
            }
            resolver.query(collection, projection, selection, args, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 位图

    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sample = 1
        while (width / sample / 2 >= targetWidth * 2 && sample < 16) {
            sample *= 2
        }
        // 再按总像素限制一次，防止长图爆内存
        while ((width.toLong() / sample) * (height.toLong() / sample) > 16_000_000L && sample < 16) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    private fun copyExif(context: Context, item: MediaItem, target: File) = runCatching {
        val source = context.contentResolver.openInputStream(item.uri)?.use { ExifInterface(it) }
            ?: return@runCatching
        val dest = ExifInterface(target.absolutePath)
        dest.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_LENS_MODEL,
        ).forEach { tag ->
            source.getAttribute(tag)?.let { dest.setAttribute(tag, it) }
        }
        dest.saveAttributes()
    }.onFailure { Log.w(TAG, "复制 EXIF 失败", it) }
}
