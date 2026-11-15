package io.legado.app.ui.book.read.page.delegate

import android.graphics.Canvas
import android.view.Surface
import io.legado.app.constant.PageAnim
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.eink.IReaderPageH
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.entities.PageDirection

class NoAnimPageDelegate(readView: ReadView) : HorizontalPageDelegate(readView) {

    override fun onAnimStart(animationSpeed: Int) {
        if (!isCancel) {
            val pageChanged = readView.fillPage(mDirection)
            if (pageChanged
                && AppConfig.isEInkMode
                && AppConfig.iReaderPageHEnabled
                && ReadBook.pageAnim() == PageAnim.noAnim
                && IReaderPageH.isAvailable()
            ) {
                IReaderPageH.prepare(
                    forward = mDirection == PageDirection.NEXT,
                    rotation = readView.display?.rotation ?: Surface.ROTATION_0,
                    speedIndex = AppConfig.iReaderPageHSpeed
                )
            }
        }
        stopScroll()
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


}
