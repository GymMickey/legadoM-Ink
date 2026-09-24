package io.legado.app.ui.main.homepage

import io.legado.app.help.book.BookMatcher

internal data class HomepageBookSelection(
    val lastReadBook: HomepageBookSummary?,
    val recentBooks: List<HomepageBookSummary>
)

/** Filters the already sorted DAO result without sorting the full list again. */
internal fun selectHomepageBooks(
    summaries: List<HomepageBookSummary>,
    readBookKeys: Set<String>
): HomepageBookSelection {
    val readSummaries = summaries.filter { summary ->
        "${BookMatcher.normalize(summary.name)}|${BookMatcher.normalize(summary.author)}" in readBookKeys
    }
    return HomepageBookSelection(
        lastReadBook = readSummaries.firstOrNull(),
        recentBooks = readSummaries.drop(1).take(10)
    )
}
