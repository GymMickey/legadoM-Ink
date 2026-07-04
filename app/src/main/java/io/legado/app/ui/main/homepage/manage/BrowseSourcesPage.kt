package io.legado.app.ui.main.homepage.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.SourceConfig
import io.legado.app.ui.main.homepage.HomepageSourceManageUi
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank

/**
 * 书源浏览列表页面。
 *
 * 以列表形式展示所有可用书源，支持分组筛选和搜索功能，
 * 点击某个书源可进入该书源的模块详情页。
 *
 * 主要功能：
 * - 展示所有可用书源及其模块数量
 * - 支持按书源分组筛选
 * - 支持按书源名称搜索
 * - 点击书源项进入书源模块详情页
 *
 * @param sources 所有可用书源的 UI 数据列表
 * @param onSourceClick 点击书源项的回调
 * @param onBack 返回上一页的回调
 */
@Composable
fun BrowseSourcesPage(
    sources: List<HomepageSourceManageUi>,
    onSourceClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    // 搜索关键词
    var searchQuery by remember { mutableStateOf("") }
    // 分组筛选状态
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var showGroupMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 提取所有分组（书源的 bookSourceGroup 字段，使用与 BookSourceDao.dealGroups() 一致的分隔符和排序）
    val allGroups = remember(sources) {
        sources.flatMap { source ->
            source.sourceGroup
                ?.splitNotBlank(AppPattern.splitGroupRegex)
                ?.toList() ?: emptyList()
        }
            .distinct()
            .let { groups ->
                val savedOrder = SourceConfig.getBookSourceGroupOrder()
                if (savedOrder.isEmpty()) {
                    groups.sortedWith { o1, o2 -> o1.cnCompare(o2) }
                } else {
                    val orderMap = savedOrder.withIndex().associate { it.value to it.index }
                    groups.sortedWith { o1, o2 ->
                        val order1 = orderMap[o1] ?: Int.MAX_VALUE
                        val order2 = orderMap[o2] ?: Int.MAX_VALUE
                        order1.compareTo(order2)
                    }
                }
            }
    }

    // 根据搜索和筛选条件过滤书源列表
    val filteredSources = remember(sources, searchQuery, groupFilter) {
        sources.filter { source ->
            // 分组筛选
            val groupMatch = groupFilter == null ||
                source.sourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.any { it == groupFilter } == true
            // 搜索筛选
            val searchMatch = searchQuery.isBlank() ||
                source.sourceName.contains(searchQuery, ignoreCase = true)
            groupMatch && searchMatch
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 搜索栏和分组筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 搜索输入框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.homepage_search_source)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.size(8.dp))
            // 分组筛选按钮
            Box {
                IconButton(onClick = { showGroupMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = stringResource(R.string.homepage_group_filter)
                    )
                }
                DropdownMenu(
                    expanded = showGroupMenu,
                    onDismissRequest = { showGroupMenu = false }
                ) {
                    // 全部分组选项
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.homepage_all_groups)) },
                        onClick = {
                            groupFilter = null
                            showGroupMenu = false
                        },
                        leadingIcon = {
                            if (groupFilter == null) Icon(Icons.Default.Check, null)
                        }
                    )
                    // 各分组选项
                    allGroups.forEach { group ->
                        DropdownMenuItem(
                            text = { Text(group) },
                            onClick = {
                                groupFilter = group
                                showGroupMenu = false
                            },
                            leadingIcon = {
                                if (groupFilter == group) Icon(Icons.Default.Check, null)
                            }
                        )
                    }
                }
            }
        }

        // 当前筛选状态提示
        if (groupFilter != null || searchQuery.isNotBlank()) {
            Text(
                text = buildString {
                    if (groupFilter != null) append("分组: $groupFilter  ")
                    if (searchQuery.isNotBlank()) append("搜索: $searchQuery  ")
                    append("(${filteredSources.size}/${sources.size})")
                },
                style = MaterialTheme.typography.labelSmall,
                color = pageSecondaryTextColor(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // 书源列表
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSources) { source ->
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSourceClick(source.sourceUrl) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 书源名称和模块数量
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.sourceName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(R.string.homepage_module_count, source.moduleCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = pageSecondaryTextColor()
                                )
                            }
                            // 右侧箭头图标
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.homepage_view),
                                tint = pageSecondaryTextColor()
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}
