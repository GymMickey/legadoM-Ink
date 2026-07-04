package io.legado.app.ui.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.sysBattery
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.min
import com.google.android.material.R as materialR

class ReaderInfoBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textBounds = Rect()
    private val textFontMetrics = Paint.FontMetrics()
    private val batteryRect = RectF()
    private val batteryFillRect = RectF()
    private val batteryCapRect = RectF()
    private val timeFormat = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT)
    private val timeReceiver = TimeReceiver()
    private var insetLeft: Int = 0
    private var insetRight: Int = 0
    private var insetTop: Int = 0
    private var cutoutInsetLeft = 0
    private var cutoutInsetRight = 0
    private val colorText = ColorUtils.setAlphaComponent(
        context.obtainStyledAttributes(intArrayOf(materialR.attr.colorOnSurface)).use {
            it.getColor(0, Color.BLACK)
        },
        200,
    )
    private val colorOutline = ColorUtils.setAlphaComponent(
        context.obtainStyledAttributes(intArrayOf(materialR.attr.colorSurface)).use {
            it.getColor(0, Color.WHITE)
        },
        200,
    )

    var textInfoAlignment: Int = ALIGN_CENTER
        set(value) {
            field = value
            updateTextSize()
            invalidate()
        }
    var showBattery: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    private var timeText = timeFormat.format(Date())
    private var battery = context.sysBattery.coerceIn(0, 100)
    private var text: String = ""
    private val innerHeight
        get() = height - paddingTop - paddingBottom - insetTop
    private val innerWidth
        get() = width - paddingLeft - paddingRight - insetLeft - insetRight

    init {
        val insetStart = 10.dpToPx()
        val insetEnd = 10.dpToPx()
        paint.strokeWidth = 2f.dpToPx()
        paint.setShadowLayer(2f, 1f, 1f, Color.GRAY)
        batteryPaint.strokeWidth = 1f.dpToPx()
        batteryPaint.setShadowLayer(2f, 1f, 1f, Color.GRAY)
        insetLeft = insetStart
        insetRight = insetEnd
        insetTop = minOf(insetLeft, insetRight)
        setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (insets.left >= paddingLeft) {
                cutoutInsetLeft = insets.left
            }
            if (insets.right >= paddingRight) {
                cutoutInsetRight = insets.right
            }
            windowInsets
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ty = innerHeight / 2f + textBounds.height() / 2f - textBounds.bottom

        val textX = when (textInfoAlignment) {
            ALIGN_CENTER -> {
                val textWidth = paint.measureText(text)
                (width / 2f).coerceIn(
                    paddingLeft + insetLeft + cutoutInsetLeft + textWidth / 2,
                    width - paddingRight - insetRight - cutoutInsetRight - textWidth / 2
                )
            }

            else -> (paddingLeft + insetLeft + cutoutInsetLeft).toFloat()
        }
        paint.textAlign = when (textInfoAlignment) {
            ALIGN_CENTER -> Paint.Align.CENTER
            else -> Paint.Align.LEFT
        }

        canvas.drawTextOutline(
            text,
            textX,
            paddingTop + insetTop + ty,
        )

        val infoRight = (width - paddingRight - insetRight - cutoutInsetRight).toFloat()
        val timeX = if (showBattery) {
            val batterySize = drawBatteryIcon(canvas, infoRight, paddingTop + insetTop + ty)
            infoRight - batterySize - 6f.dpToPx()
        } else {
            infoRight
        }

        paint.textAlign = Paint.Align.RIGHT
        canvas.drawTextOutline(
            timeText,
            timeX,
            paddingTop + insetTop + ty,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateTextSize()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ContextCompat.registerReceiver(
            context,
            timeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.unregisterReceiver(timeReceiver)
    }

    fun update(label: String) {
        text = label
        updateTextSize()
        invalidate()
    }

    private fun updateTextSize() {
        val testTextSize = 48f
        paint.textSize = testTextSize
        paint.getTextBounds(text, 0, text.length, textBounds)

        val maxTextHeight = innerHeight * 0.8f
        val scaleFactor = min(
            maxTextHeight / textBounds.height(),
            calculateMaxWidthScale()
        )
        paint.textSize = testTextSize * scaleFactor

        paint.getTextBounds(text, 0, text.length, textBounds)
    }

    private fun calculateMaxWidthScale(): Float {
        return when (textInfoAlignment) {
            ALIGN_CENTER -> {
                val availableWidth = innerWidth - cutoutInsetLeft - cutoutInsetRight
                val requiredWidth = paint.measureText(text)
                if (requiredWidth > availableWidth) availableWidth / requiredWidth else 1f
            }

            else -> 1f
        }
    }

    private fun Canvas.drawTextOutline(text: String, x: Float, y: Float) {
        paint.color = colorOutline
        paint.style = Paint.Style.STROKE
        drawText(text, x, y, paint)
        paint.color = colorText
        paint.style = Paint.Style.FILL
        drawText(text, x, y, paint)
    }

    private fun drawBatteryIcon(canvas: Canvas, right: Float, textBaseline: Float): Float {
        val iconHeight = min(innerHeight * 0.62f, 12f.dpToPx()).coerceAtLeast(7f.dpToPx())
        val iconWidth = iconHeight * 28f / 12f
        val capWidth = iconHeight * 0.12f
        val bodyWidth = iconWidth - capWidth
        paint.getFontMetrics(textFontMetrics)
        val textCenterY = textBaseline + (textFontMetrics.ascent + textFontMetrics.descent) / 2f
        val top = textCenterY - iconHeight / 2f
        val left = right - iconWidth
        val corner = iconHeight * 0.12f

        batteryRect.set(left, top, left + bodyWidth, top + iconHeight)
        batteryCapRect.set(
            left + bodyWidth,
            top + iconHeight * 0.32f,
            right,
            top + iconHeight * 0.68f
        )

        val foregroundStrokeWidth = 1f.dpToPx()
        val outlineStrokeWidth = foregroundStrokeWidth + 1f.dpToPx()

        batteryPaint.color = colorOutline
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = outlineStrokeWidth
        canvas.drawRoundRect(batteryRect, corner, corner, batteryPaint)
        canvas.drawRoundRect(batteryCapRect, corner, corner, batteryPaint)

        batteryPaint.color = colorText
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = foregroundStrokeWidth
        canvas.drawRoundRect(batteryRect, corner, corner, batteryPaint)
        canvas.drawRoundRect(batteryCapRect, corner, corner, batteryPaint)

        val fillPadding = iconHeight * 0.18f
        val fillWidth = (bodyWidth - fillPadding * 2f) * battery / 100f
        if (fillWidth > 0f) {
            batteryFillRect.set(
                left + fillPadding,
                top + fillPadding,
                left + fillPadding + fillWidth,
                top + iconHeight - fillPadding
            )
            batteryPaint.color = colorText
            batteryPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(batteryFillRect, corner * 0.7f, corner * 0.7f, batteryPaint)
        }

        return iconWidth
    }

    private inner class TimeReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    battery = intent.getIntExtra("level", battery).coerceIn(0, 100)
                }
                else -> {
                    timeText = timeFormat.format(Date())
                }
            }
            invalidate()
        }
    }
}
