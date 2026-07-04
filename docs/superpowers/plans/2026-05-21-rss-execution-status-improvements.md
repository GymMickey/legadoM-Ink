# RSS 执行情况板块信息增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 改进调试日志中 RSS 执行情况板块的信息全面性，支持按源分组展开/收缩、配置字段合法性校验、总耗时与时间戳显示。

**架构：** 修改数据模型增加源标识和执行会话概念，改造 Recorder 支持按源分组，增强配置检查逻辑，在 UI 层按 FlowLogList 的卡片模式重新组织展示。

**技术栈：** Kotlin, Jetpack Compose, SharedFlow

---

## 修改文件清单

| 文件 | 改动类型 | 说明 |
|------|---------|------|
| `app/.../model/debug/RssExecutionRecord.kt` | 修改 | 数据模型增加 sourceUrl/sourceName/executionId/startTime 字段 |
| `app/.../data/repository/debug/RssExecutionRecorder.kt` | 修改 | 改造为按 executionId 分组存储，支持源粒度查询 |
| `app/.../ui/debuglog/components/RssExecutionStatus.kt` | 修改 | UI 重构为按源分组的展开/收缩卡片 |
| `app/.../model/rss/Rss.kt` | 修改 | 传入 sourceName，添加执行会话开始/结束标记 |
| `app/.../model/rss/RssParserByRule.kt` | 修改 | 传入 sourceName 到 recorder 调用 |

---

### 任务 1：修改数据模型

**文件：** `app/src/main/java/io/legado/app/model/debug/RssExecutionRecord.kt`

- [ ] **步骤 1：添加源标识和执行会话字段**

在 `RssExecutionRecord` data class 中增加字段：

```kotlin
data class RssExecutionRecord(
    val step: RssExecutionStep,
    val status: RssExecutionStatus,
    val detail: String? = null,
    val error: String? = null,
    val duration: Long? = null,
    val time: Long = System.currentTimeMillis(),
    val sourceUrl: String = "",
    val sourceName: String = "",
    val executionId: String = "",
    val isSessionStart: Boolean = false,
    val isSessionEnd: Boolean = false
)
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/model/debug/RssExecutionRecord.kt
git commit -m "feat: RSS执行记录增加源标识和执行会话字段"
```

---

### 任务 2：改造 Recorder 支持按源分组

**文件：** `app/src/main/java/io/legado/app/data/repository/debug/RssExecutionRecorder.kt`

- [ ] **步骤 1：重构存储结构，增加会话管理**

整体改造 RssExecutionRecorder：

```kotlin
package io.legado.app.data.repository.debug

import io.legado.app.model.debug.RssExecutionRecord
import io.legado.app.model.debug.RssExecutionStatus
import io.legado.app.model.debug.RssExecutionStep
import io.legado.app.utils.isAbsUrl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.ArrayDeque
import java.util.UUID

object RssExecutionRecorder {

    private const val MAX_RECORDS = 500

    private val records = ArrayDeque<RssExecutionRecord>()

    private val _recordsFlow = MutableSharedFlow<List<RssExecutionRecord>>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val recordsFlow: SharedFlow<List<RssExecutionRecord>> = _recordsFlow.asSharedFlow()

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
        emitUpdate()
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
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/data/repository/debug/RssExecutionRecorder.kt
git commit -m "feat: Recorder 支持按源分组和执行会话管理"
```

---

### 任务 3：修改 RSS 执行入口，传入源信息

**文件：**
- `app/src/main/java/io/legado/app/model/rss/Rss.kt`
- `app/src/main/java/io/legado/app/model/rss/RssParserByRule.kt`

- [ ] **步骤 1：在 Rss.kt 的 getArticlesAwait 中添加会话管理和源信息**

在 `getArticlesAwait` 方法中，配置检查之前添加：

```kotlin
val recorder = RssExecutionRecorder

// 开始执行会话
recorder.startSession(rssSource.sourceUrl, rssSource.sourceName)

// 配置检查阶段（现有代码不变）
recorder.check(RssExecutionStep.SOURCE_NAME, rssSource.sourceName)
...
```

在方法末尾（return 之前），添加会话结束：

```kotlin
// 结束执行会话
recorder.endSession()
return RssParserByRule.parseXML(sortName, sortUrl, res.url, res.body, rssSource, ruleData)
```

在 on failure 的 catch 块中也要调用 `endSession()`。

- [ ] **步骤 2：在 Rss.kt 中对 sourceUrl 做 URL 合法性校验**

将 `recorder.check(RssExecutionStep.SOURCE_URL, rssSource.sourceUrl)` 替换为：

```kotlin
recorder.checkWithValidation(
    RssExecutionStep.SOURCE_URL,
    rssSource.sourceUrl,
    if (rssSource.sourceUrl.isAbsUrl()) true to ""
    else false to "不是合法的 URL（缺少 http:// 或 https://）"
)
```

需要 import `io.legado.app.utils.isAbsUrl`。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/model/rss/Rss.kt
git commit -m "feat: RSS 执行入口添加会话管理和 URL 合法性校验"
```

- [ ] **步骤 4：Commit RssParserByRule.kt（如无需改动则跳过）**

RssParserByRule.kt 中的 recorder 调用不需要修改，因为 recorder 已经通过 startSession 记住了源信息，内部 makeRecord 会自动填充。

---

### 任务 4：重构 RssExecutionStatus UI 为按源分组

**文件：** `app/src/main/java/io/legado/app/ui/debuglog/components/RssExecutionStatus.kt`

- [ ] **步骤 1：整体重写 RssExecutionStatus 组件**

参照 `FlowLogList.kt` 的 `FlowLogCard` 模式，按 executionId 分组，每组显示为可展开/收缩的卡片：

```kotlin
package io.legado.app.ui.debuglog.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.model.debug.RssExecutionRecord
import io.legado.app.model.debug.RssExecutionStatus
import io.legado.app.model.debug.RssExecutionStep
import io.legado.app.ui.widget.components.VerticalScrollbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 订阅源执行情况组件
 *
 * 按源分组展示每个执行会话的步骤状态，
 * 每个源可展开/收缩查看详细步骤。
 */
