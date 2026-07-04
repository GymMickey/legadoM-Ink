package io.legado.app.ui.widget.recycler.scroller

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import kotlin.math.max
import kotlin.math.min

class FastScrollerBuilder(private val recyclerView: RecyclerView) {
    
    private var thumbDrawable: Drawable? = null
    private var trackDrawable: Drawable? = null
    private var thumbColor: Int = 0
    private var trackColor: Int = 0
    
    fun setThumbDrawable(drawable: Drawable): FastScrollerBuilder {
        thumbDrawable = drawable
        return this
    }
    
    fun setTrackDrawable(drawable: Drawable): FastScrollerBuilder {
        trackDrawable = drawable
        return this
    }
    
    fun setThumbColor(@ColorInt color: Int): FastScrollerBuilder {
        thumbColor = color
        return this
    }
    
    fun setTrackColor(@ColorInt color: Int): FastScrollerBuilder {
        trackColor = color
        return this
    }
    
    fun build(): FastScrollerView {
        val fastScroller = FastScrollerView(recyclerView.context)
        thumbDrawable?.let { fastScroller.setThumbDrawable(it) }
        trackDrawable?.let { fastScroller.setTrackDrawable(it) }
        if (thumbColor != 0) fastScroller.setThumbColor(thumbColor)
        if (trackColor != 0) fastScroller.setTrackColor(trackColor)
        fastScroller.attachToRecyclerView(recyclerView)
        return fastScroller
    }
}

class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var thumbDrawable: Drawable? = null
    private var trackDrawable: Drawable? = null
    private var thumbColor: Int = ColorUtils.adjustAlpha(context.accentColor, 0.5f)
    private var trackColor: Int = 0x26000000
    
    private var recyclerView: RecyclerView? = null
    
    private var thumbHeight: Int = 0
    private var thumbTop: Int = 0
    private var thumbBottom: Int = 0
    private val thumbWidth: Int = dpToPx(6)
    private val trackWidth: Int = dpToPx(1)
    
    private var isDragging: Boolean = false
    private var lastTouchY: Float = 0f
    
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            updateThumb()
        }
    }

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateThumb()
        }
    }

    fun detachFromRecyclerView() {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = null
    }

    fun setThumbDrawable(drawable: Drawable) {
        thumbDrawable = drawable
        invalidate()
    }

    fun setTrackDrawable(drawable: Drawable) {
        trackDrawable = drawable
        invalidate()
    }

    fun setThumbColor(@ColorInt color: Int) {
        thumbColor = color
        paint.color = color
        invalidate()
    }

    fun setTrackColor(@ColorInt color: Int) {
        trackColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (thumbHeight <= 0) return
        
        val left = width - paddingRight - thumbWidth
        val right = width - paddingRight
        
        trackDrawable?.let {
            it.setBounds(left + (thumbWidth - trackWidth) / 2, paddingTop, 
                         left + (thumbWidth - trackWidth) / 2 + trackWidth, height - paddingBottom)
            it.draw(canvas)
        } ?: run {
            paint.color = trackColor
            paint.style = Paint.Style.FILL
            val trackLeft = left + (thumbWidth - trackWidth) / 2f
            canvas.drawRect(trackLeft, paddingTop.toFloat(), 
                           trackLeft + trackWidth, (height - paddingBottom).toFloat(), paint)
        }
        
        thumbDrawable?.let {
            it.setBounds(left, thumbTop, right, thumbBottom)
            it.draw(canvas)
        } ?: run {
            paint.color = thumbColor
            paint.style = Paint.Style.FILL
            val radius = thumbWidth / 2f
            canvas.drawRoundRect(left.toFloat(), thumbTop.toFloat(), 
                                right.toFloat(), thumbBottom.toFloat(), radius, radius, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isInThumbArea(event.x, event.y)) {
                    isDragging = true
                    lastTouchY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaY = event.y - lastTouchY
                    lastTouchY = event.y
                    scrollByDelta(deltaY)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInThumbArea(x: Float, y: Float): Boolean {
        val left = width - paddingRight - thumbWidth * 2
        return x >= left && y >= thumbTop - thumbHeight / 2 && y <= thumbBottom + thumbHeight / 2
    }

    private fun scrollByDelta(deltaY: Float) {
        val rv = recyclerView ?: return
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        if (range <= 0 || extent <= 0) return

        val viewHeight = height - paddingTop - paddingBottom
        val scrollRange = range - extent
        if (scrollRange <= 0 || viewHeight <= 0) return

        val scrollDelta = (deltaY / viewHeight * scrollRange).toInt()
        val currentOffset = rv.computeVerticalScrollOffset()
        val targetOffset = (currentOffset + scrollDelta).coerceIn(0, scrollRange)

        scrollToPosition(targetOffset, scrollRange)
    }

    private fun scrollToPosition(targetOffset: Int, scrollRange: Int) {
        val rv = recyclerView ?: return
        val adapter = rv.adapter ?: return
        val layoutManager = rv.layoutManager ?: return
        
        val itemCount = adapter.itemCount
        if (itemCount == 0) return

        val proportion = if (scrollRange > 0) targetOffset.toFloat() / scrollRange else 0f
        val targetPosition = (proportion * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)

        if (layoutManager is LinearLayoutManager) {
            layoutManager.scrollToPositionWithOffset(targetPosition, 0)
        } else {
            layoutManager.scrollToPosition(targetPosition)
        }
    }

    private fun updateThumb() {
        val rv = recyclerView ?: return
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val offset = rv.computeVerticalScrollOffset()

        if (range <= 0 || extent <= 0 || extent >= range) {
            visibility = GONE
            return
        }

        val viewHeight = height - paddingTop - paddingBottom
        if (viewHeight <= 0) {
            return
        }

        visibility = VISIBLE

        val minThumbHeight = dpToPx(24)
        val maxThumbHeight = max(minThumbHeight, viewHeight / 3)
        val calculatedHeight = (extent * viewHeight / range.toFloat()).toInt()
        thumbHeight = calculatedHeight.coerceIn(minThumbHeight, maxThumbHeight)
        
        val scrollRange = range - extent
        thumbTop = if (scrollRange > 0 && viewHeight > thumbHeight) {
            paddingTop + (offset * (viewHeight - thumbHeight) / scrollRange.toFloat()).toInt()
        } else {
            paddingTop
        }
        thumbBottom = min(thumbTop + thumbHeight, height - paddingBottom)

        invalidate()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
