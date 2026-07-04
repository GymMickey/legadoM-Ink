package io.legado.app.ui.main.homepage.modules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.homepage.HomepageBookItemUi

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BannerModule(
    books: List<HomepageBookItemUi>,
    onClick: (SearchBook, String?) -> Unit,
    onLongClick: ((SearchBook, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(books, key = { index, item -> "${item.book.bookUrl}:$index" }) { index, item ->
            val book = item.book
            Box {
                HomepageBookCover(
                    name = book.name,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    modifier = Modifier
                        .width(96.dp)
                        .aspectRatio(5f / 7f)
                        .combinedClickable(
                            onClick = { onClick(book, null) },
                            onLongClick = onLongClick?.let { cb -> { cb(book, null) } }
                        ),
                    cornerRadius = 12.dp,
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
        }
    }
}
