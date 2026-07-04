package io.legado.app.ui.blockrule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.RssSourceLite
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchBook
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog
import io.legado.app.model.blockrule.BlockRule
import io.legado.app.model.blockrule.BlockRuleGroupStore
import io.legado.app.model.blockrule.BlockRuleStore
import io.legado.app.ui.widget.components.VerticalScrollbar
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getClipText
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 屏蔽规则配置弹窗
 *
 * 使用 Compose 构建界面，通过 DialogFragment + ComposeView 模式桥接到传统 View 系统。
 * 包含规则列表、编辑弹窗、分组管理、起效规则查看等功能。
 */
class BlockRuleConfigDialog : DialogFragment() {

    var sourceUrl: String = ""
    var allBooks: List<SearchBook> = emptyList()
    var allRssArticles: List<RssArticle> = emptyList()
    var onRulesChanged: (() -> Unit)? = null
    var onShowProgressChanged: ((Boolean) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    BlockRuleConfigContent(
                        sourceUrl = sourceUrl,
                        allBooks = allBooks,
                        allRssArticles = allRssArticles,
                        onDismiss = { dismissAllowingStateLoss() },
                        onRulesChanged = {
                            onRulesChanged?.invoke()
                        },
                        onShowProgressChanged = { show ->
                            onShowProgressChanged?.invoke(show)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 屏蔽规则配置主界面
 * 包含规则列表、分组筛选、屏蔽进度开关、起效规则查看等功能
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockRuleConfigContent(
    sourceUrl: String,
    allBooks: List<SearchBook>,
    allRssArticles: List<RssArticle>,
    onDismiss: () -> Unit,
    onRulesChanged: () -> Unit,
    onShowProgressChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf<List<BlockRule>>(BlockRuleStore.load(context)) }
    var currentGroup by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<BlockRule?>(null) }
    var deletingRule by remember { mutableStateOf<BlockRule?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(context.getPrefBoolean(PreferKey.blockRuleShowProgress, false)) }
    var masterEnabled by remember { mutableStateOf(context.getPrefBoolean(PreferKey.blockRuleEnabled, true)) }
    var showActiveRules by remember { mutableStateOf(false) }
    var allSources by remember { mutableStateOf<List<BookSourcePart>>(emptyList()) }
    var allRssSources by remember { mutableStateOf<List<RssSourceLite>>(emptyList()) }

    // 加载所有书源和订阅源，用于规则列表中名称显示
    // 使用 BookSourcePart（轻量视图）替代全量 BookSource，避免大量书源时 OOM
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allSources = appDb.bookSourceDao.allPart
            allRssSources = appDb.rssSourceDao.allLite
        }
    }

    fun refresh() {
        BlockRuleStore.invalidateCache()
        rules = BlockRuleStore.load(context)
        onRulesChanged()
    }

    fun saveRules(newRules: List<BlockRule>) {
        BlockRuleStore.save(context, newRules)
        rules = newRules
        onRulesChanged()
    }

    val filteredRules = when (currentGroup) {
        null -> rules
        BlockRuleGroupStore.BOOK_SOURCE_GROUP -> rules.filter { BlockRuleGroupStore.isInBookSourceGroup(it) }
        BlockRuleGroupStore.RSS_SOURCE_GROUP -> rules.filter { BlockRuleGroupStore.isInRssSourceGroup(it) }
        else -> rules.filter { it.group == currentGroup }
    }
    val groups = BlockRuleGroupStore.load(context)

