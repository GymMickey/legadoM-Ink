package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WiFi 传书上传历史记录
 * 仅保存成功上传的书籍，最多 10 条，按时间倒序
 */
@Entity(tableName = "wifi_upload_records")
data class WifiUploadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val uploadTime: Long = System.currentTimeMillis()
)