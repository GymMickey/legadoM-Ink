package io.legado.app.ui.widget

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.cardview.widget.CardView
import io.legado.app.R
import io.legado.app.utils.dpToPx

class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val batteryTextView: TextView
    private val batteryTextInnerView: TextView
    private val batteryTextEndView: TextView
    private val batteryFillView: CardView
    private val batteryIconView: ImageView
    private val arrowIconView: ImageView
    private val batteryClassicView: BatteryViewOrgin

    private var battery: Int = 0
    private var batteryText: String? = null
    private var textSizePxValue = 11f * resources.displayMetrics.scaledDensity
    var batteryInnerOnly: Boolean = false
        set(value) {
            field = value
            updateMode()
            updateFill()
        }
    var batteryTextWithIconOnly: Boolean = false
        set(value) {
            field = value
            updateMode()
            updateFill()
        }

    var isBattery: Boolean = false
        set(value) {
            field = value
            updateMode()
        }

    var text: CharSequence?
        get() = batteryTextView.text
        set(value) {
            batteryTextView.text = value
            batteryTextInnerView.text = value
            batteryTextEndView.text = value
        }

    var typeface: Typeface?
        get() = batteryTextView.typeface
        set(value) {
            batteryTextView.typeface = value ?: Typeface.DEFAULT
            batteryTextInnerView.typeface = value ?: Typeface.DEFAULT
            batteryTextEndView.typeface = value ?: Typeface.DEFAULT
            batteryClassicView.typeface = value
        }

    var textSize: Float
        get() = textSizePxValue
        set(value) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, value)
        }

    fun syncTag(tag: Any?) {
        super.setTag(tag)
        batteryTextView.tag = tag
        batteryTextInnerView.tag = tag
        batteryTextEndView.tag = tag
    }

    fun setTextSize(unit: Int, size: Float) {
        textSizePxValue = TypedValue.applyDimension(
            unit,
            size,
            resources.displayMetrics
        )
        batteryTextView.setTextSize(unit, size)
        batteryTextEndView.setTextSize(unit, size)
        batteryClassicView.setTextSize(unit, size)
        batteryTextInnerView.setTextSize(TypedValue.COMPLEX_UNIT_PX, batteryTextView.textSize * 0.73f)
        updateScaledLayout()
    }

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 3.dpToPx(), 0, 3.dpToPx())
        LayoutInflater.from(context).inflate(R.layout.view_battery, this, true)
        batteryTextView = findViewById(R.id.battery_text)
        batteryTextInnerView = findViewById(R.id.battery_text_inner)
        batteryTextEndView = findViewById(R.id.battery_text_end)
        batteryFillView = findViewById(R.id.battery_fill)
        batteryIconView = findViewById(R.id.battery_icon)
        arrowIconView = findViewById(R.id.arrow_icon)
        batteryClassicView = findViewById(R.id.battery_classic)
        syncTag(tag)
        updateScaledLayout()
        updateMode()
    }

    fun setColor(@ColorInt color: Int) {
        batteryTextView.setTextColor(color)
        batteryTextInnerView.setTextColor(color)
        batteryTextEndView.setTextColor(color)
        batteryFillView.setCardBackgroundColor(color)
        batteryClassicView.setColor(color)
        batteryIconView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        arrowIconView.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        batteryIconView.alpha = 0.76f
        arrowIconView.alpha = 0.76f
    }

    fun setTextIfNotEqual(newText: String?) {
        if (text?.toString() != newText) {
            text = newText
        }
    }

    fun setBattery(battery: Int, text: String? = null) {
        this.battery = battery.coerceIn(0, 100)
        this.batteryText = text

        if (!isBattery) {
            this.text = text.orEmpty()
            return
        }

        if (batteryInnerOnly) {
            this.text = ""
            batteryTextInnerView.text = battery.toString()
        } else if (batteryTextWithIconOnly) {
            this.text = text.orEmpty()
            batteryTextInnerView.text = ""
        } else if (text.isNullOrEmpty()) {
            this.text = ""
        } else {
            batteryTextView.text = text
            batteryTextEndView.text = text
            batteryTextInnerView.text = battery.toString()
            batteryClassicView.text = "$text $battery%"
        }
        batteryClassicView.setBattery(this.battery, text)
        updateMode()
        updateFill()
    }

    private fun updateMode() {
        if (!isBattery) {
            batteryFillView.visibility = GONE
            batteryIconView.visibility = GONE
            batteryTextView.visibility = VISIBLE
            batteryTextEndView.visibility = GONE
            batteryTextInnerView.visibility = GONE
            arrowIconView.visibility = GONE
            batteryClassicView.visibility = GONE
            return
        }

        if (batteryInnerOnly) {
            batteryTextView.visibility = GONE
            batteryTextEndView.visibility = GONE
            batteryTextInnerView.visibility = VISIBLE
            batteryFillView.visibility = GONE
            batteryIconView.visibility = VISIBLE
            arrowIconView.visibility = GONE
            batteryClassicView.visibility = GONE
        } else if (batteryTextWithIconOnly) {
            batteryTextView.visibility = VISIBLE
            batteryTextEndView.visibility = GONE
            batteryTextInnerView.visibility = GONE
            batteryFillView.visibility = VISIBLE
            batteryIconView.visibility = VISIBLE
            arrowIconView.visibility = GONE
            batteryClassicView.visibility = GONE
        } else if (batteryText.isNullOrEmpty()) {
            batteryTextView.visibility = GONE
            batteryTextEndView.visibility = GONE
            batteryTextInnerView.visibility = GONE
            batteryFillView.visibility = VISIBLE
            batteryIconView.visibility = VISIBLE
            arrowIconView.visibility = GONE
            batteryClassicView.visibility = GONE
        } else {
            batteryTextView.visibility = VISIBLE
            batteryTextEndView.visibility = GONE
            batteryTextInnerView.visibility = VISIBLE
            batteryFillView.visibility = GONE
            batteryIconView.visibility = VISIBLE
            arrowIconView.visibility = GONE
            batteryClassicView.visibility = GONE
        }
    }

    private fun updateFill() {
        post {
            val params = batteryFillView.layoutParams
            params.width = (((17.dpToPx() * scaleFactor()) * battery) / 100f).toInt().coerceAtLeast(0)
            batteryFillView.layoutParams = params
        }
    }

    private fun updateScaledLayout() {
        val scale = scaleFactor()
        batteryTextInnerView.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            batteryTextView.textSize * 0.73f
        )
        updateLayoutSize(batteryIconView, (28.dpToPx() * scale).toInt(), (12.dpToPx() * scale).toInt())
        updateLayoutSize(arrowIconView, (12.dpToPx() * scale).toInt(), (12.dpToPx() * scale).toInt())
        updateMarginStart(batteryIconView, (4.dpToPx() * scale).toInt())
        updateMarginEnd(arrowIconView, (8.dpToPx() * scale).toInt())
        updateMarginEnd(batteryTextInnerView, (2.4f.dpToPx() * scale).toInt())
        updateMarginBottom(batteryTextInnerView, (0.8f.dpToPx() * scale).toInt())
        updateMarginStart(batteryFillView, (4.2f.dpToPx() * scale).toInt())
        updateLayoutSize(batteryFillView, (17.dpToPx() * scale).toInt(), (8.dpToPx() * scale).toInt())
        batteryFillView.radius = 1f.dpToPx() * scale
    }

    private fun scaleFactor(): Float {
        val base = 11f * resources.displayMetrics.scaledDensity
        return ((textSizePxValue / base) * 0.95f).coerceIn(0.66f, 1.9f)
    }

    private fun updateLayoutSize(view: android.view.View, width: Int, height: Int) {
        val params = view.layoutParams
        params.width = width
        params.height = height
        view.layoutParams = params
    }

    private fun updateMarginStart(view: android.view.View, marginStart: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.marginStart = marginStart
        view.layoutParams = params
    }

    private fun updateMarginEnd(view: android.view.View, marginEnd: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.marginEnd = marginEnd
        view.layoutParams = params
    }

    private fun updateMarginBottom(view: android.view.View, marginBottom: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.bottomMargin = marginBottom
        view.layoutParams = params
    }
}
