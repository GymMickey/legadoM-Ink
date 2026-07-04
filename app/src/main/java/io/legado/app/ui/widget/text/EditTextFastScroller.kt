package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.core.view.ViewCompat
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getCompatColor
import kotlin.math.roundToInt

/**
 * A draggable vertical scrollbar for large editable text fields.
 *
 * RecyclerView fast scrollers in this project rely on adapter positions, while
 * these editor dialogs scroll a single TextView/EditText. This view maps thumb
 * position directly to the target view's scrollY.
 */
class EditTextFastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @IdRes
    private var targetViewId: Int = View.NO_ID

    private var targetView: TextView? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val thumbWidth = dpToPx(6)
    private val trackWidth = dpToPx(1)
    private val minThumbHeight = dpToPx(24)

    private var thumbTop = 0
    private var thumbBottom = 0
    private var thumbHeight = 0
    private var isDragging = false

    private val thumbColor = ColorUtils.adjustAlpha(context.accentColor, 0.62f)
    private val thumbPressedColor = ColorUtils.adjustAlpha(context.accentColor, 0.82f)
    private val trackColor = context.getCompatColor(R.color.transparent20)

    private val targetLayoutListener =
        OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateThumb() }

    private val selfLayoutListener =
        OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateThumb() }

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        updateThumb()
    }

    private val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            updateThumb()
            return true
        }
    }

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            post { updateThumb() }
        }
    }

    init {
        if (attrs != null) {
            val typedArray = context.obtainStyledAttributes(attrs, R.styleable.EditTextFastScroller)
            try {
                targetViewId = typedArray.getResourceId(
                    R.styleable.EditTextFastScroller_targetViewId,
                    View.NO_ID
                )
            } finally {
                typedArray.recycle()
            }
        }
        visibility = INVISIBLE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        addOnLayoutChangeListener(selfLayoutListener)
        if (targetViewId != View.NO_ID) {
            val parentView = parent as? ViewGroup
            attachToTextView(parentView?.findViewById(targetViewId))
        }
    }

    override fun onDetachedFromWindow() {
        detachTarget()
        removeOnLayoutChangeListener(selfLayoutListener)
        super.onDetachedFromWindow()
    }

    fun attachToTextView(textView: TextView?) {
        if (targetView === textView) return
        detachTarget()
        targetView = textView
        textView?.let {
            it.addOnLayoutChangeListener(targetLayoutListener)
            it.viewTreeObserver.addOnScrollChangedListener(scrollListener)
            it.viewTreeObserver.addOnPreDrawListener(preDrawListener)
            it.addTextChangedListener(textWatcher)
            post { updateThumb() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbHeight <= 0) return

        val centerX = width / 2f
        val trackLeft = centerX - trackWidth / 2f
        val thumbLeft = centerX - thumbWidth / 2f
        val thumbRight = centerX + thumbWidth / 2f
        val radius = thumbWidth / 2f

        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(
            trackLeft,
            paddingTop.toFloat(),
            trackLeft + trackWidth,
            (height - paddingBottom).toFloat(),
            trackWidth / 2f,
            trackWidth / 2f,
            paint
        )

        paint.color = if (isDragging) thumbPressedColor else thumbColor
        canvas.drawRoundRect(
            thumbLeft,
            thumbTop.toFloat(),
            thumbRight,
            thumbBottom.toFloat(),
            radius,
            radius,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (getMaxScroll() <= 0) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isInTouchArea(event.x)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                scrollTargetTo(event.y)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                scrollTargetTo(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun detachTarget() {
        targetView?.let {
            it.removeOnLayoutChangeListener(targetLayoutListener)
            if (it.viewTreeObserver.isAlive) {
                it.viewTreeObserver.removeOnScrollChangedListener(scrollListener)
                it.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
            }
            it.removeTextChangedListener(textWatcher)
        }
        targetView = null
    }

    private fun updateThumb() {
        val target = targetView ?: return hideThumb()
        val maxScroll = getMaxScroll()
        val trackHeight = height - paddingTop - paddingBottom
        if (maxScroll <= 0 || trackHeight <= 0 || target.height <= 0) {
            hideThumb()
            return
        }

        val contentHeight = target.layout.height + target.totalPaddingTop + target.totalPaddingBottom
        thumbHeight = (target.height * trackHeight / contentHeight.toFloat())
            .roundToInt()
            .coerceIn(minThumbHeight, trackHeight)

        val maxThumbOffset = (trackHeight - thumbHeight).coerceAtLeast(0)
        val scrollFraction = (target.scrollY.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
        thumbTop = paddingTop + (scrollFraction * maxThumbOffset).roundToInt()
        thumbBottom = thumbTop + thumbHeight

        if (visibility != VISIBLE) visibility = VISIBLE
        invalidate()
    }

    private fun hideThumb() {
        thumbHeight = 0
        if (visibility != INVISIBLE) visibility = INVISIBLE
    }

    private fun scrollTargetTo(y: Float) {
        val target = targetView ?: return
        val maxScroll = getMaxScroll()
        val trackHeight = height - paddingTop - paddingBottom
        if (maxScroll <= 0 || trackHeight <= 0) return

        val availableTrack = (trackHeight - thumbHeight).coerceAtLeast(1)
        val targetThumbTop = (y - paddingTop - thumbHeight / 2f)
            .coerceIn(0f, availableTrack.toFloat())
        val fraction = targetThumbTop / availableTrack.toFloat()
        val targetY = (fraction * maxScroll).roundToInt().coerceIn(0, maxScroll)

        target.scrollTo(target.scrollX, targetY)
        updateThumb()
    }

    private fun getMaxScroll(): Int {
        val target = targetView ?: return 0
        val layout = target.layout ?: return 0
        val contentHeight = layout.height + target.totalPaddingTop + target.totalPaddingBottom
        return (contentHeight - target.height).coerceAtLeast(0)
    }

    private fun isInTouchArea(x: Float): Boolean {
        return if (ViewCompat.getLayoutDirection(this) == ViewCompat.LAYOUT_DIRECTION_RTL) {
            x <= width
        } else {
            x >= 0
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }
}
