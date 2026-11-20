package com.albumworkshop.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 重命名用的文字标签，全局持久化（SharedPreferences + JSON）。
 */
data class RenameTag(
    val id: String,
    val text: String,
    val createdAt: Long,
)

class TagStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tags = MutableStateFlow(readFromPrefs())
    val tags: StateFlow<List<RenameTag>> = _tags.asStateFlow()

    /** 返回 null 表示重复或非法 */
    fun add(rawText: String): RenameTag? {
        val text = rawText.trim()
        if (text.isEmpty()) return null
        if (_tags.value.any { it.text.equals(text, ignoreCase = true) }) return null
        val tag = RenameTag(id = UUID.randomUUID().toString(), text = text, createdAt = System.currentTimeMillis())
        commit(_tags.value + tag)
        return tag
    }

    fun update(id: String, rawText: String): Boolean {
        val text = rawText.trim()
        if (text.isEmpty()) return false
        if (_tags.value.any { it.id != id && it.text.equals(text, ignoreCase = true) }) return false
        commit(_tags.value.map { if (it.id == id) it.copy(text = text) else it })
        return true
    }

    fun remove(id: String) {
        commit(_tags.value.filterNot { it.id == id })
    }

    private fun commit(list: List<RenameTag>) {
        _tags.value = list
        writeToPrefs(list)
    }

    private fun readFromPrefs(): List<RenameTag> {
        val raw = prefs.getString(KEY_TAGS, null)
        if (raw == null) {
            val defaults = defaultTags()
            writeToPrefs(defaults)
            return defaults
        }
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val text = obj.optString("text")
                    if (text.isNotBlank()) {
                        add(
                            RenameTag(
                                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                                text = text,
                                createdAt = obj.optLong("createdAt", 0L),
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        return parsed
    }

    private fun writeToPrefs(list: List<RenameTag>) {
        val array = JSONArray()
        list.forEach { tag ->
            array.put(
                JSONObject()
                    .put("id", tag.id)
                    .put("text", tag.text)
                    .put("createdAt", tag.createdAt)
            )
        }
        prefs.edit().putString(KEY_TAGS, array.toString()).apply()
    }

    /** 首次启动给几个常用后缀，用户可直接删改 */
    private fun defaultTags(): List<RenameTag> {
        val now = System.currentTimeMillis()
        return listOf("已压缩", "已调整", "备份", "精选").mapIndexed { index, text ->
            RenameTag(id = UUID.randomUUID().toString(), text = text, createdAt = now + index)
        }
    }

    private companion object {
        const val PREFS_NAME = "album_workshop_tags"
        const val KEY_TAGS = "rename_tags"
    }
}
