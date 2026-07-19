package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import com.google.gson.annotations.SerializedName
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

object ClipboardController {

    // 单条内容最大字符数（防大文本写库）
    private const val MAX_CONTENT_LENGTH = 5000

    /**
     * 接收剪贴板内容
     * POST /clipboard
     * Body: { "items": ["文本1", "文本2", ...] }
     *
     * 处理逻辑：
     * 1. 解析 JSON → 提取 items 数组
     * 2. 对每条：trim → take(5000) → 过滤空内容
     * 3. 逐条调用 dao.insertOrUpdate()（自动去重/置顶/裁剪到 50 条）
     */
    suspend fun receiveClipboard(postData: String?): ReturnData {
        val returnData = ReturnData()

        if (postData.isNullOrBlank()) {
            return returnData.setErrorMsg("请求体为空")
        }

        val request = try {
            GSON.fromJsonObject<ClipboardRequest>(postData).getOrThrow()
        } catch (e: Exception) {
            return returnData.setErrorMsg("JSON 解析失败: ${e.message}")
        }

        val items = request.items
            ?.map { it.trim().take(MAX_CONTENT_LENGTH) }
            ?.filter { it.isNotEmpty() }

        if (items.isNullOrEmpty()) {
            return returnData.setErrorMsg("没有有效内容")
        }

        val dao = appDb.wifiClipboardDao
        for (item in items) {
            dao.insertOrUpdate(item)
        }

        return returnData.setData(mapOf("count" to items.size))
    }

    // 请求体结构（内联 data class，不新建文件）
    private data class ClipboardRequest(
        @SerializedName("items") val items: List<String>? = null
    )
}