package io.legado.app.ui.book.bookmark

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import splitties.init.appCtx
import kotlin.math.min

class BookmarkDecoration(val adapter: BookmarkAdapter) : RecyclerView.ItemDecoration() {

    private val headerLeft = 16f.dpToPx()
    private val headerHeight = 32f.dpToPx()
    private val arrowSize = 10f.dpToPx()
    private val arrowPadding = 8f.dpToPx()

    private val headerPaint = Paint().apply {
        color = appCtx.backgroundColor
    }
    private val textPaint = TextPaint().apply {
        textSize = 16f.spToPx()
        color = appCtx.accentColor
        isAntiAlias = true
    }
    private val arrowPaint = Paint().apply {
        color = appCtx.accentColor
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    private val textRect = Rect()
    private val arrowPath = Path()

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        for (i in 0 until count) {
            val view = parent.getChildAt(i)
            val position = parent.getChildLayoutPosition(view)
            val isHeader = adapter.isItemHeader(position)
            if (isHeader) {
                val isCollapsed = adapter.isGroupCollapsed(position)
                val headerTop = view.top - headerHeight
                c.drawRect(
                    0f,
                    headerTop,
                    parent.width.toFloat(),
                    view.top.toFloat(),
                    headerPaint
                )
                drawArrow(c, headerTop, isCollapsed)
                val headerText = adapter.getHeaderText(position)
                textPaint.getTextBounds(headerText, 0, headerText.length, textRect)
                val textX = headerLeft + arrowSize + arrowPadding
                val textY = headerTop + headerHeight / 2 + (textRect.height() / 2 - textRect.bottom)
                c.drawText(headerText, textX, textY, textPaint)
            }
        }
    }

    private fun drawArrow(c: Canvas, top: Float, isCollapsed: Boolean) {
        val centerX = headerLeft + arrowSize / 2
        val centerY = top + headerHeight / 2
        
        arrowPath.reset()
        if (isCollapsed) {
            arrowPath.moveTo(centerX - arrowSize / 2, centerY - arrowSize / 2)
            arrowPath.lineTo(centerX + arrowSize / 2, centerY)
            arrowPath.lineTo(centerX - arrowSize / 2, centerY + arrowSize / 2)
        } else {
            arrowPath.moveTo(centerX - arrowSize / 2, centerY - arrowSize / 2)
            arrowPath.lineTo(centerX + arrowSize / 2, centerY - arrowSize / 2)
            arrowPath.lineTo(centerX, centerY + arrowSize / 2)
        }
        arrowPath.close()
        c.drawPath(arrowPath, arrowPaint)
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val position = (parent.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return
        val view = parent.findViewHolderForAdapterPosition(position)?.itemView ?: return
        val isHeader = adapter.isItemHeader(position + 1)
        val headerText = adapter.getHeaderText(position)
        val isCollapsed = adapter.isGroupCollapsed(position)
        textPaint.getTextBounds(headerText, 0, headerText.length, textRect)
        val textX = headerLeft + arrowSize + arrowPadding
        
        if (isHeader) {
            val bottom = min(headerHeight.toInt(), view.bottom)
            val headerTop = view.top - headerHeight
            c.drawRect(
                0f,
                headerTop,
                parent.width.toFloat(),
                bottom.toFloat(),
                headerPaint
            )
            drawArrow(c, headerTop, isCollapsed)
            val textY = headerHeight / 2 + (textRect.height() / 2 - textRect.bottom) - (headerHeight - bottom)
            c.drawText(headerText, textX, textY, textPaint)
        } else {
            c.drawRect(
                0f,
                0f,
                parent.width.toFloat(),
                headerHeight,
                headerPaint
            )
            drawArrow(c, 0f, isCollapsed)
            val textY = headerHeight / 2 + (textRect.height() / 2 - textRect.bottom)
            c.drawText(headerText, textX, textY, textPaint)
        }
        c.save()
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildLayoutPosition(view)
        val isHeader = adapter.isItemHeader(position)
        if (isHeader) {
            outRect.top = headerHeight.toInt()
        }
    }

    fun getHeaderPositionForTouch(rv: RecyclerView, e: MotionEvent): Int {
        val x = e.x
        val y = e.y
        
        val firstVisiblePos = (rv.layoutManager as? LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: return -1
        
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val position = rv.getChildLayoutPosition(child)
            
            if (adapter.isItemHeader(position)) {
                val headerTop = child.top - headerHeight
                val headerBottom = child.top.toFloat()
                val headerRect = RectF(0f, headerTop, rv.width.toFloat(), headerBottom)
                
                if (headerRect.contains(x, y)) {
                    return position
                }
            }
        }
        
        val firstChild = rv.findViewHolderForAdapterPosition(firstVisiblePos)?.itemView ?: return -1
        val headerRect = RectF(0f, 0f, rv.width.toFloat(), headerHeight)
        if (headerRect.contains(x, y)) {
            return firstVisiblePos
        }
        
        return -1
    }

}