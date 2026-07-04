/**
 * 文件：ExploreKindSelectSheet.kt
 *
 * 作用：发现分类选择底部弹窗组件。
 *
 * 视觉规范对标 MD3-main 分支：
 * - 布局：6 列加权网格，读取 ExploreKind.style.layout_flexBasisPercent
 *   （flexBasisPercent >= 1 时占满整行，wrapBefore 时换行）
 * - 选中反馈：背景色从 surfaceVariant 过渡到 primaryContainer（无 Checkbox）
 * - 动效：animateColorAsState 200ms FastOutSlowInEasing
 * - 主题：通过 MaterialTheme.colorScheme 自动适配深色/浅色
 * - 搜索：内置搜索栏支持分类过滤
 * - 交互：单选点击后自动关闭弹窗，多选需要确认按钮提交
 *
 * 与 MD3-main 分支的关键对齐点：
 * - GlassCard RoundedCornerShape(12.dp)
 * - labelMedium 居中显示，padding(vertical = 8.dp)
 * - ModalBottomSheet 搭配 skipPartiallyExpanded = true
 * - 多选确认按钮置于标题栏右侧
 * - calculateExploreKindRows 完全对标 MD3-main 逻辑（含 flexBasisPercent / wrapBefore / tailFill）
 */
package io.legado.app.ui.widget.components.explore

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.ui.widget.components.card.GlassCard
import kotlin.math.roundToInt

// ---- 公共 API ----

/**
 * 发现分类选择底部弹窗
 *
 * 对标 MD3-main ExploreKindSelectSheet 的视觉风格：
 * - 网格布局 + 搜索栏 + 选中动效 + flexBasisPercent 整行支持
 *
 * @param show 是否显示弹窗
 * @param onDismissRequest 关闭弹窗回调
 * @param kinds 分类列表（含样式信息）
 * @param onSelected 选中确认回调
 * @param multiple 是否多选模式
 * @param initialSelectedUrls 初始已选中的分类 URL 集合（以 URL 区分同名分类）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreKindSelectSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    kinds: List<ExploreKind>,
    onSelected: (List<ExploreKind>) -> Unit,
    multiple: Boolean = false,
    initialSelectedUrls: Set<String> = emptySet(),
) {
    if (!show) return

    var selectedUrls by remember(initialSelectedUrls, show) {
        mutableStateOf(initialSelectedUrls)
    }
    var query by remember { mutableStateOf("") }

    // 搜索过滤
    val filteredKinds = remember(query, kinds) {
        if (query.isBlank()) kinds
        else kinds.filter { kind ->
            kind.title.contains(query, ignoreCase = true) ||
                    (kind.url?.contains(query, ignoreCase = true) == true)
        }
    }

    // 6 列加权网格行计算（对标 MD3-main calculateExploreKindRows，含 flexBasisPercent / wrapBefore / tailFill）
    val kindRows = remember(filteredKinds) {
        calculateExploreKindRows(filteredKinds, maxSpan = 6)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val maxHeight = with(density) {
        LocalWindowInfo.current.containerSize.height.toDp() * 0.8f
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .heightIn(max = maxHeight)
        ) {
            // ---- 标题栏（对标 MD3-main AppModalBottomSheet header） ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.homepage_select_category),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                // 多选模式：标题栏右侧显示确认按钮
                if (multiple && selectedUrls.isNotEmpty()) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                        IconButton(onClick = {
                            val selectedKinds = kinds.filter { kindUrl(it) in selectedUrls }
                            onSelected(selectedKinds)
                            onDismissRequest()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.confirm),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // ---- 搜索栏（对标 MD3-main SearchBar） ----
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.homepage_search_or_select_category),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(32.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            // ---- 分类网格列表（对标 MD3-main 6 列加权网格） ----
            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(kindRows) { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (kind, span) ->
                            val key = kindUrl(kind)
                            val isSelected = key in selectedUrls
                            KindChip(
                                text = kind.title,
                                isSelected = isSelected,
                                spanWeight = span.toFloat(),
                                onClick = {
                                    if (multiple) {
                                        selectedUrls = if (isSelected) {
                                            selectedUrls - key
                                        } else {
                                            selectedUrls + key
                                        }
                                    } else {
                                        onSelected(listOf(kind))
                                        onDismissRequest()
                                    }
                                }
                            )
                        }
                        // 填充剩余列（对齐效果）
                        val totalSpan = rowItems.sumOf { it.second }
                        if (totalSpan < 6) {
                            Spacer(modifier = Modifier.weight((6 - totalSpan).toFloat()))
                        }
                    }
                }
            }
        }
    }
}

// ---- 内部组件 ----

/**
 * 分类标签芯片
 *
 * 对标 MD3-main ExploreKindItem 的视觉风格：
 * - 选中：primaryContainer 背景 + onPrimaryContainer 文字
 * - 未选中：surfaceVariant 背景 + onSurfaceVariant 文字
 * - 动效：animateColorAsState 200ms FastOutSlowInEasing
 * - 无 Checkbox，纯背景色变化表示选中状态
 */
