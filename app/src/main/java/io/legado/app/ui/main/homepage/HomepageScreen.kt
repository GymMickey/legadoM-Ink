/**
 * 首页主屏幕 Composable（E-Ink 精简版）
 *
 * 固定布局阅读仪表盘，自上而下 4 个卡片：
 * 1. 最近阅读卡片 — 书封 + 书名/作者 + 章节进度百分比
 * 2. 统计双卡 — 累计阅读本数 | 阅读总时长
 * 3. 最近书籍横滑列表 — 书封横向 LazyRow
 * 4. WebDAV 备份卡 — 状态 + 备份/恢复/设置按钮
 *
 * 背景色使用 CommonPageColors 函数，自动适配普通/暗色/E-Ink 模式。
 * E-Ink 模式：elevation=0，添加 outline 描边，隐藏动画指示器。
 */
package io.legado.app.ui.main.homepage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.homepage.modules.HomepageBookCover
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageCardElevatedContainerColor
import io.legado.app.ui.theme.pageSecondaryTextColor

/**
 * 首页主屏幕
 */
@Composable
fun HomepageScreen(
    viewModel: HomepageViewModel = viewModel(),
    onBookClick: (name: String?, author: String?, bookUrl: String, origin: String?, coverPath: String?) -> Unit,
    onWebDavSettingsClick: () -> Unit,
    onReadRecordClick: () -> Unit = {},
    onBookshelfClick: () -> Unit = {},
    onRestoreConfirm: (backupName: String) -> Unit = {},
) {
    val state by viewModel.dashboardState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // E-Ink 适配 + 亮色主题强调色描边
    val isEInk = AppConfig.isEInkMode
    val isNightTheme = AppConfig.isNightTheme
    val cardElevation = if (isEInk) 0.dp else 1.dp
    val cardBorder = when {
        isEInk -> BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        !isNightTheme -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        else -> null
    }

    // 恢复确认弹窗状态
    var restoreDialogName by remember { mutableStateOf<String?>(null) }

    // 收集副作用
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is HomepageEffect.NavigateToBookInfo ->
                    onBookClick(effect.name, effect.author, effect.bookUrl, effect.origin, effect.coverPath)
                is HomepageEffect.NavigateToWebDavSettings ->
                    onWebDavSettingsClick()
                is HomepageEffect.ShowSnackbar ->
                    android.widget.Toast.makeText(context, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                is HomepageEffect.ShowRestoreDialog ->
                    restoreDialogName = effect.backupName
                is HomepageEffect.NavigateToReadRecord ->
                    onReadRecordClick()
                is HomepageEffect.NavigateToBookshelf ->
                    onBookshelfClick()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
    ) { paddingValues ->
        if (state.lastReadBook == null && state.recentBooks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.homepage_dashboard_empty_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = pageSecondaryTextColor()
                    )
                    Text(
                        text = stringResource(R.string.homepage_dashboard_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor().copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                // 1. 最近阅读卡片（elevated 层次）
                state.lastReadBook?.let { book ->
                    item(key = "last_read") {
                        LastReadCard(
                            book = book,
                            onClick = { viewModel.onBookClick(book) },
                            elevation = cardElevation,
                            border = cardBorder
                        )
                    }
                }

                // 2. 统计双卡
                item(key = "stats") {
                    StatsRow(
                        totalBooks = state.totalBooksRead,
                        totalReadTimeMs = state.totalReadTimeMs,
                        elevation = cardElevation,
                        border = cardBorder,
                        onStatClick = onReadRecordClick
                    )
                }

                // 3. 最近书籍横滑列表
                if (state.recentBooks.isNotEmpty()) {
                    item(key = "recent") {
                        RecentBooksRow(
                            books = state.recentBooks,
                            onBookClick = { viewModel.onBookClick(it) },
                            onViewAllClick = onBookshelfClick,
                            elevation = cardElevation,
                            border = cardBorder
                        )
                    }
                }

                // 4. WebDAV 备份卡
                item(key = "webdav") {
                    WebDavCard(
                        isConfigured = state.webDavConfigured,
                        isBackingUp = state.isBackingUp,
                        lastBackupTime = state.lastBackupTime,
                        onBackup = { viewModel.onBackup() },
                        onRestore = { viewModel.onRestore() },
                        onSettings = onWebDavSettingsClick,
                        elevation = cardElevation,
                        border = cardBorder,
                        isEInk = isEInk
                    )
                }

                item(key = "bottom") { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        // 恢复确认弹窗
        restoreDialogName?.let { name ->
            AlertDialog(
                onDismissRequest = { restoreDialogName = null },
                title = { Text(stringResource(R.string.homepage_webdav_restore)) },
                text = {
                    Text(stringResource(R.string.homepage_webdav_restore_confirm, name))
                },
                confirmButton = {
                    TextButton(onClick = {
                        restoreDialogName = null
                        onRestoreConfirm(name)
                    }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { restoreDialogName = null }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            )
        }
    }
}

// ==================== 工具函数 ====================

/** 为 Card 添加可选 border modifier */
private fun Modifier.cardBorder(border: BorderStroke?): Modifier =
    if (border != null) this.then(Modifier.border(border, RoundedCornerShape(16.dp))) else this

// ==================== 卡片 1：最近阅读 ====================

@Composable
private fun LastReadCard(book: Book, onClick: () -> Unit, elevation: androidx.compose.ui.unit.Dp, border: BorderStroke?) {
    val progressPercent = if (book.totalChapterNum > 0) {
        ((book.durChapterIndex + 1).toFloat() / book.totalChapterNum * 100).toInt().coerceIn(0, 100)
    } else 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cardBorder(border)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pageCardElevatedContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomepageBookCover(
                name = book.name,
                author = book.author,
                coverUrl = book.getDisplayCover(),
                modifier = Modifier.size(width = 72.dp, height = 96.dp),
                cornerRadius = 8.dp,
                identity = book.bookUrl,
                origin = book.origin
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.homepage_continue_reading),
                    style = MaterialTheme.typography.labelSmall,
                    color = pageSecondaryTextColor()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = pageSecondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.homepage_read_progress, progressPercent, book.durChapterTitle ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = pageSecondaryTextColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPercent / 100f)
                            .height(4.dp)
                            .background(pageAccentColor())
                    )
                }
            }
        }
    }
}

