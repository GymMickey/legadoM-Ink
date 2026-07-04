package io.legado.app.data.entities

/**
 * BookSource 轻量 DTO，用于首页 ViewModel 中书源缓存。
 * 仅包含首页模块展示所需字段，避免加载完整 BookSource 的所有解析规则 JSON。
 * 普通 data class，非 @Entity 非 @DatabaseView，不会触发 Room schema 校验。
 */
data class BookSourceExploreLite(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val exploreUrl: String? = null,
    val homepageModules: String? = null,
)
