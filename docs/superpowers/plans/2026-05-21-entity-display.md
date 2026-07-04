# 实体显示功能实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在调试日志界面的「书源」分类下新增「实体」子分类 tab，用户选择书源后查看其所有规则实体字段。

**架构：** 新增 SourceSubCategory.ENTITY 枚举值，在 DebugLogViewModel 中加载书源列表，在 DebugLogScreen 中根据选中子分类渲染 EntityDisplay 组件。EntityDisplay 包含书源选择器 + 可折叠实体卡片列表，每张卡片展示一个实体类型的所有非空字段。

**技术栈：** Jetpack Compose (Material3)、Room DAO、StateFlow、ExposedDropdownMenuBox

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/io/legado/app/model/debug/SourceSubCategory.kt` | 修改 | 新增 `ENTITY("实体")` |
| `app/src/main/java/io/legado/app/ui/debuglog/viewmodel/DebugLogViewModel.kt` | 修改 | 新增书源列表状态、选中书源状态、加载逻辑 |
| `app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt` | 修改 | ENTITY 子分类分支渲染 EntityDisplay |
| `app/src/main/java/io/legado/app/ui/debuglog/components/EntityDisplay.kt` | 新建 | 实体展示主组件 |

---

### 任务 1：新增 ENTITY 枚举值

**文件：**
- 修改：`app/src/main/java/io/legado/app/model/debug/SourceSubCategory.kt`

- [ ] **步骤 1：添加 ENTITY 枚举值**

在 `SourceSubCategory.kt` 中 `FLOW("流程")` 之后添加：

```kotlin
/** 实体显示：查看书源的规则实体配置 */
ENTITY("实体")
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/model/debug/SourceSubCategory.kt
git commit -m "feat: 新增实体显示子分类枚举值"
```

---

### 任务 2：ViewModel 新增书源状态管理

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/debuglog/viewmodel/DebugLogViewModel.kt`

- [ ] **步骤 1：添加 import**

在文件头部 import 区域添加：

```kotlin
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
```

- [ ] **步骤 2：添加书源列表和选中书源状态**

在 `searchQuery` 声明之后（约第 99 行后）添加：

```kotlin
/** 可用书源列表（用于实体显示的书源选择器） */
private val _bookSources = MutableStateFlow<List<BookSource>>(emptyList())
val bookSources: StateFlow<List<BookSource>> = _bookSources.asStateFlow()

/** 当前选中查看的书源 URL */
private val _selectedBookSourceUrl = MutableStateFlow<String?>(null)
val selectedBookSourceUrl: StateFlow<String?> = _selectedBookSourceUrl.asStateFlow()

/** 当前选中的完整书源对象（含嵌套规则实体） */
private val _selectedBookSource = MutableStateFlow<BookSource?>(null)
val selectedBookSource: StateFlow<BookSource?> = _selectedBookSource.asStateFlow()
```

- [ ] **步骤 3：添加书源操作方法**

在 `selectSubCategory` 方法之后添加：

```kotlin
/**
 * 加载书源列表
 *
 * 从数据库获取所有已启用的书源，按自定义排序排列。
 * 仅在首次切换到 ENTITY 子分类时加载。
 */
fun loadBookSources() {
    execute {
        appDb.bookSourceDao.all
            .filter { it.enabled }
            .sortedBy { it.customOrder }
    }.onSuccess { sources ->
        _bookSources.value = sources
    }.onError { e ->
        e.printStackTrace()
        showToast("加载书源列表失败：${e.message}")
    }
}

/**
 * 选择要查看的书源
 *
 * @param bookSourceUrl 书源 URL
 */
fun selectBookSource(bookSourceUrl: String) {
    _selectedBookSourceUrl.value = bookSourceUrl
    _selectedBookSource.value = _bookSources.value.firstOrNull { it.bookSourceUrl == bookSourceUrl }
}
```

- [ ] **步骤 4：修改 selectSubCategory 添加 ENTITY 触发逻辑**

将现有 `selectSubCategory` 方法修改为：

```kotlin
fun selectSubCategory(subCategory: SourceSubCategory?) {
    _selectedSubCategory.value = subCategory
    if (subCategory == SourceSubCategory.ENTITY) {
        if (_bookSources.value.isEmpty()) {
            loadBookSources()
        }
    }
}
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/viewmodel/DebugLogViewModel.kt
git commit -m "feat: ViewModel 新增书源列表和选中书源状态管理"
```

---

### 任务 3：创建 EntityDisplay 组件