@Composable
fun RssExecutionStatus(
    records: List<RssExecutionRecord>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "暂无执行记录，运行订阅源调试后将在此显示",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // 按 executionId 分组，每个 executionId 代表一次源执行
    val groupedSessions = remember(records) {
        records
            .groupBy { it.executionId }
            .entries
            .sortedByDescending { (_, items) -> items.maxOfOrNull { it.time } ?: 0L }
            .map { it.key to it.value }
    }

    val listState = rememberLazyListState()

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                count = groupedSessions.size,
                key = { index -> groupedSessions[index].first }
            ) { index ->
                val (executionId, sessionRecords) = groupedSessions[index]
                ExecutionSessionCard(
                    records = sessionRecords,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        VerticalScrollbar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 单次执行会话卡片
 */
@Composable
private fun ExecutionSessionCard(
    records: List<RssExecutionRecord>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    val firstRecord = records.firstOrNull() ?: return
    val sourceName = firstRecord.sourceName.ifBlank { "未知源" }
    val startTime = records.minOfOrNull { it.time } ?: 0L
    val totalTime = records.find { it.isSessionEnd }?.duration

    // 取最新的一条记录作为每个步骤的代表
    val stepRecords = remember(records) {
        val latestByStep = mutableMapOf<RssExecutionStep, RssExecutionRecord>()
        for (record in records) {
            if (!latestByStep.containsKey(record.step) && !record.isSessionEnd) {
                latestByStep[record.step] = record
            }
        }
        RssExecutionStep.entries.mapNotNull { latestByStep[it] }
    }

    val successCount = stepRecords.count { it.status == RssExecutionStatus.SUCCESS }
    val failedCount = stepRecords.count { it.status == RssExecutionStatus.FAILED }
    val skippedCount = stepRecords.count {
        it.status == RssExecutionStatus.SKIPPED || it.status == RssExecutionStatus.EMPTY_SKIP
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (failedCount > 0)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 卡片头部：源名称 + 展开/收缩
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = sourceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (failedCount > 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收缩" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    if (firstRecord.sourceUrl.isNotBlank()) {
                        Text(
                            text = firstRecord.sourceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (startTime > 0) {
                        Text(
                            text = timeFormatter.format(Date(startTime)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${stepRecords.size}步骤",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        totalTime?.let {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatTotalDuration(it),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = if (failedCount > 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 统计条
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatChip("✔ $successCount 成功", MaterialTheme.colorScheme.primary)
                    if (failedCount > 0) {
                        StatChip("✘ $failedCount 失败", MaterialTheme.colorScheme.error)
                    }
                    if (skippedCount > 0) {
                        StatChip("⊘ $skippedCount 跳过", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 配置检查分组
                val configRecords = stepRecords.filter { it.step.isConfigCheck }
                if (configRecords.isNotEmpty()) {
                    SectionHeader("配置检查")
                    configRecords.forEach { record ->
                        ExecutionStepRow(record = record)
                    }
                }

                // 执行步骤分组
                val executionRecords = stepRecords.filter { !it.step.isConfigCheck }
                if (executionRecords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader("执行步骤")
                    executionRecords.forEach { record ->
                        ExecutionStepRow(record = record)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun ExecutionStepRow(record: RssExecutionRecord) {
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = record.detail != null || record.error != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasDetail) Modifier.clickable { expanded = !expanded }
                else Modifier
            ),
        color = when (record.status) {
            RssExecutionStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.status.icon,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = buildString {
                        append(record.step.displayName)
                        when (record.status) {
                            RssExecutionStatus.SUCCESS -> append("执行正确")
                            RssExecutionStatus.FAILED -> append("执行失败")
                            RssExecutionStatus.SKIPPED -> append("跳过执行")
                            RssExecutionStatus.EMPTY_SKIP -> append("为空跳过执行")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (record.status) {
                        RssExecutionStatus.FAILED -> MaterialTheme.colorScheme.error
                        RssExecutionStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (record.status == RssExecutionStatus.FAILED)
                        FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                record.duration?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded && hasDetail,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(start = 24.dp, top = 4.dp)) {
                    record.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    record.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

private fun formatTotalDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/components/RssExecutionStatus.kt
git commit -m "feat: 执行情况 UI 重构为按源分组展开/收缩卡片"
```

---

### 任务 5：验证与构建

- [ ] **步骤 1：编译检查**

```bash
./gradlew assembleAppMaxDebug 2>&1 | tail -20
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 2：如有编译错误，逐个修复**

- [ ] **步骤 3：最终 Commit（如有修复）**

---

## 规格自检

1. **规格覆盖度：** 三个需求（按源分组、合法性校验、总耗时/时间戳）均有对应任务
2. **无占位符：** 所有步骤包含完整代码
3. **类型一致性：** `RssExecutionRecord` 新字段在任务 1 定义，任务 2-4 引用一致
4. **作用边界：** 只改了用户要求的三点，其余不动
