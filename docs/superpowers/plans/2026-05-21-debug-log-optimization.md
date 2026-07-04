# 调试日志代码优化实现计划

> **面向 AI 代理的工作者：** 建议使用 superpowers:subagent-driven-development 或 superpowers:executing-plans 逐任务实现。步骤使用复选框（`- [ ]`）跟踪进度。

**目标：** 修复调试日志模块中 11 个代码质量问题（排除 #3 FlowLogItem 持有重型实体对象）

**架构：** 新增 `DebugLogScope` 单例替代 GlobalScope；提取共享工具函数到 `DebugLogUtils`；修复线程安全、JSON 导出、死代码等问题。

---

## 涉及的文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `app/src/main/java/io/legado/app/model/debug/DebugLogScope.kt` | 新建 | 全局协程作用域单例 |
| `app/src/main/java/io/legado/app/model/debug/DebugLogUtils.kt` | 新建 | 共享工具函数（formatDuration/formatFullTime/highlightText） |
| `app/src/main/java/io/legado/app/data/repository/debug/FlowLogRecorder.kt` | 修改 | GlobalScope→DebugLogScope，统一同步机制，移除 AtomicInteger |
| `app/src/main/java/io/legado/app/data/repository/debug/DebugEventCenter.kt` | 修改 | eventCount 加锁，exportToJson 改用 Gson |
| `app/src/main/java/io/legado/app/constant/AppLog.kt` | 修改 | GlobalScope→DebugLogScope |
| `app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt` | 修改 | 修复 LazyColumn key，消除 !! 断言 |
| `app/src/main/java/io/legado/app/ui/debuglog/DebugFloatingBallManager.kt` | 修改 | 取消注释 DebugEventCenter.clear() |
| `app/src/main/java/io/legado/app/ui/debuglog/components/DebugTopBar.kt` | 删除 | 未使用的死代码 |
| `app/src/main/java/io/legado/app/ui/debuglog/components/DebugLogDetailDialog.kt` | 修改 | 使用 DebugLogUtils 替代本地函数 |
| `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogDetailDialog.kt` | 修改 | 使用 DebugLogUtils 替代本地函数 |
| `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogList.kt` | 修改 | 使用 DebugLogUtils 替代本地函数 |
| `app/src/main/java/io/legado/app/model/debug/FlowLogItem.kt` | 修改 | 删除未使用的 FlowLogGroup |
| `app/src/main/java/io/legado/app/model/debug/RuleExecutionNode.kt` | 修改 | 删除 formatDuration/formatTime，使用 DebugLogUtils |
| `app/src/main/java/io/legado/app/model/debug/JsExecutionContext.kt` | 修改 | 删除 formatDuration/formatTime，使用 DebugLogUtils |

---

## 任务 1：新增 DebugLogScope 全局协程作用域

**文件：** 创建 `app/src/main/java/io/legado/app/model/debug/DebugLogScope.kt`

- [ ] 创建文件

```kotlin
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
```

## 任务 2：新增 DebugLogUtils 共享工具函数

**文件：** 创建 `app/src/main/java/io/legado/app/model/debug/DebugLogUtils.kt`

- [ ] 创建文件

```kotlin
package io.legado.app.model.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志模块共享工具函数
 */
object DebugLogUtils {

    private val timeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    private val shortTimeFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }

    /** 格式化时间戳为完整日期时间 */
    fun formatFullTime(timestamp: Long): String {
        return timeFormatter.get().format(Date(timestamp))
    }

    /** 格式化时间戳为短时间（时:分:秒.毫秒） */
    fun formatShortTime(timestamp: Long): String {
        return shortTimeFormatter.get().format(Date(timestamp))
    }

    /** 格式化耗时为人类可读文本（支持 null 输入） */
    fun formatDuration(ms: Long?): String? {
        return ms?.let {
            when {
                it < 1000 -> "${it}ms"
                it < 60000 -> "${it / 1000.0}s"
                else -> "${it / 60000}m ${it % 60000 / 1000}s"
            }
        }
    }
}

/** Compose 版本的文本高亮（需在 Composable 上下文中调用） */
@Composable
fun highlightText(text: String, query: String): AnnotatedString {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        return buildAnnotatedString { append(text) }
    }

    return buildAnnotatedString {
        var startIndex = 0
        var foundIndex = text.indexOf(query, ignoreCase = true)

        while (foundIndex >= 0) {
            append(text.substring(startIndex, foundIndex))
            withStyle(
                SpanStyle(
                    background = Color.Yellow.copy(alpha = 0.3f),
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(foundIndex, foundIndex + query.length))
            }
            startIndex = foundIndex + query.length
            foundIndex = text.indexOf(query, startIndex, ignoreCase = true)
        }

        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}
```

