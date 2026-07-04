package io.legado.app.model.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * 调试日志模块的全局协程作用域
 *
 * 替代 GlobalScope，进程生命周期内有效。
 * 使用 SupervisorJob 确保单个子协程失败不会取消其他协程。
 */
object DebugLogScope : CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.IO
}
