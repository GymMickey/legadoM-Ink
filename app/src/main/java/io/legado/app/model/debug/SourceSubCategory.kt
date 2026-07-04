package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * 源日志子分类
 *
 * 用于进一步细分源相关的日志类型。
 */
@Immutable
enum class SourceSubCategory(val displayName: String) {
    /** 更新缓存：书籍更新、缓存下载等业务操作 */
    UPDATE("更新"),

    /** 规则调试：规则解析、JS运行等调试操作 */
    RULE("规则"),

    /** 流程追踪：源规则执行的完整生命周期 */
    FLOW("流程"),

    /** 实体显示：查看书源的规则实体配置 */
    ENTITY("实体")
}