**文件：**
- 新建：`app/src/main/java/io/legado/app/ui/debuglog/components/EntityDisplay.kt`

- [ ] **步骤 1：创建 EntityDisplay.kt**

完整文件内容：

```kotlin
package io.legado.app.ui.debuglog.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import io.legado.app.data.entities.rule.BookInfoRule
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.ExploreRule
import io.legado.app.data.entities.rule.SearchRule
import io.legado.app.data.entities.rule.TocRule

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
        val currentSource = bookSources.firstOrNull { it.bookSourceUrl == selectedBookSourceUrl }

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
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
                            rule = selectedBookSource.ruleSearch,
                            fields = selectedBookSource.ruleSearch?.toFieldList() ?: emptyList()
                        )
                    }
                    // ExploreRule
                    item {
                        RuleEntityCard(
                            title = "ExploreRule（发现规则）",
                            rule = selectedBookSource.ruleExplore,
                            fields = selectedBookSource.ruleExplore?.toFieldList() ?: emptyList()
                        )
                    }
                    // BookInfoRule
                    item {
                        RuleEntityCard(
                            title = "BookInfoRule（书籍信息规则）",
                            rule = selectedBookSource.ruleBookInfo,
                            fields = selectedBookSource.ruleBookInfo?.toFieldList() ?: emptyList()
                        )
                    }
                    // TocRule
                    item {
                        RuleEntityCard(
                            title = "TocRule（目录规则）",
                            rule = selectedBookSource.ruleToc,
                            fields = selectedBookSource.ruleToc?.toFieldList() ?: emptyList()
                        )
                    }
                    // ContentRule
                    item {
                        RuleEntityCard(
                            title = "ContentRule（正文规则）",
                            rule = selectedBookSource.ruleContent,
                            fields = selectedBookSource.ruleContent?.toFieldList() ?: emptyList()
                        )
                    }
                }
                io.legado.app.ui.widget.components.VerticalScrollbar(
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
private fun <T> RuleEntityCard(
    title: String,
    rule: T?,
    fields: List<Pair<String, String>>
) {
    var expanded by remember { mutableStateOf(true) }

    if (rule == null) {
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
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/components/EntityDisplay.kt
git commit -m "feat: 新增 EntityDisplay 实体展示组件"
```

---

### 任务 4：DebugLogScreen 集成 EntityDisplay

**文件：**
- 修改：`app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt`

- [ ] **步骤 1：添加 import**

在文件头部 import 区域添加：

```kotlin
import io.legado.app.ui.debuglog.components.EntityDisplay
```

- [ ] **步骤 2：添加状态收集**

在现有状态收集区域（约第 107 行附近）添加：

```kotlin
val bookSources by viewModel.bookSources.collectAsState()
val selectedBookSource by viewModel.selectedBookSource.collectAsState()
val selectedBookSourceUrl by viewModel.selectedBookSourceUrl.collectAsState()
```

- [ ] **步骤 3：在 Content area 的 when 分支中添加 ENTITY 处理**

在 `Box(modifier = Modifier.fillMaxSize())` 的 `when` 块中，在「书源流程日志视图」分支之前（约第 321 行前）插入：

```kotlin
// 书源实体显示视图
selectedCategory == DebugCategory.SOURCE && selectedSubCategory == SourceSubCategory.ENTITY -> {
    EntityDisplay(
        bookSources = bookSources,
        selectedBookSource = selectedBookSource,
        selectedBookSourceUrl = selectedBookSourceUrl,
        onBookSourceSelected = viewModel::selectBookSource
    )
}
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/debuglog/DebugLogScreen.kt
git commit -m "feat: DebugLogScreen 集成实体显示组件"
```

---

### 任务 5：验证与测试

- [ ] **步骤 1：编译验证**

```bash
./gradlew assembleAppMaxDebug
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 2：手动测试流程**

1. 安装到设备：`./gradlew installAppMaxDebug`
2. 打开调试日志面板
3. 点击「书源」分类 tab
4. 点击「实体」子分类
5. 验证书源下拉选择器显示所有已启用书源
6. 选择一个书源，验证显示 BookSource 基础字段
7. 验证 SearchRule / ExploreRule / BookInfoRule / TocRule / ContentRule 卡片正确显示
8. 验证未配置的规则显示"未配置"
9. 点击卡片标题栏，验证展开/收起动画
10. 验证长值可点击展开、水平滚动

- [ ] **步骤 3：最终 Commit（如果有修复）**

```bash
git add -A
git commit -m "fix: 实体显示功能验证修复"
```
