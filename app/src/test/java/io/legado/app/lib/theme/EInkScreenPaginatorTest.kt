package io.legado.app.lib.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EInkScreenPaginatorTest {

    @Test
    fun emptyAndSinglePageAreStable() {
        val paginator = EInkScreenPaginator<Int>()

        assertEquals(1, paginator.totalPages)
        assertEquals(emptyList<Int>(), paginator.currentItems)
        assertFalse(paginator.canPrevious)
        assertFalse(paginator.canNext)

        paginator.setItemsPerPage(2)
        paginator.setItems(listOf(1, 2))
        assertEquals(1, paginator.currentPage)
        assertEquals(listOf(1, 2), paginator.currentItems)
    }

    @Test
    fun navigationMovesBetweenLoadedPages() {
        val paginator = EInkScreenPaginator<Int>()
        paginator.setItems((1..5).toList())
        paginator.setItemsPerPage(2)

        assertEquals(listOf(1, 2), paginator.currentItems)
        assertTrue(paginator.nextPage())
        assertEquals(listOf(3, 4), paginator.currentItems)
        assertTrue(paginator.previousPage())
        assertEquals(1, paginator.currentPage)
        assertFalse(paginator.previousPage())
    }

    @Test
    fun shrinkingDataClampsPage() {
        val paginator = EInkScreenPaginator<Int>()
        paginator.setItems((1..8).toList())
        paginator.setItemsPerPage(2)
        paginator.goToPage(4)

        paginator.setItems((1..3).toList())

        assertEquals(2, paginator.currentPage)
        assertEquals(listOf(3), paginator.currentItems)
    }

    @Test
    fun capacityChangeKeepsCurrentFirstItemAsAnchor() {
        val paginator = EInkScreenPaginator<Int>()
        paginator.setItems((1..12).toList())
        paginator.setItemsPerPage(3)
        paginator.goToPage(3)

        paginator.setItemsPerPage(4)

        assertEquals(2, paginator.currentPage)
        assertEquals(listOf(5, 6, 7, 8), paginator.currentItems)
    }

    @Test
    fun localEndReportsWhetherMoreDataCanBeLoaded() {
        val paginator = EInkScreenPaginator<Int>()
        paginator.setItems((1..4).toList())
        paginator.setItemsPerPage(2)

        assertFalse(paginator.needsMore(false))
        assertFalse(paginator.needsMore(true))
        paginator.nextPage()
        assertTrue(paginator.needsMore(true))
    }
}
