package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.ReadConstants
import io.legado.app.data.appDb
import io.legado.app.help.book.update
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.config.AppConfig
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import splitties.systemservices.notificationManager
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 缓存书籍服务
 */
class CacheBookService : BaseService() {

    companion object {
        var isRun = false
            private set
    }

    private val threadCount = AppConfig.threadCount
    private var cachePool =
        Executors.newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    private var downloadJob: Job? = null
    private var notificationContent = appCtx.getString(R.string.service_starting)
    private var mutex = Mutex()
    /**
     * 已注入缓存并发率的书源 key 列表，用于去重和恢复
     */
    private val sourceKeyOrder = mutableListOf<String>()
    private val notificationBuilder by lazy {
        val builder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.offline_cache))
            .setContentIntent(activityPendingIntent<CacheActivity>("cacheActivity"))
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.cancel),
            servicePendingIntent<CacheBookService>(IntentAction.stop)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        lifecycleScope.launch {
            while (isActive) {
                delay(ReadConstants.NOTIFICATION_UPDATE_INTERVAL_MS)
                notificationContent = CacheBook.downloadSummary
                upCacheBookNotification()
                postEvent(EventBus.UP_DOWNLOAD, "")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                IntentAction.start -> addDownloadData(
                    intent.getStringExtra("bookUrl"),
                    intent.getIntExtra("start", 0),
                    intent.getIntExtra("end", 0)
                )

                IntentAction.remove -> removeDownload(intent.getStringExtra("bookUrl"))
                IntentAction.stop -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        isRun = false
        restoreAllRates()
        cachePool.close()
        CacheBook.close()
        super.onDestroy()
        postEvent(EventBus.UP_DOWNLOAD, "")
    }

    private fun addDownloadData(bookUrl: String?, start: Int, end: Int) {
        bookUrl ?: return
        execute {
            val cacheBook = CacheBook.getOrCreate(bookUrl) ?: return@execute
            // getOrCreate 内部的 updateBookSource 会用数据库副本覆盖所有同源模型的 bookSource，
            // 因此必须在调用后立即重新注入缓存并发率
            applyRateToAll()
            val chapterCount = appDb.bookChapterDao.getChapterCount(bookUrl)
            val book = cacheBook.book
            AppLog.put("📥开始缓存《${book.name}》章节范围:$start-$end")
            if (chapterCount == 0) {
                cacheBook.setLoading()
                mutex.withLock {
                    val name = book.name
                    if (book.tocUrl.isEmpty()) {
                        kotlin.runCatching {
                            WebBook.getBookInfoAwait(cacheBook.bookSource, book)
                        }.onFailure {
                            removeDownload(bookUrl)
                            val msg = "《$name》目录为空且加载详情页失败\n${it.localizedMessage}"
                            AppLog.put(msg, it, true)
                            return@execute
                        }
                    }
                    WebBook.getChapterListAwait(cacheBook.bookSource, book).onFailure {
                        if (book.totalChapterNum > 0) {
                            book.totalChapterNum = 0
                            book.update()
                        }
                        removeDownload(bookUrl)
                        val msg = "《$name》目录为空且加载目录失败\n${it.localizedMessage}"
                        AppLog.put(msg, it, true)
                        return@execute
                    }.getOrNull()?.let { toc ->
                        appDb.bookChapterDao.insert(*toc.toTypedArray())
                    }
                    book.update()
                }
            }
            val end2 = if (end < 0) {
                book.lastChapterIndex
            } else {
                min(end, book.lastChapterIndex)
            }
            cacheBook.addDownload(start, end2)
            notificationContent = CacheBook.downloadSummary
            upCacheBookNotification()
        }.onFinally {
            if (downloadJob == null) {
                download()
            }
        }
    }

    private fun removeDownload(bookUrl: String?) {
        CacheBook.cacheBookMap[bookUrl]?.stop()
        postEvent(EventBus.UP_DOWNLOAD, "")
        if (downloadJob == null && CacheBook.isRun) {
            download()
            return
        }
        if (CacheBook.cacheBookMap.isEmpty()) {
            stopSelf()
        }
    }

    /**
     * 将用户缓存并发率注入所有待缓存书籍的书源
     * 取 effectiveRate(用户设置, 书源原有)，选择限制更严格的一方
     * 同时更新 BookSource.concurrentRate 对象字段和 ConcurrentRateLimiter 的全局记录
     */
    private fun applyRateToAll() {
        val userRate = AppConfig.cacheConcurrentRate
        if (userRate.isNullOrBlank()) return
        CacheBook.cacheBookMap.values.forEach { model ->
            val source = model.bookSource
            val key = source.bookSourceUrl
            if (key !in sourceKeyOrder) {
                sourceKeyOrder.add(key)
            }
            val effective = ConcurrentRateLimiter.effectiveRate(
                userRate,
                source.concurrentRate
            )
            if (effective != null && effective != source.concurrentRate) {
                source.concurrentRate = effective
                ConcurrentRateLimiter.updateConcurrentRate(key, effective)
            }
        }
    }

    /**
     * 清理注入痕迹，恢复所有被修改书源的并发率记录
     * 清除 ConcurrentRateLimiter.concurrentRecordMap 中对应 key 的条目
     */
    private fun restoreAllRates() {
        CacheBook.cacheBookMap.values.forEach { model ->
            val key = model.bookSource.bookSourceUrl
            ConcurrentRateLimiter.concurrentRecordMap.remove(key)
        }
        sourceKeyOrder.clear()
    }

    /**
     * 启动缓存下载任务
     * 注入缓存并发率 → 启动 startProcessJob → 完成后恢复原始并发率
     */
    private fun download() {
        sourceKeyOrder.clear()
        applyRateToAll()
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch(cachePool) {
            CacheBook.startProcessJob(cachePool)
            AppLog.put("缓存任务全部完成")
            restoreAllRates()
            stopSelf()
        }
    }

    private fun upCacheBookNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        notificationManager.notify(NotificationId.CacheBookService, notification)
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        notificationBuilder.setContentText(notificationContent)
        val notification = notificationBuilder.build()
        startForeground(NotificationId.CacheBookService, notification)
    }

}
