package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 上传历史记录实体类
 * 
 * 用于记录每次上传操作的详细信息，包括成功和失败的记录
 * 
 * @property id 主键ID，自动生成
 * @property fileName 文件名
 * @property fileSize 文件大小（字节）
 * @property contentType 文件类型（MIME类型）
 * @property uploadTime 上传时间戳
 * @property duration 上传耗时（毫秒）
 * @property downloadUrl 下载链接（上传成功时）
 * @property expireTime 下载链接过期时间戳（可选）
 * @property ruleId 使用的规则ID
 * @property ruleSummary 使用的规则名称
 * @property success 是否上传成功
 * @property errorMsg 错误信息（上传失败时）
 */
@Entity(
    tableName = "upload_histories",
    indices = [
        Index(value = ["uploadTime"]),    // 上传时间索引，用于按时间排序
        Index(value = ["ruleId"]),        // 规则ID索引，用于按规则筛选
        Index(value = ["success"])        // 成功状态索引，用于筛选成功/失败记录
    ],
    foreignKeys = [
        ForeignKey(
            entity = DirectLinkUploadRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE  // 规则删除时，级联删除相关历史记录
        )
    ]
)
data class UploadHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 文件信息
    val fileName: String,               // 文件名
    val fileSize: Long,                 // 文件大小（字节）
    val contentType: String,            // 文件类型（MIME类型）
    
    // 上传信息
    val uploadTime: Long = System.currentTimeMillis(),  // 上传时间戳
    val duration: Long,                 // 上传耗时（毫秒）
    val downloadUrl: String,            // 下载链接（上传成功时）
    val expireTime: Long? = null,       // 下载链接过期时间戳（可选）
    
    // 规则信息
    val ruleId: Long,                   // 使用的规则ID
    val ruleSummary: String,            // 使用的规则名称
    
    // 状态信息
    val success: Boolean,               // 是否上传成功
    val errorMsg: String? = null        // 错误信息（上传失败时）
)
