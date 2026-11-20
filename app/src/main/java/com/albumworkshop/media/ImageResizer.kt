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
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片改分辨率：按目标宽度等比缩放。
 *
 * 两种落盘方式：
 * - 默认生成「原文件名 + gai」的新文件，存放在原目录，原文件不动
 * - [overwrite] 为 true 时直接改写原文件（Android 11+ 需要用户先授权）
 *
 * 注意原目录的可写范围：图片只能写进 DCIM / Pictures，Download 等目录会被系统拒绝，
 * 这类图片的新文件统一改存到 `Pictures/<原层级>`，并在 [Outcome.relocatedTo] 里说明去向。
 *
 * 批量处理时必须控制内存：早期版本对 4000 宽的图用 sample=1 整张解码，
 * 单张峰值就超过 100 MB，连续处理几十张必然 OOM，表现为「整批全部失败」。
 */
object ImageResizer {

    private const val TAG = "ImageResizer"
    private const val JPEG_QUALITY = 95

    /** 图片集合；Images 只接受 DCIM / Pictures 两个顶层目录，其余会被 MediaProvider 拒绝 */
    private val IMAGE_COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** 一次写入的目标：集合 + RELATIVE_PATH */
    private data class Destination(val collection: Uri, val relativePath: String)

    /** 解码后允许的最大像素数，约 1600 万（ARGB_8888 下 64 MB） */
    private const val MAX_DECODE_PIXELS = 16_000_000L

    data class Outcome(
        val uri: Uri,
        val displayName: String,
        val width: Int,
        val height: Int,
        /** 目标宽度大于原图宽度，按原尺寸输出（未放大） */
        val clamped: Boolean = false,
        /** 原目录不允许写入，新文件改存到了这里（形如 Pictures/Download）；没搬迁时为 null */
        val relocatedTo: String? = null,
    )

