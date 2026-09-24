package io.legado.app.help.storage

import android.os.SystemClock

internal data class RestoreStageTiming(
    val stage: String,
    val elapsedMs: Long,
    val itemCount: Int?
)

/** Monotonic, local-only restore timing with no user or path data. */
internal class RestoreStageTimer(
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Accumulator(var elapsedMs: Long = 0, var itemCount: Int? = null)

    private val stages = linkedMapOf<String, Accumulator>()

    fun <T> measure(stage: String, itemCount: Int? = null, block: () -> T): T {
        val start = now()
        return try {
            block()
        } finally {
            record(stage, now() - start, itemCount)
        }
    }

    suspend fun <T> measureSuspend(
        stage: String,
        itemCount: Int? = null,
        block: suspend () -> T
    ): T {
        val start = now()
        return try {
            block()
        } finally {
            record(stage, now() - start, itemCount)
        }
    }

    fun record(stage: String, elapsedMs: Long, itemCount: Int? = null) {
        val accumulator = stages.getOrPut(stage) { Accumulator() }
        accumulator.elapsedMs += elapsedMs.coerceAtLeast(0)
        if (itemCount != null) {
            accumulator.itemCount = (accumulator.itemCount ?: 0) + itemCount.coerceAtLeast(0)
        }
    }

    fun snapshot(): List<RestoreStageTiming> = stages.map { (stage, value) ->
        RestoreStageTiming(stage, value.elapsedMs, value.itemCount)
    }

    fun clear() = stages.clear()
}
