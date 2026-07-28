package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * 书籍身份判断——所有跨表/跨源的"是不是同一本书"判断必须走这里。
 *
 * 规则：
 *   1. bookUrl 相同 → 同一本（最优先）
 *   2. normalize(name) + normalize(author) 都相同 → 同一本（fallback）
 *
 * normalize 消除数据来源差异：首尾空格、连续空格、null。
 */
object BookMatcher {

    /**
     * 规范化文本：去首尾空格 + 压缩连续空格为一个空格
     *
     * " 三体  "  → "三体"
     * "刘  慈欣" → "刘 慈欣"
     * null       → ""
     */
    fun normalize(text: String?): String {
        return text
            ?.trim()
            ?.replace("\\s+".toRegex(), " ")
            ?: ""
    }

    /** 两段文本是否匹配（规范化后） */
    fun textMatches(a: String?, b: String?): Boolean {
        return normalize(a) == normalize(b)
    }

    /**
     * 判断两本书是否为同一本。
     *
     * 优先 bookUrl 精确匹配，其次 name+author 规范化匹配。
     * 适配场景：
     *   - WiFi 传书 vs 本地重新导入（bookUrl 不同但书名相同）
     *   - 不同书源返回同一本书（name/author 格式差异）
     *   - 备份恢复跨设备（路径/URI 变化）
     */
    fun isSameBook(a: Book, b: Book): Boolean {
        // 1. bookUrl 精确匹配
        if (a.bookUrl.isNotBlank() && a.bookUrl == b.bookUrl) return true
        // 2. name + author 规范化匹配
        return textMatches(a.name, b.name) && textMatches(a.author, b.author)
    }

    /**
     * 跨类型匹配：BookReview / ReadRecord 等不一定是 Book 实体，用原始字段
     */
    fun isSameBook(
        name1: String?, author1: String?,
        name2: String?, author2: String?
    ): Boolean {
        return textMatches(name1, name2) && textMatches(author1, author2)
    }
}