    // Delete confirm dialog
    deletingRule?.let { rule ->
        AlertDialog(
            onDismissRequest = { deletingRule = null },
            title = { Text(stringResource(R.string.explore_block_rule_delete_confirm, rule.name.ifBlank { rule.pattern })) },
            confirmButton = {
                TextButton(onClick = {
                    saveRules(rules.filterNot { it.id == rule.id })
                    deletingRule = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingRule = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // Edit dialog
    editingRule?.let { rule ->
        BlockRuleEditContent(
            sourceRule = rule,
            groups = groups,
            onSave = { newRule ->
                val index = rules.indexOfFirst { it.id == newRule.id }
                val newRules = if (index >= 0) {
                    rules.toMutableList().also { it[index] = newRule }
                } else {
                    rules + newRule
                }
                saveRules(newRules)
                editingRule = null
            },
            onDismiss = { editingRule = null }
        )
    }

    // Group manage dialog
    if (showGroupManage) {
        val allGroupsForManage = remember(groups) {
            BlockRuleGroupStore.RESERVED_GROUPS.toList() + groups.filter { it !in BlockRuleGroupStore.RESERVED_GROUPS }
        }
        BlockRuleGroupManageContent(
            groups = allGroupsForManage,
            onAddGroup = { name ->
                val newGroups = (groups + name).distinct()
                BlockRuleGroupStore.save(context, newGroups)
                refresh()
            },
            onRenameGroup = { oldName, newName ->
                val newRules = rules.map {
                    if (it.group == oldName) it.copy(group = newName) else it
                }
                val newGroups = groups.map { if (it == oldName) newName else it }
                BlockRuleGroupStore.save(context, newGroups)
                saveRules(newRules)
                if (currentGroup == oldName) currentGroup = newName
            },
            onDeleteGroup = { name ->
                val newRules = rules.map {
                    if (it.group == name) it.copy(group = BlockRuleGroupStore.DEFAULT_GROUP) else it
                }
                val newGroups = groups.filterNot { it == name }
                BlockRuleGroupStore.save(context, newGroups)
                saveRules(newRules)
                if (currentGroup == name) currentGroup = null
            },
            onDismiss = { showGroupManage = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = pageCardContainerColor()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭")
                }
                Text(
                    text = stringResource(R.string.explore_block_rule_config),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    editingRule = BlockRule(
                        group = currentGroup ?: BlockRuleGroupStore.DEFAULT_GROUP
                    )
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.explore_block_rule_add))
                }
                Box {
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.explore_block_rule_group_manage)) },
                            onClick = {
                                showMoreMenu = false
                                showGroupManage = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.explore_block_rule_import_success).replace("成功", "")) },
                            onClick = {
                                showMoreMenu = false
                                importFromClipboard(context) { refresh() }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.explore_block_rule_export_success).replace("已复制到剪贴板", "导出")) },
                            onClick = {
                                showMoreMenu = false
                                exportToClipboard(context, filteredRules)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Group filter chips（含保留分组：书源、订阅源）
            val allFilterGroups = remember(groups, rules) {
                val result = mutableListOf<String>()
                // 保留分组
                if (rules.any { BlockRuleGroupStore.isInBookSourceGroup(it) }) {
                    result.add(BlockRuleGroupStore.BOOK_SOURCE_GROUP)
                }
                if (rules.any { BlockRuleGroupStore.isInRssSourceGroup(it) }) {
                    result.add(BlockRuleGroupStore.RSS_SOURCE_GROUP)
                }
                // 用户自定义分组
                result.addAll(groups)
                result
            }
            if (allFilterGroups.size > 1 || allFilterGroups.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = currentGroup == null,
                        onClick = { currentGroup = null },
                        label = { Text(stringResource(R.string.explore_block_rule_scope_all)) }
                    )
                    allFilterGroups.forEach { group ->
                        FilterChip(
                            selected = currentGroup == group,
                            onClick = { currentGroup = group },
                            label = { Text(group) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ===== 屏蔽规则总控开关 =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.explore_block_rule_enable_master),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = masterEnabled,
                    onCheckedChange = { enabled ->
                        masterEnabled = enabled
                        context.putPrefBoolean(PreferKey.blockRuleEnabled, enabled)
                        BlockRuleStore.invalidateCache()
                        onRulesChanged()
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 显示屏蔽进度开关 + 开启屏蔽规则后起效的规则按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.explore_block_rule_show_progress),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = showProgress,
                    onCheckedChange = {
                        showProgress = it
                        context.putPrefBoolean(PreferKey.blockRuleShowProgress, it)
                        onShowProgressChanged(it)
                    }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showActiveRules = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.explore_block_rule_active_rules),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = pageAccentColor()
                )
                val currentMatchedRules = if (allRssArticles.isNotEmpty()) {
                    BlockRuleStore.getMatchedRssRules(context, allRssArticles, sourceUrl)
                } else {
                    BlockRuleStore.getMatchedRules(context, allBooks, sourceUrl)
                }
                Text(
                    text = "${currentMatchedRules.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = pageSecondaryTextColor()
                )
            }

            HorizontalDivider()

            // Rule list
            if (filteredRules.isEmpty()) {
                Text(
                    text = stringResource(R.string.explore_block_rule_empty),
                    modifier = Modifier.padding(18.dp),
                    color = pageSecondaryTextColor()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 480.dp)
                ) {
                    items(filteredRules, key = { it.id }) { rule ->
                        BlockRuleItem(
                            rule = rule,
                            allSources = allSources,
                            allRssSources = allRssSources,
                            onToggleEnabled = {
                                val newRules = rules.map {
                                    if (it.id == rule.id) it.copy(enabled = !it.enabled) else it
                                }
                                saveRules(newRules)
                            },
                            onEdit = { editingRule = rule },
                            onDelete = { deletingRule = rule }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // 开启屏蔽规则后起效的规则弹窗：显示实际匹配到书籍/文章的规则，可展开查看匹配的内容
    if (showActiveRules) {
        val activeMatchedRules = if (allRssArticles.isNotEmpty()) {
            BlockRuleStore.getMatchedRssRules(context, allRssArticles, sourceUrl)
        } else {
            BlockRuleStore.getMatchedRules(context, allBooks, sourceUrl)
        }
        AlertDialog(
            onDismissRequest = { showActiveRules = false },
            title = { Text(stringResource(R.string.explore_block_rule_active_rules)) },
            text = {
                if (activeMatchedRules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.explore_block_rule_active_rules_empty),
                        color = pageSecondaryTextColor()
                    )
                } else if (allRssArticles.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(activeMatchedRules, key = { it.id }) { rule ->
                            val matchedArticles = allRssArticles.filter { rule.matchesRssArticle(it) }
                            ActiveRssRuleItem(rule = rule, matchedArticles = matchedArticles)
                            HorizontalDivider()
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        items(activeMatchedRules, key = { it.id }) { rule ->
                            val matchedBooks = allBooks.filter { rule.matches(it) }
                            ActiveRuleItem(rule = rule, matchedBooks = matchedBooks)
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showActiveRules = false }) { Text(stringResource(android.R.string.ok)) }
            }
        )
    }
}

/** 起效规则项（书籍），可展开/收起查看匹配到的书籍，默认收起 */
@Composable
private fun ActiveRuleItem(
    rule: BlockRule,
    matchedBooks: List<SearchBook>
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 规则标题行，点击展开/收起
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { rule.pattern },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${rule.modeLabel()} / ${rule.scopeSummary()} / 匹配${matchedBooks.size}本",
                    style = MaterialTheme.typography.bodySmall,
                    color = pageSecondaryTextColor()
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = pageSecondaryTextColor()
            )
        }

        // 展开后显示匹配的书籍列表
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                matchedBooks.forEach { book ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "《${book.name}》",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (book.author.isNotBlank()) {
                            Text(
                                text = " ${book.author}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = pageSecondaryTextColor()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 起效规则项（订阅源文章），可展开/收起查看匹配到的文章，默认收起 */
@Composable
private fun ActiveRssRuleItem(
    rule: BlockRule,
    matchedArticles: List<RssArticle>
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 规则标题行，点击展开/收起
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name.ifBlank { rule.pattern },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${rule.modeLabel()} / ${rule.scopeSummary()} / 匹配${matchedArticles.size}条",
                    style = MaterialTheme.typography.bodySmall,
                    color = pageSecondaryTextColor()
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(20.dp),
                tint = pageSecondaryTextColor()
            )
        }

        // 展开后显示匹配的文章列表
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                matchedArticles.forEach { article ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = article.title.ifBlank { article.link },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        if (!article.pubDate.isNullOrBlank()) {
                            Text(
                                text = " ${article.pubDate}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = pageSecondaryTextColor()
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 规则列表项，显示名称、模式、范围、分组，支持启用/编辑/删除 */
@Composable
private fun BlockRuleItem(
    rule: BlockRule,
    allSources: List<BookSourcePart>,
    allRssSources: List<RssSourceLite>,
    onToggleEnabled: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = rule.name.ifBlank { rule.pattern },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = "${rule.modeLabel()} / ${rule.scopeSummary()} / ${rule.group}",
                    style = MaterialTheme.typography.bodySmall,
                    color = pageSecondaryTextColor()
                )
                if (rule.pattern.isNotBlank()) {
                    Text(
                        text = rule.pattern,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor()
                    )
                }
                if (!rule.scope.isNullOrBlank()) {
                    val scopeNames = rule.scope!!.split(";").map { it.trim() }.filter { it.isNotBlank() }.map { url ->
                        allSources.find { it.bookSourceUrl == url }?.bookSourceName ?: url
                    }
                    Text(
                        text = "书源: ${scopeNames.joinToString(", ")}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor()
                    )
                }
                if (!rule.rssScope.isNullOrBlank()) {
                    val rssScopeNames = rule.rssScope!!.split(";").map { it.trim() }.filter { it.isNotBlank() }.map { url ->
                        allRssSources.find { it.sourceUrl == url }?.sourceName ?: url
                    }
                    Text(
                        text = "订阅源: ${rssScopeNames.joinToString(", ")}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor()
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggleEnabled() }
                )
                IconButton(onClick = onEdit, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.width(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.width(32.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.width(18.dp))
                }
            }
        },
        modifier = Modifier.clickable { onEdit() }
    )
}

/** 规则编辑弹窗，支持新增和编辑屏蔽规则 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun BlockRuleEditContent(
    sourceRule: BlockRule,
    groups: List<String>,
    onSave: (BlockRule) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(sourceRule.name) }
    var pattern by remember { mutableStateOf(sourceRule.pattern) }
    var isRegex by remember { mutableStateOf(sourceRule.isRegex) }
    var selectedGroup by remember { mutableStateOf(sourceRule.group.ifBlank { BlockRuleGroupStore.DEFAULT_GROUP }) }
    var targetScope by remember { mutableIntStateOf(sourceRule.targetScope) }
    var rssTargetScope by remember { mutableIntStateOf(sourceRule.rssTargetScope) }
    var scope by remember { mutableStateOf(sourceRule.scope.orEmpty()) }
    var rssScope by remember { mutableStateOf(sourceRule.rssScope.orEmpty()) }
    var enabled by remember { mutableStateOf(sourceRule.enabled) }
    var patternError by remember { mutableStateOf<String?>(null) }
    var bookScopeError by remember { mutableStateOf<String?>(null) }
    var rssScopeError by remember { mutableStateOf<String?>(null) }
    var noScopeError by remember { mutableStateOf<String?>(null) }
    var showBookScope by remember { mutableStateOf(sourceRule.targetScope != 0 || (sourceRule.targetScope == 0 && sourceRule.rssTargetScope == 0)) }
    var showRssScope by remember { mutableStateOf(sourceRule.rssTargetScope != 0 || (sourceRule.targetScope == 0 && sourceRule.rssTargetScope == 0)) }
    var showScopeSelector by remember { mutableStateOf(false) }
    var showRssScopeSelector by remember { mutableStateOf(false) }
    var totalSourceCount by remember { mutableIntStateOf(0) }
    var totalRssSourceCount by remember { mutableIntStateOf(0) }

    // 加载书源总数和订阅源总数，用于判断是否全选
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            totalSourceCount = appDb.bookSourceDao.allCount()
            totalRssSourceCount = appDb.rssSourceDao.size
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (sourceRule.id.isBlank() || sourceRule.name.isBlank() && sourceRule.pattern.isBlank())
                    stringResource(R.string.explore_block_rule_add)
                else
                    stringResource(R.string.explore_block_rule_edit)
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Rule name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.explore_block_rule_name)) },
                    placeholder = { Text(stringResource(R.string.explore_block_rule_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Pattern + regex toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = {
                            pattern = it
                            patternError = if (it.isNotBlank() && isRegex) {
                                runCatching { Regex(it) }.exceptionOrNull()?.localizedMessage
                            } else null
                        },
                        label = { Text(stringResource(R.string.explore_block_rule_pattern)) },
                        placeholder = { Text(stringResource(R.string.explore_block_rule_pattern_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        isError = patternError != null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = isRegex, onCheckedChange = {
                            isRegex = it
                            patternError = if (pattern.isNotBlank() && it) {
                                runCatching { Regex(pattern) }.exceptionOrNull()?.localizedMessage
                            } else null
                        })
                        Text(
                            text = if (isRegex) stringResource(R.string.explore_block_rule_regex_mode)
                            else stringResource(R.string.explore_block_rule_keyword_mode),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                patternError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // Group selection
                var groupExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = groupExpanded,
                    onExpandedChange = { groupExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedGroup,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.explore_block_rule_group_default)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = groupExpanded,
                        onDismissRequest = { groupExpanded = false }
                    ) {
                        groups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group) },
                                onClick = {
                                    selectedGroup = group
                                    groupExpanded = false
                                }
                            )
                        }
                    }
                }

                // ===== 作用范围主开关 =====
                Text(
                    text = stringResource(R.string.explore_block_rule_target_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = showBookScope,
                        onClick = {
                            showBookScope = !showBookScope
                            if (showBookScope) {
                                noScopeError = null
                            } else {
                                targetScope = 0
                                scope = ""
                                bookScopeError = null
                            }
                        },
                        label = { Text("书源") }
                    )
                    FilterChip(
                        selected = showRssScope,
                        onClick = {
                            showRssScope = !showRssScope
                            if (showRssScope) {
                                noScopeError = null
                            } else {
                                rssTargetScope = 0
                                rssScope = ""
                                rssScopeError = null
                            }
                        },
                        label = { Text("订阅源") }
                    )
                }

                noScopeError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider()

                // ===== 书源作用范围子区域 =====
                AnimatedVisibility(visible = showBookScope) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.explore_block_rule_book_target_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val bookScopeOptions = listOf(
                                BlockRule.SCOPE_TITLE to stringResource(R.string.explore_block_rule_scope_title),
                                BlockRule.SCOPE_AUTHOR to stringResource(R.string.explore_block_rule_scope_author),
                                BlockRule.SCOPE_KIND to stringResource(R.string.explore_block_rule_scope_kind),
                                BlockRule.SCOPE_INTRO to stringResource(R.string.explore_block_rule_scope_intro),
                                BlockRule.SCOPE_WORD_COUNT to stringResource(R.string.explore_block_rule_scope_word_count),
                            )
                            bookScopeOptions.forEach { (flag, label) ->
                                FilterChip(
                                    selected = (targetScope and flag) != 0,
                                    onClick = {
                                        targetScope = targetScope xor flag
                                        bookScopeError = null
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        bookScopeError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        // 作用的指定书源选择器
                        OutlinedTextField(
                            value = if (scope.isBlank()) "全部书源" else {
                                val count = scope.split(";").map { it.trim() }.filter { it.isNotBlank() }.size
                                "已选 $count 个书源"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.explore_block_rule_source_scope)) },
                            trailingIcon = {
                                IconButton(onClick = { showScopeSelector = true }) {
                                    Icon(Icons.Filled.ExpandMore, contentDescription = "选择书源")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showScopeSelector = true }
                        )
                    }
                }

                // ===== 订阅源作用范围子区域 =====
                AnimatedVisibility(visible = showRssScope) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                        Text(
                            text = stringResource(R.string.explore_block_rule_rss_target_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val rssScopeOptions = listOf(
                                BlockRule.SCOPE_RSS_TITLE to stringResource(R.string.explore_block_rule_scope_rss_title),
                                BlockRule.SCOPE_RSS_TIME to stringResource(R.string.explore_block_rule_scope_rss_time),
                            )
                            rssScopeOptions.forEach { (flag, label) ->
                                FilterChip(
                                    selected = (rssTargetScope and flag) != 0,
                                    onClick = {
                                        rssTargetScope = rssTargetScope xor flag
                                        rssScopeError = null
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        rssScopeError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }

                        // 作用的指定订阅源选择器
                        OutlinedTextField(
                            value = if (rssScope.isBlank()) "全部订阅源" else {
                                val count = rssScope.split(";").map { it.trim() }.filter { it.isNotBlank() }.size
                                "已选 $count 个订阅源"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.explore_block_rule_rss_source_scope)) },
                            trailingIcon = {
                                IconButton(onClick = { showRssScopeSelector = true }) {
                                    Icon(Icons.Filled.ExpandMore, contentDescription = "选择订阅源")
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showRssScopeSelector = true }
                        )
                    }
                }

                // Enabled
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.explore_block_rule_enabled),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pattern.isNotBlank() && patternError == null,
                onClick = {
                    // 前端安全验证：必须至少选择书源或订阅源之一
                    var hasError = false
                    if (!showBookScope && !showRssScope) {
                        noScopeError = "请至少选择书源或订阅源"
                        hasError = true
                    }
                    // 已启用的作用范围必须至少选择一个子字段
                    if (showBookScope && targetScope == 0) {
                        bookScopeError = "请至少选择一项书源作用范围"
                        hasError = true
                    }
                    if (showRssScope && rssTargetScope == 0) {
                        rssScopeError = "请至少选择一项订阅源作用范围"
                        hasError = true
                    }
                    if (hasError) return@TextButton

                    onSave(
                        sourceRule.copy(
                            id = sourceRule.id.ifBlank { UUID.randomUUID().toString() },
                            name = name.ifBlank { pattern },
                            pattern = pattern,
                            isRegex = isRegex,
                            group = selectedGroup.ifBlank { BlockRuleGroupStore.DEFAULT_GROUP },
                            targetScope = targetScope,
                            rssTargetScope = rssTargetScope,
                            enabled = enabled,
                            scope = scope.takeIf { it.isNotBlank() },
                            rssScope = rssScope.takeIf { it.isNotBlank() },
                        )
                    )
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )

    // 作用的书源选择器弹窗
    if (showScopeSelector) {
        val currentScopeUrls = if (scope.isBlank()) emptySet()
        else scope.split(";").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        BookSourceSelectorDialog(
            title = stringResource(R.string.explore_block_rule_source_scope),
            initialSelectedUrls = currentScopeUrls,
            defaultSelectAll = false,
            onConfirm = { selectedUrls ->
                // 如果全选了所有书源，保存为空（空=所有书源）
                scope = if (selectedUrls.isEmpty() || selectedUrls.size == totalSourceCount) {
                    ""
                } else {
                    selectedUrls.joinToString(";")
                }
                showScopeSelector = false
            },
            onDismiss = { showScopeSelector = false }
        )
    }

    // 作用的订阅源选择器弹窗
    if (showRssScopeSelector) {
        val currentRssScopeUrls = if (rssScope.isBlank()) emptySet()
        else rssScope.split(";").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        RssSourceSelectorDialog(
            title = stringResource(R.string.explore_block_rule_rss_source_scope),
            initialSelectedUrls = currentRssScopeUrls,
            defaultSelectAll = false,
            onConfirm = { selectedUrls ->
                // 如果全选了所有订阅源，保存为空（空=所有订阅源）
                rssScope = if (selectedUrls.isEmpty() || selectedUrls.size == totalRssSourceCount) {
                    ""
                } else {
                    selectedUrls.joinToString(";")
                }
                showRssScopeSelector = false
            },
            onDismiss = { showRssScopeSelector = false }
        )
    }
}

/** 分组管理弹窗，支持新增/重命名/删除分组 */
@Composable
private fun BlockRuleGroupManageContent(
    groups: List<String>,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (oldName: String, newName: String) -> Unit,
    onDeleteGroup: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var localGroups by remember { mutableStateOf(groups) }
    var inputDialog by remember { mutableStateOf<GroupInput?>(null) }
    var deleteDialog by remember { mutableStateOf<String?>(null) }

    // Input dialog for add/rename
    inputDialog?.let { dialog ->
        var inputName by remember { mutableStateOf(dialog.initialName) }
        AlertDialog(
            onDismissRequest = { inputDialog = null },
            title = { Text(dialog.title) },
            text = {
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = inputName.isNotBlank(),
                    onClick = {
                        dialog.onConfirm(inputName.trim())
                        inputDialog = null
                    }
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { inputDialog = null }) { Text(stringResource(android.R.string.cancel)) }
            }
        )
    }

    // Delete confirm dialog
    deleteDialog?.let { groupName ->
        AppConfirmDialog(
            title = stringResource(R.string.explore_block_rule_group_manage),
            text = "确定删除分组「$groupName」？规则将移至默认分组。",
            confirmText = stringResource(android.R.string.ok),
            destructive = true,
            onConfirm = {
                onDeleteGroup(groupName)
                localGroups = localGroups.filterNot { it == groupName }
                deleteDialog = null
            },
            onDismissRequest = { deleteDialog = null }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explore_block_rule_group_manage)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(localGroups) { group ->
                    val isReserved = BlockRuleGroupStore.isReservedGroup(group)
                    ListItem(
                        leadingContent = {
                            Icon(
                                Icons.Filled.Folder,
                                contentDescription = null,
                                tint = if (isReserved) pageSecondaryTextColor() else MaterialTheme.colorScheme.primary
                            )
                        },
                        headlineContent = {
                            Text(
                                group,
                                color = if (isReserved) pageSecondaryTextColor() else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        trailingContent = {
                            if (!isReserved) {
                                Row {
                                    IconButton(onClick = {
                                        inputDialog = GroupInput("重命名分组", group) { newName ->
                                            onRenameGroup(group, newName)
                                            localGroups = localGroups.map { if (it == group) newName else it }
                                        }
                                    }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "重命名")
                                    }
                                    IconButton(onClick = { deleteDialog = group }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                                    }
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                inputDialog = GroupInput("添加分组") { name ->
                    onAddGroup(name)
                    localGroups = (localGroups + name).distinct()
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/**
 * 书源选择器弹窗
 *
 * 显示所有书源供用户勾选，支持搜索过滤、全选和反选操作。
 * 用于"作用的书源"字段的可视化选择。
 *
 * @param title 弹窗标题
 * @param initialSelectedUrls 初始选中的书源URL集合
 * @param defaultSelectAll 初始未选任何书源时是否默认全选
 * @param onConfirm 确认回调，参数为选中的书源URL集合
 * @param onDismiss 取消回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookSourceSelectorDialog(
    title: String,
    initialSelectedUrls: Set<String>,
    defaultSelectAll: Boolean = false,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var allSources by remember { mutableStateOf<List<BookSourcePart>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedUrls by remember { mutableStateOf(initialSelectedUrls) }
    var defaultSelectAllPending by remember { mutableStateOf(defaultSelectAll && initialSelectedUrls.isEmpty()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sources = appDb.bookSourceDao.allPart
            withContext(Dispatchers.Main) {
                allSources = sources
                if (defaultSelectAllPending) {
                    selectedUrls = sources.map { it.bookSourceUrl }.toSet()
                    defaultSelectAllPending = false
                }
                isLoading = false
            }
        }
    }

    val filteredSources = remember(allSources, searchQuery) {
        if (searchQuery.isBlank()) allSources
        else allSources.filter {
            it.bookSourceName.contains(searchQuery, ignoreCase = true) ||
                it.bookSourceUrl.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allSources.isEmpty()) {
                Text(
                    text = "暂无书源",
                    color = pageSecondaryTextColor()
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索书源名称或URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 全选 / 反选按钮 + 已选计数
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedUrls = filteredSources.map { it.bookSourceUrl }.toSet()
                        }) {
                            Text("全选")
                        }
                        TextButton(onClick = {
                            val filteredUrls = filteredSources.map { it.bookSourceUrl }.toSet()
                            val newSelected = selectedUrls.toMutableSet()
                            filteredUrls.forEach { url ->
                                if (url in newSelected) newSelected.remove(url) else newSelected.add(url)
                            }
                            selectedUrls = newSelected
                        }) {
                            Text("反选")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${selectedUrls.size}/${allSources.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = pageSecondaryTextColor()
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 书源列表 + 滚动条
                    val listState = rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.CenterStart)
                        ) {
                            items(filteredSources, key = { it.bookSourceUrl }) { source ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedUrls = if (source.bookSourceUrl in selectedUrls) {
                                                selectedUrls - source.bookSourceUrl
                                            } else {
                                                selectedUrls + source.bookSourceUrl
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = source.bookSourceUrl in selectedUrls,
                                        onCheckedChange = { checked ->
                                            selectedUrls = if (checked) {
                                                selectedUrls + source.bookSourceUrl
                                            } else {
                                                selectedUrls - source.bookSourceUrl
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = source.bookSourceName.ifBlank { source.bookSourceUrl },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (source.bookSourceName.isNotBlank()) {
                                            Text(
                                                text = source.bookSourceUrl,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = pageSecondaryTextColor()
                                            )
                                        }
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedUrls) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

/**
 * 订阅源选择器弹窗
 *
 * 显示所有订阅源供用户勾选，支持搜索过滤、全选和反选操作。
 * 用于"作用的订阅源"字段的可视化选择。
 *
 * @param title 弹窗标题
 * @param initialSelectedUrls 初始选中的订阅源URL集合
 * @param defaultSelectAll 初始未选任何订阅源时是否默认全选
 * @param onConfirm 确认回调，参数为选中的订阅源URL集合
 * @param onDismiss 取消回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RssSourceSelectorDialog(
    title: String,
    initialSelectedUrls: Set<String>,
    defaultSelectAll: Boolean = false,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var allSources by remember { mutableStateOf<List<RssSourceLite>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedUrls by remember { mutableStateOf(initialSelectedUrls) }
    var defaultSelectAllPending by remember { mutableStateOf(defaultSelectAll && initialSelectedUrls.isEmpty()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val sources = appDb.rssSourceDao.allLite
            withContext(Dispatchers.Main) {
                allSources = sources
                if (defaultSelectAllPending) {
                    selectedUrls = sources.map { it.sourceUrl }.toSet()
                    defaultSelectAllPending = false
                }
                isLoading = false
            }
        }
    }

    val filteredSources = remember(allSources, searchQuery) {
        if (searchQuery.isBlank()) allSources
        else allSources.filter {
            it.sourceName.contains(searchQuery, ignoreCase = true) ||
                it.sourceUrl.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (allSources.isEmpty()) {
                Text(
                    text = "暂无订阅源",
                    color = pageSecondaryTextColor()
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索订阅源名称或URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 全选 / 反选按钮 + 已选计数
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            selectedUrls = filteredSources.map { it.sourceUrl }.toSet()
                        }) {
                            Text("全选")
                        }
                        TextButton(onClick = {
                            val filteredUrls = filteredSources.map { it.sourceUrl }.toSet()
                            val newSelected = selectedUrls.toMutableSet()
                            filteredUrls.forEach { url ->
                                if (url in newSelected) newSelected.remove(url) else newSelected.add(url)
                            }
                            selectedUrls = newSelected
                        }) {
                            Text("反选")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${selectedUrls.size}/${allSources.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = pageSecondaryTextColor()
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 订阅源列表 + 滚动条
                    val listState = rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.CenterStart)
                        ) {
                            items(filteredSources, key = { it.sourceUrl }) { source ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedUrls = if (source.sourceUrl in selectedUrls) {
                                                selectedUrls - source.sourceUrl
                                            } else {
                                                selectedUrls + source.sourceUrl
                                            }
                                        }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = source.sourceUrl in selectedUrls,
                                        onCheckedChange = { checked ->
                                            selectedUrls = if (checked) {
                                                selectedUrls + source.sourceUrl
                                            } else {
                                                selectedUrls - source.sourceUrl
                                            }
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = source.sourceName.ifBlank { source.sourceUrl },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (source.sourceName.isNotBlank()) {
                                            Text(
                                                text = source.sourceUrl,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = pageSecondaryTextColor()
                                            )
                                        }
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedUrls) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

private data class GroupInput(
    val title: String,
    val initialName: String = "",
    val onConfirm: (String) -> Unit
)

private fun importFromClipboard(context: android.content.Context, onRefresh: () -> Unit) {
    val clip = context.getClipText()
    if (clip.isNullOrBlank()) {
        context.toastOnUi(R.string.explore_block_rule_clipboard_empty)
        return
    }
    val imported = GSON.fromJsonArray<BlockRule>(clip).getOrNull()
    if (imported.isNullOrEmpty()) {
        context.toastOnUi(R.string.explore_block_rule_import_invalid)
        return
    }
    val existing = BlockRuleStore.load(context)
    val existingIds = existing.map { it.id }.toSet()
    val usedIds = mutableSetOf<String>()
    
    // 检查规则是否与现有规则内容相同（忽略 id）
    fun isDuplicate(rule: BlockRule): Boolean {
        return existing.any { existingRule ->
            existingRule.name == rule.name &&
            existingRule.pattern == rule.pattern &&
            existingRule.group == rule.group &&
            existingRule.isRegex == rule.isRegex &&
            existingRule.targetScope == rule.targetScope &&
            existingRule.rssTargetScope == rule.rssTargetScope &&
            existingRule.scope == rule.scope &&
            existingRule.rssScope == rule.rssScope
        }
    }
    
    val newRules = imported.mapNotNull { rule ->
        var normalized = BlockRuleStore.sanitizeRule(rule)
        
        // 检查规则内容是否与现有规则重复，如果重复则跳过
        if (isDuplicate(normalized)) {
            return@mapNotNull null
        }
        
        // 检查与现有规则的 id 冲突，以及导入列表内部的 id 冲突
        while (normalized.id in existingIds || normalized.id in usedIds) {
            normalized = normalized.copyWithNewId()
        }
        usedIds.add(normalized.id)
        normalized
    }
    
    if (newRules.isEmpty()) {
        context.toastOnUi("导入的规则已存在，无新规则")
        return
    }
    
    BlockRuleStore.save(context, existing + newRules)
    context.toastOnUi("成功导入 ${newRules.size} 条规则")
    onRefresh()
}

private fun exportToClipboard(context: android.content.Context, rules: List<BlockRule>) {
    if (rules.isEmpty()) {
        context.toastOnUi(R.string.explore_block_rule_empty)
        return
    }
    context.sendToClip(GSON.toJson(rules))
    context.toastOnUi(R.string.explore_block_rule_export_success)
}
