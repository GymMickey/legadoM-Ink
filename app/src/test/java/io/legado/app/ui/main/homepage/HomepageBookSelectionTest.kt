package io.legado.app.ui.main.homepage

import org.junit.Assert.assertEquals
import org.junit.Test

class HomepageBookSelectionTest {
    @Test
    fun `selection keeps dao order and excludes unread books`() {
        val summaries = listOf(
            HomepageBookSummary(name = " Recent ", author = " Author ", durChapterTime = 30),
            HomepageBookSummary(name = "Older", author = "Author", durChapterTime = 20),
            HomepageBookSummary(name = "Unread", author = "Author", durChapterTime = 10)
        )
        val result = selectHomepageBooks(
            summaries,
            setOf("Recent|Author", "Older|Author")
        )

        assertEquals(" Recent ", result.lastReadBook?.name)
        assertEquals(listOf("Older"), result.recentBooks.map { it.name })
    }

    @Test
    fun `selection is capped at the homepage recent book count`() {
        val summaries = List(12) { index ->
            HomepageBookSummary(
                name = "Book$index",
                author = "Author",
                durChapterTime = 100L - index
            )
        }
        val result = selectHomepageBooks(
            summaries,
            summaries.map { "${it.name}|${it.author}" }.toSet()
        )

        assertEquals("Book0", result.lastReadBook?.name)
        assertEquals(10, result.recentBooks.size)
    }
}
