package io.legado.app.lib.theme

import kotlin.math.max

/**
 * E-Ink result pagination for the currently loaded screen data.
 *
 * This class only slices an already loaded list. Network/database loading is
 * intentionally left to the owning screen.
 */
class EInkScreenPaginator<T> {

    private var loadedItems: List<T> = emptyList()

    var itemsPerPage: Int = 1
        private set

    var currentPage: Int = 1
        private set

    val totalPages: Int
        get() = if (loadedItems.isEmpty()) 1 else (loadedItems.size + itemsPerPage - 1) / itemsPerPage

    val currentItems: List<T>
        get() {
            if (loadedItems.isEmpty()) return emptyList()
            val start = ((currentPage - 1) * itemsPerPage).coerceIn(0, loadedItems.size)
            val end = (start + itemsPerPage).coerceAtMost(loadedItems.size)
            return loadedItems.subList(start, end)
        }

    val canPrevious: Boolean
        get() = currentPage > 1

    val canNext: Boolean
        get() = currentPage < totalPages

    val isEmpty: Boolean
        get() = loadedItems.isEmpty()

    val isAtLastPage: Boolean
        get() = currentPage >= totalPages

    /** Replace loaded data while keeping the current first item as an anchor. */
    fun setItems(items: List<T>, resetToFirst: Boolean = false) {
        val anchor = if (resetToFirst) null else currentItems.firstOrNull()
        loadedItems = items.toList()
        currentPage = if (resetToFirst) {
            1
        } else {
            anchor?.let { anchorItem ->
                val anchorIndex = loadedItems.indexOf(anchorItem)
                if (anchorIndex >= 0) anchorIndex / itemsPerPage + 1 else currentPage
            } ?: currentPage
        }
        clampPage()
    }

    /** Recalculate capacity while keeping the first visible item where possible. */
    fun setItemsPerPage(count: Int) {
        val anchor = currentItems.firstOrNull()
        itemsPerPage = max(1, count)
        currentPage = anchor?.let { anchorItem ->
            val anchorIndex = loadedItems.indexOf(anchorItem)
            if (anchorIndex >= 0) anchorIndex / itemsPerPage + 1 else currentPage
        } ?: currentPage
        clampPage()
    }

    fun goToPage(page: Int): Boolean {
        val safePage = page.coerceIn(1, totalPages)
        if (safePage == currentPage) return false
        currentPage = safePage
        return true
    }

    fun nextPage(): Boolean = goToPage(currentPage + 1)

    fun previousPage(): Boolean = goToPage(currentPage - 1)

    fun goToItemIndex(index: Int): Boolean {
        if (index !in loadedItems.indices) return false
        return goToPage(index / itemsPerPage + 1)
    }

    /** True when the screen has consumed all locally loaded pages and may load more. */
    fun needsMore(hasMore: Boolean): Boolean = hasMore && isAtLastPage

    private fun clampPage() {
        currentPage = currentPage.coerceIn(1, totalPages)
    }
}
