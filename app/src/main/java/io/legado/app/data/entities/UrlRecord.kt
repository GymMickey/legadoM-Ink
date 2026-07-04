package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * URL访问记录实体类
 * 
 * 用于记录应用内所有网络请求的详细信息，包括请求URL、响应状态、耗时等。
 * 数据存储在 Room 数据库的 url_records 表中。
 * 
 * @property id 主键，自增
 * @property url 完整的请求URL
 * @property domain 请求的域名，用于按域名筛选
 * @property method HTTP请求方法（GET/POST等）
 * @property sourceName 请求来源名称（书源名/RSS源名），可选
 * @property sourceUrl 请求来源URL，可选
 * @property timestamp 请求发起的时间戳
 * @property responseCode HTTP响应状态码
 * @property duration 请求耗时（毫秒）
 * @property requestBody POST请求体内容（仅记录前1000字符），可选
 * @property errorMsg 错误信息（请求失败时记录），可选
 */
@Entity(tableName = "url_records", indices = [Index(value = ["timestamp"]), Index(value = ["domain"])])
data class UrlRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val url: String,
    val domain: String,
    val method: String,
    val sourceName: String? = null,
    val sourceUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val responseCode: Int = 0,
    val duration: Long = 0,
    val requestBody: String? = null,
    val errorMsg: String? = null
)
