package io.legado.app.ui.main.homepage.modules

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.homepage.HomepageBookItemUi

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridModule(
    books: List<HomepageBookItemUi>,
    onClick: (SearchBook, String?) -> Unit,
    onLongClick: ((SearchBook, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    maxRows: Int? = null,
) {
    if (books.isEmpty()) return
    var rows = books.chunked(columns)
    if (maxRows != null) rows = rows.take(maxRows)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (item in row) {
                    val book = item.book
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .combinedClickable(
                                onClick = { onClick(book, null) },
                                onLongClick = onLongClick?.let { cb -> { cb(book, null) } }
                            )
                    ) {
                        Box {
                            HomepageBookCover(
                                name = book.name,
                                author = book.author,
                                coverUrl = book.coverUrl,
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                minLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
