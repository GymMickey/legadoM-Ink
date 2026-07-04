package io.legado.app.ui.debuglog.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.ui.widget.components.VerticalScrollbar

/**
 * 实体显示主组件
 *
 * 包含书源选择器和实体卡片列表。
 *
 * @param bookSources 可用书源列表
 * @param selectedBookSource 当前选中的书源对象
 * @param selectedBookSourceUrl 当前选中的书源 URL
 * @param onBookSourceSelected 书源选择回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityDisplay(
    bookSources: List<BookSource>,
    selectedBookSource: BookSource?,
    selectedBookSourceUrl: String?,
    onBookSourceSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 书源选择器
        var expanded by remember { mutableStateOf(false) }
        val currentSource = remember(bookSources, selectedBookSourceUrl) {
            bookSources.firstOrNull { it.bookSourceUrl == selectedBookSourceUrl }
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = currentSource?.getDisPlayNameGroup() ?: "请选择书源",
                onValueChange = {},
                readOnly = true,
                label = { Text("书源") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                bookSources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = source.getDisPlayNameGroup(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onBookSourceSelected(source.bookSourceUrl)
                            expanded = false
                        }
                    )
                }
            }
        }

        // 实体卡片列表
        if (selectedBookSource == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请选择一个书源查看实体",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()

            val searchFields = remember(selectedBookSource) {
                selectedBookSource.ruleSearch?.toFieldList() ?: emptyList()
            }
            val exploreFields = remember(selectedBookSource) {
                selectedBookSource.ruleExplore?.toFieldList() ?: emptyList()
            }
            val bookInfoFields = remember(selectedBookSource) {
                selectedBookSource.ruleBookInfo?.toFieldList() ?: emptyList()
            }
            val tocFields = remember(selectedBookSource) {
                selectedBookSource.ruleToc?.toFieldList() ?: emptyList()
            }
            val contentFields = remember(selectedBookSource) {
                selectedBookSource.ruleContent?.toFieldList() ?: emptyList()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // BookSource 基础字段
                    item {
                        BookSourceEntityCard(selectedBookSource)
                    }
                    // SearchRule
                    item {
                        RuleEntityCard(
                            title = "SearchRule（搜索规则）",
                            isNull = selectedBookSource.ruleSearch == null,
                            fields = searchFields
                        )
                    }
                    // ExploreRule
                    item {
                        RuleEntityCard(
                            title = "ExploreRule（发现规则）",
                            isNull = selectedBookSource.ruleExplore == null,
                            fields = exploreFields
                        )
                    }
                    // BookInfoRule
                    item {
                        RuleEntityCard(
                            title = "BookInfoRule（书籍信息规则）",
                            isNull = selectedBookSource.ruleBookInfo == null,
                            fields = bookInfoFields
                        )
                    }
                    // TocRule
                    item {
                        RuleEntityCard(
                            title = "TocRule（目录规则）",
                            isNull = selectedBookSource.ruleToc == null,
                            fields = tocFields
                        )
                    }
                    // ContentRule
                    item {
                        RuleEntityCard(
                            title = "ContentRule（正文规则）",
                            isNull = selectedBookSource.ruleContent == null,
                            fields = contentFields
                        )
                    }
                }
                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * BookSource 实体卡片
 */
