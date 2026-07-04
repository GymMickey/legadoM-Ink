package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.FlowLogItem
import io.legado.app.model.debug.FlowStage

/**
 * 流程日志列表
 */
@Composable
fun FlowLogList(
    logs: List<FlowLogItem>,
    onLogClick: (FlowLogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedLogs = remember(logs) {
        logs
            .groupBy { it.requestId }
            .entries
            .sortedByDescending { (_, items) -> items.firstOrNull()?.startTime ?: 0L }
            .map { it.key to it.value }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Box 包裹 LazyColumn + 可拖拽垂直滚动条
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                count = groupedLogs.size,
                key = { index -> groupedLogs[index].first }
            ) { index ->
                val (requestId, items) = groupedLogs[index]
                FlowLogCard(
                    requestId = requestId,
                    logs = items,
                    onLogClick = onLogClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        io.legado.app.ui.widget.components.VerticalScrollbar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

/**
 * 流程日志卡片
 */
@Composable
private fun FlowLogCard(
    requestId: String,
    logs: List<FlowLogItem>,
    onLogClick: (FlowLogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(true) }
    val firstLog = logs.firstOrNull() ?: return
    val totalDuration = logs.maxOfOrNull { (it.startTime + (it.duration ?: 0)) }
        ?.let { it - firstLog.startTime } ?: 0L
    val isSuccess = logs.none { it.error != null }
    
    val displayLogs = remember(logs) {
        if (logs.size > MAX_VISIBLE_ITEMS) logs.take(MAX_VISIBLE_ITEMS) else logs
    }
    val hasMore = logs.size > MAX_VISIBLE_ITEMS
    val remainingCount = logs.size - MAX_VISIBLE_ITEMS
    
    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
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
                            text = "请求 #${requestId.take(8)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "收缩" else "展开",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    firstLog.sourceName?.let { sourceName ->
                        Text(
                            text = sourceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = firstLog.formatTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${logs.size}条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = DebugLogUtils.formatDuration(totalDuration) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) MaterialTheme.colorScheme.primary 
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                displayLogs.forEachIndexed { index, log ->
                    TimelineItem(
                        log = log,
                        isLast = index == displayLogs.size - 1 && !hasMore,
                        onClick = { onLogClick(log) }
                    )
                }
                
                if (hasMore) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "还有 ${remainingCount} 条日志，点击任意条目查看详情",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 44.dp)
                    )
                }
            }
        }
    }
}

private const val MAX_VISIBLE_ITEMS = 50

/**
 * 时间线项
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineItem(
    log: FlowLogItem,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(start = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = when {
                            log.error != null -> MaterialTheme.colorScheme.error
                            log.stage == FlowStage.NETWORK -> MaterialTheme.colorScheme.primaryContainer
                            log.stage == FlowStage.PARSE -> MaterialTheme.colorScheme.secondaryContainer
                            log.stage == FlowStage.EXTRACT -> MaterialTheme.colorScheme.tertiaryContainer
                            log.stage == FlowStage.REPLACE -> MaterialTheme.colorScheme.surfaceVariant
                            log.stage == FlowStage.VARIABLE -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = log.stage.icon,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, bottom = if (isLast) 0.dp else 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (log.error != null) MaterialTheme.colorScheme.error 
                            else MaterialTheme.colorScheme.onSurface
                )
                
                log.duration?.let { duration ->
                    Text(
                        text = "${duration}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            log.url?.let { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            
            log.rule?.let { rule ->
                Text(
                    text = "规则: $rule",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            
            log.result?.let { result ->
                Text(
                    text = "结果: ${result.take(50)}${if (result.length > 50) "..." else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
            
            log.error?.let { error ->
                Text(
                    text = "错误: ${error.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }
        }
    }
}
