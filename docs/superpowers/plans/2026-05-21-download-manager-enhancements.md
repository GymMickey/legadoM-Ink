# 下载管理界面增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 增强下载管理界面：Tab 分组过滤、实时下载速度显示、下载来源信息展示

**架构：** 扩展 `DownloadTask` 数据模型（新增 speed/sourceUrl/downloadUrl 字段），在 `DownloadState.queryAllTaskStatus()` 中计算瞬时速度，`DownloadService` 传递来源 URL，UI 层用 Compose TabRow 实现分组过滤并展示速度和来源信息。

**技术栈：** Kotlin, Jetpack Compose (Material3), Android DownloadManager

---

## 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `app/src/main/java/io/legado/app/service/DownloadState.kt` | 修改 | DownloadTask 新增字段；速度计算逻辑 |
| `app/src/main/java/io/legado/app/service/DownloadService.kt` | 修改 | startDownload 接收 sourceUrl；传递到 DownloadState |
| `app/src/main/java/io/legado/app/model/Download.kt` | 修改 | start() 新增 sourceUrl 参数 |
| `app/src/main/java/io/legado/app/ui/download/DownloadManageScreen.kt` | 修改 | TabRow；速度和来源 UI 展示 |
| `app/src/main/java/io/legado/app/ui/download/DownloadManageViewModel.kt` | 修改 | Tab 过滤逻辑 |
| `app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt:399` | 修改 | 传入 sourceUrl |
| `app/src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt:395` | 修改 | 传入 sourceUrl |
| `app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt:481` | 修改 | 传入 sourceUrl |
| `app/src/main/java/io/legado/app/ui/about/UpdateDialog.kt:91,117` | 修改 | sourceUrl 传 null（应用更新场景无来源页面） |

---

### 任务 1：扩展 DownloadTask 数据模型

**文件：** `app/src/main/java/io/legado/app/service/DownloadState.kt`

- [ ] **步骤 1：给 DownloadTask 添加三个新字段**

在 `DownloadTask` data class 中添加字段（注意 `totalSize` 和 `downloadedSize` 当前是 `Int`，保持一致）：

```kotlin
data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val notificationId: Int,
    val startTime: Long,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val totalSize: Int = 0,
    val downloadedSize: Int = 0,
    val speed: Long = 0,              // 下载速度 bytes/s
    val sourceUrl: String = "",       // 来源页面 URL
    val downloadUrl: String = ""      // 文件直链 URL
)
```

- [ ] **步骤 2：在 DownloadState 中添加速度跟踪 map 和更新逻辑**

在 `DownloadState` object 中添加：

```kotlin
// 用于计算瞬时速度：记录上一次轮询的已下载字节数
private val lastDownloadedMap = mutableMapOf<Long, Int>()

/**
 * 更新任务状态和进度，附带速度计算
 */
fun updateTask(
    id: Long,
    status: DownloadStatus,
    progress: Int = 0,
    totalSize: Int = 0,
    downloadedSize: Int = 0,
    sourceUrl: String? = null,
    downloadUrl: String? = null
) {
    taskMap[id]?.let { existing ->
        // 计算瞬时速度: 与上一次的差值 / 轮询间隔(1s)
        val lastDownloaded = lastDownloadedMap[id] ?: 0
        val speed = if (status == DownloadStatus.RUNNING && downloadedSize > lastDownloaded) {
            (downloadedSize - lastDownloaded).toLong()
        } else if (status == DownloadStatus.RUNNING) {
            existing.speed // 保持上次速度（防止瞬间为0跳动）
        } else {
            0L
        }
        lastDownloadedMap[id] = downloadedSize

        taskMap[id] = existing.copy(
            status = status,
            progress = progress,
            totalSize = totalSize,
            downloadedSize = downloadedSize,
            speed = speed,
            sourceUrl = sourceUrl ?: existing.sourceUrl,
            downloadUrl = downloadUrl ?: existing.downloadUrl
        )
        updateFlow()
    }
}
```

同时修改 `addTask` 方法，新增 sourceUrl 和 downloadUrl 参数：

```kotlin
fun addTask(
    id: Long,
    url: String,
    fileName: String,
    notificationId: Int,
    sourceUrl: String = "",
    downloadUrl: String = ""
) {
    val task = DownloadTask(
        id = id,
        url = url,
        fileName = fileName,
        notificationId = notificationId,
        startTime = System.currentTimeMillis(),
        sourceUrl = sourceUrl,
        downloadUrl = downloadUrl
    )
    taskMap[id] = task
    updateFlow()
}
```

同时修改 `removeTask` 清理速度 map：

```kotlin
fun removeTask(id: Long) {
    taskMap.remove(id)
    lastDownloadedMap.remove(id)
    updateFlow()
}
```

同时修改 `clear` 清理速度 map：

```kotlin
fun clear() {
    taskMap.clear()
    lastDownloadedMap.clear()
    updateFlow()
}
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/service/DownloadState.kt
git commit -m "feat: 扩展 DownloadTask 支持下载速度和来源信息"
```

---

### 任务 2：DownloadService 传递来源信息

