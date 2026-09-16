package io.legado.app.ui.book.read.page.delegate

import io.legado.app.ui.book.read.page.entities.PageDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAnimPageDelegateTest {

    @Test
    fun swipeMustEndPastSlopInItsOriginalDirection() {
        assertTrue(
            NoAnimSwipeDecision.shouldCommit(
                startX = 100f,
                endX = 20f,
                direction = PageDirection.NEXT,
                slopSquare = 25
            )
        )
        assertTrue(
            NoAnimSwipeDecision.shouldCommit(
                startX = 100f,
                endX = 180f,
                direction = PageDirection.PREV,
                slopSquare = 25
            )
        )
        assertFalse(
            NoAnimSwipeDecision.shouldCommit(
                startX = 100f,
                endX = 102f,
                direction = PageDirection.NEXT,
                slopSquare = 25
            )
        )
        assertFalse(
            NoAnimSwipeDecision.shouldCommit(
                startX = 100f,
                endX = 130f,
                direction = PageDirection.NEXT,
                slopSquare = 25
            )
        )
    }

    @Test
    fun pageHPreDrawGateRunsOnceAndCanBeCleared() {
        val gate = PageHPreDrawGate()
        var calls = 0
        gate.schedule { calls++ }

        assertTrue(gate.runOnce())
        assertFalse(gate.runOnce())
        assertTrue(calls == 1)

        gate.schedule { calls++ }
        gate.clear()
        assertFalse(gate.runOnce())
        assertTrue(calls == 1)
    }
}
