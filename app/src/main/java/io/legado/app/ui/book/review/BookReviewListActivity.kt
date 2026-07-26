package io.legado.app.ui.book.review

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookReview
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageCardElevatedContainerColor
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的书评列表页
 *
 * 从"我的"页面 Preference 入口进入。支持搜索、排序、E-Ink 分页。
 * 点击进入编辑页，长按删除（Toast + 撤销）。
 */
class BookReviewListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLegadoContent {
            BookReviewListScreen(
                onBackClick = { finish() },
                onEditClick = { review ->
                    val intent = Intent(this@BookReviewListActivity, BookReviewEditActivity::class.java)
                        .putExtra(BookReviewEditActivity.EXTRA_REVIEW_ID, review.id)
                    startActivity(intent)
                }
            )
        }
    }
}

// === 排序类型 ===

private enum class SortType(val label: String) {
    RECENT("最近创建"),
    RATING("评分最高")
}

// === 主屏幕 ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookReviewListScreen(
    onBackClick: () -> Unit,
    onEditClick: (BookReview) -> Unit,
    viewModel: BookReviewViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 状态
    var reviews by remember { mutableStateOf<List<BookReview>>(emptyList()) }
    var keyword by remember { mutableStateOf("") }
    var sortType by remember { mutableStateOf(SortType.RECENT) }
    var sortExpanded by remember { mutableStateOf(false) }

    // 多选模式
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // E-Ink 分页状态
    val isEInk = AppConfig.isEInkMode
    val itemsPerPage = 3  // 小屏 2，预留
    var einkPage by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableStateOf(0f) }

    // 加载数据
    fun load() {
        scope.launch {
            reviews = withContext(Dispatchers.IO) { viewModel.getAll() }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            load()
        }
    }

    // 过滤 + 排序
    val displayReviews = remember(reviews, keyword, sortType) {
        var list = if (keyword.isBlank()) reviews
        else reviews.filter {
            it.bookName.contains(keyword, ignoreCase = true) ||
            it.bookAuthor.contains(keyword, ignoreCase = true) ||
            it.content.contains(keyword, ignoreCase = true)
        }
        when (sortType) {
            SortType.RECENT -> list.sortedByDescending { it.createTime }
            SortType.RATING -> list.sortedByDescending { it.rating }
        }
    }

    // E-Ink 分页数据
    val pagedReviews = if (isEInk) {
        val from = einkPage * itemsPerPage
        displayReviews.subList(
            from.coerceAtMost(displayReviews.size),
            (from + itemsPerPage).coerceAtMost(displayReviews.size)
        )
    } else emptyList()

    val totalPages = if (isEInk && displayReviews.isNotEmpty())
        (displayReviews.size + itemsPerPage - 1) / itemsPerPage else 0

    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    val cardElevation = 0.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectionMode) {
                // 多选模式 TopBar
                TopAppBar(
                    title = { Text("已选 ${selectedIds.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Default.Close, "取消选择")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true },
                            enabled = selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                "删除",
                                tint = if (selectedIds.isNotEmpty())
                                    MaterialTheme.colorScheme.error
                                else
                                    pageSecondaryTextColor()
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                // 普通模式 TopBar
                TopAppBar(
                    title = { Text("我的书评") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // 统计副标题
            Text(
                text = "记录过 ${displayReviews.size} 本书",
                style = MaterialTheme.typography.bodyMedium,
                color = pageSecondaryTextColor(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 4.dp)
            )

            // 搜索框 + 排序
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it; einkPage = 0 },
                    placeholder = { Text("搜索书名、作者、正文") },
                    singleLine = true,
                    trailingIcon = { Icon(Icons.Default.Search, "搜索") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 排序
                Box {
                    IconButton(onClick = { sortExpanded = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "排序",
                            tint = pageSecondaryTextColor()
                        )
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false },
                        containerColor = Color.White
                    ) {
                        SortType.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = {
                                    sortType = s
                                    sortExpanded = false
                                    einkPage = 0
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 内容区
            if (displayReviews.isEmpty()) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (keyword.isBlank()) "还没有书评" else "没有匹配结果",
                            style = MaterialTheme.typography.bodyLarge,
                            color = pageSecondaryTextColor()
                        )
                        if (keyword.isBlank()) {
                            Text(
                                text = "读完一本书后，来这里记录评分和感想吧",
                                style = MaterialTheme.typography.bodySmall,
                                color = pageSecondaryTextColor().copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            } else if (isEInk) {
                // E-Ink 分页模式
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(einkPage) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        if (dragOffset < -300f && einkPage < totalPages - 1) einkPage++
                                        else if (dragOffset > 300f && einkPage > 0) einkPage--
                                        dragOffset = 0f
                                    },
                                    onVerticalDrag = { _, offset -> dragOffset += offset }
                                )
                            },
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pagedReviews.forEach { review ->
                            ReviewCard(
                                review = review,
                                onEditClick = onEditClick,
                                onLongClick = {
                                    selectionMode = true
                                    selectedIds = if (review.id in selectedIds)
                                        selectedIds - review.id
                                    else
                                        selectedIds + review.id
                                },
                                selectionMode = selectionMode,
                                isSelected = review.id in selectedIds,
                                onToggle = {
                                    selectedIds = if (review.id in selectedIds)
                                        selectedIds - review.id
                                    else
                                        selectedIds + review.id
                                    if (selectedIds.isEmpty() && selectionMode) {
                                        selectionMode = false
                                    }
                                },
                                elevation = cardElevation,
                                cardBorder = cardBorder
                            )
                        }
                    }
                }
                // 页码指示
                if (totalPages > 1) {
                    Text(
                        text = "第 ${einkPage + 1} 页 / 共 $totalPages 页",
                        style = MaterialTheme.typography.labelSmall,
                        color = pageSecondaryTextColor(),
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 8.dp)
                    )
                }
            } else {
                // 普通模式 LazyColumn
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(displayReviews, key = { it.id }) { review ->
                        ReviewCard(
                            review = review,
                            onEditClick = onEditClick,
                            onLongClick = {
                                selectionMode = true
                                selectedIds = if (review.id in selectedIds)
                                    selectedIds - review.id
                                else
                                    selectedIds + review.id
                            },
                            selectionMode = selectionMode,
                            isSelected = review.id in selectedIds,
                            onToggle = {
                                selectedIds = if (review.id in selectedIds)
                                    selectedIds - review.id
                                else
                                    selectedIds + review.id
                                if (selectedIds.isEmpty() && selectionMode) {
                                    selectionMode = false
                                }
                            },
                            elevation = cardElevation,
                            cardBorder = cardBorder
                        )
                    }
                }
            }
        }

        // 批量删除确认
        if (showBatchDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showBatchDeleteConfirm = false },
                title = { Text("删除所选书评？") },
                text = { Text("将删除已选的 ${selectedIds.size} 条书评，不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        showBatchDeleteConfirm = false
                        scope.launch {
                            selectedIds.forEach { id ->
                                withContext(Dispatchers.IO) { viewModel.delete(id) }
                            }
                            context.toastOnUi("已删除 ${selectedIds.size} 条书评")
                            selectedIds = emptySet()
                            selectionMode = false
                            load()
                        }
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

// === 书评卡片 ===

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReviewCard(
    review: BookReview,
    onEditClick: (BookReview) -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggle: () -> Unit,
    elevation: androidx.compose.ui.unit.Dp,
    cardBorder: BorderStroke
) {
    val isEInk = AppConfig.isEInkMode
    val selectBorder = if (isEInk) BorderStroke(2.dp, Color.Black)
        else BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    val selectBg = if (isEInk) Color(0xFFE0E0E0)
        else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { mod ->
                if (isSelected)
                    mod.border(selectBorder, MaterialTheme.shapes.extraSmall)
                else
                    mod.border(cardBorder, MaterialTheme.shapes.extraSmall)
            }
            .combinedClickable(
                onClick = {
                    if (selectionMode) onToggle() else onEditClick(review)
                },
                onLongClick = {
                    if (!selectionMode) onLongClick()
                }
            ),
        shape = MaterialTheme.shapes.extraSmall,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) selectBg
                else pageCardElevatedContainerColor()
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 书名 + 星级
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = review.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = review.bookAuthor,
                        style = MaterialTheme.typography.bodySmall,
                        color = pageSecondaryTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 星级
                Row {
                    repeat(5) { i ->
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = if (i < review.rating)
                                pageAccentColor()
                            else
                                pageSecondaryTextColor().copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 正文预览
            if (review.content.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = review.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = pageSecondaryTextColor(),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (review.rating > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "仅评分",
                    style = MaterialTheme.typography.bodySmall,
                    color = pageSecondaryTextColor().copy(alpha = 0.6f)
                )
            }

            // 日期
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatDate(review.createTime),
                style = MaterialTheme.typography.labelSmall,
                color = pageSecondaryTextColor().copy(alpha = 0.6f)
            )
        }
    }
}

/** 时间戳转日期字符串 */
private fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
