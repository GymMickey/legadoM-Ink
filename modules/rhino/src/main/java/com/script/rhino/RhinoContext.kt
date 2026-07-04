package com.script.rhino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0
    var currentRuleType: String? = null
    /** Rhino 调试器追踪的当前执行行号，由 [RhinoDebugAdapter] 在 onLineChange 时更新，
     *  供 toast/log 等 JS 扩展方法获取脚本行号。-1 表示未在执行或行号不可用。 */
    var currentScriptLine: Int = -1

    @Throws(RhinoInterruptError::class)
    fun ensureActive() {
        try {
            coroutineContext?.ensureActive()
        } catch (e: CancellationException) {
            throw RhinoInterruptError(e)
        }
    }

    @Throws(RhinoRecursionError::class)
    fun checkRecursive() {
        if (recursiveCount >= 10) {
            throw RhinoRecursionError()
        }
    }

}
