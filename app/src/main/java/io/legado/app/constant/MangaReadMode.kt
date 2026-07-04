package io.legado.app.constant

object MangaReadMode {
    const val SCROLL = "scroll"
    const val NORMAL = "normal"
    const val JAPANESE = "japanese"

    fun isHorizontal(mode: String): Boolean = mode != SCROLL
    fun isJapanese(mode: String): Boolean = mode == JAPANESE
}
