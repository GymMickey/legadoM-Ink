package io.legado.app.service

import android.app.DownloadManager
import io.legado.app.model.Download
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import splitties.systemservices.downloadManager

/**
 * 下载任务数据类
 * @param id 系统DownloadManager返回的任务ID
 * @param url 下载URL
 * @param fileName 文件名
 * @param notificationId 通知ID
 * @param startTime 开始时间
 * @param status 下载状态
 * @param progress 下载进度(0-100)
 * @param totalSize 文件总大小(字节)
 * @param downloadedSize 已下载大小(字节)
 */
data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val notificationId: Int,
    val startTime: Long,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Int = 0,
    val totalSize: Int = 0,
    val downloadedSize: Int = 0,
    val speed: Long = 0,              // 下载速度 bytes/s
    val sourceUrl: String = "",       // 来源页面 URL
    val downloadUrl: String = ""      // 文件直链 URL
)

/**
 * 下载状态枚举
 */
enum class DownloadStatus {
    PENDING,      // 等待中
    RUNNING,      // 下载中
    PAUSED,       // 已暂停
    SUCCESSFUL,   // 已完成
    FAILED        // 下载失败
}

/**
 * 下载状态管理单例
 * 负责管理下载任务的内存存储和状态更新
 * 使用StateFlow提供响应式数据，供UI层订阅
 */
object DownloadState {
    
    // 任务列表的StateFlow，供UI订阅
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    // 任务Map，key为downloadId，value为DownloadTask
    private val taskMap = mutableMapOf<Long, DownloadTask>()

    // 用于计算瞬时速度：记录上一次轮询的已下载字节数
    private val lastDownloadedMap = mutableMapOf<Long, Int>()

    /**
     * 添加新下载任务
     */
    fun addTask(
        id: Long,
        url: String,
        fileName: String,
        notificationId: Int,
        sourceUrl: String = "",
        downloadUrl: String = ""
    ) {
        val task = DownloadTask(
            id = id,
            url = url,
            fileName = fileName,
            notificationId = notificationId,
            startTime = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
            downloadUrl = downloadUrl
        )
        taskMap[id] = task
        updateFlow()
    }

    /**
     * 更新任务状态和进度，附带速度计算
     */
    fun updateTask(
        id: Long,
        status: DownloadStatus,
        progress: Int = 0,
        totalSize: Int = 0,
        downloadedSize: Int = 0,
        sourceUrl: String? = null,
        downloadUrl: String? = null
    ) {
        taskMap[id]?.let { existing ->
            // 计算瞬时速度: 与上一次的差值 / 轮询间隔(1s)
            val lastDownloaded = lastDownloadedMap[id] ?: 0
            val speed = if (status == DownloadStatus.RUNNING && downloadedSize > lastDownloaded) {
                (downloadedSize - lastDownloaded).toLong()
            } else if (status == DownloadStatus.RUNNING) {
                existing.speed // 保持上次速度（防止瞬间为0跳动）
            } else {
                0L
            }
            lastDownloadedMap[id] = downloadedSize

            taskMap[id] = existing.copy(
                status = status,
                progress = progress,
                totalSize = totalSize,
                downloadedSize = downloadedSize,
                speed = speed,
                sourceUrl = sourceUrl ?: existing.sourceUrl,
                downloadUrl = downloadUrl ?: existing.downloadUrl
            )
            updateFlow()
        }
    }

    /**
     * 移除任务
     */
    fun removeTask(id: Long) {
        taskMap.remove(id)
        lastDownloadedMap.remove(id)
        updateFlow()
    }

    /**
     * 获取单个任务
     */
    fun getTask(id: Long): DownloadTask? = taskMap[id]

    /**
     * 获取所有任务列表
     */
    fun getAllTasks(): List<DownloadTask> = taskMap.values.toList()

    /**
     * 检查指定URL是否已在下载列表中
     */
    fun hasTask(url: String): Boolean = taskMap.values.any { it.url == url }

    /**
     * 清空所有任务
     */
    fun clear() {
        taskMap.clear()
        lastDownloadedMap.clear()
        updateFlow()
    }

    /**
     * 更新StateFlow，按开始时间倒序排列
     */
    private fun updateFlow() {
        _tasks.value = taskMap.values.toList().sortedByDescending { it.startTime }
    }

    /**
     * 取消下载
     * 调用系统DownloadManager移除任务，并从内存中删除
     */
    fun cancelDownload(id: Long) {
        downloadManager.remove(id)
        removeTask(id)
    }

    /**
     * 重试下载
     * 先移除旧任务，再重新启动下载
     */
    fun retryDownload(context: android.content.Context, id: Long) {
        val task = taskMap[id] ?: return
        downloadManager.remove(id)
        removeTask(id)
        Download.start(context, task.url, task.fileName, task.sourceUrl.ifEmpty { null })
    }

    /**
     * 查询所有任务的状态
     * 通过系统DownloadManager查询每个任务的进度和状态
     * @return 更新后的任务列表
     */
    fun queryAllTaskStatus(): List<DownloadTask> {
        if (taskMap.isEmpty()) return emptyList()
        
        val ids = taskMap.keys.toLongArray()
        val query = DownloadManager.Query().setFilterById(*ids)
        
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return emptyList()
            
            val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val progressIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            
            do {
                val taskId = cursor.getLong(idIndex)
                val downloaded = cursor.getInt(progressIndex)
                val total = cursor.getInt(fileSizeIndex)
                val progress = if (total > 0) (downloaded * 100 / total) else 0
                val status = when (cursor.getInt(statusIndex)) {
                    DownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                    DownloadManager.STATUS_PENDING -> DownloadStatus.PENDING
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.RUNNING
                    DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.SUCCESSFUL
                    DownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                    else -> DownloadStatus.PENDING
                }
                updateTask(taskId, status, progress, total, downloaded)
            } while (cursor.moveToNext())
        }
        
        return getAllTasks()
    }
}
