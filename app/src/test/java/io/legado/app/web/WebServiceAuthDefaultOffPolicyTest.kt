package io.legado.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServiceAuthDefaultOffPolicyTest {
    @Test
    fun firstReadDisablesAuthAndMarksMigration() {
        val state = WebServiceAuthDefaultOffPolicy.migrate(currentEnabled = true, migrated = false)
        assertFalse(state.enabled)
        assertTrue(state.migrated)
    }

    @Test
    fun laterUserChoiceIsPreserved() {
        val state = WebServiceAuthDefaultOffPolicy.migrate(currentEnabled = true, migrated = true)
        assertTrue(state.enabled)
        assertTrue(state.migrated)
    }
}
