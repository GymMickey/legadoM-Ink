package io.legado.app.lib.eink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IReaderPageHTest {

    @Test
    fun nextDirectionUsesRotationMapping() {
        assertEquals(1, IReaderPageH.calculateEffect(true, 0, 2))
        assertEquals(4, IReaderPageH.calculateEffect(true, 1, 2))
        assertEquals(2, IReaderPageH.calculateEffect(true, 2, 2))
        assertEquals(3, IReaderPageH.calculateEffect(true, 3, 2))
    }

    @Test
    fun previousDirectionUsesRotationMapping() {
        assertEquals(2, IReaderPageH.calculateEffect(false, 0, 2))
        assertEquals(3, IReaderPageH.calculateEffect(false, 1, 2))
        assertEquals(1, IReaderPageH.calculateEffect(false, 2, 2))
        assertEquals(4, IReaderPageH.calculateEffect(false, 3, 2))
    }

    @Test
    fun speedBitsBuildExpectedEffect() {
        assertEquals(129, IReaderPageH.calculateEffect(true, 0, 0))
        assertEquals(65, IReaderPageH.calculateEffect(true, 0, 1))
        assertEquals(1, IReaderPageH.calculateEffect(true, 0, 2))
    }

    @Test
    fun speedIndexIsClamped() {
        assertEquals(129, IReaderPageH.calculateEffect(true, 0, -1))
        assertEquals(1, IReaderPageH.calculateEffect(true, 0, 99))
    }

    @Test
    fun unavailableBridgeReturnsFalseWithoutThrowing() {
        val controller = IReaderPageH.Controller(bridgeFactory = { null })

        assertEquals(IReaderPageH.Capability.UNAVAILABLE, controller.capability())
        assertFalse(controller.prepare(true, 0, 1))
    }

    @Test
    fun prepareCallsCommandBeforeForceModeOnce() {
        val calls = mutableListOf<String>()
        val bridge = object : IReaderPageH.Bridge {
            override fun postCommand(command: String) {
                calls += "command:$command"
            }

            override fun setForceNextPostMode(mode: Int) {
                calls += "force:$mode"
            }
        }
        val controller = IReaderPageH.Controller(bridgeFactory = { bridge })

        assertTrue(controller.prepare(true, 0, 1))
        assertEquals(
            listOf("command:next-effect-type 65", "force:16777315"),
            calls
        )
    }

    @Test
    fun invocationFailureMarksControllerFailedAndDoesNotRetry() {
        var factoryCalls = 0
        var commandCalls = 0
        val controller = IReaderPageH.Controller(
            bridgeFactory = {
                factoryCalls++
                object : IReaderPageH.Bridge {
                    override fun postCommand(command: String) {
                        commandCalls++
                        error("unsupported firmware")
                    }

                    override fun setForceNextPostMode(mode: Int) = Unit
                }
            }
        )

        assertFalse(controller.prepare(true, 0, 1))
        assertFalse(controller.prepare(true, 0, 1))
        assertEquals(IReaderPageH.Capability.FAILED, controller.capability())
        assertEquals(1, factoryCalls)
        assertEquals(1, commandCalls)
    }
}
