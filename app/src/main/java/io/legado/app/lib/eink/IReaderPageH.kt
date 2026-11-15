package io.legado.app.lib.eink

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import java.lang.reflect.Method

/**
 * Optional adapter for the private iReader PAGE_H display effect.
 *
 * The class and methods are resolved lazily and only once per process. A failed
 * invocation permanently disables the adapter for the current process.
 */
object IReaderPageH {

    const val FORCE_NEXT_PAGE_H = 0x01000063

    private val nextDirections = intArrayOf(1, 4, 2, 3)
    private val previousDirections = intArrayOf(2, 3, 1, 4)
    private val speedBits = intArrayOf(128, 64, 0)

    enum class Capability {
        UNAVAILABLE,
        AVAILABLE,
        FAILED
    }

    internal interface Bridge {
        fun postCommand(command: String)
        fun setForceNextPostMode(mode: Int)
    }

    internal class Controller(
        private val bridgeFactory: () -> Bridge?,
        private val onInvocationFailure: (Throwable) -> Unit = {}
    ) {
        private var resolved = false
        private var bridge: Bridge? = null
        private var state = Capability.UNAVAILABLE

        @Synchronized
        fun capability(): Capability {
            if (!resolved) {
                resolved = true
                bridge = runCatching { bridgeFactory() }.getOrNull()
                state = if (bridge == null) Capability.UNAVAILABLE else Capability.AVAILABLE
            }
            return state
        }

        @Synchronized
        fun prepare(forward: Boolean, rotation: Int, speedIndex: Int): Boolean {
            if (capability() != Capability.AVAILABLE) return false
            val activeBridge = bridge ?: return false
            val effect = calculateEffect(forward, rotation, speedIndex)
            return try {
                activeBridge.postCommand("next-effect-type $effect")
                activeBridge.setForceNextPostMode(FORCE_NEXT_PAGE_H)
                true
            } catch (error: Throwable) {
                state = Capability.FAILED
                bridge = null
                onInvocationFailure(error)
                false
            }
        }
    }

    private val controller = Controller(::createReflectionBridge) {
        AppConfig.iReaderPageHEnabled = false
        AppLog.putReaderDebug("掌阅 PAGE_H 调用失败，已自动关闭")
    }

    fun capability(): Capability = controller.capability()

    fun isAvailable(): Boolean = capability() == Capability.AVAILABLE

    fun prepare(forward: Boolean, rotation: Int, speedIndex: Int): Boolean {
        return controller.prepare(forward, rotation, speedIndex)
    }

    internal fun calculateEffect(forward: Boolean, rotation: Int, speedIndex: Int): Int {
        val directions = if (forward) nextDirections else previousDirections
        val direction = directions[rotation.and(3)]
        val speed = speedBits[speedIndex.coerceIn(0, speedBits.lastIndex)]
        return direction or speed
    }

    private fun createReflectionBridge(): Bridge? {
        return runCatching {
            val epdcClass = Class.forName("android.eink.EPDCDevice")
            val postCommand = epdcClass.getMethod("nativePostCommand", String::class.java)
            val setForceNextPostMode = epdcClass.getMethod(
                "setForceNextPostMode",
                Int::class.javaPrimitiveType
            )
            ReflectionBridge(postCommand, setForceNextPostMode)
        }.getOrNull()
    }

    private class ReflectionBridge(
        private val postCommandMethod: Method,
        private val forceNextPostModeMethod: Method
    ) : Bridge {

        override fun postCommand(command: String) {
            postCommandMethod.invoke(null, command)
        }

        override fun setForceNextPostMode(mode: Int) {
            forceNextPostModeMethod.invoke(null, mode)
        }
    }
}
