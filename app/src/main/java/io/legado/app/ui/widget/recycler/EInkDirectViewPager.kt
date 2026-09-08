package io.legado.app.ui.widget.recycler

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

/**
 * ViewPager with independently controllable user swiping and page-change animation.
 *
 * When user paging is disabled, events are left for the child page so its vertical
 * RecyclerView can continue to receive and handle scroll gestures.
 */
class EInkDirectViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {

    private var userPagingEnabled = true
    private var directPageChangesEnabled = false

    fun setUserPagingEnabled(enabled: Boolean) {
        userPagingEnabled = enabled
    }

    fun setDirectPageChangesEnabled(enabled: Boolean) {
        directPageChangesEnabled = enabled
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!userPagingEnabled) return false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!userPagingEnabled) return false
        return super.onTouchEvent(ev)
    }

    override fun setCurrentItem(item: Int) {
        super.setCurrentItem(item, !directPageChangesEnabled)
    }

    override fun setCurrentItem(item: Int, smoothScroll: Boolean) {
        super.setCurrentItem(item, if (directPageChangesEnabled) false else smoothScroll)
    }
}
