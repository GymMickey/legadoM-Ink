package io.legado.app.model.localBook

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import splitties.init.appCtx
import io.legado.app.utils.list
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 薄调度层：扫描默认书籍目录 → 过滤已在书架的 → 导入新书。
 * 不新增 Repository、不新增数据库查询、不新增通知渠道。
 */
object AutoImportManager {

    /**
     * 扫描 [AppConfig.defaultBookTreeUri] 并导入新书籍。
     * @return 导入成功数量，-1 表示目录未配置，0 表示无新书
     */
    suspend fun scanAndImport(context: Context): Int = withContext(Dispatchers.IO) {
        // 先清理文件已被删除的残影书架记录
        val cleaned = LocalBook.cleanMissingBooks()

        val treeUriStr = AppConfig.defaultBookTreeUri ?: return@withContext -1
        val rootDoc = FileDoc.fromUri(Uri.parse(treeUriStr), isDir = true)

        // BFS 扫描，收集不在书架的新文件 URI
        val newFiles = mutableListOf<Uri>()
        val queue = ArrayDeque<FileDoc>()
        queue.add(rootDoc)

        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            for (child in dir.list() ?: continue) {
                when {
                    child.isDir -> {
                        if (appCtx.getSharedPreferences("auto_scan", 0).getBoolean("scanSubDirs", false)) {
                            queue.add(child)
                        }
                    }
                    child.name.matches(AppPattern.bookFileRegex)
                        || child.name.matches(AppPattern.archiveFileRegex) -> {
                        if (!LocalBook.isOnBookShelf(child.name)) {
                            newFiles.add(child.uri)
                        }
                    }
                }
            }
        }

        if (newFiles.isEmpty()) return@withContext 0
        LocalBook.importFiles(newFiles)
        newFiles.size
    }
}