@Composable
private fun BookSourceEntityCard(bookSource: BookSource) {
    var expanded by remember { mutableStateOf(true) }

    val fields = remember(bookSource) {
        buildList {
            add("bookSourceName" to bookSource.bookSourceName)
            add("bookSourceUrl" to bookSource.bookSourceUrl)
            bookSource.bookSourceGroup.takeIf { !it.isNullOrBlank() }?.let {
                add("bookSourceGroup" to it)
            }
            add("bookSourceType" to bookSource.bookSourceType.toString())
            add("enabled" to bookSource.enabled.toString())
            add("enabledExplore" to bookSource.enabledExplore.toString())
            add("customOrder" to bookSource.customOrder.toString())
            add("weight" to bookSource.weight.toString())
            bookSource.bookUrlPattern.takeIf { !it.isNullOrBlank() }?.let {
                add("bookUrlPattern" to it)
            }
            bookSource.exploreUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("exploreUrl" to it)
            }
            bookSource.searchUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("searchUrl" to it)
            }
            bookSource.header.takeIf { !it.isNullOrBlank() }?.let {
                add("header" to it)
            }
            bookSource.loginUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("loginUrl" to it)
            }
            bookSource.loginUi.takeIf { !it.isNullOrBlank() }?.let {
                add("loginUi" to it)
            }
            bookSource.loginCheckJs.takeIf { !it.isNullOrBlank() }?.let {
                add("loginCheckJs" to it)
            }
            bookSource.jsLib.takeIf { !it.isNullOrBlank() }?.let {
                add("jsLib" to it)
            }
            bookSource.concurrentRate.takeIf { !it.isNullOrBlank() }?.let {
                add("concurrentRate" to it)
            }
            bookSource.enabledCookieJar?.let {
                add("enabledCookieJar" to it.toString())
            }
            bookSource.coverDecodeJs.takeIf { !it.isNullOrBlank() }?.let {
                add("coverDecodeJs" to it)
            }
            bookSource.bookSourceComment.takeIf { !it.isNullOrBlank() }?.let {
                add("bookSourceComment" to it)
            }
            bookSource.variableComment.takeIf { !it.isNullOrBlank() }?.let {
                add("variableComment" to it)
            }
            bookSource.exploreScreen.takeIf { !it.isNullOrBlank() }?.let {
                add("exploreScreen" to it)
            }
            add("lastUpdateTime" to bookSource.lastUpdateTime.toString())
            add("respondTime" to bookSource.respondTime.toString())
            add("eventListener" to bookSource.eventListener.toString())
            add("customButton" to bookSource.customButton.toString())
            add("nextPageLazyLoad" to bookSource.nextPageLazyLoad.toString())
        }
    }

    EntityCard(
        title = "BookSource（书源）",
        fieldCount = fields.size,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        fields.forEach { (label, value) ->
            EntityFieldRow(label, value)
        }
    }
}

/**
 * 规则实体卡片（SearchRule / ExploreRule / BookInfoRule / TocRule / ContentRule）
 */
@Composable
private fun RuleEntityCard(
    title: String,
    isNull: Boolean,
    fields: List<Pair<String, String>>
) {
    var expanded by remember { mutableStateOf(true) }

    if (isNull) {
        EntityCard(
            title = title,
            fieldCount = 0,
            expanded = expanded,
            onToggle = { expanded = !expanded }
        ) {
            Text(
                text = "未配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        EntityCard(
            title = title,
            fieldCount = fields.size,
            expanded = expanded,
            onToggle = { expanded = !expanded }
        ) {
            fields.forEach { (label, value) ->
                EntityFieldRow(label, value)
            }
        }
    }
}

/**
 * 实体卡片通用外壳
 */
@Composable
private fun EntityCard(
    title: String,
    fieldCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 标题栏
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "$fieldCount 字段",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 内容区
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 实体字段行
 */
@Composable
private fun EntityFieldRow(label: String, value: String) {
    var expanded by remember { mutableStateOf(false) }
    val needsExpand = remember(value) { value.length > 60 || value.count { it == '\n' } > 1 }
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (needsExpand) Modifier.clickable { expanded = !expanded }
                else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(130.dp),
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = if (!expanded && needsExpand) value.take(60) + "..." else value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (expanded) Modifier.horizontalScroll(scrollState) else Modifier
                ),
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (expanded) Int.MAX_VALUE else 3
        )
    }
}

// ========== 订阅源实体显示 ==========

