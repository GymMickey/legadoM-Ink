package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 直链上传规则实体类
 * 
 * 用于存储用户配置的上传规则，支持多个规则管理
 * 
 * @property id 主键ID，自动生成
 * @property uploadUrl 上传URL地址
 * @property downloadUrlRule 下载链接提取规则（JSONPath表达式）
 * @property summary 规则注释说明
 * @property compress 是否自动压缩文件
 * @property customHeaders 自定义请求头（JSON格式）
 * @property timeout 超时时间（毫秒）
 * @property retryCount 重试次数
 * @property sortOrder 排序顺序（数字越小越靠前）
 * @property isDefault 是否为默认规则
 * @property uploadCount 上传次数统计
 * @property lastUsedTime 最后使用时间戳
 * @property createTime 创建时间戳
 * @property updateTime 更新时间戳
 */
@Entity(
    tableName = "direct_link_upload_rules",
    indices = [
        Index(value = ["sortOrder"]),      // 排序索引，用于规则列表排序
        Index(value = ["isDefault"])       // 默认规则索引，用于快速查询默认规则
    ]
)
data class DirectLinkUploadRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 基础配置字段
    val uploadUrl: String,              // 上传URL地址
    val downloadUrlRule: String,        // 下载链接提取规则（JSONPath表达式）
    val summary: String,                // 规则注释说明
    val compress: Boolean = false,      // 是否自动压缩文件
    
    // 高级配置字段
    val customHeaders: String? = null,  // 自定义请求头（JSON格式）
    val timeout: Long = 30000,          // 超时时间（毫秒），默认30秒
    val retryCount: Int = 3,            // 重试次数，默认3次
    
    // 管理字段
    val sortOrder: Int = 0,             // 排序顺序（数字越小越靠前）
    val isDefault: Boolean = false,     // 是否为默认规则
    val uploadCount: Int = 0,           // 上传次数统计
    val lastUsedTime: Long = 0,         // 最后使用时间戳
    val createTime: Long = System.currentTimeMillis(),  // 创建时间戳
    val updateTime: Long = System.currentTimeMillis()   // 更新时间戳
) {
    override fun toString(): String = summary
}
