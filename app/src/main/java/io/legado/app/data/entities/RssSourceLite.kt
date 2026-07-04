package io.legado.app.data.entities

/**
 * RssSource 轻量 DTO，用于列表/名称映射等仅需少量字段的场景。
 * 普通 data class，非 @Entity 非 @DatabaseView，不会触发 Room schema 校验。
 */
data class RssSourceLite(
    val sourceUrl: String = "",
    val sourceName: String = "",
    val sourceGroup: String? = null,
    val rulePubDate: String? = null,
)
