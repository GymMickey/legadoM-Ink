package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjacentChapterLoadPolicyTest {

    @Test
    fun `local books prepare next chapter when pre-download is not enabled`() {
        assertTrue(
            AdjacentChapterLoadPolicy.shouldPrepareLocalNext(
                isLocalBook = true,
                nextChapterReady = false,
                currentIndex = 0,
                chapterCount = 2
            )
        )
        assertFalse(
            AdjacentChapterLoadPolicy.shouldPrepareLocalNext(
                isLocalBook = false,
                nextChapterReady = false,
                currentIndex = 0,
                chapterCount = 2
            )
        )
    }

    @Test
    fun `next chapter preparation stops at bounds and ready chapters`() {
        assertFalse(
            AdjacentChapterLoadPolicy.shouldPrepareLocalNext(true, false, 1, 2)
        )
        assertFalse(
            AdjacentChapterLoadPolicy.shouldPrepareLocalNext(true, true, 0, 2)
        )
        assertFalse(
            AdjacentChapterLoadPolicy.shouldPrepareLocalNext(true, false, -1, 2)
        )
    }

    @Test
    fun `stale results outside the three chapter window are rejected`() {
        assertTrue(AdjacentChapterLoadPolicy.isInCurrentWindow(4, 5))
        assertTrue(AdjacentChapterLoadPolicy.isInCurrentWindow(6, 5))
        assertFalse(AdjacentChapterLoadPolicy.isInCurrentWindow(3, 5))
        assertFalse(AdjacentChapterLoadPolicy.isInCurrentWindow(7, 5))
    }
}
