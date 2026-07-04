package io.legado.app.help.source

import android.content.Context
import io.legado.app.constant.PreferKey
import org.json.JSONObject

/**
 * 跳转确认记忆管理
 * 以源URL为键，存储 allow/deny 行为
 */
object OpenUrlConfirmMemory {

    private fun getPrefs(context: Context) =
        context.getSharedPreferences("openUrlConfirm", Context.MODE_PRIVATE)

    /** 记住选择 */
    fun remember(context: Context, sourceUrl: String, allow: Boolean) {
        if (sourceUrl.isBlank()) return
        val action = if (allow) "allow" else "deny"
        val jsonStr = getPrefs(context).getString(PreferKey.openUrlConfirmMemory, "{}") ?: "{}"
        val json = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            JSONObject()
        }
        json.put(sourceUrl, action)
        getPrefs(context).edit().putString(PreferKey.openUrlConfirmMemory, json.toString()).apply()
    }

    /** 读取记忆，返回 allow / deny / null */
    fun getAction(context: Context, sourceUrl: String): String? {
        if (sourceUrl.isBlank()) return null
        val jsonStr = getPrefs(context).getString(PreferKey.openUrlConfirmMemory, "{}") ?: "{}"
        return try {
            JSONObject(jsonStr).optString(sourceUrl, null)
        } catch (e: Exception) {
            null
        }
    }

    /** 清理指定源的记忆 */
    fun forget(context: Context, sourceUrl: String) {
        if (sourceUrl.isBlank()) return
        val jsonStr = getPrefs(context).getString(PreferKey.openUrlConfirmMemory, "{}") ?: "{}"
        val json = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            return
        }
        if (json.has(sourceUrl)) {
            json.remove(sourceUrl)
            getPrefs(context).edit().putString(PreferKey.openUrlConfirmMemory, json.toString()).apply()
        }
    }

    /** 清空所有记忆 */
    fun clearAll(context: Context) {
        getPrefs(context).edit().remove(PreferKey.openUrlConfirmMemory).apply()
    }

}