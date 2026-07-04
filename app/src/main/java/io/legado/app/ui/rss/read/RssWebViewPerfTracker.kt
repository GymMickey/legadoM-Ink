package io.legado.app.ui.rss.read

import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.RssSource

/**
 * 订阅源 WebView 性能追踪器
 * 
 * 用于测量订阅源页面加载各阶段的耗时，帮助分析性能瓶颈
 * 
 * 追踪的阶段：
 * 1. HTML下载 - OkHttp下载网页的时间
 * 2. HTML解析 - 解析HTML并注入JS标签的时间
 * 3. JS注入拦截 - 创建WebResourceResponse对象的时间
 * 4. JS解析执行 - WebView解析执行JS代码的时间（在DOM渲染中）
 * 5. DOM渲染 - onPageStarted到onPageFinished的总时间
 * 
 * 使用方法：
 * 1. 调用 start() 开始追踪
 * 2. 在各阶段调用对应的方法记录时间点
 * 3. 页面加载完成后调用 report() 输出性能报告
 */
class RssWebViewPerfTracker(private val source: RssSource) {
    
    /** 用于日志输出的订阅源名称 */
    private val sourceTag = source.getTag()
    
    /** 页面开始加载时间 */
    var startTime = 0L
        private set
    
    /** HTML下载开始时间 */
    private var htmlDownloadStart = 0L
    /** HTML下载结束时间 */
    private var htmlDownloadEnd = 0L
    /** HTML解析开始时间 */
    private var htmlParseStart = 0L
    /** HTML解析结束时间 */
    private var htmlParseEnd = 0L
    /** JS注入开始时间 */
    private var jsInjectStart = 0L
    /** JS注入结束时间 */
    private var jsInjectEnd = 0L
    /** JS解析开始时间（预留） */
    private var jsExecStart = 0L
    /** JS解析结束时间（预留） */
    private var jsExecEnd = 0L
    /** DOM渲染开始时间（= startTime） */
    private var domRenderStart = 0L
    /** DOM渲染结束时间（onPageFinished时设置） */
    private var domRenderEnd = 0L
    
    /** 注入的JS代码大小（字节） */
    private var jsInjectionSize = 0
    
    /**
     * 开始追踪
     * 调用此方法后开始计时
     */
    fun start() {
        startTime = SystemClock.uptimeMillis()
        domRenderStart = startTime
    }
    
    /**
     * HTML下载开始
     * 在 OkHttp 开始下载前调用
     */
    fun htmlDownloadStart() {
        htmlDownloadStart = SystemClock.uptimeMillis()
    }
    
    /**
     * HTML下载结束
     * 在 OkHttp 返回响应后调用
     */
    fun htmlDownloadEnd() {
        htmlDownloadEnd = SystemClock.uptimeMillis()
    }
    
    /**
     * HTML解析开始
     * 在开始解析HTML内容前调用
     */
    fun htmlParseStart() {
        htmlParseStart = SystemClock.uptimeMillis()
    }
    
    /**
     * HTML解析结束
     * 在HTML解析完成、准备返回响应前调用
     */
    fun htmlParseEnd() {
        htmlParseEnd = SystemClock.uptimeMillis()
    }
    
    /**
     * JS注入开始
     * 在创建预注入JS的WebResourceResponse前调用
     */
    fun jsInjectStart() {
        jsInjectStart = SystemClock.uptimeMillis()
    }
    
    /**
     * JS注入结束
     * 在WebResourceResponse创建完成后调用
     * @param injectionSize 注入的JS代码大小（字节）
     */
    fun jsInjectEnd(injectionSize: Int = 0) {
        jsInjectEnd = SystemClock.uptimeMillis()
        jsInjectionSize = injectionSize
    }
    
    /**
     * JS解析开始（预留方法）
     * WebView执行JS代码时调用
     */
    fun jsExecStart() {
        jsExecStart = SystemClock.uptimeMillis()
    }
    
    /**
     * JS解析结束（预留方法）
     */
    fun jsExecEnd() {
        jsExecEnd = SystemClock.uptimeMillis()
    }
    
    /**
     * DOM渲染结束
     * 在 onPageFinished 回调中调用
     */
    fun domRenderEnd() {
        domRenderEnd = SystemClock.uptimeMillis()
    }
    
