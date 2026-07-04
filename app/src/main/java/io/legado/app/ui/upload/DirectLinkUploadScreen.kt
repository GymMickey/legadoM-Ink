/**
 * 直链上传配置界面
 * 
 * 该文件实现了直链上传功能的配置界面,包括:
 * - 上传规则的管理(添加、编辑、删除、测试)
 * - 上传历史的查看和管理
 * - 规则的导入导出功能
 * 
 * 主要组件:
 * - DirectLinkUploadScreen: 主界面,包含规则管理和上传历史两个标签页
 * - RuleListTab: 规则列表标签页
 * - HistoryListTab: 上传历史标签页
 * - RuleCard: 单个规则卡片
 * - HistoryCard: 单条历史记录卡片
 * - RuleEditDialog: 规则编辑对话框
 */
package io.legado.app.ui.upload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.DirectLinkUploadRule
import io.legado.app.data.entities.UploadHistory
import io.legado.app.data.entities.UploadHistoryWithRule
import io.legado.app.ui.upload.DirectLinkUploadViewModel.*
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog
import java.text.SimpleDateFormat
import java.util.*

/**
 * 直链上传配置主界面
 * 
 * 该 Composable 是直链上传功能的入口界面,提供:
 * - 顶部应用栏,包含返回按钮、添加规则按钮和更多操作菜单
 * - 标签页布局,切换规则管理和上传历史
 * - 各种对话框(添加/编辑规则、清除历史、导入默认规则、测试结果)
 * 
 * @param viewModel 直链上传的 ViewModel,负责业务逻辑和状态管理
 * @param onBackClick 返回按钮点击回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectLinkUploadScreen(
    viewModel: DirectLinkUploadViewModel = viewModel(),
    onBackClick: () -> Unit
) {
    // 获取上下文和剪贴板管理器
    val context = LocalContext.current
    val clipboardManager = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    
    // 从 ViewModel 收集状态
    val rules by viewModel.rules.collectAsState(initial = emptyList())  // 上传规则列表
    val histories by viewModel.histories.collectAsState(initial = emptyList())  // 上传历史列表
    val uiState by viewModel.uiState.collectAsState()  // UI 状态
    val uploadState by viewModel.uploadState.collectAsState()  // 上传/测试状态
    
    // 本地 UI 状态
    var selectedTab by remember { mutableStateOf(0) }  // 当前选中的标签页索引
    var showAddDialog by remember { mutableStateOf(false) }  // 是否显示添加规则对话框
    var editingRule by remember { mutableStateOf<DirectLinkUploadRule?>(null) }  // 正在编辑的规则
    var showClearDialog by remember { mutableStateOf(false) }  // 是否显示清除历史确认对话框
    var showImportDialog by remember { mutableStateOf(false) }  // 是否显示导入默认规则对话框
    var testingRule by remember { mutableStateOf<DirectLinkUploadRule?>(null) }  // 正在测试的规则
    var testResult by remember { mutableStateOf<String?>(null) }  // 测试结果
    
    // 标签页标题
    val tabs = listOf("规则管理", "上传历史")
    
    // 主界面布局
    Scaffold(
        containerColor = Color.Transparent,
        // 顶部应用栏
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "直链上传配置",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    )
                },
                // 顶部栏颜色配置
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    scrolledContainerColor = MaterialTheme.colorScheme.secondary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSecondary,
                    titleContentColor = MaterialTheme.colorScheme.onSecondary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                ),
                // 导航图标(返回按钮)
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                // 操作按钮区域
                actions = {
                    // 添加规则按钮
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "添加规则")
                    }
                    // 更多操作菜单
                    var showMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    // 下拉菜单
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        // 粘贴规则选项
                        DropdownMenuItem(
                            text = { Text("粘贴规则") },
                            onClick = {
                                showMenu = false
                                val clip = clipboardManager.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val json = clip.getItemAt(0).text?.toString() ?: ""
                                    if (json.isNotBlank() && viewModel.pasteRule(json)) {
                                        // 粘贴成功
                                    }
                                }
                            },
                            leadingIcon = { 
                                Icon(Icons.Default.ContentPaste, null, tint = MaterialTheme.colorScheme.primary) 
                            }
                        )
                        // 导入默认规则选项
                        DropdownMenuItem(
                            text = { Text("导入默认规则") },
                            onClick = { showImportDialog = true; showMenu = false },
                            leadingIcon = { 
                                Icon(Icons.Default.CloudDownload, null, tint = MaterialTheme.colorScheme.primary) 
                            }
                        )
                        HorizontalDivider()
                        // 清除历史选项
                        DropdownMenuItem(
                            text = { Text("清除历史") },
                            onClick = { showClearDialog = true; showMenu = false },
                            leadingIcon = { 
                                Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.primary) 
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        // 主内容区域
        Column(modifier = Modifier.padding(paddingValues)) {
            // 标签页布局
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { 
                            Text(
                                title,
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary 
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            ) 
                        }
                    )
                }
            }
            
            // 根据选中的标签页显示不同内容
            when (selectedTab) {
                // 规则管理标签页
                0 -> RuleListTab(
                    rules = rules,
                    onEdit = { editingRule = it },
                    onDelete = { viewModel.deleteRule(it) },
                    onSetDefault = { viewModel.setDefaultRule(it.id) },
                    onTest = { rule ->
                        testingRule = rule
                        viewModel.testRule(rule)
                    },
                    onCopy = { rule ->
                        val json = viewModel.copyRule(rule)
                        val clip = ClipData.newPlainText("上传规则", json)
                        clipboardManager.setPrimaryClip(clip)
                    }
                )
                // 上传历史标签页
                1 -> HistoryListTab(
                    histories = histories,
                    onDelete = { historyWithRule ->
                        viewModel.deleteHistory(historyWithRule.toUploadHistory())
                        Toast.makeText(context, "已删除历史记录", Toast.LENGTH_SHORT).show()
                    },
                    onCopy = { historyWithRule ->
                        if (historyWithRule.downloadUrl.isNotBlank()) {
                            val clip = ClipData.newPlainText("下载链接", historyWithRule.downloadUrl)
                            clipboardManager.setPrimaryClip(clip)
                            Toast.makeText(context, "已复制下载链接", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
        
        // 添加规则对话框
        if (showAddDialog) {
            RuleEditDialog(
                onDismiss = { showAddDialog = false },
                onSave = { 
                    viewModel.addRule(it)
                    showAddDialog = false
                }
            )
        }
        
        // 编辑规则对话框(当 editingRule 不为 null 时显示)
        editingRule?.let { rule ->
            RuleEditDialog(
                rule = rule,
                onDismiss = { editingRule = null },
                onSave = { 
                    viewModel.updateRule(it)
                    editingRule = null
                }
            )
        }
        
        // 清除历史确认对话框
        if (showClearDialog) {
            AppConfirmDialog(
                title = "清除历史",
                text = "确定要清除所有上传历史记录吗?",
                confirmText = "确定",
                destructive = true,
                onConfirm = {
                    viewModel.clearAllHistories()
                    showClearDialog = false
                },
                onDismissRequest = { showClearDialog = false }
            )
        }

        // 导入默认规则确认对话框
        if (showImportDialog) {
            AppConfirmDialog(
                title = "导入默认规则",
                text = "将导入2个预置的网盘规则(喵公子网盘①、喵公子网盘②)。\n\n注意:如果已有规则,将不会重复导入。",
                confirmText = "导入",
                onConfirm = {
                    viewModel.importDefaultRules()
                    showImportDialog = false
                },
                onDismissRequest = { showImportDialog = false }
            )
        }

        // 上传/测试状态对话框
        uploadState.let { state ->
            when (state) {
                // 测试中状态
                is UploadState.Testing -> {
                    AlertDialog(
                        onDismissRequest = { },
                        containerColor = MaterialTheme.colorScheme.surface,
                        title = { Text("测试中") },
                        text = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text("正在测试上传规则...")
                            }
                        },
                        confirmButton = {}
                    )
                }
                // 测试成功状态
                is UploadState.TestSuccess -> {
                    AlertDialog(
                        onDismissRequest = { 
                            testingRule = null
                            viewModel.resetUploadState()
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        title = { Text("测试成功") },
                        text = { 
                            Column {
                                Text("下载链接:")
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        text = state.downloadUrl,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { 
                                    testingRule = null
                                    viewModel.resetUploadState()
                                }
                            ) {
                                Text("确定", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
                // 测试失败状态
                is UploadState.TestError -> {
                    AlertDialog(
                        onDismissRequest = { 
                            testingRule = null
                            viewModel.resetUploadState()
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        title = { Text("测试失败") },
                        text = { Text(state.message) },
                        confirmButton = {
                            TextButton(
                                onClick = { 
                                    testingRule = null
                                    viewModel.resetUploadState()
                                }
                            ) {
                                Text("确定", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
                // 其他状态(空闲等)
                else -> {}
            }
        }
    }
}

/**
 * 规则列表标签页
 * 
 * 显示所有上传规则的列表,支持编辑、删除、设为默认、测试和拷贝操作
 * 
 * @param rules 上传规则列表
 * @param onEdit 编辑规则回调
 * @param onDelete 删除规则回调
 * @param onSetDefault 设为默认规则回调
 * @param onTest 测试规则回调
 * @param onCopy 拷贝规则回调
 */
