package io.legado.app.ui.book.read.config

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import io.legado.app.utils.dpToPx

/**
 * 背景颜色+下划线 Span，用于高亮规则匹配区域
 * @param textColor 文字颜色
 * @param bgColor 背景颜色
 * @param underlineMode 下划线样式：0=无, 1=实线, 2=虚线, 3=波浪, 4=双线
 * @param underlineColor 下划线颜色
 * @param underlineWidth 下划线粗细(dp)
 * @param underlineSvgPath SVG路径（用于自定义下划线）
 * @param underlineOffset 下划线与文字的距离(dp)
 */
class BgColorSpan(
    private val textColor: Int,
    private val bgColor: Int,
    private val underlineMode: Int = 0,
    private val underlineColor: Int = 0,
    private val underlineWidth: Float = 1f,
    private val underlineSvgPath: String = "",
    private val underlineOffset: Float = 6f,
) : ReplacementSpan() {

    private val offsetPx = underlineOffset.toInt().dpToPx()

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val metrics = paint.fontMetricsInt
            fm.top = metrics.top
            fm.ascent = metrics.ascent
            fm.descent = metrics.descent + if (underlineMode != 0) offsetPx else 0
            fm.bottom = metrics.bottom + if (underlineMode != 0) offsetPx else 0
        }
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)
        
        // 绘制背景颜色
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = bgColor
            isAntiAlias = true
        }
        canvas.drawRect(x, top.toFloat(), x + width, bottom.toFloat(), bgPaint)

        // 绘制文字
        paint.color = textColor
        paint.shader = null
        canvas.drawText(text, start, end, x, y.toFloat(), paint)

        // 绘制下划线
        if (underlineMode != 0) {
            drawUnderline(canvas, x, x + width, y + offsetPx, paint)
        }
    }

    private fun drawUnderline(canvas: Canvas, startX: Float, endX: Float, lineY: Int, paint: Paint) {
        val ulPaint = Paint(paint).apply {
            color = underlineColor
            style = Paint.Style.STROKE
            strokeWidth = underlineWidth.dpToPx()
            isAntiAlias = true
        }
        when (underlineMode) {
            1 -> canvas.drawLine(startX, lineY.toFloat(), endX, lineY.toFloat(), ulPaint)
            2 -> {
                ulPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                canvas.drawLine(startX, lineY.toFloat(), endX, lineY.toFloat(), ulPaint)
            }
            3 -> {
                val path = android.graphics.Path()
                val waveAmplitude = 3.dpToPx().toFloat()
                val waveLength = 12.dpToPx().toFloat()
                path.moveTo(startX, lineY.toFloat())
                var currentX = startX
                val endY = lineY.toFloat()
                while (currentX < endX) {
                    val nextX = (currentX + waveLength).coerceAtMost(endX)
                    val midX = (currentX + nextX) / 2
                    path.quadTo(midX, endY - waveAmplitude, nextX, endY)
                    currentX = nextX
                    if (currentX < endX) {
                        val nextX2 = (currentX + waveLength).coerceAtMost(endX)
                        val midX2 = (currentX + nextX2) / 2
                        path.quadTo(midX2, endY + waveAmplitude, nextX2, endY)
                        currentX = nextX2
                    }
                }
                canvas.drawPath(path, ulPaint)
            }
            4 -> {
                val lineGap = 3.dpToPx()
                val line2Y = lineY + lineGap + underlineWidth.dpToPx()
                canvas.drawLine(startX, lineY.toFloat(), endX, lineY.toFloat(), ulPaint)
                canvas.drawLine(startX, line2Y.toFloat(), endX, line2Y.toFloat(), ulPaint)
            }
        }
    }
}