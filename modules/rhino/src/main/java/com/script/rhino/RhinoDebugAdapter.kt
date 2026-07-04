package com.script.rhino

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.debug.DebugFrame
import org.mozilla.javascript.debug.DebuggableScript
import org.mozilla.javascript.debug.Debugger

/**
 * 轻量级 Rhino 调试器，用于追踪脚本执行时的当前行号。
 * 行号存储在 [RhinoContext.currentScriptLine] 中，供 toast/log 等 JS 扩展方法使用。
 */
object RhinoDebugAdapter : Debugger {

    override fun handleCompilationDone(cx: Context, script: DebuggableScript, source: String) {
        // 不需要处理编译事件
    }

    override fun getFrame(cx: Context, script: DebuggableScript): DebugFrame {
        return ScriptDebugFrame
    }

    private object ScriptDebugFrame : DebugFrame {
        override fun onEnter(cx: Context, activation: Scriptable, thisObj: Scriptable, args: Array<Any?>) {
            if (cx is RhinoContext) {
                cx.currentScriptLine = -1
            }
        }

        override fun onLineChange(cx: Context, lineNumber: Int) {
            if (cx is RhinoContext) {
                cx.currentScriptLine = lineNumber
            }
        }

        override fun onExceptionThrown(cx: Context, ex: Throwable) {}
        override fun onExit(cx: Context, byThrow: Boolean, resultOrException: Any?) {}
        override fun onDebuggerStatement(cx: Context) {}
    }
}
