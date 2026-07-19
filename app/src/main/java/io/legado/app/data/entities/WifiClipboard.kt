package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * WiFi 传书剪贴板历史记录实体类
 *
 * 自动记录通过 WiFi 传入的文本内容，支持去重和数量裁剪
 *
 * @property id 主键ID，自动生成
 * @property content 文本内容
 * @property time 更新时间戳，新增和去重更新都设为当前时间
 */
@Entity(tableName = "wifi_clipboard")
data class WifiClipboard(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val time: Long = System.currentTimeMillis()
)