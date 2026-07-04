package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * 流程阶段
 *
 * 定义源规则执行的各个阶段
 */
@Immutable
enum class FlowStage(val displayName: String, val icon: String) {
    /** 网络请求阶段 */
    NETWORK("网络请求", "🌐"),
    
    /** 规则解析阶段 */
    PARSE("规则解析", "🔍"),
    
    /** 字段提取阶段 */
    EXTRACT("字段提取", "📝"),
    
    /** 数据替换阶段 */
    REPLACE("数据替换", "🔄"),
    
    /** 变量存取阶段 */
    VARIABLE("变量存取", "📦"),
    
    /** 数据流转阶段 */
    DATA_FLOW("数据流转", "📊")
}
