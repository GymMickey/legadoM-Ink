package io.legado.app.lib.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.legado.app.R
import io.legado.app.help.config.AppConfig

/**
 * Small, opt-in visual adjustments shared by the E-Ink target pages.
 * Normal, dark and automatic themes leave these views untouched.
 */
object EInkVisuals {

    fun applyScreen(root: View) {
        if (!AppConfig.isEInkMode) return
        root.setBackgroundResource(R.color.eink_background)
        applyView(root)
    }

    fun applyItem(root: View) {
        if (!AppConfig.isEInkMode) return
        // Items use spacing and their own content hierarchy instead of a card frame.
        root.background = null
        applyView(root)
    }

    fun applyDialog(root: View) {
        if (!AppConfig.isEInkMode) return
        applyView(root)
    }

    fun applyTextLabel(view: TextView) {
        if (!AppConfig.isEInkMode) return
        // Keep the label opaque and readable without framing every tag.
        view.setBackgroundColor(ContextCompat.getColor(view.context, R.color.eink_surface))
        view.setTextColor(ContextCompat.getColor(view.context, R.color.eink_primary_text))
        view.elevation = 0f
        view.stateListAnimator = null
    }

    private fun applyView(view: View) {
        view.elevation = 0f
        view.stateListAnimator = null
        if (view is RecyclerView) {
            view.itemAnimator = null
        }

        when {
            view is FloatingActionButton -> {
                view.rippleColor = Color.TRANSPARENT
            }
            view.background is RippleDrawable -> view.background = null
        }
        if (view.foreground is RippleDrawable) {
            view.foreground = null
        }
        normalizeTextColor(view)

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyView(view.getChildAt(index))
            }
        }
    }

    private fun normalizeTextColor(view: View) {
        val textView = view as? TextView ?: return
        val color = textView.textColors.defaultColor
        if (Color.red(color) > 8 || Color.green(color) > 8 || Color.blue(color) > 8) return

        val normal = if (Color.alpha(color) < 180) {
            R.color.eink_secondary_text
        } else {
            R.color.eink_primary_text
        }
        textView.setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf(android.R.attr.state_enabled)
                ),
                intArrayOf(
                    ContextCompat.getColor(textView.context, R.color.eink_disabled_text),
                    ContextCompat.getColor(textView.context, normal)
                )
            )
        )
    }
}
