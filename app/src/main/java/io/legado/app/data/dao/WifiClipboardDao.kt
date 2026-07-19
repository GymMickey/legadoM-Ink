package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.WifiClipboard
import kotlinx.coroutines.flow.Flow

/**
 * WiFi 传书剪贴板历史数据访问对象（DAO）
 *
 * 提供剪贴板历史记录的增删改查操作
 * 支持响应式查询，数据变化时自动更新
 */
@Dao
interface WifiClipboardDao {

    /**
     * 获取全部剪贴板历史，按时间倒序
     * 用于 RecyclerView 列表展示
     *
     * @return 剪贴板历史记录列表的 Flow 流
     */
    @Query("SELECT * FROM wifi_clipboard ORDER BY time DESC")
    fun flowAll(): Flow<List<WifiClipboard>>

    /**
     * 根据 content 精确匹配（用于去重判断）
     *
     * @param content 文本内容
     * @return 匹配的记录，不存在则返回 null
     */
    @Query("SELECT * FROM wifi_clipboard WHERE content = :content LIMIT 1")
    suspend fun getByContent(content: String): WifiClipboard?

    /**
     * 更新指定记录的时间（去重时置顶）
     *
     * @param id 记录 ID
     * @param time 更新时间戳
     */
    @Query("UPDATE wifi_clipboard SET time = :time WHERE id = :id")
    suspend fun updateTime(id: Long, time: Long)

    /**
     * 插入新记录
     * 如果记录已存在则替换
     *
     * @param item 要插入的记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WifiClipboard)

    /**
     * 保持最多 max 条记录，删除最旧的超出部分
     *
     * @param max 最大保留数量
     */
    @Query("DELETE FROM wifi_clipboard WHERE id NOT IN (SELECT id FROM wifi_clipboard ORDER BY time DESC LIMIT :max)")
    suspend fun trimToMax(max: Int)

    /**
     * 删除单条记录
     *
     * @param item 要删除的记录
     */
    @Delete
    suspend fun delete(item: WifiClipboard)

    /**
     * 清空全部记录
     */
    @Query("DELETE FROM wifi_clipboard")
    suspend fun deleteAll()

    /**
     * 核心方法：插入或更新
     *
     * 如果 content 已存在则更新时间以置顶，否则插入新记录并裁剪到 50 条
     *
     * @param content 文本内容
     */
    @Transaction
    suspend fun insertOrUpdate(content: String) {
        val existing = getByContent(content)
        if (existing != null) {
            updateTime(existing.id, System.currentTimeMillis())
        } else {
            insert(WifiClipboard(content = content))
            trimToMax(5)
        }
    }
}