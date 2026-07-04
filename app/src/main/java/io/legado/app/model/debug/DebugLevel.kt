package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * 调试日志级别
 *
 * 定义日志的重要程度，用于UI展示时的视觉区分和过滤。
 */
@Immutable
enum class DebugLevel(val displayName: String, val priority: Int) {
    /** 调试信息，用于开发阶段详细跟踪 */
    DEBUG("调试", 0),

    /** 一般信息，正常流程记录 */
    INFO("信息", 1),

    /** 警告信息，潜在问题提示 */
    WARN("警告", 2),

    /** 错误信息，需要关注和处理 */
    ERROR("错误", 3)
}
