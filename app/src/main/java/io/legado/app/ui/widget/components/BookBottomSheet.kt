package io.legado.app.ui.widget.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.splitNotBlank

/**
 * 书籍底部弹窗
 *
 * 长按书籍时从底部弹出，显示书籍信息和操作按钮。
 * 可用于首页、发现界面、搜索界面等。
 * 固定显示半屏高度，内容过多时可滚动，适配主题颜色。
 *
 * @param show 是否显示弹窗
 * @param book 书籍信息
 * @param shelfState 书籍在书架中的状态
 * @param onDismiss 关闭弹窗的回调
 * @param onAddToShelf 加入书架的回调
 * @param onShowInfo 查看书籍详情的回调
 * @param isRssArticle 是否为 RSS 订阅源文章（true 时显示"加入收藏"/"查看内容"）
 * @param onAddToFavorites 加入 RSS 收藏的回调
 * @param onViewContent 查看 RSS 内容的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookBottomSheet(
    show: Boolean,
    book: SearchBook?,
    shelfState: BookShelfState,
    onDismiss: () -> Unit,
    onAddToShelf: (SearchBook) -> Unit,
    onShowInfo: (SearchBook) -> Unit,
    isRssArticle: Boolean = false,
    onAddToFavorites: ((SearchBook) -> Unit)? = null,
    onViewContent: ((SearchBook) -> Unit)? = null,
) {
    // 使用 skipPartiallyExpanded = true，确保弹窗直接展开到最大高度
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    // 预先获取字符串资源，避免在onClick中调用stringResource
    val addedToBookshelfMsg = stringResource(R.string.added_to_bookshelf, book?.name ?: "")
    val alreadyInBookshelfText = stringResource(R.string.already_in_bookshelf)
    val addToBookshelfText = stringResource(R.string.add_to_bookshelf)
    val addToFavoritesText = stringResource(R.string.add_to_favorites)
    val viewDetailsText = stringResource(R.string.view_details)
    val viewContentText = stringResource(R.string.view_content)

    if (show && book != null) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            // 整个内容区域可滚动，按钮在滚动内容内部
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .verticalScroll(scrollState)
            ) {
                // 书籍信息区域
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 封面图片
                    AndroidView(
                        modifier = Modifier
                            .width(90.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        factory = { ctx ->
                            CoverImageView(ctx).apply {
                                load(book, AppConfig.loadCoverOnlyWifi)
                            }
                        },
                        update = { view ->
                            view.load(book, AppConfig.loadCoverOnlyWifi)
                        }
                    )

                    // 书籍基本信息
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.Top),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 书名
                        Text(
                            text = book.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // 作者
                        if (book.author.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.author_show, book.author),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // 书架状态提示
                        if (shelfState == BookShelfState.IN_SHELF) {
                            Text(
                                text = "✓ ${stringResource(R.string.already_in_bookshelf)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        } else if (shelfState == BookShelfState.SAME_NAME_AUTHOR) {
                            Text(
                                text = "! ${stringResource(R.string.same_name_book_in_shelf)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 分隔线
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(12.dp))

                // 详细信息区域
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 分类（带外框）
                    val kindText = book.kind
                    if (!kindText.isNullOrEmpty()) {
                        CategoryRow(label = stringResource(R.string.category), value = kindText)
                    }

                    // 字数
                    val wordCountText = book.wordCount
                    if (!wordCountText.isNullOrEmpty()) {
                        InfoRow(label = stringResource(R.string.words), value = wordCountText)
                    }

                    // 最新章节
                    val latestChapterText = book.latestChapterTitle
                    if (!latestChapterText.isNullOrEmpty()) {
                        InfoRow(label = stringResource(R.string.latest_chapter), value = latestChapterText)
                    }

                    // 来源
                    if (book.originName.isNotBlank()) {
                        InfoRow(
                            label = if (isRssArticle) stringResource(R.string.rss_source) else stringResource(R.string.book_source),
                            value = book.originName
                        )
                    }
                }

                // 简介
                val introText = book.intro
                if (!introText.isNullOrEmpty()) {
                    val trimmedIntro = introText.trim()
                    if (trimmedIntro.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = stringResource(R.string.intro),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = trimmedIntro,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 操作按钮区域（在滚动内容内部，确保用户能立即看到）
                if (isRssArticle) {
                    // RSS 文章专用按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 加入收藏按钮
                        TextButton(
                            onClick = {
                                onAddToFavorites?.invoke(book)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = addToFavoritesText,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = addToFavoritesText,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 查看内容按钮
                        TextButton(
                            onClick = {
                                onViewContent?.invoke(book)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = viewContentText,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewContentText,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    // 书籍操作按钮（原有逻辑）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 加入书架按钮
                        TextButton(
                            onClick = {
                                onAddToShelf(book)
                                Toast.makeText(context, addedToBookshelfMsg, Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = addToBookshelfText,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (shelfState == BookShelfState.IN_SHELF) alreadyInBookshelfText 
                                       else addToBookshelfText,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 查看详情按钮
                        TextButton(
                            onClick = {
                                onShowInfo(book)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = viewDetailsText,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewDetailsText,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 信息行组件
 * 显示标签和值，适配主题颜色
 */
@Composable
private fun InfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = Int.MAX_VALUE,
            softWrap = true
        )
    }
}

/**
 * 分类行组件（带外框）
 * 显示分类标签和值，每个分类值单独有外框样式
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryRow(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 标签
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
        // 分类值（每个单独有外框）
        // 使用 splitNotBlank 方法分隔分类值（与书架标签一致，使用逗号和换行符）
        val categories = value.splitNotBlank(",", "\n")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (category in categories) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    maxLines = Int.MAX_VALUE,
                    softWrap = true
                )
            }
        }
    }
}