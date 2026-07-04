package io.legado.app.ui.main.homepage.manage

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.legado.app.R
import io.legado.app.ui.main.homepage.HomepageSourceManageUi
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 集列表页面。
 *
 * 展示所有可用的集（书源集或自定义集），支持长按拖拽排序、
 * 启用/禁用、重命名、删除等操作，并提供新建自定义集和浏览书源模块的入口。
 *
 * @param sets 当前所有集的 UI 数据列表
 * @param onToggleSet 切换集启用状态的回调
 * @param onSetClick 点击集项的回调
 * @param onRenameSet 重命名集的回调
 * @param onDeleteSet 删除集的回调
 * @param onReorderSets 重新排序集的回调
 * @param onCreateCustomSet 点击"新建自定义集"按钮的回调
 * @param onBrowseSources 点击"浏览书源模块"按钮的回调
 * @param onBrowseRssSources 点击"浏览订阅源模块"按钮的回调
 */
@Composable
fun SetListPage(
    sets: List<HomepageSourceManageUi>,
    onToggleSet: (String, Boolean) -> Unit,
    onSetClick: (String) -> Unit,
    onRenameSet: (String) -> Unit,
    onDeleteSet: (String) -> Unit,
    onReorderSets: (List<String>) -> Unit,
    onCreateCustomSet: () -> Unit,
    onBrowseSources: () -> Unit,
    onBrowseRssSources: () -> Unit,
) {
    // 本地排序列表，拖拽时即时更新
    var localSets by remember(sets) { mutableStateOf(sets) }
    val listState = rememberLazyListState()
    val hapticFeedback = LocalHapticFeedback.current

    // 拖拽排序状态
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localSets = localSets.toMutableList().apply {
            if (isEmpty()) return@apply
            val fromIndex = from.index.coerceIn(0, lastIndex)
            val toIndex = to.index.coerceIn(0, lastIndex)
            if (fromIndex in indices && toIndex in indices) {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    // 拖拽结束后持久化排序
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            val orderedUrls = localSets.map { it.sourceUrl }
            if (orderedUrls != sets.map { it.sourceUrl }) {
                onReorderSets(orderedUrls)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(localSets, key = { it.sourceUrl }) { set ->
                    ReorderableItem(reorderableState, key = set.sourceUrl) { isDragging ->
                        SetItem(
                            set = set,
                            isDragging = isDragging,
                            onToggle = { onToggleSet(set.sourceUrl, it) },
                            onClick = { onSetClick(set.sourceUrl) },
                            onRename = { onRenameSet(set.sourceUrl) },
                            onDelete = { onDeleteSet(set.sourceUrl) },
                            dragModifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                        )
                    }
                }
            }
            VerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCreateCustomSet,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.homepage_new_custom_set))
            }
            OutlinedButton(
                onClick = onBrowseSources,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.homepage_browse_source_modules))
            }
            OutlinedButton(
                onClick = onBrowseRssSources,
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.homepage_browse_rss_source_modules))
            }
        }
    }
}

/**
 * 单个集项的 UI 组件。
 * 以卡片形式展示集名称和模块数量，支持长按拖拽排序。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetItem(
    set: HomepageSourceManageUi,
    isDragging: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖拽手柄图标
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.homepage_drag_sort),
                tint = pageSecondaryTextColor(),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            // 集名称和模块数量
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = set.sourceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.homepage_module_count, set.moduleCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor()
                    )
                    // 集类型标识标签
                    val typeLabel = when (set.sourceType) {
                        "book" -> "书源集"
                        "rss" -> "订阅源集"
                        else -> null
                    }
                    if (typeLabel != null) {
                        TextCard(
                            text = typeLabel,
                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
            // 重命名按钮
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.homepage_rename))
            }
            // 删除按钮
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.homepage_delete))
            }
            // 启用/禁用开关
            Switch(checked = set.isSelected, onCheckedChange = onToggle)
        }
    }
}
