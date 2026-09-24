package io.legado.app.model

/** Pure decisions for the small prev/current/next reading window. */
internal object AdjacentChapterLoadPolicy {

    fun shouldPrepareLocalNext(
        isLocalBook: Boolean,
        nextChapterReady: Boolean,
        currentIndex: Int,
        chapterCount: Int
    ): Boolean = isLocalBook &&
        !nextChapterReady &&
        currentIndex in 0 until (chapterCount - 1)

    fun isInCurrentWindow(chapterIndex: Int, currentIndex: Int): Boolean {
        return chapterIndex in currentIndex - 1..currentIndex + 1
    }
}
