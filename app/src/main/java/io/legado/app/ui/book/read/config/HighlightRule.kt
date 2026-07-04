package io.legado.app.ui.book.read.config

data class HighlightRule(
    var id: String = System.currentTimeMillis().toString(),
    var name: String = "",
    var pattern: String = "",
    var sampleText: String = "",
    var group: String = HighlightRuleGroupStore.DEFAULT_GROUP,
    var targetScope: Int = TARGET_ALL,
    var enabled: Boolean = true,
    var textColor: Int? = null,
    var underlineMode: Int = 0,
    var underlineColor: Int? = null,
    var underlineWidth: Float = 1f,
    var underlineOffset: Float = 2f,
    var underlineSvgPath: String? = null,
    var bgColor: Int? = null,
    var bgImage: String? = null,
    var bgImageFit: Int = 0,
    var bgImageScale: Float = 1f,
    /** 作用范围，书名或书源URL，分号分隔，为空则对所有书籍生效 */
    var scope: String? = null,
    /** 排除范围，书名或书源URL，分号分隔，匹配的书籍不应用该规则 */
    var excludeScope: String? = null,
) {

    fun styleSummary(): String {
        val parts = ArrayList<String>(4)
        parts.add(targetScopeLabel())
        if (!scope.isNullOrBlank()) {
            parts.add("仅: ${scope!!.replace(";", "; ").trim()}")
        }
        if (!excludeScope.isNullOrBlank()) {
            parts.add("排除: ${excludeScope!!.replace(";", "; ").trim()}")
        }
        textColor?.let {
            parts.add("字色 ${it.toHexColor()}")
        }
        if (underlineMode != 0) {
            parts.add(
                when (underlineMode) {
                    1 -> "实线下划线"
                    2 -> "虚线下划线"
                    3 -> "波浪下划线"
                    4 -> "双下划线"
                    5 -> "自定义SVG"
                    else -> "下划线"
                } + underlineColor?.let { " ${it.toHexColor()}" }.orEmpty()
            )
        }
        if (!bgImage.isNullOrBlank()) {
            parts.add(
                when (bgImageFit) {
                    1 -> "背景图(拉伸)"
                    2 -> "背景图(裁剪)"
                    else -> "背景图(平铺)"
                }
            )
        } else if (bgColor != null) {
            parts.add("背景色 ${bgColor!!.toHexColor()}")
        }
        if (parts.isEmpty()) {
            parts.add("无样式")
        }
        return parts.joinToString(" / ")
    }

    fun targetScopeLabel(): String {
        return when (targetScope) {
            TARGET_TITLE -> "作用于标题"
            TARGET_BODY -> "作用于正文"
            else -> "作用于全部"
        }
    }

    fun displayPattern(): String {
        return pattern.ifBlank { ".*" }
    }

    fun normalizedSampleText(): String {
        return sampleText.ifBlank {
            "她轻声说：“今晚就出发。”\n最近在重读《百年孤独》（纪念版），节奏依然很稳。"
        }
    }

    fun copyWithNewId(): HighlightRule {
        return copy(id = "${System.currentTimeMillis()}_${name.hashCode()}")
    }

    /**
     * 判断规则是否对指定书籍生效
     * - scope 为空时，默认对所有书籍生效（仍会检查 excludeScope）
     * - scope 非空时，仅对匹配书名或书源URL的书籍生效
     * - excludeScope 非空时，匹配的书籍会被排除
     */
    fun matchesScope(bookName: String, bookOrigin: String): Boolean {
        // 检查作用范围：scope 非空时，书名或书源URL必须匹配其中一项
        val scopeVal = scope
        if (!scopeVal.isNullOrBlank()) {
            val scopeItems = scopeVal.split(";").map { it.trim() }.filter { it.isNotBlank() }
            val inScope = scopeItems.any { item ->
                bookName.contains(item) || bookOrigin.contains(item)
            }
            if (!inScope) return false
        }
        // 检查排除范围：书名或书源URL匹配排除项则不生效
        val excludeVal = excludeScope
        if (!excludeVal.isNullOrBlank()) {
            val excludeItems = excludeVal.split(";").map { it.trim() }.filter { it.isNotBlank() }
            val excluded = excludeItems.any { item ->
                bookName.contains(item) || bookOrigin.contains(item)
            }
            if (excluded) return false
        }
        return true
    }

    companion object {
        const val TARGET_ALL = 0
        const val TARGET_TITLE = 1
        const val TARGET_BODY = 2

        fun Int.toHexColor(): String = String.format("#%08X", this)
    }
}