@Composable
fun RuleListTab(
    rules: List<DirectLinkUploadRule>,
    onEdit: (DirectLinkUploadRule) -> Unit,
    onDelete: (DirectLinkUploadRule) -> Unit,
    onSetDefault: (DirectLinkUploadRule) -> Unit,
    onTest: (DirectLinkUploadRule) -> Unit,
    onCopy: (DirectLinkUploadRule) -> Unit
) {
    // 空状态显示
    if (rules.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无上传规则",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "点击右上角 + 添加规则",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // 规则列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(rules) { rule ->
                RuleCard(
                    rule = rule,
                    onEdit = { onEdit(rule) },
                    onDelete = { onDelete(rule) },
                    onSetDefault = { onSetDefault(rule) },
                    onTest = { onTest(rule) },
                    onCopy = { onCopy(rule) }
                )
            }
        }
    }
}

/**
 * 规则卡片
 * 
 * 显示单个上传规则的详细信息,包括:
 * - 规则名称和图标
 * - 默认规则标记
 * - 上传次数和最后使用时间
 * - 操作菜单(设为默认、编辑、测试、拷贝、删除)
 * 
 * @param rule 上传规则数据
 * @param onEdit 编辑回调
 * @param onDelete 删除回调
 * @param onSetDefault 设为默认回调
 * @param onTest 测试回调
 * @param onCopy 拷贝回调
 */