    suspend fun resize(
        context: Context,
        item: MediaItem,
        targetWidth: Int,
        overwrite: Boolean = false,
    ): Outcome = withContext(Dispatchers.IO) {
        require(targetWidth > 0) { "宽度必须大于 0" }
        val resolver = context.contentResolver

        // 1. 先只读尺寸，拿到原图大小再决定采样率
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(item.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) throw IOException("无法读取图片尺寸")

        // 2. EXIF 方向（很多相机照片是横向存储 + 旋转标记）
        val orientation = readOrientation(context, item.uri)

        // 3. 按目标宽度定采样率，避免整张原图进内存
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(srcWidth, srcHeight, targetWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var decoded: Bitmap? = null
        var upright: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            decoded = resolver.openInputStream(item.uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: throw IOException("图片解码失败")

            // 4. 摆正
            upright = applyOrientation(decoded, orientation)

            // 5. 等比缩放：目标宽度不允许超过原图，避免把小图拉大糊掉
            val outWidth = targetWidth.coerceIn(1, upright.width)
            val outHeight = max(1, (upright.height.toFloat() * outWidth / upright.width).roundToInt())
            scaled = if (upright.width == outWidth && upright.height == outHeight) {
                upright
            } else {
                Bitmap.createScaledBitmap(upright, outWidth, outHeight, true)
            }

            // 6. 压缩到缓存目录
            val isPng = item.mimeType.equals("image/png", true)
            val extension = if (isPng) ".png" else ".jpg"
            val mimeType = if (isPng) "image/png" else "image/jpeg"
            val temp = File(context.cacheDir, "aw_resize_${System.currentTimeMillis()}$extension")
            try {
                temp.outputStream().use { out ->
                    val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    if (!scaled.compress(format, JPEG_QUALITY, out)) {
                        throw IOException("图片编码失败")
                    }
                }
                if (!isPng) copyExif(context, item, temp)

                // 7. 写回
                if (overwrite) {
                    overwriteOriginal(context, item, temp)
                    Outcome(
                        uri = item.uri,
                        displayName = item.displayName,
                        width = outWidth,
                        height = outHeight,
                        clamped = targetWidth > upright.width,
                    )
                } else {
                    val destination = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        destinationOf(item)
                    } else {
                        null
                    }
                    val newName = buildUniqueName(context, item, extension, destination)
                    val newUri = saveToMediaStore(context, item, temp, newName, mimeType, destination)
                    Outcome(
                        uri = newUri,
                        displayName = newName,
                        width = outWidth,
                        height = outHeight,
                        clamped = targetWidth > upright.width,
                        relocatedTo = destination?.relativePath
                            ?.takeIf { it != relativePathOf(item) }
                            ?.trimEnd('/'),
                    )
                }
            } finally {
                temp.delete()
            }
        } finally {
            recycleAll(scaled, upright, decoded)
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

    /** 直接改写原文件内容，文件名不变 */
    private fun overwriteOriginal(context: Context, item: MediaItem, source: File) {
        val resolver = context.contentResolver
        // "wt" = write + truncate，必须截断否则残留旧数据的尾巴
        resolver.openOutputStream(item.uri, "wt")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: throw IOException("无法写入原文件，请检查存储权限")

        // 覆盖后 MediaStore 缓存的大小/时间还是旧的，顺手刷一下
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.SIZE, source.length())
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }
            resolver.update(item.uri, values, null, null)
        }
    }

    /**
     * 新文件往哪儿写。
     *
     * `MediaStore.Images` 只允许写进 DCIM / Pictures 这两个顶层目录，其他目录
     * （Download、文档、微信自定义文件夹）插进去会被 MediaProvider 直接拒绝：
     * `Primary directory Download not allowed ... allowed directories are [DCIM, Pictures]`。
     * 这种目录一律改存到 `Pictures/<原层级>`，保留层级既方便找回也不会撞名。
     */
    private fun destinationOf(item: MediaItem): Destination {
        val raw = relativePathOf(item).trim('/')
        val segments = raw.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return Destination(IMAGE_COLLECTION, "Pictures/")
        // 大小写不敏感，顺手把顶层目录名规范成系统认的写法（pictures → Pictures）
        val top = when (segments.first().uppercase(Locale.ROOT)) {
            "DCIM" -> "DCIM"
            "PICTURES" -> "Pictures"
            else -> null
        }
        val rest = if (top != null) segments.drop(1) else segments
        return if (top != null) {
            Destination(IMAGE_COLLECTION, (listOf(top) + rest).joinToString("/") + "/")
        } else {
            Destination(IMAGE_COLLECTION, "Pictures/" + rest.joinToString("/") + "/")
        }
    }

    private fun saveToMediaStore(
        context: Context,
        item: MediaItem,
        source: File,
        displayName: String,
        mimeType: String,
        destination: Destination?,
    ): Uri {
        val resolver = context.contentResolver
        val collection = destination?.collection ?: IMAGE_COLLECTION
        val isQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (isQ) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, destination?.relativePath ?: "Pictures/")
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

    private fun buildUniqueName(
        context: Context,
        item: MediaItem,
        extension: String,
        destination: Destination?,
    ): String {
        val base = item.baseName.ifBlank { "image" }
        var candidate = "${base}gai$extension"
        var index = 2
        while (nameExists(context, item, candidate, destination) && index < 1000) {
            candidate = "${base}gai_$index$extension"
            index++
        }
        return candidate
    }

    /** 目标目录里是否已有同名文件（查 Files 表，图片/视频混住的目录也能覆盖到） */
    private fun nameExists(
        context: Context,
        item: MediaItem,
        displayName: String,
        destination: Destination?,
    ): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        return runCatching {
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?"
                args = arrayOf(displayName, destination?.relativePath ?: "Pictures/")
            } else {
                val dir = directoryOf(item)
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.DATA}=?"
                args = arrayOf(displayName, "$dir/$displayName")
            }
            resolver.query(collection, projection, selection, args, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 位图

    /**
     * 选采样率：解码后的宽度不小于目标宽度（否则缩放会失真），
     * 同时尽可能选大的采样率，让原图不必整张进内存。
     *
     * 旧实现要求「保留 2 倍余量」（`width/2 >= target*2`），目标 2000 时
     * 4000 宽的图依然 sample=1，等于全解码；这里改成按实际需要取。
     */
    private fun calculateSampleSize(width: Int, height: Int, targetWidth: Int): Int {
        var sample = 1
        while (sample < 32 && width / (sample * 2) >= targetWidth) {
            sample *= 2
        }
        // 超长图兜底：解码后总像素超过上限时继续降采样
        while (sample < 32 &&
            (width.toLong() / sample) * (height.toLong() / sample) > MAX_DECODE_PIXELS
        ) {
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

    /**
     * 回收这一轮产生的所有位图。
     *
     * 摆正、缩放都可能返回同一个对象（尺寸没变时），所以必须按身份去重，
     * 否则二次 recycle 会抛异常。
     */
    private fun recycleAll(vararg bitmaps: Bitmap?) {
        val seen = IdentityHashMap<Bitmap, Boolean>()
        bitmaps.forEach { bitmap ->
            if (bitmap != null && !bitmap.isRecycled && seen.put(bitmap, true) == null) {
                runCatching { bitmap.recycle() }
            }
        }
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
