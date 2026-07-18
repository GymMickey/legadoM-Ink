package io.legado.app.lib.theme

import android.graphics.drawable.Drawable
import android.view.View

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
object ViewUtils {

    fun setBackgroundCompat(view: View, drawable: Drawable?) {
        view.background = drawable
    }
}
