package io.legado.app.data.repository.debug

import io.legado.app.model.debug.RssExecutionRecord
import io.legado.app.model.debug.RssExecutionStatus
import io.legado.app.model.debug.RssExecutionStep
import io.legado.app.model.debug.RssRuleExecutionRecord
import io.legado.app.model.debug.RuleExecutionTree
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import java.util.UUID

/**
 * 订阅源执行记录器
 *
 * 记录订阅源执行过程中每一步的状态（成功/失败/跳过），
 * 用于在调试日志面板的"执行情况"区域展示。
 *
 * 按 sourceUrl 分组存储，支持同时调试多个订阅源。
 */
object RssExecutionRecorder {

    private const val MAX_RECORDS = 500
    private const val MAX_RULE_RECORDS = 200

    private val records = ArrayDeque<RssExecutionRecord>()
    private val ruleRecords = ArrayDeque<RssRuleExecutionRecord>()

    private val _recordsFlow = MutableSharedFlow<List<RssExecutionRecord>>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val recordsFlow: SharedFlow<List<RssExecutionRecord>> = _recordsFlow.asSharedFlow()

    private val _ruleRecordsFlow = MutableSharedFlow<List<RssRuleExecutionRecord>>(
        replay = 1,
        extraBufferCapacity = 16
    )
    /**
     * 规则执行记录的响应式流
     * 用于 UI 层实时订阅最新记录
     */
    val ruleRecordsFlow: SharedFlow<List<RssRuleExecutionRecord>> = _ruleRecordsFlow.asSharedFlow()

    val isEnabled: Boolean get() = io.legado.app.help.config.AppConfig.debugLogFloatingBall

    // 当前执行会话的源信息
    private var currentSourceUrl: String = ""
    private var currentSourceName: String = ""
    private var currentExecutionId: String = ""
    private var currentStartTime: Long = 0L

    /**
     * 开始一个新的执行会话
     */
    fun startSession(sourceUrl: String, sourceName: String) {
        currentSourceUrl = sourceUrl
        currentSourceName = sourceName
        currentExecutionId = UUID.randomUUID().toString().take(8)
        currentStartTime = System.currentTimeMillis()
    }

    /**
     * 记录一个步骤的执行结果
     */
    fun record(record: RssExecutionRecord) {
        if (!isEnabled) return
        synchronized(records) {
            records.addFirst(record)
            while (records.size > MAX_RECORDS) {
                records.removeLast()
            }
            emitUpdate()
        }
    }

    /**
     * 记录配置检查步骤：字段为空则跳过，非空则执行正确
     */
    fun check(step: RssExecutionStep, value: String?) {
        if (value.isNullOrBlank()) {
            record(makeRecord(step, RssExecutionStatus.EMPTY_SKIP))
        } else {
            record(makeRecord(step, RssExecutionStatus.SUCCESS, detail = value.take(100)))
        }
    }

    /**
     * 记录配置检查：带合法性校验
     */
    fun checkWithValidation(step: RssExecutionStep, value: String?, validation: Pair<Boolean, String>) {
        val (isValid, reason) = validation
        if (value.isNullOrBlank()) {
            record(makeRecord(step, RssExecutionStatus.EMPTY_SKIP))
        } else if (!isValid) {
            record(makeRecord(step, RssExecutionStatus.FAILED, error = reason, detail = value.take(100)))
        } else {
            record(makeRecord(step, RssExecutionStatus.SUCCESS, detail = value.take(100)))
        }
    }

    /**
     * 记录布尔值配置检查
     */
    fun check(step: RssExecutionStep, value: Boolean) {
        record(makeRecord(step, RssExecutionStatus.SUCCESS, detail = value.toString()))
    }

    /**
     * 记录执行步骤：成功
     */
    fun success(step: RssExecutionStep, detail: String? = null, duration: Long? = null) {
        record(makeRecord(step, RssExecutionStatus.SUCCESS, detail = detail, duration = duration))
    }

    /**
     * 记录执行步骤：失败
     */
    fun failed(step: RssExecutionStep, error: String, duration: Long? = null) {
        record(makeRecord(step, RssExecutionStatus.FAILED, error = error, duration = duration))
    }

