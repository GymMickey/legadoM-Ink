package io.legado.app.ui.widget.components.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageTopBarContainerColor

/**
 * 多选项目数据模型
 */
data class MultiSelectItem(
    val key: String,                    // 唯一标识
    val title: String,                  // 主标题
    val subtitle: String? = null,       // 副标题 (如文件名)
    val size: String? = null,           // 大小信息 (如 "2.5 MB")
    val rawSize: Long? = null,
    val count: String? = null,          // 数量信息 (如 "128 个")
    val group: String,                  // 分组名称
    val iconEmoji: String? = null,      // Emoji图标 (如 "📚")
    val selected: Boolean = true        // 是否选中
)

/**
 * 分组数据模型
 */
data class MultiSelectGroup(
    val name: String,                   // 分组名称
    val iconEmoji: String? = null,      // 分组图标
    val items: List<MultiSelectItem>    // 该分组的项目
)

/**
 * 通用的多选对话框组件
 * 
 * 功能特性:
 * - 分组展示项目
 * - 显示多种信息(标题、副标题、大小、数量、图标)
 * - 实时计算选中项总大小
 * - 全选/全不选快捷按钮
 * - 主题适配
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MultiSelectDialogContent(
    title: String,                      // 对话框标题
    description: String? = null,        // 描述文字
    groups: List<MultiSelectGroup>,     // 分组数据
    selectedKeys: Set<String>,          // 已选中的key集合
    totalSizeCalculator: (List<MultiSelectItem>) -> String?, // 总大小计算器
    onSelectionChange: (String, Boolean) -> Unit, // 选择变化回调
    onDismiss: () -> Unit,              // 关闭回调
    onSelectAll: () -> Unit,            // 全选回调
    onDeselectAll: () -> Unit           // 全不选回调
) {
    val topBarColor = pageTopBarContainerColor()
    val cardColor = pageCardContainerColor()
    
    // 计算选中项
    val selectedItems = remember(groups, selectedKeys) {
        groups.flatMap { it.items }.filter { it.key in selectedKeys }
    }
    
    // 计算总大小
    val totalSize = remember(selectedItems) {
        totalSizeCalculator(selectedItems)
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .heightIn(max = maxHeight * 0.92f),
                shape = MaterialTheme.shapes.large,
                color = cardColor
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 标题栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(topBarColor)
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        // 左侧：标题和描述
                        Column(
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 右侧：已选数量
                        Text(
                            text = "已选 ${selectedItems.size}/${groups.sumOf { it.items.size }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }

                    // 项目列表
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 分组展示
                        groups.forEach { group ->
                            // 分组标题
                            item {
                                GroupHeader(
                                    groupName = group.name,
                                    iconEmoji = group.iconEmoji
                                )
                            }

                            // 分组内的项目
                            items(group.items, key = { it.key }) { item ->
                                MultiSelectItemRow(
                                    item = item,
                                    isSelected = item.key in selectedKeys,
                                    onSelectionChange = { isSelected ->
                                        onSelectionChange(item.key, isSelected)
                                    }
                                )
                            }

                            // 分组间距
                            item {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }
                    }

                    // 总大小显示（在操作按钮上方）
                    if (totalSize != null) {
                        Text(
                            text = "总大小: $totalSize",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }

                    // 操作按钮
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onSelectAll,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minWidth = 96.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.select_all),
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                        }

                        OutlinedButton(
                            onClick = onDeselectAll,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minWidth = 96.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.un_select_all),
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minWidth = 96.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ok),
                                maxLines = 2,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分组标题
 */
@Composable
private fun GroupHeader(
    groupName: String,
    iconEmoji: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconEmoji != null) {
            Text(
                text = iconEmoji,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        Text(
            text = groupName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        
        Divider(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp
        )
    }
}

/**
 * 多选项目行
 */
@Composable
private fun MultiSelectItemRow(
    item: MultiSelectItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 复选框
        Checkbox(
            checked = isSelected,
            onCheckedChange = onSelectionChange,
            modifier = Modifier.padding(end = 8.dp)
        )
        
        // 内容区域
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // 主标题行
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.iconEmoji != null) {
                    Text(
                        text = item.iconEmoji,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            
            // 副标题和详细信息
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 副标题 (文件名)
                if (item.subtitle != null) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // 数量信息
                if (item.count != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = item.count,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                // 大小信息
                if (item.size != null) {
                    Text(
                        text = item.size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
