package io.legado.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.api.ProviderTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun readerProviderDeclaresSignatureReadWritePermissions() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val packageManager = appContext.packageManager
        val provider = packageManager.getProviderInfo(
            ComponentName(appContext, io.legado.app.api.ReaderProvider::class.java),
            PackageManager.GET_META_DATA
        )
        val packageName = appContext.packageName
        val readPermission = "$packageName.permission.READ_READER_PROVIDER"
        val writePermission = "$packageName.permission.WRITE_READER_PROVIDER"

        assertTrue("ReaderProvider 不应退化为未保护的导出组件", provider.exported)
        assertEquals(readPermission, provider.readPermission)
        assertEquals(writePermission, provider.writePermission)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            packageManager.getPermissionInfo(readPermission, PackageManager.GET_META_DATA)
                .protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        )
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            packageManager.getPermissionInfo(writePermission, PackageManager.GET_META_DATA)
                .protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        )
    }

    @Test
    fun readerProviderAcceptsBookshelfQueryUri() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"
        appContext.contentResolver.query(
                Uri.Builder()
                    .scheme("content")
                    .authority(authority)
                    .appendPath("books")
                    .appendPath("query")
                    .build(),
                null,
                null,
                null,
                null
            ).use { cursor ->
                assertNotNull("ReaderProvider 应返回 Cursor", cursor)
                assertTrue("ReaderProvider Cursor 应包含结果行", cursor!!.moveToFirst())
                assertTrue("ReaderProvider 结果应为 JSON", cursor.getString(0).isNotBlank())
            }
    }

    @Test
    fun readerProviderRejectsUnknownAuthorityAndUri() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"

        assertNull(
            appContext.contentResolver.query(
                Uri.parse("content://${appContext.packageName}.unknown/books/query"),
                null,
                null,
                null,
                null
            )
        )
        assertNull(
            appContext.contentResolver.query(
                Uri.parse("content://$authority/unknown/query"),
                null,
                null,
                null,
                null
            )
        )
    }

    @Test
    fun readerProviderReturnsStructuredErrorForMissingQueryParameter() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val authority = "${appContext.packageName}.readerProvider"
        appContext.contentResolver.query(
            Uri.parse("content://$authority/bookSource/query"),
            null,
            null,
            null,
            null
        ).use { cursor ->
            assertNotNull(cursor)
            assertTrue(cursor!!.moveToFirst())
            assertTrue(cursor.getString(0).contains("url"))
        }
    }

    @Test
    fun providerTaskRunnerCompletesBeforeTimeout() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val runner = ProviderTaskRunner(scope, cancellationWaitMs = 200)
        try {
            assertEquals("done", runner.execute(2_000) {
                delay(20)
                "done"
            })
        } finally {
            runner.shutdown()
            dispatcher.close()
        }
    }

    @Test
    fun providerTaskRunnerTimeoutCancelsJobAndPreventsDelayedWrite() = runBlocking {
        // Use a dedicated dispatcher so the runner can return to its timeout clock while the
        // test task is suspended, instead of Unconfined executing the task inline.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val runner = ProviderTaskRunner(scope, cancellationWaitMs = 200)
        val writeCompleted = AtomicBoolean(false)
        val cancellationObserved = CountDownLatch(1)
        try {
            assertThrows(ProviderTaskRunner.TimeoutException::class.java) {
                runner.execute(500) {
                    try {
                        delay(5_000)
                        writeCompleted.set(true)
                    } catch (_: CancellationException) {
                        cancellationObserved.countDown()
                        throw CancellationException("test cancellation")
                    } finally {
                        if (!currentCoroutineContext().isActive) cancellationObserved.countDown()
                    }
                }
            }
            assertTrue("超时后任务应观察到取消", cancellationObserved.await(1_000, TimeUnit.MILLISECONDS))
            assertFalse("超时后任务不得继续写入", writeCompleted.get())
            delay(100)
            assertFalse("超时后任务不得延迟完成写入", writeCompleted.get())
        } finally {
            runner.shutdown()
            dispatcher.close()
        }
    }

    @Test
    fun providerTaskRunnerShutdownCancelsRunningTaskWithoutPermanentWait() {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val runner = ProviderTaskRunner(scope, cancellationWaitMs = 200)
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val caller = Thread {
            runCatching {
                runner.execute(5_000) {
                    started.countDown()
                    try {
                        delay(5_000)
                        completed.set(true)
                    } finally {
                        if (!currentCoroutineContext().isActive) cancelled.set(true)
                    }
                }
            }
        }
        caller.start()
        try {
            assertTrue("测试任务未及时启动", started.await(5, TimeUnit.SECONDS))
            runner.shutdown()
            caller.join(5_000)
            assertFalse("shutdown 后调用不应永久等待", caller.isAlive)
            assertTrue("shutdown 后任务应被取消", cancelled.get())
            assertFalse("shutdown 后任务不得完成写入", completed.get())
        } finally {
            runner.shutdown()
            if (caller.isAlive) {
                caller.interrupt()
                caller.join(1_000)
            }
            dispatcher.close()
        }
    }
}
