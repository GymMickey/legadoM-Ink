package io.legado.app.model

import androidx.annotation.Keep

/**
 * 书评数据模型
 *
 * 一本书至多一条书评。bookUrl 必填——入口始终从书开始。
 * 数据存储在 filesDir/bookReview.json，格式为 {"version": 1, "reviews": [...]}
 */
@Keep
data class BookReview(
    val id: String,              // UUID，唯一标识
    val bookUrl: String,         // 关联书籍 URL 或本地文件路径，必填
    val bookName: String,        // 书名（创建时从 Book 快照）
    val bookAuthor: String,      // 作者（创建时从 Book 快照）
    val rating: Int = 0,         // 评分 1–5，0 = 未评分
    val content: String,         // 正文，纯文本，可为空
    val createTime: Long,        // 创建时间戳（毫秒）
    val updateTime: Long         // 最后修改时间戳（毫秒）
)

/**
 * 书评文件顶层结构
 *
 * GSON 的 fromJsonObject<T>() 返回 Result<T>，需要一个顶层包装类
 * 来承载 version 字段——不能直接反序列化 List<BookReview>
 *
 * version 预留未来扩展（v2 可加 tags / Markdown / 阅读次数等）
 */
@Keep
data class BookReviewData(
    val version: Int,
    val reviews: List<BookReview> = emptyList()
)
