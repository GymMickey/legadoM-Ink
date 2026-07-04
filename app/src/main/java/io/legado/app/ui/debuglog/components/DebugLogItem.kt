package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志列表项组件
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DebugLogItem(
    log: DebugEvent,
    onClick: (DebugEvent) -> Unit,
    onLongClick: (DebugEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .combinedClickable(
                onClick = { onClick(log) },
                onLongClick = { onLongClick(log) }
            ),
        shape = MaterialTheme.shapes.medium,
        color = getBackgroundColorByLevel(log.level),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getIconByLevel(log.level),
                contentDescription = log.level.displayName,
                tint = getTintColorByLevel(log.level),
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = getContentColorByLevel(log.level),
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatRelativeTime(log.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (log.category != io.legado.app.model.debug.DebugCategory.APP) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = log.category.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (log.duration != null && log.duration > 0) {
                Spacer(Modifier.width(8.dp))

                Text(
                    text = "${log.duration}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = getDurationColor(log.duration!!),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

// ==================== 辅助函数 ====================

@Composable
private fun getBackgroundColorByLevel(level: DebugLevel) = when (level) {
    DebugLevel.ERROR -> MaterialTheme.colorScheme.errorContainer
    DebugLevel.WARN -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun getIconByLevel(level: DebugLevel) = when (level) {
    DebugLevel.ERROR -> Icons.Default.Error
    DebugLevel.WARN -> Icons.Default.Warning
    else -> Icons.Default.Info
}

@Composable
private fun getTintColorByLevel(level: DebugLevel) = when (level) {
    DebugLevel.ERROR -> MaterialTheme.colorScheme.error
    DebugLevel.WARN -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun getContentColorByLevel(level: DebugLevel) = when (level) {
    DebugLevel.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    DebugLevel.WARN -> MaterialTheme.colorScheme.onTertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun getDurationColor(durationMs: Long) = when {
    durationMs > 1000 -> MaterialTheme.colorScheme.error
    durationMs > 500 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 1000 -> "刚刚"
        diff < 60_000 -> "${diff / 1000}秒前"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        diff < 86400_000 -> "${diff / 3600_000}小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
