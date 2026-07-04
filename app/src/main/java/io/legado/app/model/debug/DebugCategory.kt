package io.legado.app.model.debug

import androidx.compose.runtime.Immutable

/**
 * 调试事件分类
 *
 * 定义事件的来源类型，用于分类展示和过滤。
 * 包含ALL作为特殊值表示"全部分类"（仅用于UI筛选）。
 */
@Immutable
enum class DebugCategory(val displayName: String) {
    /** 全部分类（仅用于UI筛选，不作为实际事件分类）*/
    ALL("全部"),

    /** 应用日志：来自AppLog的一般性日志 */
    APP("应用"),

    /** 网络请求：来自OkHttp拦截器的网络访问记录 */
    NETWORK("网络"),

    /** 规则执行：来自书源/RSS源规则解析过程 */
    RULE("规则"),

    /** 书源操作：与书源相关的其他操作 */
    SOURCE("书源"),

    /** RSS源操作：与RSS源相关的操作 */
    RSS("RSS"),

    /** Toast消息：应用内所有Toast消息记录 */
    TOAST("Toast"),

    /** 书源校验：来自CheckSource的校验结果 */
    CHECK("校验"),

    /** 应用崩溃：来自CrashHandler的崩溃信息 */
    CRASH("崩溃"),

    /** 阅读器调试：阅读器模块专属调试日志，仅通过调试悬浮球通道输出 */
    READER("阅读器")
}