    /**
     * 输出性能报告到日志
     * 报告包含各阶段的耗时分解和性能瓶颈分析
     */
    fun report() {
        if (startTime == 0L) return
        
        val total = domRenderEnd - startTime
        val htmlDownload = if (htmlDownloadEnd > 0 && htmlDownloadStart > 0) htmlDownloadEnd - htmlDownloadStart else 0
        val htmlParse = if (htmlParseEnd > 0 && htmlParseStart > 0) htmlParseEnd - htmlParseStart else 0
        val jsInject = if (jsInjectEnd > 0 && jsInjectStart > 0) jsInjectEnd - jsInjectStart else 0
        val jsExec = if (jsExecEnd > 0 && jsExecStart > 0) jsExecEnd - jsExecStart else 0
        val domRender = if (domRenderEnd > 0 && domRenderStart > 0) domRenderEnd - domRenderStart else total
        
        val sb = StringBuilder()
        sb.appendLine("┌─────────────────────────────────────────")
        sb.appendLine("│ [订阅源性能分析] 总耗时: ${total}ms")
        sb.appendLine("├─────────────────────────────────────────")
        sb.appendLine("│ 阶段耗时分解:")
        
        // 1. HTML下载
        if (htmlDownloadStart > 0) {
            sb.appendLine("│ ├─ 1. HTML下载: ${htmlDownload}ms")
        } else {
            sb.appendLine("│ ├─ 1. HTML下载: -- (未触发)")
        }
        
        // 2. HTML解析
        if (htmlParseStart > 0) {
            sb.appendLine("│ ├─ 2. HTML解析: ${htmlParse}ms")
        } else {
            sb.appendLine("│ ├─ 2. HTML解析: -- (未触发)")
        }
        
        // 3. JS注入拦截
        if (jsInjectStart > 0) {
            sb.appendLine("│ ├─ 3. JS注入拦截: ${jsInject}ms (代码量: ${jsInjectionSize}字节)")
        } else {
            sb.appendLine("│ ├─ 3. JS注入拦截: -- (无preloadJs)")
        }
        
        // 4. JS解析执行
        if (jsExecStart > 0) {
            sb.appendLine("│ ├─ 4. JS解析执行: ${jsExec}ms")
        } else {
            sb.appendLine("│ ├─ 4. JS解析执行: -- (未触发)")
        }
        
        // 5. DOM渲染
        sb.appendLine("│ └─ 5. DOM渲染: ${domRender}ms")
        
        sb.appendLine("│")
        sb.appendLine("│ 性能瓶颈分析:")
        
        // 只统计已触发的阶段
        val stages = mutableListOf<Pair<String, Long>>()
        if (htmlDownloadStart > 0 && htmlDownload > 0) stages.add("HTML下载" to htmlDownload)
        if (htmlParseStart > 0 && htmlParse > 0) stages.add("HTML解析" to htmlParse)
        if (jsInjectStart > 0 && jsInject > 0) stages.add("JS注入拦截" to jsInject)
        if (jsExecStart > 0 && jsExec > 0) stages.add("JS解析执行" to jsExec)
        if (domRender > 0) stages.add("DOM渲染" to domRender)
        
        if (stages.isEmpty()) {
            sb.appendLine("│ ℹ️ 无有效性能数据")
        } else {
            val maxStage = stages.maxByOrNull { it.second }
            if (maxStage != null && maxStage.second > 0) {
                sb.appendLine("│ ⚠️ ${maxStage.first}是主要瓶颈 (${maxStage.second}ms)")
            } else {
                sb.appendLine("│ ✅ 各阶段耗时均衡")
            }
        }
        
        if (jsInjectionSize > 5000) {
            sb.appendLine("│ 💡 建议: JS注入代码量较大(${jsInjectionSize}字节)，可考虑优化")
        }
        
        sb.appendLine("└─────────────────────────────────────────")
        
        AppLog.put("$sourceTag: ${sb.toString()}")
    }
    
    fun reset() {
        startTime = 0L
        htmlDownloadStart = 0L
        htmlDownloadEnd = 0L
        htmlParseStart = 0L
        htmlParseEnd = 0L
        jsInjectStart = 0L
        jsInjectEnd = 0L
        jsExecStart = 0L
        jsExecEnd = 0L
        domRenderStart = 0L
        domRenderEnd = 0L
        jsInjectionSize = 0
    }
}