/**
 * 订阅源实体显示主组件
 *
 * 包含订阅源选择器和实体卡片列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RssSourceEntityDisplay(
    rssSources: List<RssSource>,
    selectedRssSource: RssSource?,
    selectedRssSourceUrl: String?,
    onRssSourceSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 订阅源选择器
        var expanded by remember { mutableStateOf(false) }
        val currentSource = remember(rssSources, selectedRssSourceUrl) {
            rssSources.firstOrNull { it.sourceUrl == selectedRssSourceUrl }
        }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = currentSource?.getDisplayNameGroup() ?: "请选择订阅源",
                onValueChange = {},
                readOnly = true,
                label = { Text("订阅源") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                rssSources.forEach { source ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = source.getDisplayNameGroup(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onRssSourceSelected(source.sourceUrl)
                            expanded = false
                        }
                    )
                }
            }
        }

        // 实体卡片列表
        if (selectedRssSource == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "请选择一个订阅源查看实体",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // RssSource 基础字段
                    item {
                        RssSourceEntityCard(selectedRssSource)
                    }
                    // 列表规则
                    item {
                        val fields = remember(selectedRssSource) {
                            buildList {
                                selectedRssSource.ruleArticles?.let { add("ruleArticles" to it) }
                                selectedRssSource.ruleNextPage?.let { add("ruleNextPage" to it) }
                                selectedRssSource.ruleTitle?.let { add("ruleTitle" to it) }
                                selectedRssSource.rulePubDate?.let { add("rulePubDate" to it) }
                                selectedRssSource.ruleDescription?.let { add("ruleDescription" to it) }
                                selectedRssSource.ruleImage?.let { add("ruleImage" to it) }
                                selectedRssSource.ruleLink?.let { add("ruleLink" to it) }
                            }
                        }
                        RuleEntityCard(
                            title = "列表规则",
                            isNull = fields.isEmpty(),
                            fields = fields
                        )
                    }
                    // 正文规则
                    item {
                        val fields = remember(selectedRssSource) {
                            buildList {
                                selectedRssSource.ruleContent?.let { add("ruleContent" to it) }
                                selectedRssSource.contentWhitelist?.let { add("contentWhitelist" to it) }
                                selectedRssSource.contentBlacklist?.let { add("contentBlacklist" to it) }
                            }
                        }
                        RuleEntityCard(
                            title = "正文规则",
                            isNull = fields.isEmpty(),
                            fields = fields
                        )
                    }
                    // WebView 规则
                    item {
                        val fields = remember(selectedRssSource) {
                            buildList {
                                selectedRssSource.shouldOverrideUrlLoading?.let { add("shouldOverrideUrlLoading" to it) }
                                selectedRssSource.style?.let { add("style" to it) }
                                add("enableJs" to selectedRssSource.enableJs.toString())
                                add("loadWithBaseUrl" to selectedRssSource.loadWithBaseUrl.toString())
                                selectedRssSource.injectJs?.let { add("injectJs" to it) }
                                selectedRssSource.preloadJs?.let { add("preloadJs" to it) }
                                selectedRssSource.startHtml?.let { add("startHtml" to it) }
                                selectedRssSource.startStyle?.let { add("startStyle" to it) }
                                selectedRssSource.startJs?.let { add("startJs" to it) }
                            }
                        }
                        RuleEntityCard(
                            title = "WebView 规则",
                            isNull = fields.isEmpty(),
                            fields = fields
                        )
                    }
                }
                VerticalScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/**
 * RssSource 实体卡片
 */
@Composable
private fun RssSourceEntityCard(rssSource: RssSource) {
    var expanded by remember { mutableStateOf(true) }

    val fields = remember(rssSource) {
        buildList {
            add("sourceName" to rssSource.sourceName)
            add("sourceUrl" to rssSource.sourceUrl)
            rssSource.sourceIcon.takeIf { it.isNotBlank() }?.let {
                add("sourceIcon" to it)
            }
            rssSource.sourceGroup.takeIf { !it.isNullOrBlank() }?.let {
                add("sourceGroup" to it)
            }
            rssSource.sourceComment.takeIf { !it.isNullOrBlank() }?.let {
                add("sourceComment" to it)
            }
            add("enabled" to rssSource.enabled.toString())
            rssSource.variableComment.takeIf { !it.isNullOrBlank() }?.let {
                add("variableComment" to it)
            }
            rssSource.jsLib.takeIf { !it.isNullOrBlank() }?.let {
                add("jsLib" to it)
            }
            rssSource.enabledCookieJar?.let {
                add("enabledCookieJar" to it.toString())
            }
            rssSource.concurrentRate.takeIf { !it.isNullOrBlank() }?.let {
                add("concurrentRate" to it)
            }
            rssSource.header.takeIf { !it.isNullOrBlank() }?.let {
                add("header" to it)
            }
            rssSource.loginUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("loginUrl" to it)
            }
            rssSource.loginUi.takeIf { !it.isNullOrBlank() }?.let {
                add("loginUi" to it)
            }
            rssSource.loginCheckJs.takeIf { !it.isNullOrBlank() }?.let {
                add("loginCheckJs" to it)
            }
            rssSource.coverDecodeJs.takeIf { !it.isNullOrBlank() }?.let {
                add("coverDecodeJs" to it)
            }
            rssSource.sortUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("sortUrl" to it)
            }
            add("singleUrl" to rssSource.singleUrl.toString())
            add("articleStyle" to rssSource.articleStyle.toString())
            add("lastUpdateTime" to rssSource.lastUpdateTime.toString())
            add("customOrder" to rssSource.customOrder.toString())
            add("type" to rssSource.type.toString())
            add("preload" to rssSource.preload.toString())
            add("cacheFirst" to rssSource.cacheFirst.toString())
            rssSource.searchUrl.takeIf { !it.isNullOrBlank() }?.let {
                add("searchUrl" to it)
            }
            add("showWebLog" to rssSource.showWebLog.toString())
        }
    }

    EntityCard(
        title = "RssSource（订阅源）",
        fieldCount = fields.size,
        expanded = expanded,
        onToggle = { expanded = !expanded }
    ) {
        fields.forEach { (label, value) ->
            EntityFieldRow(label, value)
        }
    }
}

