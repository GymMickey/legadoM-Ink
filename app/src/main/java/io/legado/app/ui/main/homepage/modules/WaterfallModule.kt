/**
 * 首页瀑布流模块组件
 *
 * 文件作用：提供首页瀑布流布局中单个书籍卡片的 UI 组件实现。
 * 主要功能：
 * - 以瀑布流形式展示书籍封面、书名和作者信息
 * - 支持点击和长按交互
 * - 显示书架状态角标（已在书架 / 同名作者）
 * - 支持新版和经典两种样式切换
 *
 * 瀑布流容器由 HomepageScreen 中的 LazyVerticalStaggeredGrid 管理
 */
package io.legado.app.ui.main.homepage.modules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.homepage.HomepageBookItemUi
import io.legado.app.ui.widget.components.card.GlassCard

/**
 * 瀑布流单个项目组件
 *
 * 用于在瀑布流布局中展示单本书籍信息，包含封面、书名、作者及书架状态角标。
 *
 * @param book 首页书籍 UI 数据，包含书籍信息和书架状态
 * @param onClick 点击回调
 * @param onLongClick 长按回调
 * @param modifier 布局修饰符
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaterfallItem(
    book: HomepageBookItemUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val searchBook = book.book
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 8.dp
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(4.dp)
        ) {
            Box {
                HomepageBookCover(
                    name = searchBook.name,
                    author = searchBook.author,
                    coverUrl = searchBook.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    cornerRadius = 4.dp,
                    identity = buildString {
                        append(searchBook.bookUrl)
                        append('|')
                        append(searchBook.origin)
                        append('|')
                        append(searchBook.name)
                        append('|')
                        append(searchBook.author)
                    }
                )
                // 新版样式：显示图标
                if (AppConfig.bookshelfIconStyle == 0) {
                    val shelfIcon = when (book.shelfState) {
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
                                .padding(4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(bgColor)
                                .padding(horizontal = 2.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = shelfIcon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            // 书名区域
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 经典样式：显示小绿点
                if (AppConfig.bookshelfIconStyle == 1) {
                    if (book.shelfState == BookShelfState.IN_SHELF || book.shelfState == BookShelfState.SAME_NAME_AUTHOR) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                }
                // 书名，最多两行显示，超出部分省略
                Text(
                    text = searchBook.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            // 作者，单行显示，超出部分省略
            Text(
                text = searchBook.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            // 简介，最多两行显示，超出部分省略
            val intro = searchBook.intro?.takeIf { it.isNotBlank() }?.replace("\\s+".toRegex(), " ")
            if (!intro.isNullOrBlank()) {
                Text(
                    text = intro,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
