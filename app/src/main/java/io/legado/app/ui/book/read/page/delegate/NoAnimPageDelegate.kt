package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewTreeObserver
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.eink.IReaderPageH
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override val invalidateDuringSwipe: Boolean = false
    override val resetStartPointDuringSwipe: Boolean = false

    private val pageHPreDrawGate = PageHPreDrawGate()
    private var pageHPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null

    override fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_CANCEL -> isCancel = true
            MotionEvent.ACTION_UP -> if (isMoved && mDirection != PageDirection.NONE) {
                isCancel = !NoAnimSwipeDecision.shouldCommit(
                    startX = startX,
                    endX = event.x,
                    direction = mDirection,
                    slopSquare = readView.pageSlopSquare2
                )
            }
        }
        super.onTouch(event)
    }

    override fun onAnimStart(animationSpeed: Int) {
        if (!isMoved) isCancel = false
        clearPageHPreDraw()
        val direction = mDirection
        if (!isCancel && direction != PageDirection.NONE) {
            val usePageH = AppConfig.isEInkMode
                && AppConfig.iReaderPageHEnabled
                && ReadBook.pageAnim() == PageAnim.noAnim
                && IReaderPageH.isAvailable()

            val pageChanged = readView.fillPage(direction) {
                if (usePageH) schedulePageHPreDraw(direction)
            }
            if (!pageChanged) clearPageHPreDraw()
        }
        stopScroll()
    }

    private fun schedulePageHPreDraw(direction: PageDirection) {
        clearPageHPreDraw()
        if (!readView.viewTreeObserver.isAlive) return

        val rotation = readView.display?.rotation ?: Surface.ROTATION_0
        val speedIndex = AppConfig.iReaderPageHSpeed
        val reverse = AppConfig.iReaderPageHDirection == 1
        pageHPreDrawGate.schedule {
            val usePageH = AppConfig.isEInkMode
                && AppConfig.iReaderPageHEnabled
                && ReadBook.pageAnim() == PageAnim.noAnim
                && IReaderPageH.isAvailable()
            if (usePageH) {
                IReaderPageH.prepare(
                    forward = direction == PageDirection.NEXT,
                    rotation = rotation,
                    speedIndex = speedIndex,
                    reverse = reverse
                )
            }
        }
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                removePageHPreDrawListener()
                pageHPreDrawGate.runOnce()
                return true
            }
        }
        pageHPreDrawListener = listener
        readView.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun removePageHPreDrawListener() {
        val listener = pageHPreDrawListener ?: return
        pageHPreDrawListener = null
        if (readView.viewTreeObserver.isAlive) {
            readView.viewTreeObserver.removeOnPreDrawListener(listener)
        }
    }

    private fun clearPageHPreDraw() {
        pageHPreDrawGate.clear()
        removePageHPreDrawListener()
    }

    override fun setBitmap() {
        // nothing
    }

    override fun onDraw(canvas: Canvas) {
        // nothing
    }

    override fun onAnimStop() {
        // nothing
    }

    override fun onDestroy() {
        clearPageHPreDraw()
        super.onDestroy()
    }

}

internal class PageHPreDrawGate {

    private var pendingAction: (() -> Unit)? = null

    fun schedule(action: () -> Unit) {
        pendingAction = action
    }

    fun runOnce(): Boolean {
        val action = pendingAction ?: return false
        pendingAction = null
        action()
        return true
    }

    fun clear() {
        pendingAction = null
    }
}

internal object NoAnimSwipeDecision {

    fun shouldCommit(
        startX: Float,
        endX: Float,
        direction: PageDirection,
        slopSquare: Int
    ): Boolean {
        val deltaX = endX - startX
        if (deltaX * deltaX <= slopSquare) return false
        return when (direction) {
            PageDirection.NEXT -> deltaX < 0f
            PageDirection.PREV -> deltaX > 0f
            PageDirection.NONE -> false
        }
    }
}
