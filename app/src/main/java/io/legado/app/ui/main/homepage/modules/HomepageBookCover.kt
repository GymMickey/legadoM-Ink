package io.legado.app.ui.main.homepage.modules

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import splitties.init.appCtx

/**
 * 首页书籍封面组件
 *
 * 受封面设置（CoverConfig）控制：
 * - 当 useDefaultCover 开启时，强制使用默认封面
 * - 当无封面 URL 时，回退到默认封面
 * - 根据封面设置决定是否在封面上绘制书名和作者
 * - 当启用封面图集时，根据 identity 参数随机选择封面图片
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomepageBookCover(
    name: String,
    author: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
    identity: String? = null,
    origin: String? = null,
) {
    val galleryDefaultCover = BookCover.getGalleryDefaultCover(identity)
    val useDefaultCover = AppConfig.useDefaultCover
    val displayCover = if (useDefaultCover) null else (galleryDefaultCover ?: coverUrl)
    var coverLoadFailed by remember(displayCover) { mutableStateOf(false) }
    val shouldDrawName = (displayCover == null || coverLoadFailed) &&
            BookCover.drawBookName &&
            name.isNotBlank()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (displayCover != null) {
            GlideImage(
                model = displayCover,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            ) {
                var options = RequestOptions()
                if (origin != null) {
                    options = options.set(OkHttpModelLoader.sourceOriginOption, origin)
                }
                it.apply(options)
                    .placeholder(BookCover.defaultDrawable)
                    .error(BookCover.defaultDrawable)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            coverLoadFailed = true
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            model: Any,
                            target: Target<Drawable>?,
                            dataSource: DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            coverLoadFailed = false
                            return false
                        }
                    })
            }
        } else {
            GlideImage(
                model = BookCover.defaultDrawable,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (shouldDrawName) {
            CoverTextOverlay(
                name = name,
                author = author,
                drawAuthor = BookCover.drawBookAuthor,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 封面书名/作者文字叠层
 *
 * 复用 CoverImageView 的竖排绘制逻辑：
 * - 书名从左上角 20% 位置开始竖排，带描边
 * - 作者从右下角 80% 位置开始竖排
 */
@Composable
private fun CoverTextOverlay(
    name: String,
    author: String,
    drawAuthor: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = remember { ThemeStore.backgroundColor(appCtx) }
    val accentColor = remember { appCtx.accentColor }

    Canvas(modifier = modifier) {
        val viewWidth = size.width
        val viewHeight = size.height
        val canvas = drawContext.canvas.nativeCanvas

        val namePaint = TextPaint().apply {
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            textSize = viewWidth / 7f
            strokeWidth = textSize / 6f
        }

        val nameChars = name.toStringArray()
        var startX = viewWidth * 0.2f
        var startY = viewHeight * 0.2f
        var line = 0
        nameChars.forEachIndexed { index, char ->
            namePaint.color = bgColor
            namePaint.style = Paint.Style.STROKE
            canvas.drawText(char, startX, startY, namePaint)
            namePaint.color = accentColor
            namePaint.style = Paint.Style.FILL
            canvas.drawText(char, startX, startY, namePaint)
            startY += namePaint.textHeight
            if (startY > viewHeight * 0.9f) {
                if ((nameChars.size - index - 1) == 1) {
                    startY -= namePaint.textHeight / 5f
                    namePaint.textSize = viewWidth / 9f
                    return@forEachIndexed
                }
                startX += namePaint.textSize
                line++
                namePaint.textSize = viewWidth / 10f
                startY = viewHeight * 0.2f + namePaint.textHeight * line
            } else if (startY > viewHeight * 0.8f && (nameChars.size - index - 1) > 2) {
                startX += namePaint.textSize
                line++
                namePaint.textSize = viewWidth / 10f
                startY = viewHeight * 0.2f + namePaint.textHeight * line
            }
        }

        if (drawAuthor && author.isNotBlank()) {
            val authorPaint = TextPaint(namePaint).apply {
                typeface = Typeface.DEFAULT
                textSize = viewWidth / 10f
                strokeWidth = textSize / 5f
            }
            val authorChars = author.toStringArray()
            startX = viewWidth * 0.8f
            startY = viewHeight * 0.95f - authorChars.size * authorPaint.textHeight
            startY = maxOf(startY, viewHeight * 0.3f)
            authorChars.forEach {
                authorPaint.color = bgColor
                authorPaint.style = Paint.Style.STROKE
                canvas.drawText(it, startX, startY, authorPaint)
                authorPaint.color = accentColor
                authorPaint.style = Paint.Style.FILL
                canvas.drawText(it, startX, startY, authorPaint)
                startY += authorPaint.textHeight
                if (startY > viewHeight * 0.95f) return@forEach
            }
        }
    }
}
