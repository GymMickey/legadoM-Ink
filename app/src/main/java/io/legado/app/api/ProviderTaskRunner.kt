package io.legado.app.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlin.coroutines.ContinuationInterceptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs a synchronous ContentProvider task on the provider's IO scope.
 *
 * ContentProvider methods remain synchronous: the caller waits for completion or timeout. The
 * scope only keeps controller work, including legacy blocking bridges, off the Binder thread.
 */
internal class ProviderTaskRunner(
    private val scope: CoroutineScope,
    private val cancellationWaitMs: Long = DEFAULT_CANCELLATION_WAIT_MS,
) {

    init {
        require(cancellationWaitMs >= 0) { "cancellationWaitMs must not be negative" }
    }

    fun <T> execute(timeoutMs: Long, block: suspend () -> T): T {
        require(timeoutMs > 0) { "timeoutMs must be positive" }

        val result = AtomicReference<Result<T>?>(null)
        val completed = CountDownLatch(1)
        val job: Job = scope.launch {
            try {
                val taskContext = currentCoroutineContext()
                // Some legacy controllers bridge synchronous APIs with runBlocking. Keep that
                // bridge on IO, inherit the task Job, and make cancellation interrupt the
                // underlying blocking call.
                result.set(runCatching {
                    runInterruptible {
                        runBlocking(taskContext.minusKey(ContinuationInterceptor)) { block() }
                    }
                })
            } finally {
                completed.countDown()
            }
        }

        if (!completed.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            job.cancel(CancellationException("Provider operation timed out"))
            // A timed-out write/delete must not continue after the ContentProvider call returns.
            // The grace period is bounded as well, so cancellation cannot make the caller wait
            // forever if a legacy operation ignores interruption.
            completed.await(cancellationWaitMs, TimeUnit.MILLISECONDS)
            throw TimeoutException()
        }

        return result.get()?.getOrThrow()
            ?: throw IllegalStateException("Provider task did not complete")
    }

    fun shutdown() {
        scope.cancel()
    }

    class TimeoutException : Exception()

    private companion object {
        const val DEFAULT_CANCELLATION_WAIT_MS = 1_000L
    }
}
