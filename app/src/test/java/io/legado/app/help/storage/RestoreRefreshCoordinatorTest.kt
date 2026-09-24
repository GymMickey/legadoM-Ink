package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreRefreshCoordinatorTest {
    @Test
    fun `coalesces active requests and keeps channels independent`() {
        var bookshelf = 0
        var homepage = 0
        var config = 0
        val coordinator = RestoreRefreshCoordinator(
            onBookshelfRefresh = { bookshelf++ },
            onHomepageRefresh = { homepage++ },
            onConfigApply = { config++ }
        )

        coordinator.begin()
        coordinator.requestBookshelfRefresh()
        coordinator.requestBookshelfRefresh()
        coordinator.requestHomepageRefresh()
        coordinator.requestConfigApply()
        coordinator.finish()

        assertEquals(1, bookshelf)
        assertEquals(1, homepage)
        assertEquals(1, config)
    }

    @Test
    fun `does not refresh when there was no change`() {
        var refreshes = 0
        val coordinator = RestoreRefreshCoordinator(onBookshelfRefresh = { refreshes++ })
        coordinator.begin()
        coordinator.finish()
        assertEquals(0, refreshes)
    }

    @Test
    fun `cancel clears pending state for the next restore`() {
        var refreshes = 0
        val coordinator = RestoreRefreshCoordinator(onBookshelfRefresh = { refreshes++ })
        coordinator.begin()
        coordinator.requestBookshelfRefresh()
        coordinator.cancel()
        coordinator.finish()
        coordinator.begin()
        coordinator.finish()
        assertEquals(0, refreshes)
    }
}
