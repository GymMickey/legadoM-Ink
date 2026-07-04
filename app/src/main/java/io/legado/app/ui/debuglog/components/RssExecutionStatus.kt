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
import io.legado.app.model.debug.RssRuleExecutionRecord
import io.legado.app.model.debug.RuleExecutionNode
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
    ruleRecords: List<RssRuleExecutionRecord> = emptyList(),
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
            .filter { !it.isSessionEnd }
            .groupBy { it.executionId }
            .entries
            .sortedByDescending { (_, items) -> items.maxOfOrNull { it.time } ?: 0L }
            .map { it.key to it.value }
    }

    // 预构建 sessionEndMap，避免 LazyColumn 每个 item 都做 O(n) 查找
    val sessionEndMap = remember(records) {
        records.filter { it.isSessionEnd }.associateBy { it.executionId }
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
                val sessionRuleRecords = ruleRecords.filter { it.executionId == executionId }
                ExecutionSessionCard(
                    records = sessionRecords,
                    ruleRecords = sessionRuleRecords,
                    totalDuration = sessionEndMap[executionId]?.duration,
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
    ruleRecords: List<RssRuleExecutionRecord> = emptyList(),
    totalDuration: Long?,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }

    val firstRecord = records.firstOrNull() ?: return
    val sourceName = firstRecord.sourceName.ifBlank { "未知源" }
    val startTime = records.minOfOrNull { it.time } ?: 0L

    // 取每个步骤最新的一条记录
    val stepRecords = remember(records) {
        val latestByStep = mutableMapOf<RssExecutionStep, RssExecutionRecord>()
        for (record in records) {
            if (!latestByStep.containsKey(record.step)) {
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
            // 卡片头部
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
                        totalDuration?.let {
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

            // 展开内容
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

                val configRecords = stepRecords.filter { it.step.isConfigCheck }
                if (configRecords.isNotEmpty()) {
                    SectionHeader("配置检查")
                    configRecords.forEach { record ->
                        ExecutionStepRow(record = record)
                    }
                }

                val executionRecords = stepRecords.filter { !it.step.isConfigCheck }
                if (executionRecords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader("执行步骤")
                    executionRecords.forEach { record ->
                        ExecutionStepRow(record = record)
                    }
                }
                
                if (ruleRecords.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SectionHeader("规则执行路径")
                    ruleRecords.forEach { ruleRecord ->
                        RuleExecutionRow(record = ruleRecord)
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

@Composable
/**
 * 规则执行行组件
 *
 * 展示单条规则执行记录，包含：
 * - 执行步骤名称
 * - 规则内容
 * - 输出结果
 * - 可展开查看执行树
 *
 * @param record 规则执行记录数据
 */
private fun RuleExecutionRow(record: RssRuleExecutionRecord) {
    var expanded by remember { mutableStateOf(false) }
    val hasTree = record.executionTree != null && record.executionTree!!.root.children.isNotEmpty()
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasTree) Modifier.clickable { expanded = !expanded }
                else Modifier
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🌳",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    text = record.step.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
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
            
            record.ruleContent?.let { rule ->
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                )
            }
            
            record.output?.let { output ->
                Text(
                    text = "→ $output",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 24.dp, top = 2.dp)
                )
            }
            
            AnimatedVisibility(
                visible = expanded && hasTree,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                record.executionTree?.let { tree ->
                    Column(modifier = Modifier.padding(start = 24.dp, top = 8.dp)) {
                        tree.root.children.forEach { node ->
                            RuleExecutionNodeView(node, indent = 0)
                        }
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

@Composable
/**
 * 规则执行节点视图组件
 *
 * 递归展示规则执行树的节点，包含：
 * - 步骤序号和类型图标
 * - 规则内容
 * - 执行耗时
 * - 输出结果
 * - 子节点（嵌套规则）
 *
 * @param node 规则执行节点
 * @param indent 缩进层级，用于展示嵌套关系
 */
private fun RuleExecutionNodeView(node: RuleExecutionNode, indent: Int) {
    val indentStr = "  ".repeat(indent)
    
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$indentStr[${node.stepIndex}] ${node.ruleType.icon}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(60.dp + 16.dp * indent)
            )
            Text(
                text = node.ruleContent.take(50),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            node.duration?.let {
                Text(
                    text = "${it}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        node.output?.let { output ->
            Text(
                text = "$indentStr    → ${output.take(80)}",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = (60 + indent * 16).dp)
            )
        }
        
        node.children.forEach { child ->
            RuleExecutionNodeView(child, indent + 1)
        }
    }
}