**文件：** `app/src/main/java/io/legado/app/service/DownloadService.kt`

- [ ] **步骤 1：修改 startDownload 接收 sourceUrl**

```kotlin
@Synchronized
private fun startDownload(url: String?, fileName: String?, sourceUrl: String?) {
    if (url == null || fileName == null) {
        if (downloads.isEmpty()) {
            stopSelf()
        }
        return
    }
    if (downloads.values.any { it.url == url }) {
        toastOnUi("已在下载列表")
        return
    }
    kotlin.runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = downloadManager.enqueue(request)
        val notificationId = NotificationId.Download + downloads.size
        downloads[downloadId] = DownloadInfo(url, fileName, notificationId)
        DownloadState.addTask(
            downloadId, url, fileName, notificationId,
            sourceUrl = sourceUrl ?: "",
            downloadUrl = url
        )
        AppLog.put("📥开始下载: $fileName\nURL: $url")
        queryState()
        if (upStateJob == null) {
            checkDownloadState()
        }
    }.onFailure {
        it.printStackTrace()
        val msg = when (it) {
            is SecurityException -> "下载出错,没有存储权限"
            else -> "下载出错,${it.localizedMessage}"
        }
        toastOnUi(msg)
        AppLog.put(msg, it)
    }
}
```

- [ ] **步骤 2：修改 onStartCommand 传递 sourceUrl**

```kotlin
IntentAction.start -> startDownload(
    intent.getStringExtra("url"),
    intent.getStringExtra("fileName"),
    intent.getStringExtra("sourceUrl")
)
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/io/legado/app/service/DownloadService.kt
git commit -m "feat: DownloadService 传递下载来源 URL"
```

---

### 任务 3：Download.start() 入口添加 sourceUrl 参数

**文件：** `app/src/main/java/io/legado/app/model/Download.kt`

- [ ] **步骤 1：修改 start 方法签名**

```kotlin
fun start(context: Context, url: String, fileName: String, sourceUrl: String? = null) {
    context.startService<DownloadService> {
        action = IntentAction.start
        putExtra("url", url)
        putExtra("fileName", fileName)
        putExtra("sourceUrl", sourceUrl)
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/model/Download.kt
git commit -m "feat: Download.start() 支持 sourceUrl 参数"
```

---

### 任务 4：调用方传入来源 URL

**文件：**
- `app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt:399`
- `app/src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt:395`
- `app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt:481`

- [ ] **步骤 1：WebViewActivity.kt — 传入 currentWebView.url**

```kotlin
// 第 399 行
Download.start(this, url, fileName, currentWebView.url)
```

- [ ] **步骤 2：BottomWebViewDialog.kt — 传入 currentWebView.url**

```kotlin
// 第 395 行
Download.start(requireContext(), url, fileName, currentWebView.url)
```

- [ ] **步骤 3：ReadRssActivity.kt — 传入 currentWebView.url**

```kotlin
// 第 481 行
Download.start(this, url, fileName, currentWebView.url)
```

- [ ] **步骤 4：UpdateDialog.kt — 保持默认 null（应用更新无来源页面）**

两处调用 `Download.start(requireContext(), url, name)` 和 `Download.start(requireContext(), selected.downloadUrl, selected.fileName)` 不需要改动，因为 sourceUrl 默认值已经是 null。

- [ ] **步骤 5：DownloadState.retryDownload — 传递已有 sourceUrl**

```kotlin
// 第 150 行
Download.start(context, task.url, task.fileName, task.sourceUrl.ifEmpty { null })
```

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/browser/WebViewActivity.kt \
        app/src/main/java/io/legado/app/ui/widget/dialog/BottomWebViewDialog.kt \
        app/src/main/java/io/legado/app/ui/rss/read/ReadRssActivity.kt \
        app/src/main/java/io/legado/app/service/DownloadState.kt
git commit -m "feat: 各下载调用方传入来源页面 URL"
```

---

### 任务 5：ViewModel 添加 Tab 过滤逻辑

**文件：** `app/src/main/java/io/legado/app/ui/download/DownloadManageViewModel.kt`

- [ ] **步骤 1：添加 Tab 枚举和过滤状态**

```kotlin
enum class DownloadTab(val label: String) {
    ALL("全部"),
    DOWNLOADING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败")
}
```

在 ViewModel 类中添加：

```kotlin
// 当前选中的 Tab
private val _selectedTab = MutableStateFlow(DownloadTab.ALL)
val selectedTab: StateFlow<DownloadTab> = _selectedTab.asStateFlow()

// 过滤后的任务列表
val filteredTasks: StateFlow<List<DownloadTask>> = kotlinx.coroutines.flow.combine(
    _tasks, _selectedTab
) { tasks, tab ->
    when (tab) {
        DownloadTab.ALL -> tasks
        DownloadTab.DOWNLOADING -> tasks.filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING
        }
        DownloadTab.PAUSED -> tasks.filter { it.status == DownloadStatus.PAUSED }
        DownloadTab.COMPLETED -> tasks.filter { it.status == DownloadStatus.SUCCESSFUL }
        DownloadTab.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
    }
}.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, emptyList())