    /**
     * 标记当前会话结束，记录总耗时
     */
    fun endSession() {
        if (currentExecutionId.isEmpty()) return
        val totalDuration = System.currentTimeMillis() - currentStartTime
        record(makeRecord(
            RssExecutionStep.SOURCE_NAME,
            RssExecutionStatus.SUCCESS,
            detail = "本次执行共耗时 ${formatDuration(totalDuration)}",
            duration = totalDuration,
            isSessionEnd = true
        ))
        currentSourceUrl = ""
        currentSourceName = ""
        currentExecutionId = ""
        currentStartTime = 0L
    }

    /**
     * 获取当前所有记录
     */
    fun getCurrentRecords(): List<RssExecutionRecord> {
        synchronized(records) {
            return records.toList()
        }
    }

    /**
     * 清空记录
     */
    fun clear() {
        synchronized(records) {
            records.clear()
        }
        synchronized(ruleRecords) {
            ruleRecords.clear()
        }
        emitUpdate()
        emitRuleRecordsUpdate()
    }

    /**
     * 记录规则执行
     *
     * @param record 规则执行记录，包含执行树、输入输出等信息
     */
    fun recordRuleExecution(record: RssRuleExecutionRecord) {
        if (!isEnabled) return
        synchronized(ruleRecords) {
            ruleRecords.addFirst(record)
            while (ruleRecords.size > MAX_RULE_RECORDS) {
                ruleRecords.removeLast()
            }
            emitRuleRecordsUpdate()
        }
    }

    /**
     * 获取当前所有规则执行记录
     */
    fun getCurrentRuleRecords(): List<RssRuleExecutionRecord> {
        synchronized(ruleRecords) {
            return ruleRecords.toList()
        }
    }

    /**
     * 记录规则执行成功
     *
     * @param step 执行步骤类型
     * @param ruleContent 规则内容
     * @param executionTree 规则执行树（包含嵌套规则执行详情）
     * @param input 输入数据
     * @param output 输出数据
     * @param matchCount 匹配数量
     * @param duration 执行耗时
     */
    fun ruleSuccess(
        step: RssExecutionStep,
        ruleContent: String? = null,
        executionTree: RuleExecutionTree? = null,
        input: String? = null,
        output: String? = null,
        matchCount: Int? = null,
        duration: Long? = null
    ) {
        recordRuleExecution(RssRuleExecutionRecord(
            step = step,
            ruleContent = ruleContent,
            executionTree = executionTree,
            input = input?.take(200),
            output = output?.take(200),
            matchCount = matchCount,
            duration = duration,
            sourceUrl = currentSourceUrl,
            sourceName = currentSourceName,
            executionId = currentExecutionId
        ))
    }

    /**
     * 记录规则执行失败
     *
     * @param step 执行步骤类型
     * @param ruleContent 规则内容
     * @param error 错误信息
     * @param duration 执行耗时
     */
    fun ruleFailed(
        step: RssExecutionStep,
        ruleContent: String? = null,
        error: Throwable,
        duration: Long? = null
    ) {
        recordRuleExecution(RssRuleExecutionRecord(
            step = step,
            ruleContent = ruleContent,
            error = error,
            duration = duration,
            sourceUrl = currentSourceUrl,
            sourceName = currentSourceName,
            executionId = currentExecutionId
        ))
    }

    /**
     * 发射规则执行记录更新
     *
     * 将当前规则执行记录列表通过 Flow 发射给订阅者
     */
    private fun emitRuleRecordsUpdate() {
        try {
            _ruleRecordsFlow.tryEmit(getCurrentRuleRecords())
        } catch (e: Exception) {
            io.legado.app.model.Debug.log("RssExecutionRecorder", "emitRuleRecordsUpdate失败: ${e.message}")
        }
    }

    private fun makeRecord(
        step: RssExecutionStep,
        status: RssExecutionStatus,
        detail: String? = null,
        error: String? = null,
        duration: Long? = null,
        isSessionStart: Boolean = false,
        isSessionEnd: Boolean = false
    ): RssExecutionRecord {
        return RssExecutionRecord(
            step = step,
            status = status,
            detail = detail,
            error = error,
            duration = duration,
            sourceUrl = currentSourceUrl,
            sourceName = currentSourceName,
            executionId = currentExecutionId,
            isSessionStart = isSessionStart,
            isSessionEnd = isSessionEnd
        )
    }

    private fun emitUpdate() {
        try {
            _recordsFlow.tryEmit(getCurrentRecords())
        } catch (e: Exception) {
            io.legado.app.model.Debug.log("RssExecutionRecorder", "emitUpdate失败: ${e.message}")
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
        else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    }
}
