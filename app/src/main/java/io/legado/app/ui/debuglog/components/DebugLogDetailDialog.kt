package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.TextFieldDefaults
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
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.ToastContext
import io.legado.app.model.debug.ToastRuleType
import io.legado.app.model.debug.ToastSourceType
import io.legado.app.model.debug.highlightText
import io.legado.app.utils.toastOnUi

@Composable
fun DebugLogDetailDialog(
    log: DebugEvent,
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
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
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
                                imageVector = when (log.level) {
                                    DebugLevel.ERROR -> Icons.Default.Error
                                    DebugLevel.WARN -> Icons.Default.Warning
                                    else -> Icons.Default.Info
                                },
                                tint = when (log.level) {
                                    DebugLevel.ERROR -> MaterialTheme.colorScheme.error
                                    DebugLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "日志详情",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
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
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Search
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    // 搜索功能已通过 searchQuery 状态自动实现
                                }
                            )
                        )
                    }
                }

                // 内容区域：可滚动 Column + 可拖拽滚动条，替代原来的 verticalScroll 修饰符
                Box(modifier = Modifier.weight(1f)) {
                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    ) {
                    DetailSection(title = "基本信息", searchQuery = searchQuery) {
                        DetailRow("时间", DebugLogUtils.formatFullTime(log.time), searchQuery)
                        DetailRow("级别", log.level.displayName, searchQuery)
                        DetailRow("分类", log.category.displayName, searchQuery)

                        if (!log.dialogName.isNullOrBlank()) {
                            DetailRow("Dialog", log.dialogName, searchQuery)
                        }

                        if (!log.traceId.isNullOrBlank()) {
                            DetailRow("Trace ID", log.traceId, searchQuery)
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

                    Spacer(Modifier.height(12.dp))

                    when (log.category) {
                        DebugCategory.NETWORK -> {
                            if (log.url != null) {
                                DetailSection(title = "请求信息", searchQuery = searchQuery) {
                                    DetailRow("URL", log.url, searchQuery)
                                    DetailRow("方法", log.method ?: "-", searchQuery)
                                    DetailRow("状态码", log.statusCode?.toString() ?: "-", searchQuery)
                                    DetailRow("耗时", "${log.duration ?: 0}ms", searchQuery)
                                }

                                // 请求详情板块
                                if (log.userAgent != null || log.cookies != null || !log.requestHeaders.isNullOrEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    DetailSection(title = "请求详情", searchQuery = searchQuery) {
                                        log.userAgent?.let { 
                                            DetailRow("User-Agent", it, searchQuery) 
                                        }
                                        log.cookies?.let { 
                                            DetailRow("Cookie", it, searchQuery) 
                                        }
                                        
                                        // 显示其他请求头
                                        log.requestHeaders?.forEach { (key, value) ->
                                            if (key !in listOf("User-Agent", "Cookie", "X-Source-Name", "X-Source-Url")) {
                                                DetailRow(key, value, searchQuery)
                                            }
                                        }
                                    }
                                }

                                if (log.sourceName != null || log.sourceUrl != null) {
                                    Spacer(Modifier.height(12.dp))
                                    DetailSection(title = "来源信息", searchQuery = searchQuery) {
                                        log.sourceName?.let { DetailRow("书源名", it, searchQuery) }
                                        log.sourceUrl?.let { DetailRow("书源URL", it, searchQuery) }
                                    }
                                }
                            }
                        }
                        
                        DebugCategory.SOURCE, DebugCategory.RULE -> {
                            if (log.sourceName != null || log.sourceUrl != null || log.url != null) {
                                DetailSection(title = "书源信息", searchQuery = searchQuery) {
                                    log.sourceName?.let { DetailRow("书源名", it, searchQuery) }
                                    log.sourceUrl?.let { DetailRow("书源URL", it, searchQuery) }
                                    log.url?.let { DetailRow("请求URL", it, searchQuery) }
                                    log.method?.let { DetailRow("请求方法", it, searchQuery) }
                                    log.statusCode?.let { DetailRow("状态码", it.toString(), searchQuery) }
                                    log.duration?.let { DetailRow("耗时", "${it}ms", searchQuery) }
                                }
                            }
                        }
                        
                        DebugCategory.RSS -> {
                            if (log.sourceName != null || log.sourceUrl != null || log.url != null) {
                                DetailSection(title = "订阅源信息", searchQuery = searchQuery) {
                                    log.sourceName?.let { DetailRow("订阅源名", it, searchQuery) }
                                    log.sourceUrl?.let { DetailRow("订阅源URL", it, searchQuery) }
                                    log.url?.let { DetailRow("请求URL", it, searchQuery) }
                                    log.method?.let { DetailRow("请求方法", it, searchQuery) }
                                    log.statusCode?.let { DetailRow("状态码", it.toString(), searchQuery) }
                                    log.duration?.let { DetailRow("耗时", "${it}ms", searchQuery) }
                                }
                            }
                        }
                        
                        DebugCategory.TOAST -> {
                            val toastContext = ToastContext.fromTagsMap(log.tags)
                            
                            if (toastContext.activityName != null) {
                                DetailSection(title = "显示位置", searchQuery = searchQuery) {
                                    DetailRow("界面", toastContext.activityName, searchQuery)
                                }
                            }
                            
                            if (toastContext.hasSourceContext()) {
                                Spacer(Modifier.height(12.dp))
                                DetailSection(title = "源信息", searchQuery = searchQuery) {
                                    toastContext.sourceName?.let { 
                                        DetailRow("源名称", it, searchQuery) 
                                    }
                                    toastContext.sourceType?.let { 
                                        DetailRow("源类型", it.displayName, searchQuery) 
                                    }
                                    toastContext.ruleType?.let { 
                                        DetailRow("规则类型", it.displayName, searchQuery) 
                                    }
                                    toastContext.ruleLine?.let { 
                                        DetailRow("规则行号", "第${it}行", searchQuery) 
                                    }
                                }
                            }
                        }
                        
                        else -> {
                            if (log.sourceName != null || log.sourceUrl != null) {
                                DetailSection(title = "来源信息", searchQuery = searchQuery) {
                                    log.sourceName?.let { DetailRow("来源名", it, searchQuery) }
                                    log.sourceUrl?.let { DetailRow("来源URL", it, searchQuery) }
                                }
                            }
                        }
                    }

                    if (log.throwable != null) {
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
                                        log.throwable?.stackTraceToString() ?: "",
                                        searchQuery
                                    ),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
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
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
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
            maxLines = if (expanded) Int.MAX_VALUE else 3
        )
    }
}
