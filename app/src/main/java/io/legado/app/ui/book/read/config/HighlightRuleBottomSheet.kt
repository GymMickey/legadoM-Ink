package io.legado.app.ui.book.read.config

import android.view.MotionEvent
import android.view.View
import io.legado.app.utils.dpToPx

internal fun attachBottomSheetDismiss(
    dragHandle: View,
    sheetContainer: View,
    onDismiss: () -> Unit,
) {
    val dismissThreshold = 72.dpToPx().toFloat()
    var downRawY = 0f
    dragHandle.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val offset = (event.rawY - downRawY).coerceAtLeast(0f)
                sheetContainer.translationY = offset
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val offset = (event.rawY - downRawY).coerceAtLeast(0f)
                if (offset >= dismissThreshold) {
                    sheetContainer.animate()
                        .translationY(sheetContainer.height.toFloat())
                        .setDuration(180)
                        .withEndAction(onDismiss)
                        .start()
                } else {
                    sheetContainer.animate()
                        .translationY(0f)
                        .setDuration(180)
                        .start()
                }
                true
            }
            else -> false
        }
    }
}
