package com.albumworkshop.data

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 通过 MediaStore 读取系统相册里的图片与视频（只读，不做任何写入）。
 */
class MediaStoreRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val isQOrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 一次查出相册、视频和“全部”列表，图片只扫一遍。
     */
    suspend fun loadLibrary(): MediaLibrary = withContext(Dispatchers.IO) {
        val images = queryImages(bucketId = null)
        val videos = queryVideos()
        val albums = images
            .groupBy { it.bucketId }
            .map { (id, list) ->
                val first = list.first()
                Album(
                    id = id,
                    name = first.bucketName.ifBlank { "未命名相册" },
                    coverUri = first.uri,
                    count = list.size,
                    latestModified = first.dateModified,
                )
            }
            .sortedByDescending { it.latestModified }
        MediaLibrary(
            albums = albums,
            videos = videos,
            all = (images + videos).sortedByDescending { it.dateModified },
        )
    }

    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.IO) {
        queryImages(bucketId = null)
            .groupBy { it.bucketId }
            .map { (id, list) ->
                val first = list.first()
                Album(
                    id = id,
                    name = first.bucketName.ifBlank { "未命名相册" },
                    coverUri = first.uri,
                    count = list.size,
                    latestModified = first.dateModified,
                )
            }
            .sortedByDescending { it.latestModified }
    }

    suspend fun loadImages(bucketId: Long): List<MediaItem> = withContext(Dispatchers.IO) {
        queryImages(bucketId)
    }

    suspend fun loadVideos(): List<MediaItem> = withContext(Dispatchers.IO) {
        queryVideos()
    }

    // ------------------------------------------------------------------ 图片

    private fun queryImages(bucketId: Long?): List<MediaItem> {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATA,
        )
        if (isQOrAbove) projection.add(MediaStore.Images.Media.RELATIVE_PATH)

        val selection = if (bucketId != null) "${MediaStore.Images.Media.BUCKET_ID}=?" else null
        val args = if (bucketId != null) arrayOf(bucketId.toString()) else null
        val sort = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        val result = ArrayList<MediaItem>()
        runCatching {
            resolver.query(uri, projection.toTypedArray(), selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                val relCol = if (isQOrAbove) cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        displayName = cursor.getString(nameCol) ?: "IMG_$id",
                        bucketId = cursor.getLong(bucketIdCol),
                        bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "" else "",
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg",
                        size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        dateModified = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        relativePath = if (relCol >= 0) cursor.getString(relCol) else null,
                        absolutePath = if (dataCol >= 0) cursor.getString(dataCol) else null,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "查询图片失败", it) }
        return result
    }

    // ------------------------------------------------------------------ 视频

    private fun queryVideos(): List<MediaItem> {
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA,
        )
        if (isQOrAbove) projection.add(MediaStore.Video.Media.RELATIVE_PATH)

        val sort = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        val result = ArrayList<MediaItem>()
        runCatching {
            resolver.query(uri, projection.toTypedArray(), null, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val mimeCol = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                val relCol = if (isQOrAbove) cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    result += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(uri, id),
                        displayName = cursor.getString(nameCol) ?: "VID_$id",
                        bucketId = cursor.getLong(bucketIdCol),
                        bucketName = if (bucketNameCol >= 0) cursor.getString(bucketNameCol) ?: "" else "",
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "video/mp4" else "video/mp4",
                        size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        dateModified = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        relativePath = if (relCol >= 0) cursor.getString(relCol) else null,
                        absolutePath = if (dataCol >= 0) cursor.getString(dataCol) else null,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                        isVideo = true,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "查询视频失败", it) }
        return result
    }

    private companion object {
        const val TAG = "MediaStoreRepo"
    }
}