@Composable
fun RuleCard(
    rule: DirectLinkUploadRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onTest: () -> Unit,
    onCopy: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行:规则名称和操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 规则名称和图标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = rule.summary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // 右侧:默认标记和操作菜单
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 默认规则标记
                    if (rule.isDefault) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "默认",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    // 更多操作按钮
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    
                    // 操作下拉菜单
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text("设为默认") },
                            onClick = { onSetDefault(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        DropdownMenuItem(
                            text = { Text("编辑") },
                            onClick = { onEdit(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        DropdownMenuItem(
                            text = { Text("测试") },
                            onClick = { onTest(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        DropdownMenuItem(
                            text = { Text("拷贝规则") },
                            onClick = { onCopy(); showMenu = false },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            onClick = { onDelete(); showMenu = false },
                            leadingIcon = { 
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) 
                            }
                        )
                    }
                }
            }
            
            // 第二行:上传统计信息(如果有上传记录)
            if (rule.uploadCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "上传 ${rule.uploadCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (rule.lastUsedTime > 0) {
                        Text(
                            text = formatTime(rule.lastUsedTime),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * 上传历史列表标签页
 * 
 * 显示所有上传历史记录,支持删除和复制下载链接操作
 * 
 * @param histories 上传历史列表(包含规则信息)
 * @param onDelete 删除历史记录回调
 * @param onCopy 复制下载链接回调
 */
@Composable
fun HistoryListTab(
    histories: List<UploadHistoryWithRule>,
    onDelete: (UploadHistoryWithRule) -> Unit,
    onCopy: (UploadHistoryWithRule) -> Unit
) {
    // 空状态显示
    if (histories.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "暂无上传历史",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        // 历史记录列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(histories) { historyWithRule ->
                HistoryCard(
                    historyWithRule = historyWithRule,
                    onDelete = { onDelete(historyWithRule) },
                    onCopy = { onCopy(historyWithRule) }
                )
            }
        }
    }
}

/**
 * 历史记录卡片
 * 
 * 显示单条上传历史记录的详细信息,包括:
 * - 文件名(长按可查看完整名称)
 * - 规则名称(长按可查看完整规则名)
 * - 上传状态(成功/失败)
 * - 文件大小、耗时、上传时间
 * - 下载链接(成功时显示)
 * - 错误信息(失败时显示)
 * - 操作按钮(复制链接、删除)
 * 
 * @param historyWithRule 历史记录数据(包含规则信息)
 * @param onDelete 删除回调
 * @param onCopy 复制链接回调
 */
@Composable
fun HistoryCard(
    historyWithRule: UploadHistoryWithRule,
    onDelete: () -> Unit,
    onCopy: () -> Unit
) {
    val history = historyWithRule.toUploadHistory()
    // 对话框状态
    var showFullFileNameDialog by remember { mutableStateOf(false) }  // 显示完整文件名对话框
    var showFullRuleSummaryDialog by remember { mutableStateOf(false) }  // 显示完整规则名对话框

    // 完整文件名对话框
    if (showFullFileNameDialog) {
        AlertDialog(
            onDismissRequest = { showFullFileNameDialog = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("完整名称") },
            text = {
                SelectionContainer {
                    Text(
                        text = history.fileName,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullFileNameDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    // 完整规则名对话框
    if (showFullRuleSummaryDialog) {
        AlertDialog(
            onDismissRequest = { showFullRuleSummaryDialog = false },
            shape = RectangleShape,
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = { Text("完整规则") },
            text = {
                SelectionContainer {
                    Text(
                        text = history.ruleSummary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showFullRuleSummaryDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 第一行:文件名、规则名、状态标记
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件名(长按查看完整名称)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(history.fileName) {
                            detectTapGestures(
                                onLongPress = { showFullFileNameDialog = true }
                            )
                        }
                ) {
                    // 成功/失败图标
                    Icon(
                        imageVector = if (history.success) Icons.Default.CheckCircle 
                                      else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (history.success) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = history.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                // 右侧:规则名和状态标记
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 规则名称标签(长按查看完整名称)
                    val displayRuleSummary = historyWithRule.getDisplayRuleSummary()
                    if (displayRuleSummary.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.pointerInput(history.ruleSummary) {
                                detectTapGestures(
                                    onLongPress = { showFullRuleSummaryDialog = true }
                                )
                            }
                        ) {
                            Text(
                                text = displayRuleSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    // 失败标记
                    if (!history.success) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = "失败",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            
            // 第二行:文件大小和耗时
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = formatFileSize(history.fileSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (history.success) {
                    Text(
                        text = "耗时 ${history.duration}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 第三行:上传时间
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = formatDateTime(history.uploadTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 第四行:下载链接(成功时显示)
            if (history.success && history.downloadUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = history.downloadUrl,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // 第五行:错误信息(失败时显示)
            if (!history.success && !history.errorMsg.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = history.errorMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            
            // 第六行:操作按钮
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 复制链接按钮(成功时显示)
                if (history.success && history.downloadUrl.isNotBlank()) {
                    TextButton(onClick = onCopy) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制链接", color = MaterialTheme.colorScheme.primary)
                    }
                }
                // 删除按钮
                TextButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * 规则编辑对话框
 * 
 * 用于添加或编辑上传规则,包含以下字段:
 * - 上传URL: 文件上传的目标地址
 * - 下载URL规则: 从上传响应中提取下载链接的规则
 * - 注释说明: 规则的描述信息
 * - 自动压缩: 是否在上传前自动压缩文件
 * 
 * @param rule 要编辑的规则(null 表示添加新规则)
 * @param onDismiss 取消回调
 * @param onSave 保存回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditDialog(
    rule: DirectLinkUploadRule? = null,
    onDismiss: () -> Unit,
    onSave: (DirectLinkUploadRule) -> Unit
) {
    // 表单字段状态
    var uploadUrl by remember { mutableStateOf(rule?.uploadUrl ?: "") }
    var downloadUrlRule by remember { mutableStateOf(rule?.downloadUrlRule ?: "") }
    var summary by remember { mutableStateOf(rule?.summary ?: "") }
    var compress by remember { mutableStateOf(rule?.compress ?: false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (rule == null) "添加上传规则" else "编辑上传规则") },
        text = {
            // 表单内容
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // 上传URL输入框
                OutlinedTextField(
                    value = uploadUrl,
                    onValueChange = { uploadUrl = it },
                    label = { Text("上传URL *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 下载URL规则输入框
                OutlinedTextField(
                    value = downloadUrlRule,
                    onValueChange = { downloadUrlRule = it },
                    label = { Text("下载URL规则 *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 注释说明输入框
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("注释说明 *") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 自动压缩选项
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = compress,
                        onCheckedChange = { compress = it }
                    )
                    Text("自动压缩文件")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 创建新的规则对象
                    val newRule = DirectLinkUploadRule(
                        id = rule?.id ?: 0,
                        uploadUrl = uploadUrl,
                        downloadUrlRule = downloadUrlRule,
                        summary = summary,
                        compress = compress,
                        isDefault = rule?.isDefault ?: false,
                        sortOrder = rule?.sortOrder ?: 0
                    )
                    onSave(newRule)
                },
                // 只有必填字段都填写后才能保存
                enabled = uploadUrl.isNotBlank() && 
                          downloadUrlRule.isNotBlank() && 
                          summary.isNotBlank()
            ) {
                Text("保存", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

/**
 * 格式化相对时间
 * 
 * 将时间戳转换为相对时间描述,如"刚刚"、"5分钟前"、"2小时前"等
 * 
 * @param timestamp 时间戳(毫秒)
 * @return 格式化后的时间字符串
 */
private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "刚刚"  // 小于1分钟
        diff < 3600_000 -> "${diff / 60_000}分钟前"  // 小于1小时
        diff < 86400_000 -> "${diff / 3600_000}小时前"  // 小于1天
        diff < 2592000_000 -> "${diff / 86400_000}天前"  // 小于30天
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))  // 超过30天显示日期
    }
}

/**
 * 格式化日期时间
 * 
 * 将时间戳转换为"yyyy-MM-dd HH:mm"格式的字符串
 * 
 * @param timestamp 时间戳(毫秒)
 * @return 格式化后的日期时间字符串
 */
private fun formatDateTime(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

/**
 * 格式化文件大小
 * 
 * 将字节数转换为人类可读的文件大小格式,如"1.5 KB"、"2.3 MB"等
 * 
 * @param size 文件大小(字节)
 * @return 格式化后的文件大小字符串
 */
private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"  // 小于1KB
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)  // 小于1MB
        size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))  // 小于1GB
        else -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))  // 大于等于1GB
    }
}
