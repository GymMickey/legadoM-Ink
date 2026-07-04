package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.UploadHistory
import io.legado.app.data.entities.UploadHistoryWithRule
import kotlinx.coroutines.flow.Flow

/**
 * 上传历史记录数据访问对象（DAO）
 * 
 * 提供历史记录数据的增删改查操作
 * 支持多种查询方式：按规则、按时间、按成功状态、搜索等
 */
@Dao
interface UploadHistoryDao {

    /**
     * 获取所有历史记录（响应式）
     * 按上传时间倒序排列
     * 
     * @return 历史记录列表的Flow流，数据变化时自动更新
     */
    @Query("SELECT * FROM upload_histories ORDER BY uploadTime DESC")
    fun flowAll(): Flow<List<UploadHistory>>

    /**
     * 获取所有历史记录（带规则名称，响应式）
     * 关联查询规则表，获取最新的规则名称
     * 如果规则被删除，则使用历史记录中保存的规则名称
     * 按上传时间倒序排列
     * 
     * @return 历史记录与规则名称的关联列表的Flow流
     */
    @Query("""
        SELECT h.*, r.summary as ruleSummary
        FROM upload_histories h
        LEFT JOIN direct_link_upload_rules r ON h.ruleId = r.id
        ORDER BY h.uploadTime DESC
    """)
    fun flowAllWithRule(): Flow<List<UploadHistoryWithRule>>

    /**
     * 根据规则ID获取历史记录（响应式）
     * 按上传时间倒序排列
     * 
     * @param ruleId 规则ID
     * @return 历史记录列表的Flow流
     */
    @Query("SELECT * FROM upload_histories WHERE ruleId = :ruleId ORDER BY uploadTime DESC")
    fun flowByRuleId(ruleId: Long): Flow<List<UploadHistory>>

    /**
     * 搜索历史记录（响应式）
     * 支持按文件名、下载链接、规则名称搜索
     * 按上传时间倒序排列
     * 
     * @param keyword 搜索关键词
     * @return 匹配的历史记录列表的Flow流
     */
    @Query("""
        SELECT * FROM upload_histories 
        WHERE fileName LIKE '%' || :keyword || '%' 
        OR downloadUrl LIKE '%' || :keyword || '%'
        OR ruleSummary LIKE '%' || :keyword || '%'
        ORDER BY uploadTime DESC
    """)
    fun flowSearch(keyword: String): Flow<List<UploadHistory>>

    /**
     * 根据成功状态获取历史记录（响应式）
     * 按上传时间倒序排列
     * 
     * @param success 是否成功
     * @return 历史记录列表的Flow流
     */
    @Query("SELECT * FROM upload_histories WHERE success = :success ORDER BY uploadTime DESC")
    fun flowBySuccess(success: Boolean): Flow<List<UploadHistory>>

    /**
     * 根据时间范围获取历史记录（响应式）
     * 按上传时间倒序排列
     * 
     * @param startTime 开始时间戳
     * @param endTime 结束时间戳
     * @return 历史记录列表的Flow流
     */
    @Query("SELECT * FROM upload_histories WHERE uploadTime >= :startTime AND uploadTime <= :endTime ORDER BY uploadTime DESC")
    fun flowByTimeRange(startTime: Long, endTime: Long): Flow<List<UploadHistory>>

    /**
     * 获取所有历史记录（一次性）
     * 按上传时间倒序排列
     * 
     * @return 历史记录列表
     */
    @Query("SELECT * FROM upload_histories ORDER BY uploadTime DESC")
    suspend fun getAll(): List<UploadHistory>

    /**
     * 根据ID获取历史记录（一次性）
     * 
     * @param id 历史记录ID
     * @return 历史记录对象，如果不存在则返回null
     */
    @Query("SELECT * FROM upload_histories WHERE id = :id")
    suspend fun getById(id: Long): UploadHistory?

    /**
     * 获取历史记录总数
     * 
     * @return 历史记录数量
     */
    @Query("SELECT COUNT(*) FROM upload_histories")
    suspend fun getCount(): Int

    /**
     * 获取成功上传次数
     * 
     * @return 成功上传的次数
     */
    @Query("SELECT COUNT(*) FROM upload_histories WHERE success = 1")
    suspend fun getSuccessCount(): Int

    /**
     * 获取上传文件总大小
     * 只统计成功的上传
     * 
     * @return 文件总大小（字节），如果没有则返回null
     */
    @Query("SELECT SUM(fileSize) FROM upload_histories WHERE success = 1")
    suspend fun getTotalUploadSize(): Long?

    /**
     * 插入历史记录
     * 如果记录已存在，则替换
     * 
     * @param history 要插入的历史记录
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: UploadHistory)

    /**
     * 删除历史记录
     * 
     * @param history 要删除的历史记录
     */
    @Delete
    suspend fun delete(history: UploadHistory)

    /**
     * 根据ID删除历史记录
     * 
     * @param id 要删除的历史记录ID
     */
    @Query("DELETE FROM upload_histories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 删除所有历史记录
     * 
     * @return 删除的记录数
     */
    @Query("DELETE FROM upload_histories")
    suspend fun deleteAll(): Int

    /**
     * 删除指定时间之前的历史记录
     * 用于清理旧记录
     * 
     * @param timestamp 时间戳，删除此时间之前的记录
     * @return 删除的记录数
     */
    @Query("DELETE FROM upload_histories WHERE uploadTime < :timestamp")
    suspend fun deleteOldRecords(timestamp: Long): Int

    /**
     * 根据规则ID删除历史记录
     * 当规则被删除时，级联删除相关历史记录
     * 
     * @param ruleId 规则ID
     * @return 删除的记录数
     */
    @Query("DELETE FROM upload_histories WHERE ruleId = :ruleId")
    suspend fun deleteByRuleId(ruleId: Long): Int
}