@Composable
private fun RowScope.KindChip(
    text: String,
    isSelected: Boolean,
    spanWeight: Float,
    onClick: () -> Unit,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "KindChipBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "KindChipContent"
    )

    GlassCard(
        onClick = onClick,
        cornerRadius = 12.dp,
        containerColor = backgroundColor,
        contentColor = contentColor,
        modifier = Modifier.weight(spanWeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

// ---- 辅助函数 ----

/**
 * 计算分类在网格中的行布局
 *
 * 对标 MD3-main ExploreKindLayout.calculateExploreKindRows 完整算法：
 * - 读取 ExploreKind.style().layout_flexBasisPercent 决定 span
 *   - flexBasisPercent >= 1.0f → 占满整行 (span = maxSpan)
 *   - flexBasisPercent > 0 → 按比例分配 span
 *   - flexGrow > 0 → 默认 3 列
 *   - 其他 → 默认 2 列
 * - layout_wrapBefore → 强制换行
 * - 尾部填充（tailFill）：均匀或尾部扩展以填满 maxSpan
 *
 * @param kinds 分类列表（含样式）
 * @param maxSpan 每行最大列数
 * @return 行列表，每行由 (分类, span) 组成
 */
private fun calculateExploreKindRows(
    kinds: List<ExploreKind>,
    maxSpan: Int,
): List<List<Pair<ExploreKind, Int>>> {
    val rows = mutableListOf<MutableList<Pair<ExploreKind, Int>>>()
    var currentRow = mutableListOf<Pair<ExploreKind, Int>>()
    var currentSpan = 0

    // 尾部填充：均匀扩展或尾部扩展以填满 maxSpan
    fun fillCurrentRowTail() {
        if (currentRow.isEmpty()) return
        val remain = maxSpan - currentSpan
        if (remain <= 0) return
        val allSameSpan = currentRow.map { it.second }.distinct().size == 1
        if (allSameSpan && currentRow.size > 1) {
            // 均匀分配剩余空间
            val addEach = remain / currentRow.size
            var extra = remain % currentRow.size
            currentRow.indices.forEach { index ->
                val (kind, span) = currentRow[index]
                val add = addEach + if (extra > 0) {
                    extra -= 1
                    1
                } else 0
                currentRow[index] = kind to (span + add)
            }
        } else {
            // 尾部扩展
            val (lastKind, lastSpan) = currentRow.last()
            currentRow[currentRow.lastIndex] = lastKind to (lastSpan + remain)
        }
        currentSpan += remain
    }

    kinds.forEach { kind ->
        val style = kind.style()
        val span = when {
            style.layout_wrapBefore || style.layout_flexBasisPercent >= 1.0f -> maxSpan
            style.layout_flexBasisPercent > 0 -> (maxSpan * style.layout_flexBasisPercent).roundToInt()
                .coerceIn(1, maxSpan)
            style.layout_flexGrow > 0f -> 3
            else -> 2
        }
        if ((style.layout_wrapBefore && currentRow.isNotEmpty()) || (currentSpan + span > maxSpan)) {
            fillCurrentRowTail()
            rows.add(currentRow)
            currentRow = mutableListOf()
            currentSpan = 0
        }
        currentRow.add(kind to span)
        currentSpan += span
        if (currentSpan >= maxSpan) {
            rows.add(currentRow)
            currentRow = mutableListOf()
            currentSpan = 0
        }
    }
    if (currentRow.isNotEmpty()) {
        fillCurrentRowTail()
        rows.add(currentRow)
    }
    return rows
}

/**
 * 获取分类的唯一标识符（URL 优先，回退到标题）
 *
 * URL 是区分同名分类的唯一依据：多个分类可能标题相同但指向不同链接。
 */
private fun kindUrl(kind: ExploreKind): String {
    return kind.url?.takeIf { it.isNotBlank() } ?: kind.title
}
