package io.legado.app.ui.download

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.service.DownloadState
import io.legado.app.utils.toastOnUi
import io.legado.app.service.DownloadStatus
import io.legado.app.service.DownloadTask
import io.legado.app.service.DownloadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DownloadTab(val label: String) {
    ALL("全部"),
    DOWNLOADING("下载中"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败")
}

/**
 * 下载管理ViewModel
 * 负责管理UI状态、轮询下载进度、执行下载操作
 */
class DownloadManageViewModel(application: Application) : BaseViewModel(application) {

    // 任务列表StateFlow，供UI订阅
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    // 当前选中的 Tab
    private val _selectedTab = MutableStateFlow(DownloadTab.ALL)
    val selectedTab: StateFlow<DownloadTab> = _selectedTab.asStateFlow()

    // 过滤后的任务列表
    val filteredTasks: StateFlow<List<DownloadTask>> = combine(
        _tasks, _selectedTab
    ) { tasks, tab ->
        when (tab) {
            DownloadTab.ALL -> tasks
            DownloadTab.DOWNLOADING -> tasks.filter {
                it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING
            }
            DownloadTab.PAUSED -> tasks.filter { it.status == DownloadStatus.PAUSED }
            DownloadTab.COMPLETED -> tasks.filter { it.status == DownloadStatus.SUCCESSFUL }
            DownloadTab.FAILED -> tasks.filter { it.status == DownloadStatus.FAILED }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun selectTab(tab: DownloadTab) {
        _selectedTab.value = tab
    }

    // 轮询任务Job
    private var pollJob: Job? = null

    init {
        startPolling()
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    /**
     * 启动轮询任务
     * 每500ms查询一次下载状态
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                val updatedTasks = DownloadState.queryAllTaskStatus()
                _tasks.value = updatedTasks
                delay(500)
            }
        }
    }

    /**
     * 停止轮询任务
     */
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * 取消下载
     * @param id 下载任务ID
     */
    fun cancelDownload(id: Long) {
        DownloadService.cancelDownload(id)
    }

    /**
     * 重试下载
     * @param context 上下文
     * @param id 下载任务ID
     */
    fun retryDownload(context: Context, id: Long) {
        DownloadService.retryDownload(context, id)
    }

    /**
     * 清除已完成的任务
     * 包括成功和失败的任务
     */
    fun clearCompletedTasks() {
        _tasks.value.filter { 
            it.status == DownloadStatus.SUCCESSFUL || it.status == DownloadStatus.FAILED 
        }.forEach {
            DownloadState.removeTask(it.id)
        }
    }

    /**
     * 清除所有任务
     */
    fun clearAllTasks() {
        DownloadService.clearAllTasks()
    }

    /**
     * 打开已下载的文件
     * @param context 上下文
     * @param id 下载任务ID
     */
    fun openFile(context: Context, id: Long) {
        val task = DownloadState.getTask(id) ?: return
        kotlin.runCatching {
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            downloadManager.getUriForDownloadedFile(id)?.let { uri ->
                val mimeType = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(task.fileName.substringAfterLast(".", "")) ?: "*/*"
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        }.onFailure {
            it.printStackTrace()
            context.toastOnUi("无法打开文件")
        }
    }

    /**
     * 打开下载文件所在的文件夹
     * @param context 上下文
     */
    fun openFolder(context: Context) {
        kotlin.runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "resource/folder"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            // 降级：打开系统下载管理器
            kotlin.runCatching {
                val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                context.startActivity(intent)
            }.onFailure { e ->
                context.toastOnUi("无法打开文件夹")
            }
        }
    }

    /**
     * 复制文件路径到剪贴板
     * @param context 上下文
     * @param id 下载任务ID
     */
    fun copyPath(context: Context, id: Long) {
        val task = DownloadState.getTask(id) ?: return
        val filePath =
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath}/${task.fileName}"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("file path", filePath))
        context.toastOnUi("已复制路径")
    }

}
