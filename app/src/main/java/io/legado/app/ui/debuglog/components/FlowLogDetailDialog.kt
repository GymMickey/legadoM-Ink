package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.FlowLogItem
import io.legado.app.model.debug.FlowStage
import io.legado.app.model.debug.RuleExecutionTree
import io.legado.app.model.debug.RuleExecutionNode
import io.legado.app.model.debug.JsExecutionRecord
import io.legado.app.model.debug.RuleType
import io.legado.app.model.debug.VariableOperation
import io.legado.app.model.debug.VariableOperationType
import io.legado.app.model.debug.BookDataFlow
import io.legado.app.model.debug.DataFlowStage
import io.legado.app.model.debug.FieldFillRecord
import io.legado.app.model.debug.highlightText
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.utils.toastOnUi

@Composable
fun FlowLogDetailDialog(
    log: FlowLogItem,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = getStageIcon(log.stage),
                                tint = getStageColor(log.stage),
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "流程日志详情",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = log.stage.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = getStageColor(log.stage)
                                )
                            }
                        }

                        Row {
                            IconButton(onClick = { showSearch = !showSearch }) {
                                Icon(Icons.Default.Search, contentDescription = "查找")
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                    }
                }

                if (showSearch) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            placeholder = { Text("在日志中查找...") },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {})
                        )
                    }
                }

                // 内容区域：可滚动 Column + 可拖拽滚动条，支持查看请求头、规则执行树等长内容
                Box(modifier = Modifier.weight(1f)) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                    DetailSection(title = "基本信息", searchQuery = searchQuery) {
                        DetailRow("时间", DebugLogUtils.formatFullTime(log.startTime), searchQuery)
                        DetailRow("阶段", log.stage.displayName, searchQuery)
                        log.operation?.let { DetailRow("操作", it, searchQuery) }
                        log.sourceName?.let { DetailRow("书源", it, searchQuery) }
                        log.sourceUrl?.let { DetailRow("书源URL", it, searchQuery) }
                        if (log.duration != null && log.duration > 0) {
                            DetailRow("耗时", "${log.duration}ms", searchQuery)
                        }
                        log.ruleType?.let { 
                            DetailRow("规则类型", "${it.icon}${it.displayName}", searchQuery) 
                        }
                        log.matchCount?.let { 
                            DetailRow("匹配数量", "$it 个", searchQuery) 
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    DetailSection(title = "消息", searchQuery = searchQuery) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = highlightText(log.message, searchQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (log.url != null || log.method != null || log.statusCode != null) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "请求信息", searchQuery = searchQuery) {
                            log.url?.let { DetailRow("URL", it, searchQuery) }
                            log.method?.let { DetailRow("方法", it, searchQuery) }
                            log.statusCode?.let { DetailRow("状态码", it.toString(), searchQuery) }
                        }
                    }

                    // 请求头区域：Cookie 单独置顶，其余请求头过滤掉 Cookie 和 User-Agent 避免重复
                    if (!log.requestHeaders.isNullOrEmpty() || log.cookies != null) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "请求头", searchQuery = searchQuery) {
                            log.cookies?.let { DetailRow("Cookie", it, searchQuery) }
                            log.requestHeaders?.forEach { (key, value) ->
                                if (key !in listOf("Cookie", "User-Agent")) {
                                    DetailRow(key, value, searchQuery)
                                }
                            }
                        }
                    }

                    val hasRuleInfo = listOfNotNull(
                        log.rule, log.inputPreview, log.originalValue, log.outputPreview, log.result
                    ).any { it.isNotBlank() }
                    
                    if (hasRuleInfo) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "规则信息", searchQuery = searchQuery) {
                            log.rule?.takeIf { it.isNotBlank() }?.let { rule ->
                                DetailRow("规则", rule, searchQuery)
                            }
                            log.inputPreview?.takeIf { it.isNotBlank() }?.let { input ->
                                DetailRow("输入", input, searchQuery)
                            }
                            log.originalValue?.takeIf { it.isNotBlank() }?.let { originalValue ->
                                DetailRow("原始数据", originalValue, searchQuery)
                            }
                            log.outputPreview?.takeIf { it.isNotBlank() }?.let { output ->
                                DetailRow("输出", output, searchQuery)
                            }
                            log.result?.takeIf { it.isNotBlank() }?.let { result ->
                                DetailRow("结果", result, searchQuery)
                            }
                        }
                    }

                    log.executionTree?.takeIf { it.root.children.isNotEmpty() }?.let { tree ->
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "规则执行路径", searchQuery = searchQuery) {
                            RuleExecutionTreeView(tree, searchQuery)
                        }
                    }

                    log.jsExecution?.let { jsExec ->
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "JS执行环境", searchQuery = searchQuery) {
                            JsExecutionView(jsExec, searchQuery)
                        }
                    }

                    if (log.variableOperations.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "变量操作", searchQuery = searchQuery) {
                            VariableOperationsView(log.variableOperations, searchQuery)
                        }
                    }

                    log.dataFlow?.let { dataFlow ->
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "数据流转", searchQuery = searchQuery) {
                            DataFlowView(dataFlow, searchQuery)
                        }
                    }

                    if (log.book != null || log.bookChapter != null || log.bookSource != null) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "实体显示", searchQuery = searchQuery) {
                            log.bookSource?.let { source ->
                                Text(
                                    text = "BookSource（书源）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                BookSourceEntityView(source, searchQuery)
                                if (log.book != null || log.bookChapter != null) {
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            log.book?.let { book ->
                                Text(
                                    text = "Book（书籍）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                BookEntityView(book, searchQuery)
                                if (log.bookChapter != null) {
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                            log.bookChapter?.let { chapter ->
                                Text(
                                    text = "BookChapter（章节）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                                BookChapterEntityView(chapter, searchQuery)
                            }
                        }
                    }

                    if (!log.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "详细内容", searchQuery = searchQuery) {
                            Text(
                                text = highlightText(log.detail ?: "", searchQuery),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (log.error != null) {
                        Spacer(Modifier.height(12.dp))
                        DetailSection(title = "异常信息", searchQuery = searchQuery) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            ) {
                                Text(
                                    text = highlightText(
                                        log.error?.stackTraceToString() ?: "",
                                        searchQuery
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    }
                    io.legado.app.ui.widget.components.VerticalScrollbar(
                        state = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(onClick = onDismiss) {
                            Text("关闭")
                        }

                        Spacer(Modifier.width(8.dp))

                        Button(onClick = onCopy) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("复制全部")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleExecutionTreeView(
    tree: RuleExecutionTree,
    searchQuery: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetailRow("完整规则", tree.fullRule, searchQuery)
        DetailRow("总耗时", tree.formatTotalDuration(), searchQuery)
        DetailRow("执行状态", if (tree.isSuccess()) "✅ 成功" else "❌ 失败", searchQuery)
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = "执行步骤:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        
        tree.root.children.forEach { node ->
            RuleExecutionNodeView(node, searchQuery, indent = 0)
        }
    }
}

@Composable
private fun RuleExecutionNodeView(
    node: RuleExecutionNode,
    searchQuery: String,
    indent: Int
) {
    val indentStr = "  ".repeat(indent)
    val prefix = if (indent == 0) "├──" else "│  ├──"
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 16).dp),
        shape = MaterialTheme.shapes.small,
        color = if (node.error != null) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${node.ruleType.icon} ${node.ruleType.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (node.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                node.duration?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (node.ruleContent.isNotBlank()) {
                var ruleExpanded by remember { mutableStateOf(false) }
                val ruleNeedsExpand = remember(node.ruleContent) { node.ruleContent.length > 60 }
                val ruleScrollState = rememberScrollState()
                Text(
                    text = "规则: ${if (!ruleExpanded && ruleNeedsExpand) node.ruleContent.take(60) + "..." else node.ruleContent}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .then(
                            if (ruleNeedsExpand) Modifier.clickable { ruleExpanded = !ruleExpanded } else Modifier
                        )
                        .then(
                            if (ruleExpanded) Modifier.horizontalScroll(ruleScrollState) else Modifier
                        ),
                    maxLines = if (ruleExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            node.input?.let { input ->
                var inputExpanded by remember { mutableStateOf(false) }
                val inputNeedsExpand = remember(input) { input.length > 50 }
                val inputScrollState = rememberScrollState()
                Text(
                    text = "输入: ${if (!inputExpanded && inputNeedsExpand) input.take(50) + "..." else input}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (inputNeedsExpand) Modifier.clickable { inputExpanded = !inputExpanded } else Modifier
                        )
                        .then(
                            if (inputExpanded) Modifier.horizontalScroll(inputScrollState) else Modifier
                        ),
                    maxLines = if (inputExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            node.output?.let { output ->
                var outputExpanded by remember { mutableStateOf(false) }
                val outputNeedsExpand = remember(output) { output.length > 50 }
                val outputScrollState = rememberScrollState()
                Text(
                    text = "输出: ${if (!outputExpanded && outputNeedsExpand) output.take(50) + "..." else output}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (outputNeedsExpand) Modifier.clickable { outputExpanded = !outputExpanded } else Modifier
                        )
                        .then(
                            if (outputExpanded) Modifier.horizontalScroll(outputScrollState) else Modifier
                        ),
                    maxLines = if (outputExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            node.matchCount?.let { count ->
                Text(
                    text = "匹配: $count 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 正则捕获组：由 AnalyzeRule.getElement/getElements 在 endStep 时传入
            if (!node.regexGroups.isNullOrEmpty()) {
                Text(
                    text = "捕获组: [${node.regexGroups.joinToString(", ") { "\"$it\"" }}]",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            node.error?.let { error ->
                Text(
                    text = "错误: ${error.localizedMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            node.children.forEach { child ->
                Spacer(Modifier.height(4.dp))
                RuleExecutionNodeView(child, searchQuery, indent + 1)
            }
        }
    }
}

@Composable
private fun JsExecutionView(
    jsExec: JsExecutionRecord,
    searchQuery: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DetailRow("执行状态", if (jsExec.isSuccess()) "✅ 成功" else "❌ 失败", searchQuery)
        DebugLogUtils.formatDuration(jsExec.duration)?.let {
            DetailRow("执行耗时", it, searchQuery)
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = "JS代码:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = jsExec.jsCode,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(8.dp)
            )
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = "执行环境:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        
        val context = jsExec.context
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                context.result?.let {
                    EnvVarRow("result", it)
                }
                context.src?.let {
                    EnvVarRow("src", it)
                }
                context.baseUrl?.let {
                    EnvVarRow("baseUrl", it)
                }
                context.book?.let { book ->
                    EnvVarRow("book", "{name=\"${book.name}\", author=\"${book.author}\"}")
                }
                context.chapter?.let { chapter ->
                    EnvVarRow("chapter", "{title=\"${chapter.title}\", index=${chapter.index}}")
                }
                context.source?.let { source ->
                    EnvVarRow("source", "{name=\"${source.name}\"}")
                }
                context.nextChapterUrl?.let {
                    EnvVarRow("nextChapterUrl", it)
                }
                if (context.fromBookInfo) {
                    EnvVarRow("fromBookInfo", "true")
                }
                context.variables.forEach { (key, value) ->
                    EnvVarRow(key, value)
                }
            }
        }
        
        jsExec.result?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "执行结果:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ) {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        
        jsExec.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "执行错误:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ) {
                Text(
                    text = error.localizedMessage ?: error.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun EnvVarRow(name: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$name:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(100.dp)
        )
        var envExpanded by remember { mutableStateOf(false) }
        val envNeedsExpand = remember(value) { value.length > 60 }
        val envScrollState = rememberScrollState()
        Text(
            text = if (!envExpanded && envNeedsExpand) value.take(60) + "..." else value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .then(
                    if (envNeedsExpand) Modifier.clickable { envExpanded = !envExpanded } else Modifier
                )
                .then(
                    if (envExpanded) Modifier.horizontalScroll(envScrollState) else Modifier
                ),
            maxLines = if (envExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailSection(
    title: String,
    searchQuery: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        text = highlightText(title, searchQuery),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(
    label: String,
    value: String,
    searchQuery: String
) {
    var expanded by remember { mutableStateOf(false) }
    val needsExpand = remember(value) { value.length > 80 || value.count { it == '\n' } > 2 }
    val scrollState = rememberScrollState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (needsExpand) Modifier.clickable { expanded = !expanded }
                else Modifier
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = highlightText(label, searchQuery),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )

        Text(
            text = highlightText(value, searchQuery),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (expanded) Modifier.horizontalScroll(scrollState)
                    else Modifier
                )
                .combinedClickable(
                    onClick = { if (needsExpand) expanded = !expanded },
                    onLongClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(value))
                        context.toastOnUi("已复制: $label")
                    }
                ),
            overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
            maxLines = if (expanded) Int.MAX_VALUE else 5
        )
    }
}

@Composable
private fun getStageColor(stage: FlowStage) = when (stage) {
    FlowStage.NETWORK -> MaterialTheme.colorScheme.primary
    FlowStage.PARSE -> MaterialTheme.colorScheme.tertiary
    FlowStage.EXTRACT -> MaterialTheme.colorScheme.secondary
    FlowStage.REPLACE -> MaterialTheme.colorScheme.error
    FlowStage.VARIABLE -> MaterialTheme.colorScheme.tertiary
    FlowStage.DATA_FLOW -> MaterialTheme.colorScheme.primary
}

@Composable
private fun getStageIcon(stage: FlowStage) = when (stage) {
    FlowStage.NETWORK -> Icons.Default.Language
    FlowStage.PARSE -> Icons.Default.Code
    FlowStage.EXTRACT -> Icons.Default.DataObject
    FlowStage.REPLACE -> Icons.Default.SwapHoriz
    FlowStage.VARIABLE -> Icons.Default.Inventory
    FlowStage.DATA_FLOW -> Icons.Default.DataObject
}

@Composable
private fun VariableOperationsView(
    operations: List<VariableOperation>,
    searchQuery: String
) {
    val readOps = operations.filter { it.operationType == VariableOperationType.READ }
    val writeOps = operations.filter { it.operationType == VariableOperationType.WRITE }
    val deleteOps = operations.filter { it.operationType == VariableOperationType.DELETE }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "统计: 读${readOps.size}次, 写${writeOps.size}次, 删${deleteOps.size}次",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "涉及${operations.map { it.key }.toSet().size}个变量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(Modifier.height(12.dp))
        
        if (writeOps.isNotEmpty()) {
            Text(
                text = "📤 写入操作:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            
            writeOps.forEach { op ->
                VariableOperationItem(op, searchQuery)
                Spacer(Modifier.height(4.dp))
            }
            
            Spacer(Modifier.height(8.dp))
        }
        
        if (readOps.isNotEmpty()) {
            Text(
                text = "📥 读取操作:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            
            readOps.forEach { op ->
                VariableOperationItem(op, searchQuery)
                Spacer(Modifier.height(4.dp))
            }
        }
        
        if (deleteOps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "🗑️ 删除操作:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            
            deleteOps.forEach { op ->
                VariableOperationItem(op, searchQuery)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun VariableOperationItem(
    operation: VariableOperation,
    searchQuery: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = when (operation.operationType) {
            VariableOperationType.READ -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            VariableOperationType.WRITE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            VariableOperationType.DELETE -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = operation.key,
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = when (operation.operationType) {
                        VariableOperationType.READ -> MaterialTheme.colorScheme.secondary
                        VariableOperationType.WRITE -> MaterialTheme.colorScheme.primary
                        VariableOperationType.DELETE -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = operation.storage.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            operation.value?.let { value ->
                var valExpanded by remember { mutableStateOf(false) }
                val valNeedsExpand = remember(value) { value.length > 60 }
                val valScrollState = rememberScrollState()
                Text(
                    text = "值: ${if (!valExpanded && valNeedsExpand) value.take(60) + "..." else value}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .then(
                            if (valNeedsExpand) Modifier.clickable { valExpanded = !valExpanded } else Modifier
                        )
                        .then(
                            if (valExpanded) Modifier.horizontalScroll(valScrollState) else Modifier
                        ),
                    maxLines = if (valExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            operation.oldValue?.let { oldValue ->
                var oldExpanded by remember { mutableStateOf(false) }
                val oldNeedsExpand = remember(oldValue) { oldValue.length > 40 }
                val oldScrollState = rememberScrollState()
                Text(
                    text = "原值: ${if (!oldExpanded && oldNeedsExpand) oldValue.take(40) + "..." else oldValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (oldNeedsExpand) Modifier.clickable { oldExpanded = !oldExpanded } else Modifier
                        )
                        .then(
                            if (oldExpanded) Modifier.horizontalScroll(oldScrollState) else Modifier
                        ),
                    maxLines = if (oldExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DataFlowView(
    dataFlow: BookDataFlow,
    searchQuery: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        dataFlow.bookName?.let {
            DetailRow("书名", it, searchQuery)
        }
        dataFlow.author?.let {
            DetailRow("作者", it, searchQuery)
        }
        dataFlow.bookUrl?.let {
            DetailRow("书籍URL", it, searchQuery)
        }
        dataFlow.sourceName?.let {
            DetailRow("书源名称", it, searchQuery)
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = "字段填充过程:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        
        if (dataFlow.stages.isEmpty()) {
            Text(
                text = "暂无数据流转记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            dataFlow.stages.sortedBy { it.stage.order }.forEach { stageFlow ->
                StageDataFlowView(stageFlow, searchQuery)
                Spacer(Modifier.height(8.dp))
            }
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            text = dataFlow.getSummary(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StageDataFlowView(
    stageFlow: io.legado.app.model.debug.StageDataFlow,
    searchQuery: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stageFlow.stage.icon} ${stageFlow.stage.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                stageFlow.duration?.let {
                    Text(
                        text = "${it}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (stageFlow.fields.isEmpty()) {
                Text(
                    text = "无字段填充",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                stageFlow.fields.forEach { field ->
                    FieldFillRecordView(field, searchQuery)
                }
            }
            
            if (stageFlow.hasAnyError()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "⚠️ 有 ${stageFlow.getErrorFields().size} 个错误",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun FieldFillRecordView(
    field: FieldFillRecord,
    searchQuery: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        val changeIndicator = if (field.hasChange()) "←" else "="
        val errorIndicator = if (field.isError) "❌" else ""
        
        Text(
            text = "${field.fieldName}:",
            style = MaterialTheme.typography.labelSmall,
            color = if (field.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(80.dp)
        )
        
        Column(modifier = Modifier.weight(1f)) {
            if (field.isError) {
                Text(
                    text = "错误: ${field.errorMessage ?: "未知错误"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    text = "\"${field.getResultPreview(50) ?: "(空)"}\" $changeIndicator $errorIndicator",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
            
            field.rule?.let { rule ->
                if (rule.isNotBlank()) {
                    Text(
                        text = "rule: ${field.getRulePreview(30) ?: ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BookEntityView(book: Book, searchQuery: String) {
    DetailRow("书名", book.name, searchQuery)
    DetailRow("作者", book.author, searchQuery)
    DetailRow("bookUrl", book.bookUrl, searchQuery)
    DetailRow("tocUrl", book.tocUrl, searchQuery)
    DetailRow("origin", book.origin, searchQuery)
    DetailRow("originName", book.originName, searchQuery)
    book.coverUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("coverUrl", it, searchQuery)
    }
    book.customCoverUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("customCoverUrl", it, searchQuery)
    }
    book.customTag.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("customTag", it, searchQuery)
    }
    book.kind.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("kind", it, searchQuery)
    }
    book.intro.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("intro", it, searchQuery)
    }
    book.customIntro.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("customIntro", it, searchQuery)
    }
    book.charset.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("charset", it, searchQuery)
    }
    DetailRow("type", book.type.toString(), searchQuery)
    DetailRow("group", book.group.toString(), searchQuery)
    book.durChapterTitle.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("durChapterTitle", it, searchQuery)
    }
    DetailRow("durChapterIndex", book.durChapterIndex.toString(), searchQuery)
    DetailRow("durChapterPos", book.durChapterPos.toString(), searchQuery)
    DetailRow("durChapterTime", book.durChapterTime.toString(), searchQuery)
    DetailRow("durVolumeIndex", book.durVolumeIndex.toString(), searchQuery)
    DetailRow("chapterInVolumeIndex", book.chapterInVolumeIndex.toString(), searchQuery)
    DetailRow("totalChapterNum", book.totalChapterNum.toString(), searchQuery)
    book.latestChapterTitle.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("latestChapterTitle", it, searchQuery)
    }
    DetailRow("latestChapterTime", book.latestChapterTime.toString(), searchQuery)
    DetailRow("lastCheckTime", book.lastCheckTime.toString(), searchQuery)
    DetailRow("lastCheckCount", book.lastCheckCount.toString(), searchQuery)
    book.wordCount.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("wordCount", it, searchQuery)
    }
    DetailRow("canUpdate", book.canUpdate.toString(), searchQuery)
    DetailRow("order", book.order.toString(), searchQuery)
    DetailRow("originOrder", book.originOrder.toString(), searchQuery)
    book.variable.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("variable", it, searchQuery)
    }
    book.readConfig?.let { rc ->
        DetailRow("readConfig.reverseToc", rc.reverseToc.toString(), searchQuery)
        DetailRow("readConfig.reSegment", rc.reSegment.toString(), searchQuery)
        DetailRow("readConfig.useReplaceRule", rc.useReplaceRule.toString(), searchQuery)
    }
    DetailRow("syncTime", book.syncTime.toString(), searchQuery)
}

@Composable
private fun BookChapterEntityView(chapter: BookChapter, searchQuery: String) {
    DetailRow("title", chapter.title, searchQuery)
    DetailRow("url", chapter.url, searchQuery)
    DetailRow("baseUrl", chapter.baseUrl, searchQuery)
    DetailRow("index", chapter.index.toString(), searchQuery)
    DetailRow("bookUrl", chapter.bookUrl, searchQuery)
    DetailRow("isVolume", chapter.isVolume.toString(), searchQuery)
    DetailRow("isVip", chapter.isVip.toString(), searchQuery)
    DetailRow("isPay", chapter.isPay.toString(), searchQuery)
    chapter.tag.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("tag", it, searchQuery)
    }
    chapter.wordCount.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("wordCount", it, searchQuery)
    }
    chapter.resourceUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("resourceUrl", it, searchQuery)
    }
    chapter.imgUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("imgUrl", it, searchQuery)
    }
    chapter.start?.let {
        DetailRow("start", it.toString(), searchQuery)
    }
    chapter.end?.let {
        DetailRow("end", it.toString(), searchQuery)
    }
    chapter.startFragmentId.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("startFragmentId", it, searchQuery)
    }
    chapter.endFragmentId.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("endFragmentId", it, searchQuery)
    }
    chapter.variable.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("variable", it, searchQuery)
    }
}

@Composable
private fun BookSourceEntityView(source: BookSource, searchQuery: String) {
    DetailRow("bookSourceName", source.bookSourceName, searchQuery)
    DetailRow("bookSourceUrl", source.bookSourceUrl, searchQuery)
    source.bookSourceGroup.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("bookSourceGroup", it, searchQuery)
    }
    DetailRow("bookSourceType", source.bookSourceType.toString(), searchQuery)
    DetailRow("enabled", source.enabled.toString(), searchQuery)
    DetailRow("enabledExplore", source.enabledExplore.toString(), searchQuery)
    DetailRow("customOrder", source.customOrder.toString(), searchQuery)
    DetailRow("weight", source.weight.toString(), searchQuery)
    source.searchUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("searchUrl", it, searchQuery)
    }
    source.exploreUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("exploreUrl", it, searchQuery)
    }
    source.header.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("header", it, searchQuery)
    }
    source.loginUrl.takeIf { !it.isNullOrBlank() }?.let {
        DetailRow("loginUrl", it, searchQuery)
    }
}
