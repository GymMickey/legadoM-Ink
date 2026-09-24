package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreStageTimerTest {
    @Test
    fun `aggregates monotonic stage timings without sensitive fields`() {
        var clock = 100L
        val timer = RestoreStageTimer { clock }

        timer.measure("json_parse", itemCount = 2) {
            clock += 7
        }
        timer.measure("json_parse", itemCount = 3) {
            clock += 5
        }

        val timing = timer.snapshot().single()
        assertEquals("json_parse", timing.stage)
        assertEquals(12L, timing.elapsedMs)
        assertEquals(5, timing.itemCount)
        assertFalse(timing.stage.contains("/"))
        assertTrue(timing.elapsedMs >= 0)
    }

    @Test
    fun `clear prevents state leaking into the next restore`() {
        val timer = RestoreStageTimer { 10L }
        timer.record("db_write", 4, 1)
        timer.clear()
        assertTrue(timer.snapshot().isEmpty())
    }
}