// ==================== 卡片 2：统计双卡 ====================

@Composable
private fun StatsRow(totalBooks: Int, totalReadTimeMs: Long, elevation: androidx.compose.ui.unit.Dp, border: BorderStroke?, onStatClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Book,
            value = "$totalBooks",
            label = stringResource(R.string.homepage_stats_books_count),
            elevation = elevation,
            border = border,
            onClick = onStatClick
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Schedule,
            value = ReadTimeFormatter.format(totalReadTimeMs),
            label = stringResource(R.string.homepage_stats_read_time),
            elevation = elevation,
            border = border,
            onClick = onStatClick
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    elevation: androidx.compose.ui.unit.Dp,
    border: BorderStroke?,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = modifier.cardBorder(border).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pageCardElevatedContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = pageAccentColor(),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = pageSecondaryTextColor()
            )
        }
    }
}

// ==================== 卡片 3：最近书籍横滑列表 ====================

@Composable
private fun RecentBooksRow(books: List<Book>, onBookClick: (Book) -> Unit, onViewAllClick: () -> Unit = {}, elevation: androidx.compose.ui.unit.Dp, border: BorderStroke?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cardBorder(border),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pageCardElevatedContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.homepage_recent_books),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = pageSecondaryTextColor(),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onViewAllClick)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                books.forEach { book ->
                    RecentBookItem(book = book, onClick = { onBookClick(book) })
                }
            }
        }
    }
}

@Composable
private fun RecentBookItem(book: Book, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HomepageBookCover(
            name = book.name,
            author = book.author,
            coverUrl = book.getDisplayCover(),
            modifier = Modifier.size(width = 72.dp, height = 96.dp),
            cornerRadius = 8.dp,
            identity = book.bookUrl,
            origin = book.origin
        )
    }
}

// ==================== 卡片 4：WebDAV 备份卡 ====================

@Composable
private fun WebDavCard(
    isConfigured: Boolean,
    isBackingUp: Boolean,
    lastBackupTime: Long,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onSettings: () -> Unit,
    elevation: androidx.compose.ui.unit.Dp,
    border: BorderStroke?,
    isEInk: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .cardBorder(border),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = pageCardElevatedContainerColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = pageAccentColor(),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.homepage_webdav_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                !isConfigured -> stringResource(R.string.homepage_webdav_not_configured)
                                lastBackupTime > 0 -> stringResource(
                                    R.string.homepage_webdav_last_backup,
                                    formatBackupTime(lastBackupTime)
                                )
                                else -> stringResource(R.string.homepage_webdav_configured)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = pageSecondaryTextColor()
                        )
                    }
                }
                if (isBackingUp && !isEInk) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            if (isConfigured) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = onBackup,
                        enabled = !isBackingUp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (isBackingUp) stringResource(R.string.homepage_webdav_backing_up)
                            else stringResource(R.string.homepage_webdav_backup)
                        )
                    }
                    TextButton(
                        onClick = onRestore,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.homepage_webdav_restore))
                    }
                    TextButton(
                        onClick = onSettings,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.homepage_webdav_settings))
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onSettings) {
                    Text(stringResource(R.string.homepage_webdav_configure))
                }
            }
        }
    }
}

// ==================== 时长格式化 ====================

/** 将毫秒格式化为可读的时长字符串 */
private object ReadTimeFormatter {
    fun format(ms: Long): String {
        if (ms <= 0) return stringRes(R.string.homepage_stats_minutes, 0)
        val totalMinutes = ms / 60_000
        return if (totalMinutes < 60) {
            stringRes(R.string.homepage_stats_minutes, totalMinutes.toInt())
        } else {
            val hours = ms / 3_600_000.0
            stringRes(R.string.homepage_stats_hours_decimal, hours)
        }
    }

    private fun stringRes(resId: Int, vararg args: Any): String {
        return try {
            val context = splitties.init.appCtx
            java.lang.String.format(context.getString(resId), *args)
        } catch (_: Exception) {
            "..."
        }
    }
}

private fun formatBackupTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
