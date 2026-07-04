package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.model.debug.FlowStage

/**
 * 流程阶段过滤选择器
 *
 * 用于筛选书源操作流程的不同阶段：
 * - 全部：显示所有阶段
 * - 网络请求：网络请求阶段
 * - 规则解析：规则解析阶段
 * - 字段提取：字段提取阶段
 * - 数据替换：数据替换阶段
 *
 * 支持水平滚动，确保在手机上也能看到所有选项。
 *
 * @param selectedStage 当前选中的阶段
 * @param onStageSelected 阶段选择回调
 * @param showExecutionStatus 是否显示执行情况（订阅源专用）
 * @param onToggleExecutionStatus 执行情况切换回调（订阅源专用）
 * @param modifier 修饰符
 */
@Composable
fun FlowStageFilter(
    selectedStage: FlowStage?,
    onStageSelected: (FlowStage?) -> Unit,
    showExecutionStatus: Boolean = false,
    onToggleExecutionStatus: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "全部"选项
            FilterChip(
                selected = selectedStage == null && !showExecutionStatus,
                onClick = { 
                    onStageSelected(null)
                },
                label = { Text("全部") }
            )

            // 各阶段选项
            FlowStage.entries.forEach { stage ->
                FilterChip(
                    selected = selectedStage == stage && !showExecutionStatus,
                    onClick = { 
                        onStageSelected(stage)
                    },
                    label = { Text("${stage.icon} ${stage.displayName}") }
                )
            }

            // 执行情况选项（订阅源专用）
            if (onToggleExecutionStatus != null) {
                FilterChip(
                    selected = showExecutionStatus,
                    onClick = onToggleExecutionStatus,
                    label = { Text("📋 执行情况") }
                )
            }
        }
    }
}