fun selectTab(tab: DownloadTab) {
    _selectedTab.value = tab
}

// 各状态计数
fun getPausedCount(): Int = _tasks.value.count { it.status == DownloadStatus.PAUSED }
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/download/DownloadManageViewModel.kt
git commit -m "feat: ViewModel 添加 Tab 过滤逻辑"
```

---

### 任务 6：UI 添加 TabRow、速度显示、来源信息

**文件：** `app/src/main/java/io/legado/app/ui/download/DownloadManageScreen.kt`

- [ ] **步骤 1：添加必要的 import**

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.legado.app.ui.download.DownloadTab
```

- [ ] **步骤 2：修改 DownloadManageScreen 主界面，添加 TabRow**

将现有的 `Scaffold` body 部分替换，新增 TabRow 和使用 `filteredTasks`：

```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadManageScreen(
    viewModel: DownloadManageViewModel = viewModel(),
    onBackClick: () -> Unit
) {
    val allTasks by viewModel.tasks.collectAsState()
    val filteredTasks by viewModel.filteredTasks.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val context = LocalContext.current

    val containerColor = pageCardContainerColor()
    val topBarColor = pageTopBarContainerColor()

    // 统计各状态任务数量
    val activeCount = allTasks.count { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING }
    val completedCount = allTasks.count { it.status == DownloadStatus.SUCCESSFUL }
    val failedCount = allTasks.count { it.status == DownloadStatus.FAILED }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                // ... 保持原有 topBar 不变 ...
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            // TabRow
            val tabs = DownloadTab.values()
            TabRow(
                selectedTabIndex = tabs.indexOf(selectedTab),
                containerColor = topBarColor,
                contentColor = MaterialTheme.colorScheme.onSecondary
            ) {
                tabs.forEach { tab ->
                    val count = when (tab) {
                        DownloadTab.ALL -> allTasks.size
                        DownloadTab.DOWNLOADING -> activeCount
                        DownloadTab.PAUSED -> allTasks.count { it.status == DownloadStatus.PAUSED }
                        DownloadTab.COMPLETED -> completedCount
                        DownloadTab.FAILED -> failedCount
                    }
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = tab.label, style = MaterialTheme.typography.bodySmall)
                                if (count > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(text = count.toString(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // 任务列表或空状态
            if (filteredTasks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // ... 保持原有空状态 UI ...
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredTasks, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            onCancelClick = { viewModel.cancelDownload(task.id) },
                            onRetryClick = { viewModel.retryDownload(context, task.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}
```

- [ ] **步骤 3：修改 DownloadTaskCard，添加速度和来源显示**

```kotlin
@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit
) {
    val containerColor = pageCardContainerColor()
    val clipboardManager = LocalClipboardManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusIcon(task.status, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = getStatusText(task.status),
                            style = MaterialTheme.typography.bodySmall,
                            color = getStatusColor(task.status)
                        )
                        // 下载中显示进度百分比
                        if (task.status == DownloadStatus.RUNNING) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${task.progress}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // 显示文件总大小
                        if (task.totalSize > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ConvertUtils.formatFileSize(task.totalSize.toLong()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 操作按钮 (保持不变)
                // ...
            }

            // 进度条 (保持不变)
            // ...

            // 下载中显示速度 + 已下载/总大小
            if (task.status == DownloadStatus.RUNNING && task.downloadedSize > 0 && task.totalSize > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${ConvertUtils.formatFileSize(task.downloadedSize.toLong())} / ${ConvertUtils.formatFileSize(task.totalSize.toLong())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.speed > 0) {
                        Text(
                            text = "${formatSpeed(task.speed)}/s",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 来源信息
            if (task.sourceUrl.isNotEmpty() || task.downloadUrl.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                if (task.sourceUrl.isNotEmpty()) {
                    Text(
                        text = "来源: ${task.sourceUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (task.downloadUrl.isNotEmpty()) {
                    Text(
                        text = "链接: ${task.downloadUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
```

- [ ] **步骤 4：添加速度格式化辅助函数**

```kotlin
/**
 * 格式化下载速度
 */
private fun formatSpeed(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> String.format("%.1f KB", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B"
    }
}
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/io/legado/app/ui/download/DownloadManageScreen.kt
git commit -m "feat: 下载管理 UI 添加 Tab 分组、实时速度和来源信息"
```

---

### 任务 7：验证

- [ ] **步骤 1：编译检查**

```bash
./gradlew assembleAppMaxDebug
```

预期：BUILD SUCCESSFUL

- [ ] **步骤 2：功能验证清单**

在设备上验证：
1. 打开下载管理页面，确认 TabRow 显示"全部/下载中/已暂停/已完成/失败"
2. 从浏览器触发一次下载，确认下载中 Tab 显示实时速度（如 2.5 MB/s）
3. 确认任务卡片显示来源页面 URL 和文件直链
4. 暂停下载（断网），确认"已暂停" Tab 有计数
5. 切换各 Tab 确认过滤正确
6. 重试失败的下载，确认 sourceUrl 正确传递
