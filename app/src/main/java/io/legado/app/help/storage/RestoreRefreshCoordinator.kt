package io.legado.app.help.storage

/**
 * Coordinates active refresh requests produced during one restore run.
 * Room invalidation remains responsible for the normal observer updates.
 */
internal class RestoreRefreshCoordinator(
    private val onBookshelfRefresh: () -> Unit,
    private val onHomepageRefresh: () -> Unit = {},
    private val onConfigApply: () -> Unit = {}
) {
    private var active = false
    private var bookshelfPending = false
    private var homepagePending = false
    private var configPending = false

    fun begin() {
        clear()
        active = true
    }

    fun requestBookshelfRefresh() {
        if (active) bookshelfPending = true else onBookshelfRefresh()
    }

    fun requestHomepageRefresh() {
        if (active) homepagePending = true else onHomepageRefresh()
    }

    fun requestConfigApply() {
        if (active) configPending = true else onConfigApply()
    }

    fun finish() {
        if (!active) return
        val bookshelf = bookshelfPending
        val homepage = homepagePending
        val config = configPending
        clear()
        active = false
        if (bookshelf) onBookshelfRefresh()
        if (homepage) onHomepageRefresh()
        if (config) onConfigApply()
    }

    fun cancel() {
        clear()
        active = false
    }

    private fun clear() {
        bookshelfPending = false
        homepagePending = false
        configPending = false
    }
}
