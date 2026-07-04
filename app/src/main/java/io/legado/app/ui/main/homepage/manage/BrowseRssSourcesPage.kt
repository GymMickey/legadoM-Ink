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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank

/**
 * 订阅源浏览列表页面。
 *
 * 以列表形式展示所有已添加的订阅源，支持分组筛选和搜索功能，
 * 点击某个订阅源可进入该订阅源的模块详情页（已加入 / 发现双 Tab）。
 *
 * 注意：订阅源的 sourceUrl 可能与书源的 URL 相同，此处通过
 * 独立的 browsingRssSourceUrl 导航状态避免与书源浏览页状态冲突，
 * onClick 回调通过导航状态进入 RssSourceBrowseDetail 页面。
 *
 * @param onSourceClick 点击订阅源的回调（传入 sourceUrl）
 * @param onBack 返回上一页的回调
 */
@Composable
fun BrowseRssSourcesPage(
    onSourceClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val allSources by appDb.rssSourceDao.flowAllLite().collectAsStateWithLifecycle(emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var showGroupMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val allGroups = remember(allSources) {
        allSources.mapNotNull { it.sourceGroup }
            .flatMap { it.splitNotBlank(AppPattern.splitGroupRegex).toList() }
            .distinct()
            .sortedWith { o1, o2 -> o1.cnCompare(o2) }
    }

    val filteredSources = remember(allSources, searchQuery, groupFilter) {
        allSources.filter { source ->
            val groupMatch = groupFilter == null ||
                source.sourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.any { it == groupFilter } == true
            val searchMatch = searchQuery.isBlank() ||
                source.sourceName.contains(searchQuery, ignoreCase = true) ||
                source.sourceUrl.contains(searchQuery, ignoreCase = true)
            groupMatch && searchMatch
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.search_rss_source)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.size(8.dp))
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

        if (groupFilter != null || searchQuery.isNotBlank()) {
            Text(
                text = buildString {
                    if (groupFilter != null) append("分组: $groupFilter  ")
                    if (searchQuery.isNotBlank()) append("搜索: $searchQuery  ")
                    append("(${filteredSources.size}/${allSources.size})")
                },
                style = MaterialTheme.typography.labelSmall,
                color = pageSecondaryTextColor(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredSources, key = { it.sourceUrl }) { source ->
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.sourceName.ifBlank { source.sourceUrl },
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = buildString {
                                    if (!source.rulePubDate.isNullOrBlank()) {
                                        append(source.rulePubDate)
                                    }
                                    if (!source.sourceGroup.isNullOrBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(source.sourceGroup)
                                    }
                                    if (isEmpty() && !source.sourceUrl.isBlank()) {
                                        append(source.sourceUrl)
                                    }
                                }
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = pageSecondaryTextColor(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
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
