package io.legado.app.ui.module

import io.legado.app.model.CacheBook
import io.legado.app.service.CacheBookService
import io.legado.app.service.DownloadService
import io.legado.app.service.DownloadStatus
import io.legado.app.service.WebService

/**
 * 模块运行状态枚举
 */
enum class ModuleRunStatus {
    RUNNING,   // 运行中
    IDLE,      // 空闲
    ERROR,     // 异常
    DISABLED   // 未启用
}

/**
 * 模块状态项
 * @param name 模块名称
 * @param status 当前运行状态
 */
data class ModuleStatusItem(
    val name: String,
    val status: ModuleRunStatus
)

/**
 * 模块状态提供者（单例）
 * 负责从各服务收集运行状态快照
 */
object ModuleStatusProvider {

    /**
     * 获取所有模块的状态快照
     * @return 模块状态列表
     */
    fun snapshot(): List<ModuleStatusItem> {
        val downloadTasks = DownloadService.getAllTasks()
        return listOf(
            // Web服务：检查WebService.isRun静态标志
            ModuleStatusItem(
                name = "Web 服务",
                status = if (WebService.isRun) ModuleRunStatus.RUNNING else ModuleRunStatus.IDLE
            ),
            // 离线缓存：优先检查错误，再检查运行状态
            ModuleStatusItem(
                name = "离线缓存",
                status = when {
                    CacheBook.errorDownloadMap.isNotEmpty() -> ModuleRunStatus.ERROR
                    CacheBookService.isRun || CacheBook.isRun -> ModuleRunStatus.RUNNING
                    else -> ModuleRunStatus.IDLE
                }
            ),
            // 文件下载：遍历任务状态，错误优先
            ModuleStatusItem(
                name = "文件下载",
                status = when {
                    downloadTasks.any { it.status == DownloadStatus.FAILED } -> ModuleRunStatus.ERROR
                    downloadTasks.any {
                        it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PENDING
                    } -> ModuleRunStatus.RUNNING
                    else -> ModuleRunStatus.IDLE
                }
            ),
            // 以下模块暂未实现状态检测，预留扩展
            ModuleStatusItem(
                name = "书源搜索",
                status = ModuleRunStatus.IDLE
            ),
            ModuleStatusItem(
                name = "订阅模块",
                status = ModuleRunStatus.IDLE
            ),
            ModuleStatusItem(
                name = "备份同步",
                status = ModuleRunStatus.IDLE
            )
        )
    }
}
