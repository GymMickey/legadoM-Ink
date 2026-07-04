/**
 * 首页网格排行榜模块
 *
 * 文件作用：提供首页排行榜模块的 UI 组件实现。
 * 主要功能：
 * - 以分页卡片形式展示排行榜书籍，每页 4 行
 * - 每行包含封面、排名编号、书名和分类/作者信息
 * - 前 3 名使用特殊样式（主色 + 斜体）
 * - 支持点击和长按交互
 * - 样式对齐 MD3-main 分支设计规范
 * - 支持新版和经典两种书架状态样式
 */
package io.legado.app.ui.main.homepage.modules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.homepage.HomepageBookItemUi
import io.legado.app.ui.theme.pageAccentColor
import io.legado.app.ui.widget.components.card.GlassCard

/** 每页显示的行数 */
private const val ROWS_PER_PAGE = 4
/** 最多显示的书籍数量 */
private const val MAX_COUNT = 20
/** 占位项高度 */
private val PLACEHOLDER_HEIGHT = 76.dp

/**
 * 网格排行榜模块
 *
 * 以分页卡片形式显示排行榜书籍，每页 4 行。
 * 使用 HorizontalPager 实现左右翻页浏览，样式对齐 MD3-main 分支。
 *
 * @param books 书籍列表数据
 * @param onClick 点击书籍回调
 * @param onLongClick 长按书籍回调
 * @param modifier 布局修饰符
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridRankingModule(
    books: List<HomepageBookItemUi>,
    onClick: (HomepageBookItemUi) -> Unit,
    onLongClick: (HomepageBookItemUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) return
    // 限制最多显示 20 个
    val limitedBooks = books.take(MAX_COUNT)
    val pages = limitedBooks.chunked(ROWS_PER_PAGE)
    val pagerState = rememberPagerState(pageCount = { pages.size })

    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(end = 100.dp),
        pageSpacing = 12.dp,
        modifier = modifier.fillMaxWidth()
    ) { pageIndex ->
        // 防止 books 变化导致 pages 缩减时越界崩溃
        val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 20.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 12.dp)
            ) {
                for ((rowIndex, item) in page.withIndex()) {
                    val itemIndex = pageIndex * ROWS_PER_PAGE + rowIndex
                    GridRankingItem(
                        rank = itemIndex + 1,
                        item = item,
                        onClick = { onClick(item) },
                        onLongClick = { onLongClick(item) }
                    )
                }
                // 占位逻辑：不足一页时用空占位填充，保持每页高度一致
                repeat(ROWS_PER_PAGE - page.size) {
                    Spacer(modifier = Modifier.height(PLACEHOLDER_HEIGHT))
                }
            }
        }
    }
}

/**
 * 网格排行榜单个项目组件
 *
 * 以横向行布局展示单本排行榜书籍，包含封面、排名编号和文字信息。
 * 前 3 名使用主色和斜体样式突出显示。
 *
 * @param rank 排名序号（从 1 开始）
 * @param item 书籍 UI 数据
 * @param onClick 点击回调
 * @param onLongClick 长按回调
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridRankingItem(
    rank: Int,
    item: HomepageBookItemUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val book = item.book
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. 封面（带书架状态图标）
        Box {
            HomepageBookCover(
                name = book.name,
                author = book.author,
                coverUrl = book.coverUrl,
                modifier = Modifier
                    .width(48.dp)
                    .aspectRatio(5f / 7f),
                cornerRadius = 4.dp,
                identity = book.bookUrl
            )
            // 新版样式：显示图标
            if (AppConfig.bookshelfIconStyle == 0) {
                val shelfIcon = when (item.shelfState) {
                    BookShelfState.IN_SHELF -> Icons.Default.Check
                    BookShelfState.SAME_NAME_AUTHOR -> Icons.Default.Shuffle
                    else -> null
                }
                if (shelfIcon != null) {
                    // 亮色主题：白色背景+黑色图标；暗色主题：黑色背景+白色图标
                    val isLight = !AppConfig.isNightTheme
                    val bgColor = if (isLight) Color.White else Color.Black
                    val iconColor = if (isLight) Color.Black else Color.White
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(bgColor)
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = shelfIcon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.height(14.dp)
                        )
                    }
                }
            }
        }

        // 2. 排名编号，前3名使用主色和斜体样式
        Text(
            text = "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            fontStyle = if (rank <= 3) FontStyle.Italic else FontStyle.Normal,
            color = if (rank <= 3) pageAccentColor() else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp)
        )

        // 3. 文字信息：书名 + 分类/作者
        Column(
            modifier = Modifier
                .padding(start = 4.dp)
                .weight(1f)
        ) {
            // 书名区域
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 经典样式：显示小绿点
                if (AppConfig.bookshelfIconStyle == 1) {
                    if (item.shelfState == BookShelfState.IN_SHELF || item.shelfState == BookShelfState.SAME_NAME_AUTHOR) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                }
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            val subTitle = buildString {
                append(book.kind?.split(",")?.firstOrNull() ?: "")
                if (book.author.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append(book.author)
                }
            }
            if (subTitle.isNotBlank()) {
                Text(
                    text = subTitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