## 任务 3：FlowLogRecorder — GlobalScope→DebugLogScope + 统一同步机制

**文件：** `app/src/main/java/io/legado/app/data/repository/debug/FlowLogRecorder.kt`

- [ ] 替换 import：删除 `import kotlinx.coroutines.GlobalScope`，不再需要（DebugLogScope 已在同一包）
- [ ] 将所有 `GlobalScope.launch(Dispatchers.IO)` 替换为 `DebugLogScope.launch`
  - 行 211：`logRuleExecution` 方法
  - 行 263：`logJsExecution` 方法
  - 行 422：`logVariable` 方法
  - 行 527：`logStageDataFlow` 方法
  - 行 624：`log` 方法
  - 行 683：`scheduleUpdate` 方法
  - 行 714：`clear` 方法
- [ ] 移除 `logSize` AtomicInteger：在 `addLog` 中改用 `synchronized(logDeque)` 内直接检查 `logDeque.size`
- [ ] 统一 `addLog` 和 `getCurrentLogs` 都使用 `synchronized(logDeque)`，删除 `@Synchronized` 注解

修改后的 `addLog` 和 `scheduleUpdate`：

```kotlin
private fun addLog(item: FlowLogItem) {
    synchronized(logDeque) {
        logDeque.addFirst(item)
        while (logDeque.size > MAX_LOG_COUNT) {
            logDeque.removeLast()
        }
    }
    scheduleUpdate()
}

private fun scheduleUpdate() {
    if (pendingUpdate.compareAndSet(false, true)) {
        DebugLogScope.launch {
            delay(UPDATE_DEBOUNCE_MS)
            pendingUpdate.set(false)
            _logs.emit(getCurrentLogs())
        }
    }
}
```

`getCurrentLogs` 保持不变（已经是 `synchronized(logDeque)`）。

`clear` 方法：

```kotlin
fun clear() {
    synchronized(logDeque) {
        logDeque.clear()
    }
    requestSessions.clear()
    operationMap.clear()

    DebugLogScope.launch {
        _logs.emit(emptyList())
    }
}
```

- [ ] 删除 import `java.util.concurrent.atomic.AtomicInteger`
- [ ] 删除 `private val logSize = AtomicInteger(0)` 声明

## 任务 4：DebugEventCenter — 修复 eventCount 竞争 + Gson 导出

**文件：** `app/src/main/java/io/legado/app/data/repository/debug/DebugEventCenter.kt`

- [ ] 修复 `eventCount`：加锁读取

```kotlin
val eventCount: Int get() = synchronized(_events) { _events.size }
```

- [ ] 添加 Gson import：`import com.google.gson.Gson`

- [ ] 用 Gson 替换 `exportToJson()` 方法（行 136-172）：

```kotlin
fun exportToJson(): String {
    val gson = Gson()
    return synchronized(_events) {
        gson.toJson(_events.map { event ->
            mapOf(
                "id" to event.id,
                "time" to event.time,
                "level" to event.level.name,
                "category" to event.category.name,
                "message" to event.message,
                "detail" to event.detail,
                "url" to event.url,
                "statusCode" to event.statusCode,
                "duration" to event.duration
            )
        })
    }
}
```

## 任务 5：AppLog — GlobalScope→DebugLogScope

**文件：** `app/src/main/java/io/legado/app/constant/AppLog.kt`

