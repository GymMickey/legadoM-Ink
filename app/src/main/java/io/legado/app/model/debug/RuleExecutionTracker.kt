package io.legado.app.model.debug

import io.legado.app.data.entities.BaseSource

/**
 * 规则执行跟踪器
 *
 * 用于跟踪规则执行的完整路径，记录每个步骤的输入输出。
 * 使用方式：
 * ```
 * val tracker = RuleExecutionTracker(source, fullRule)
 * tracker.startStep("CSS选择器", "@css:.content", input)
 * // ... 执行规则 ...
 * tracker.endStep(output, matchCount)
 * val tree = tracker.buildTree()
 * ```
 */
class RuleExecutionTracker(
    private val source: BaseSource?,
    private val fullRule: String,
    private val operation: String? = null
) {
    private val steps = mutableListOf<StepInfo>()
    private var currentStep: StepInfo? = null
    private var startTime = System.currentTimeMillis()

    data class StepInfo(
        val index: Int,
        val ruleType: RuleType,
        val ruleContent: String,
        val input: String?,
        var output: String? = null,
        var matchCount: Int? = null,
        var duration: Long? = null,
        var jsContext: JsExecutionContext? = null,
        var regexGroups: List<String>? = null,
        var error: Throwable? = null,
        val startTime: Long = System.currentTimeMillis()
    )

    fun startStep(
        ruleType: RuleType,
        ruleContent: String,
        input: Any? = null
    ) {
        val stepIndex = steps.size
        currentStep = StepInfo(
            index = stepIndex,
            ruleType = ruleType,
            ruleContent = ruleContent,
            input = input?.toString()?.take(200)
        )
    }

    fun endStep(
        output: Any? = null,
        matchCount: Int? = null,
        jsContext: JsExecutionContext? = null,
        regexGroups: List<String>? = null
    ) {
        currentStep?.let { step ->
            step.output = output?.toString()?.take(200)
            step.matchCount = matchCount
            step.duration = System.currentTimeMillis() - step.startTime
            step.jsContext = jsContext
            step.regexGroups = regexGroups
            steps.add(step)
        }
        currentStep = null
    }

    fun failStep(error: Throwable) {
        currentStep?.let { step ->
            step.error = error
            step.duration = System.currentTimeMillis() - step.startTime
            steps.add(step)
        }
        currentStep = null
    }

    fun buildTree(): RuleExecutionTree {
        val totalDuration = System.currentTimeMillis() - startTime
        
        val rootNode = if (steps.isEmpty()) {
            RuleExecutionNode(
                stepIndex = 0,
                ruleType = RuleType.ROOT,
                ruleContent = fullRule,
                duration = totalDuration
            )
        } else {
            val children = steps.map { step ->
                RuleExecutionNode(
                    stepIndex = step.index,
                    ruleType = step.ruleType,
                    ruleContent = step.ruleContent,
                    input = step.input,
                    output = step.output,
                    matchCount = step.matchCount,
                    duration = step.duration,
                    jsContext = step.jsContext,
                    regexGroups = step.regexGroups,
                    error = step.error,
                    startTime = step.startTime
                )
            }
            
            RuleExecutionNode(
                stepIndex = 0,
                ruleType = RuleType.ROOT,
                ruleContent = fullRule,
                output = steps.lastOrNull()?.output,
                duration = totalDuration,
                matchCount = steps.firstNotNullOfOrNull { it.matchCount },
                children = children,
                error = steps.firstNotNullOfOrNull { it.error }
            )
        }
        
        return RuleExecutionTree(
            sourceUrl = source?.getKey(),
            sourceName = source?.getTag(),
            operation = operation,
            fullRule = fullRule,
            root = rootNode,
            startTime = startTime,
            totalDuration = totalDuration
        )
    }

    fun hasSteps(): Boolean = steps.isNotEmpty() || currentStep != null

    fun getLastOutput(): String? = steps.lastOrNull()?.output ?: currentStep?.output
}
