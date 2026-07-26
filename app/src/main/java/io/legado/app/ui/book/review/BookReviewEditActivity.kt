package io.legado.app.ui.book.review

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.legado.app.help.config.AppConfig
import io.legado.app.data.repository.BookReviewRepository
import io.legado.app.model.BookReview
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageSecondaryTextColor
import io.legado.app.ui.theme.setLegadoContent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 书评编辑页
 *
 * 不区分查看/编辑——进入就是可编辑状态。
 * 新建时传入 bookUrl/bookName/bookAuthor（从详情页），编辑时传入 reviewId（从列表页）。
 * bookName/bookAuthor 创建后只读，rating 和 content 始终可修改。
 */
class BookReviewEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_BOOK_NAME = "bookName"
        const val EXTRA_BOOK_AUTHOR = "bookAuthor"
        const val EXTRA_REVIEW_ID = "reviewId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 读取 Intent 参数
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL) ?: ""
        val bookName = intent.getStringExtra(EXTRA_BOOK_NAME) ?: ""
        val bookAuthor = intent.getStringExtra(EXTRA_BOOK_AUTHOR) ?: ""
        val reviewId = intent.getStringExtra(EXTRA_REVIEW_ID)

        setLegadoContent {
            BookReviewEditScreen(
                bookUrl = bookUrl,
                bookName = bookName,
                bookAuthor = bookAuthor,
                existingReviewId = reviewId,
                onBackClick = { finish() },
                onSaved = {
                    setResult(RESULT_OK)
                    finish()
                }
            )
        }
    }
}

// === 编辑屏幕 ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookReviewEditScreen(
    bookUrl: String,
    bookName: String,
    bookAuthor: String,
    existingReviewId: String?,
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    viewModel: BookReviewViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 编辑状态
    var review by remember { mutableStateOf<BookReview?>(null) }
    var rating by remember { mutableIntStateOf(0) }
    var content by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var isNew by remember { mutableStateOf(true) }
    // 未保存退出确认
    var hasChanges by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    // 加载已有书评
    androidx.compose.runtime.LaunchedEffect(existingReviewId) {
        if (existingReviewId != null) {
            val loaded = withContext(Dispatchers.IO) {
                BookReviewRepository.getById(existingReviewId)
            }
            loaded?.let { r ->
                review = r
                rating = r.rating
                content = r.content
                isNew = false
            }
        }
    }

    // 待保存的数据
    val currentBookUrl = review?.bookUrl ?: bookUrl
    val currentBookName = review?.bookName ?: bookName
    val currentBookAuthor = review?.bookAuthor ?: bookAuthor

    val isEInk = AppConfig.isEInkMode
    val cardElevation = 0.dp
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

    // 未保存退出拦截
    if (showDiscardConfirm) {
        DiscardChangesDialog(
            onDiscard = {
                showDiscardConfirm = false
                onBackClick()
            },
            onStay = { showDiscardConfirm = false }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "写书评" else "我的书评") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) showDiscardConfirm = true else onBackClick()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (isSaving) return@TextButton
                            isSaving = true
                            val now = System.currentTimeMillis()
                            val entity = BookReview(
                                id = review?.id ?: UUID.randomUUID().toString(),
                                bookUrl = currentBookUrl,
                                bookName = currentBookName,
                                bookAuthor = currentBookAuthor,
                                rating = rating,
                                content = content.trim(),
                                createTime = review?.createTime ?: now,
                                updateTime = now
                            )
                            scope.launch {
                                withContext(Dispatchers.IO) { viewModel.save(entity) }
                                onSaved()
                            }
                        },
                        enabled = !isSaving
                    ) {
                        Text(if (isSaving) "保存中…" else "保存")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // 书籍信息卡片（只读）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .let { mod ->
                        if (cardBorder != null) mod.border(cardBorder, MaterialTheme.shapes.extraSmall)
                        else mod
                    },
                shape = MaterialTheme.shapes.extraSmall,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = cardElevation)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = currentBookName.ifBlank { "（未关联书籍）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (currentBookAuthor.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currentBookAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = pageSecondaryTextColor()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 评分
            Text(
                text = "评分",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                repeat(5) { i ->
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "${i + 1} 星",
                        tint = if (i < rating) pageAccentColor()
                        else pageSecondaryTextColor().copy(alpha = 0.3f),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable {
                                rating = if (rating == i + 1) i else i + 1
                                hasChanges = true
                            }
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 正文
            Text(
                text = "感想",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = {
                    content = it
                    hasChanges = true
                },
                placeholder = { Text("写下你的感想...（可选）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                minLines = if (isEInk) 6 else 8
            )

            // 删除按钮（仅已有书评时显示）
            if (!isNew) {
                Spacer(modifier = Modifier.height(16.dp))
                var showDeleteConfirm by remember { mutableStateOf(false) }
                Button(
                    onClick = { showDeleteConfirm = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("删除这条书评")
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (showDeleteConfirm) {
                    DeleteConfirmDialog(
                        onConfirm = {
                            showDeleteConfirm = false
                            scope.launch {
                                review?.let {
                                    withContext(Dispatchers.IO) { viewModel.delete(it.id) }
                                }
                                onSaved()
                            }
                        },
                        onDismiss = { showDeleteConfirm = false }
                    )
                }
            }
        }
    }
}

// === 放弃修改确认 ===

@Composable
private fun DiscardChangesDialog(
    onDiscard: () -> Unit,
    onStay: () -> Unit
) {
    // Phase 1: 用 AlertDialog
    // 如果 Flyme 12.6 确认无问题，可改为 Toast + 二次返回
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onStay,
        title = { Text("放弃修改？") },
        text = { Text("当前内容尚未保存，放弃后不会保留。") },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text("放弃") }
        },
        dismissButton = {
            TextButton(onClick = onStay) { Text("继续编辑") }
        }
    )
}

// === 删除确认 ===

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    // Phase 1: 用 AlertDialog
    // 若 Flyme 12.6 实测后销毁，改为 Toast + 撤销模式
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除书评？") },
        text = { Text("删除后不可恢复。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
