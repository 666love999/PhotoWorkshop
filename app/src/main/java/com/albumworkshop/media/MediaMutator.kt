package com.albumworkshop.media

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 删除 / 重命名系统相册里的媒体文件。
 *
 * Android 11 起，应用修改「别人的」媒体文件必须由用户逐次授权，
 * 这里统一用 MediaStore.createWriteRequest / createDeleteRequest 拉起系统弹窗。
 */
object MediaMutator {

    private const val TAG = "MediaMutator"

    /** 需要用户点击系统授权弹窗后才能继续 */
    class ConsentRequired(val intentSender: IntentSender) : Exception("需要系统授权")

    @RequiresApi(Build.VERSION_CODES.R)
    fun deleteRequest(context: Context, uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender

    @RequiresApi(Build.VERSION_CODES.R)
    fun writeRequest(context: Context, uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(context.contentResolver, uris).intentSender

    /** 返回 (成功数, 失败数) */
    suspend fun delete(context: Context, uris: List<Uri>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var success = 0
        var failed = 0
        uris.forEach { uri ->
            // 部分 ROM（MIUI 等）删成功仍返回 0；而系统授权弹窗本身往往已经把文件删掉了，
            // 再删一次会抛「Unknown URL」。所以删除结果只当参考，最终以「记录还在不在」为准。
            val removed = try {
                context.contentResolver.delete(uri, null, null) > 0
            } catch (e: SecurityException) {
                val sender = recoverableSender(e)
                if (sender != null) throw ConsentRequired(sender)
                Log.w(TAG, "删除失败: $uri", e)
                false
            } catch (e: Exception) {
                Log.w(TAG, "删除异常: $uri", e)
                false
            }
            if (removed || !exists(context, uri)) success++ else failed++
        }
        success to failed
    }

    /**
     * 删除之后确认这条记录是否还在。
     *
     * 「查不到」= 已经删掉了；进了回收站（Android 11+ 的 is_trashed）也算删除，
     * 相册里已经看不到，系统会在 30 天内清理。查询本身抛 SecurityException
     * 说明记录还在但没权限，其余异常（如 URI 已失效）按已删除处理。
     */
    private fun exists(context: Context, uri: Uri): Boolean {
        return try {
            val hasTrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val projection = if (hasTrash) {
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
            } else {
                arrayOf(MediaStore.MediaColumns._ID)
            }
            var found = false
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                found = if (cursor.moveToFirst()) {
                    val index = if (hasTrash) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    } else {
                        -1
                    }
                    index < 0 || cursor.getInt(index) == 0
                } else {
                    false
                }
            }
            found
        } catch (e: SecurityException) {
            true
        } catch (e: Exception) {
            false
        }
    }


    /** 直接改 DISPLAY_NAME，文件本体不搬家（等价于原地重命名，覆盖原名） */
    suspend fun rename(context: Context, plan: List<Pair<Uri, String>>): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            var success = 0
            var failed = 0
            plan.forEach { (uri, newName) ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                }
                try {
                    val count = context.contentResolver.update(uri, values, null, null)
                    if (count > 0) success++ else failed++
                } catch (e: SecurityException) {
                    val sender = recoverableSender(e)
                    if (sender != null) throw ConsentRequired(sender)
                    Log.w(TAG, "重命名失败: $uri", e)
                    failed++
                } catch (e: Exception) {
                    Log.w(TAG, "重命名异常: $uri", e)
                    failed++
                }
            }
            success to failed
        }

    private fun recoverableSender(e: SecurityException): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
            return e.userAction.actionIntent.intentSender
        }
        return null
    }

    /** 去掉文件名里的非法字符 */
    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "")
            .trim()
            .trimEnd('.')
            .take(120)
}