// ========== 实体字段提取扩展 ==========

/**
 * SearchRule 转换为字段列表
 */
private fun SearchRule.toFieldList(): List<Pair<String, String>> = buildList {
    checkKeyWord?.let { add("checkKeyWord" to it) }
    bookList?.let { add("bookList" to it) }
    name?.let { add("name" to it) }
    author?.let { add("author" to it) }
    intro?.let { add("intro" to it) }
    kind?.let { add("kind" to it) }
    lastChapter?.let { add("lastChapter" to it) }
    updateTime?.let { add("updateTime" to it) }
    bookUrl?.let { add("bookUrl" to it) }
    coverUrl?.let { add("coverUrl" to it) }
    wordCount?.let { add("wordCount" to it) }
}

/**
 * ExploreRule 转换为字段列表
 */
private fun ExploreRule.toFieldList(): List<Pair<String, String>> = buildList {
    bookList?.let { add("bookList" to it) }
    name?.let { add("name" to it) }
    author?.let { add("author" to it) }
    intro?.let { add("intro" to it) }
    kind?.let { add("kind" to it) }
    lastChapter?.let { add("lastChapter" to it) }
    updateTime?.let { add("updateTime" to it) }
    bookUrl?.let { add("bookUrl" to it) }
    coverUrl?.let { add("coverUrl" to it) }
    wordCount?.let { add("wordCount" to it) }
}

/**
 * BookInfoRule 转换为字段列表
 */
private fun BookInfoRule.toFieldList(): List<Pair<String, String>> = buildList {
    init?.let { add("init" to it) }
    name?.let { add("name" to it) }
    author?.let { add("author" to it) }
    intro?.let { add("intro" to it) }
    kind?.let { add("kind" to it) }
    lastChapter?.let { add("lastChapter" to it) }
    updateTime?.let { add("updateTime" to it) }
    coverUrl?.let { add("coverUrl" to it) }
    tocUrl?.let { add("tocUrl" to it) }
    wordCount?.let { add("wordCount" to it) }
    canReName?.let { add("canReName" to it) }
    downloadUrls?.let { add("downloadUrls" to it) }
}

/**
 * TocRule 转换为字段列表
 */
private fun TocRule.toFieldList(): List<Pair<String, String>> = buildList {
    preUpdateJs?.let { add("preUpdateJs" to it) }
    chapterList?.let { add("chapterList" to it) }
    chapterName?.let { add("chapterName" to it) }
    chapterUrl?.let { add("chapterUrl" to it) }
    formatJs?.let { add("formatJs" to it) }
    isVolume?.let { add("isVolume" to it) }
    isVip?.let { add("isVip" to it) }
    isPay?.let { add("isPay" to it) }
    updateTime?.let { add("updateTime" to it) }
    nextTocUrl?.let { add("nextTocUrl" to it) }
}

/**
 * ContentRule 转换为字段列表
 */
private fun ContentRule.toFieldList(): List<Pair<String, String>> = buildList {
    content?.let { add("content" to it) }
    subContent?.let { add("subContent" to it) }
    title?.let { add("title" to it) }
    nextContentUrl?.let { add("nextContentUrl" to it) }
    webJs?.let { add("webJs" to it) }
    sourceRegex?.let { add("sourceRegex" to it) }
    replaceRegex?.let { add("replaceRegex" to it) }
    imageStyle?.let { add("imageStyle" to it) }
    imageDecode?.let { add("imageDecode" to it) }
    payAction?.let { add("payAction" to it) }
    callBackJs?.let { add("callBackJs" to it) }
}