- [ ] 替换 import：删除 `import kotlinx.coroutines.GlobalScope`，添加 `import io.legado.app.model.debug.DebugLogScope`
- [ ] 将所有 4 处 `GlobalScope.launch(Dispatchers.IO)` 替换为 `DebugLogScope.launch`
  - 行 46：`put` 方法
  - 行 77：`putSource` 方法
  - 行 108：`putNotSave` 方法
  - 行 134：`putDebug` 方法

## 任务 6：DebugLogScreen — 修复 LazyColumn key 和 !! 断言

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt`

- [ ] 修复 LazyColumn key（行 448）：移除 index，只用 id

```kotlin
items(
    count = logs.size,
    key = { index -> logs[index].id }
) { index ->
```

- [ ] 消除 !! 断言（行 366-381）：

```kotlin
// 日志详情弹窗
uiState.selectedLog?.let { log ->
    DebugLogDetailDialog(
        log = log,
        onDismiss = { viewModel.clearSelection() },
        onCopy = { viewModel.copyLogDetail(log) }
    )
}

// 流程日志详情弹窗
uiState.selectedFlowLog?.let { flowLog ->
    FlowLogDetailDialog(
        log = flowLog,
        onDismiss = { viewModel.clearSelection() },
        onCopy = { viewModel.copyFlowLogDetail(flowLog) }
    )
}
```

## 任务 7：DebugFloatingBallManager — 恢复清空逻辑

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/DebugFloatingBallManager.kt`

- [ ] 取消注释并修正 `updateFloatingBallState(false)` 中的清空逻辑（行 39-43）：

```kotlin
fun updateFloatingBallState(enabled: Boolean) {
    if (enabled) {
        currentActivity?.let { activity ->
            if (!activity.isFinishing && !activity.isDestroyed) {
                show(activity)
            }
        }
    } else {
        hide()
        FlowLogRecorder.clear()
        DebugEventCenter.clearSync()
    }
}
```

- [ ] 添加 import：`import io.legado.app.data.repository.debug.DebugEventCenter`

## 任务 8：删除 DebugTopBar.kt 死代码

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/DebugTopBar.kt`

- [ ] 删除整个文件（144 行未被任何地方引用）

## 任务 9：提取共享工具函数 — DebugLogDetailDialog

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/DebugLogDetailDialog.kt`

- [ ] 删除文件底部的 `formatFullTime` 函数（行 483-485）
- [ ] 删除文件底部的 `highlightText` 函数（行 452-481）
- [ ] 添加 import：

```kotlin
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.highlightText
```

- [ ] 将所有 `formatFullTime(...)` 调用改为 `DebugLogUtils.formatFullTime(...)`
- [ ] 删除不再需要的 import：`java.text.SimpleDateFormat`、`java.util.Date`、`java.util.Locale`

## 任务 10：提取共享工具函数 — FlowLogDetailDialog

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogDetailDialog.kt`

- [ ] 删除文件底部的 `formatFullTime` 函数（行 839-841）
- [ ] 删除文件底部的 `highlightText` 函数（行 808-837）
- [ ] 添加 import：

```kotlin
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.highlightText
```

- [ ] 将所有 `formatFullTime(...)` 调用改为 `DebugLogUtils.formatFullTime(...)`
- [ ] 删除不再需要的 import：`java.text.SimpleDateFormat`、`java.util.Date`、`java.util.Locale`

## 任务 11：提取共享工具函数 — FlowLogList

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogList.kt`

- [ ] 删除文件底部的 `formatDuration` 函数（行 74-80）
- [ ] 添加 import：`import io.legado.app.model.debug.DebugLogUtils`
- [ ] 将 `formatDuration(totalDuration)` 调用改为 `DebugLogUtils.formatDuration(totalDuration)`

## 任务 12：提取共享工具函数 — RuleExecutionNode

**文件：** `app/src/main/java/io/legado/app/model/debug/RuleExecutionNode.kt`

- [ ] 删除 `formatDuration()` 方法（行 61-69）
- [ ] 本文件无其他调用 `formatDuration()` 的地方，无需新增调用

## 任务 13：提取共享工具函数 — JsExecutionContext

**文件：** `app/src/main/java/io/legado/app/model/debug/JsExecutionContext.kt`

- [ ] 删除 `formatDuration()` 方法（行 137-145）
- [ ] 修改 `toDisplayString()` 中的调用（行 155）：

```kotlin
// 旧：
duration?.let { sb.append("├── 耗时: ${formatDuration()}\n") }
// 新：
DebugLogUtils.formatDuration(duration)?.let { sb.append("├── 耗时: ${it}\n") }
```

## 任务 13b：FlowLogDetailDialog 中调用 JsExecutionRecord.formatDuration

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/FlowLogDetailDialog.kt`

- [ ] 修改行 569：

```kotlin
// 旧：
jsExec.duration?.let {
    DetailRow("执行耗时", jsExec.formatDuration() ?: "${it}ms", searchQuery)
}
// 新：
DebugLogUtils.formatDuration(jsExec.duration)?.let {
    DetailRow("执行耗时", it, searchQuery)
}
```

## 任务 13c：FlowLogItem — 删除 formatDuration 并更新调用

**文件：** `app/src/main/java/io/legado/app/model/debug/FlowLogItem.kt`

- [ ] 删除 `formatDuration()` 方法（行 69-77）
- [ ] 修改 `getSummaryText()` 中的调用（行 103）：

```kotlin
// 旧：
duration?.let { parts.add(formatDuration() ?: "") }
// 新：
DebugLogUtils.formatDuration(duration)?.let { parts.add(it) }
```

## 任务 14：删除未使用的 FlowLogGroup

**文件：** `app/src/main/java/io/legado/app/model/debug/FlowLogItem.kt`

- [ ] 删除 `FlowLogGroup` data class（行 113-152）

## 任务 15：DebugLogViewModel — 使用 DebugLogUtils.formatTime

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/viewmodel/DebugLogViewModel.kt`

- [ ] 替换 `formatTime` 方法体：

```kotlin
private fun formatTime(timestamp: Long): String {
    return DebugLogUtils.formatFullTime(timestamp)
}
```

- [ ] 添加 import：`import io.legado.app.model.debug.DebugLogUtils`
- [ ] 删除不再需要的 import：`java.text.SimpleDateFormat`（如有）

## 任务 15b：模型类 formatTime 委托给 DebugLogUtils

**FlowLogItem.kt：** 修改 `formatTime()`（行 61-64）：
```kotlin
fun formatTime(): String {
    return DebugLogUtils.formatShortTime(startTime)
}
```

**RuleExecutionNode.kt：** 修改 `formatTime()`（行 56-59）：
```kotlin
fun formatTime(): String {
    return DebugLogUtils.formatShortTime(startTime)
}
```

**JsExecutionContext.kt：** 修改 `JsExecutionRecord.formatTime()`（行 132-135）：
```kotlin
fun formatTime(): String {
    return DebugLogUtils.formatShortTime(startTime)
}
```

## 任务 16：验证构建

- [ ] 运行 `./gradlew assembleDebug` 确认编译通过

---

## 自检

1. **规格覆盖度：** 11 个问题（除 #3 外）均有对应任务：
   - #1 GlobalScope → 任务 1,3,5
   - #2 线程安全 → 任务 3
   - #4 formatTime/formatDuration/highlightText 重复 → 任务 2,9,10,11,12,13,13b,13c,15,15b
   - #5 双重同步 → 任务 3
   - #6 !! 断言 → 任务 6
   - #7 LazyColumn key → 任务 6
   - #8 死代码 → 任务 8
   - #9 SimpleDateFormat 重建 → 任务 2,9,10,15,15b
   - #10 exportToJson 手动拼接 → 任务 4
   - #11 清空逻辑不一致 → 任务 7
   - #12 FlowLogGroup 未使用 → 任务 14

2. **无占位符：** 所有代码步骤均给出完整代码。

3. **类型一致性：** DebugLogUtils.formatDuration(ms: Long?) 返回 String?，所有调用处通过 ?.let 安全调用，一致。

4. **包可见性：** DebugLogUtils 和 DebugLogScope 与 RuleExecutionNode/JsExecutionContext/FlowLogItem 同在 `io.legado.app.model.debug` 包，同包调用无需额外 import。
