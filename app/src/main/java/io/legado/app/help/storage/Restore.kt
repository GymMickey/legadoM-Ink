package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import android.util.Xml
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.CoverGalleryGroup
import io.legado.app.data.entities.CoverGalleryImage
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HomepageCustomSet
import io.legado.app.data.entities.HomepageModule
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.ui.book.read.websearch.SearchEngine
import io.legado.app.ui.book.read.websearch.SearchEngineHelper
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.AppCacheManager
import io.legado.app.help.CacheManager
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.BookMatcher
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复管理类
 * 
 * 负责从备份文件恢复应用数据，包括：
 * - 解压备份ZIP文件
 * - 恢复数据库数据（书籍、书签、书源等）
 * - 恢复SharedPreferences配置
 * - 恢复自定义配置文件
 * 
 * 恢复流程：
 * 1. 解压ZIP文件到临时目录
 * 2. 读取JSON文件并导入数据库
 * 3. 恢复SharedPreferences配置
 * 4. 应用主题和阅读配置
 * 5. 清理临时文件
 * 
 * 特殊处理：
 * - 书籍数据：支持忽略本地书籍，更新已存在书籍
 * - 阅读记录：恢复前清空本地记录，再导入备份记录
 * - 服务器配置：需要解密
 * - WebDav密码：需要解密
 */
object Restore {
    private const val runtimeSourceCacheFileName = "runtimeSourceCache.json"
    private const val bookCacheFolderName = "book_cache"
    private const val bookCacheIndexFileName = "bookCacheIndex.json"
    private const val bookCacheBooksFileName = "bookCacheBooks.json"

    /** 互斥锁，防止并发恢复操作 */
    private val mutex = Mutex()

    private const val TAG = "Restore"

    private class RestoreExecution {
        val refresh = RestoreRefreshCoordinator(
            onBookshelfRefresh = { postEvent(EventBus.BOOKSHELF_REFRESH, "") }
        )
        val timer = RestoreStageTimer { android.os.SystemClock.elapsedRealtime() }
    }

    private fun newExecution() = RestoreExecution()

    private fun logTimings(execution: RestoreExecution) {
        execution.timer.snapshot().forEach { timing ->
            val count = timing.itemCount?.let { ", count=$it" }.orEmpty()
            AppLog.put("恢复阶段 ${timing.stage}: ${timing.elapsedMs}ms$count")
        }
        execution.timer.clear()
    }

    private fun isReadConfigSelected(selectedFiles: Set<String>): Boolean =
        ReadBackgroundBackupPolicy.readConfigFileName in selectedFiles

    private fun isReadShareConfigSelected(selectedFiles: Set<String>): Boolean =
        ReadBackgroundBackupPolicy.shareConfigFileName in
            ReadBackgroundBackupPolicy.selectedReadConfigNames(selectedFiles)
    /**
     * 从URI恢复备份
     * 支持SAF（Storage Access Framework）和普通文件路径
     * 
     * @param context Android Context
     * @param uri 备份文件URI
     */
    suspend fun restore(
        context: Context,
        uri: Uri,
        onProgress: ((String) -> Unit)? = null
    ) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        val execution = newExecution()
        execution.refresh.begin()
        try {
            onProgress?.invoke(BackupInfoHelper.getDisplayName("unzipBackup"))
            FileUtils.delete(Backup.backupPath)
            execution.timer.measureSuspend("zip_unzip") {
                if (uri.isContentScheme()) {
                    DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                        ZipUtils.unZipToPath(it, Backup.backupPath)
                    }
                } else {
                    ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
                }
            }
        } catch (e: CancellationException) {
            execution.refresh.cancel()
            execution.timer.clear()
            throw e
        } catch (e: Exception) {
            execution.refresh.cancel()
            logTimings(execution)
            AppLog.put("复制解压文件出错\n${e.localizedMessage}", e)
            return
        }
        try {
            mutex.withLock {
                execution.timer.measureSuspend("restore_total") {
                    restore(Backup.backupPath, onProgress, execution)
                }
            }
            applyRestoreUi()
            LocalConfig.lastBackup = System.currentTimeMillis()
            LocalConfig.lastRestore = System.currentTimeMillis()
            execution.refresh.finish()
            logTimings(execution)
        } catch (e: CancellationException) {
            execution.refresh.cancel()
            execution.timer.clear()
            throw e
        } catch (e: Exception) {
            execution.refresh.cancel()
            logTimings(execution)
            appCtx.toastOnUi("恢复备份出错\n${e.localizedMessage}")
            AppLog.put("恢复备份出错\n${e.localizedMessage}", e)
        }
    }

    /**
     * 带锁的恢复方法
     * 使用互斥锁确保同一时间只有一个恢复操作在执行
     * 
     * @param path 备份文件解压后的目录路径
     */
    suspend fun restoreLocked(
        path: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        val execution = newExecution()
        execution.refresh.begin()
        try {
            mutex.withLock {
                execution.timer.measureSuspend("restore_total") {
                    restore(path, onProgress, execution)
                }
            }
            applyRestoreUi()
            execution.refresh.finish()
            logTimings(execution)
        } catch (e: CancellationException) {
            execution.refresh.cancel()
            execution.timer.clear()
            throw e
        } catch (e: Exception) {
            execution.refresh.cancel()
            logTimings(execution)
            throw e
        }
    }

    /**
     * 选择性恢复方法
     * 只恢复用户选中的文件
     * 
     * @param context Android Context
     * @param path 已解压的备份目录路径
     * @param selectedFiles 选中的文件名列表
     */
    suspend fun restoreSelected(
        context: Context,
        path: String,
        selectedFiles: List<String>,
        onProgress: ((String) -> Unit)? = null
    ) {
        LogUtils.d(TAG, "开始选择性恢复备份 path:$path, files:${selectedFiles.joinToString()}")
        val execution = newExecution()
        execution.refresh.begin()
        try {
            mutex.withLock {
                execution.timer.measureSuspend("restore_selected_total") {
                    restoreSelectedFiles(path, selectedFiles, onProgress, execution)
                }
            }
            applyRestoreUi()
            LocalConfig.lastBackup = System.currentTimeMillis()
            LocalConfig.lastRestore = System.currentTimeMillis()
            execution.refresh.finish()
            logTimings(execution)
        } catch (e: CancellationException) {
            execution.refresh.cancel()
            execution.timer.clear()
            throw e
        } catch (e: Exception) {
            execution.refresh.cancel()
            logTimings(execution)
            appCtx.toastOnUi("恢复备份出错\n${e.localizedMessage}")
            AppLog.put("选择性恢复备份出错\n${e.localizedMessage}", e)
        }
    }

    /**
     * 核心选择性恢复逻辑
     * 
     * @param path 备份文件解压后的目录路径
     * @param selectedFiles 选中的文件名列表
     */
    private suspend fun restoreSelectedFiles(
        path: String,
        selectedFiles: List<String>,
        onProgress: ((String) -> Unit)? = null,
        execution: RestoreExecution
    ) {
        val aes = BackupAES()
        val selectedSet = BackupFileMappingPolicy.expandLogicalSelection(selectedFiles)
        fun progress(fileName: String) {
            onProgress?.invoke(BackupInfoHelper.getDisplayName(fileName))
        }

        // 恢复书架数据
        if ("bookshelf.json" in selectedSet) {
            progress("bookshelf.json")
            when (val result = readBooksResult(path, execution.timer)) {
                is BackupJsonResult.Valid -> replaceBooks(result.data, execution.timer)
                BackupJsonResult.Missing,
                BackupJsonResult.Invalid -> Unit
            }
        }

        // 恢复书签
        if ("bookmark.json" in selectedSet) {
            progress("bookmark.json")
            when (val result = readBackupListResult<Bookmark>(path, "bookmark.json", execution.timer)) {
                is BackupJsonResult.Valid -> replaceBookmarks(result.data, execution.timer)
                BackupJsonResult.Missing,
                BackupJsonResult.Invalid -> Unit
            }
        }

        // 恢复书籍分组
        if ("bookGroup.json" in selectedSet) {
            progress("bookGroup.json")
            restoreBookGroups(path, execution.timer)
        }

        // 恢复书源
        if ("bookSource.json" in selectedSet) {
            progress("bookSource.json")
            when (val result = readBookSourcesResult(path, execution.timer)) {
                is BackupJsonResult.Valid -> replaceBookSources(result.data, execution.timer)
                BackupJsonResult.Missing,
                BackupJsonResult.Invalid -> Unit
            }
        }

        // 恢复RSS源
        if ("rssSources.json" in selectedSet) {
            progress("rssSources.json")
            restoreRssSources(path, execution.timer)
        }

        // 恢复RSS收藏
        if ("rssStar.json" in selectedSet) {
            progress("rssStar.json")
            restoreRssStars(path, execution.timer)
        }

        // 恢复源订阅链接
        if ("sourceSub.json" in selectedSet) {
            progress("sourceSub.json")
            restoreSourceSubs(path, execution.timer)
        }

        // 恢复搜索引擎规则
        if ("webSearchEngines.json" in selectedSet) {
            progress("webSearchEngines.json")
            val enginesFile = File(path, "webSearchEngines.json")
            if (enginesFile.exists()) {
                try {
                    val enginesJson = enginesFile.readText()
                    val engines = GSON.fromJsonArray<SearchEngine>(enginesJson).getOrNull()
                    if (engines != null) {
                        SearchEngineHelper.saveSearchEngines(appCtx, engines)
                    }
                } catch (e: Exception) {
                    AppLog.put("恢复搜索引擎规则出错\n${e.localizedMessage}", e)
                }
            }
        }

        // 恢复首页数据
        if ("homepage.json" in selectedSet) {
            progress("homepage.json")
            restoreHomepage(path, execution.timer)
        }

        // 恢复替换规则
        if ("replaceRule.json" in selectedSet) {
            progress("replaceRule.json")
            restoreReplaceRules(path, execution.timer)
        }

        // 恢复搜索历史
        if (HighlightRuleStore.backupFileName in selectedSet) {
            progress(HighlightRuleStore.backupFileName)
            File(path, HighlightRuleStore.backupFileName).takeIf { it.exists() }?.runCatching {
                GSON.fromJsonObject<HighlightRuleStore.BackupData>(readText()).getOrNull()?.let {
                    HighlightRuleStore.restoreBackupData(appCtx, it, path)
                }
            }?.onFailure {
                AppLog.put("鎭㈠楂樹寒瑙勫垯鍑洪敊\n${it.localizedMessage}", it)
            }
        }
        if ("searchHistory.json" in selectedSet) {
            progress("searchHistory.json")
            restoreSearchHistory(path, execution.timer)
        }

        // 恢复TXT目录规则
        if ("txtTocRule.json" in selectedSet) {
            progress("txtTocRule.json")
            restoreTxtTocRules(path, execution.timer)
        }

        // 恢复词典规则
        if ("dictRule.json" in selectedSet) {
            progress("dictRule.json")
            restoreDictRules(path, execution.timer)
        }

        // 恢复键盘辅助
        if ("keyboardAssists.json" in selectedSet) {
            progress("keyboardAssists.json")
            restoreKeyboardAssists(path, execution.timer)
        }

        if (CoverGalleryRepository.backupDirName in selectedSet) {
            progress(CoverGalleryRepository.backupDirName)
            restoreCoverGallery(path, execution.refresh, execution.timer)
        }

        // 恢复阅读记录
        if (BackupFileMappingPolicy.readRecordFileNames.any(selectedSet::contains)) {
            progress("readRecord.json")
            restoreReadRecordBundle(path, selectedSet, execution.timer)
        }

        // 恢复服务器配置
        if ("servers.json" in selectedSet) {
            progress("servers.json")
            restoreServers(path, aes, execution.timer)
        }

        // 恢复直链上传配置
        if (DirectLinkUpload.ruleFileName in selectedSet) {
            progress(DirectLinkUpload.ruleFileName)
            File(path, DirectLinkUpload.ruleFileName).takeIf { it.exists() }?.runCatching {
                val json = readText()
                ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
            }?.onFailure { AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it) }
        }

        // 恢复书评数据
        if ("bookReview.json" in selectedSet) {
            progress("bookReview.json")
            val srcFile = java.io.File(path, "bookReview.json")
            if (srcFile.exists()) {
                val destPath = io.legado.app.utils.FileUtils.getPath(
                    splitties.init.appCtx.filesDir, "bookReview.json"
                )
                srcFile.copyTo(java.io.File(destPath), overwrite = true)
            }
        }

        // 恢复主题配置
        if (!BackupConfig.ignoreThemeConfig && ThemeConfig.configFileName in selectedSet) {
            progress(ThemeConfig.configFileName)
            File(path, ThemeConfig.configFileName).takeIf { it.exists() }?.runCatching {
                val configs = GSON.fromJsonArray<ThemeConfig.Config>(readText()).getOrNull()
                FileUtils.delete(ThemeConfig.configFilePath)
                copyTo(File(ThemeConfig.configFilePath))
                ThemeConfig.replaceConfigs(configs)
            }?.onFailure { AppLog.put("恢复主题出错\n${it.localizedMessage}", it) }
        }

        // 恢复封面规则配置
        if (BookCover.configFileName in selectedSet) {
            progress(BookCover.configFileName)
            File(path, BookCover.configFileName).takeIf { it.exists() }?.runCatching {
                val json = readText()
                BookCover.saveCoverRule(json)
                // 清除封面缓存，确保使用新配置生成封面
                CoverImageView.clearAllCache()
            }?.onFailure { AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it) }
        }

        // 恢复阅读界面配置
        val selectedReadConfigs = buildSet {
            if (isReadConfigSelected(selectedSet)) add(ReadBookConfig.configFileName)
            if (isReadShareConfigSelected(selectedSet)) add(ReadBookConfig.shareConfigFileName)
        }
        if (!BackupConfig.ignoreReadConfig && selectedReadConfigs.isNotEmpty()) {
            progress("backgroundImages")
            execution.timer.measure("read_background_copy") {
                restoreReadConfigBackgrounds(path, selectedReadConfigs, ::progress)
            }
        }

        // 修正阅读背景图片路径
        if (!BackupConfig.ignoreReadConfig && selectedReadConfigs.isNotEmpty()) {
            fixReadConfigBackgroundPaths(selectedReadConfigs)
        }

        // 恢复SharedPreferences配置
        if ("config.xml" in selectedSet) {
            progress("config.xml")
            readBackupPrefs(path, "config")?.let { map ->
                if (!BackupConfig.ignoreThemeConfig) {
                    clearThemeRestorePrefs()
                }
                val edit = appCtx.defaultSharedPreferences.edit()
                map.forEach { (key, value) ->
                    if (BackupConfig.shouldRestorePreference(key)) {
                        when (key) {
                            PreferKey.webDavPassword -> {
                                kotlin.runCatching { aes.decryptStr(value.toString()) }.getOrNull()?.let {
                                    edit.putString(key, it)
                                } ?: let {
                                    if (appCtx.getPrefString(PreferKey.webDavPassword).isNullOrBlank()) {
                                        edit.putString(key, value.toString())
                                    }
                                }
                            }
                            else -> when (value) {
                                is Int -> edit.putInt(key, value)
                                is Boolean -> edit.putBoolean(key, value)
                                is Long -> edit.putLong(key, value)
                                is Float -> edit.putFloat(key, value)
                                is String -> edit.putString(key, value)
                            }
                        }
                    }
                }
                edit.apply()
            }
        }

        // 修正主题背景图片路径
        if (!BackupConfig.ignoreThemeConfig &&
            ("config.xml" in selectedSet || ThemeConfig.configFileName in selectedSet)
        ) {
            progress("themeBackgroundImages")
            execution.timer.measure("theme_background_copy") {
                restoreThemeBackgrounds(
                    backupPath = path,
                    clearExisting = "config.xml" in selectedSet || ThemeConfig.configFileName in selectedSet
                )
            }
            fixThemeBackgroundPaths()
            fixThemeConfigBackgroundPaths()
        }

        // 应用阅读配置
        if (runtimeSourceCacheFileName in selectedSet) {
            progress(runtimeSourceCacheFileName)
            restoreRuntimeSourceCaches(path, execution.timer)
        }

        // 恢复书籍缓存和章节目录
        LogUtils.d(TAG, "检查是否需要恢复书籍缓存")
        LogUtils.d(TAG, "selectedSet 内容: ${selectedSet.joinToString(", ")}")
        LogUtils.d(TAG, "bookCacheFolderName: $bookCacheFolderName, 是否在 selectedSet: ${bookCacheFolderName in selectedSet}")
        LogUtils.d(TAG, "bookCacheIndexFileName: $bookCacheIndexFileName, 是否在 selectedSet: ${bookCacheIndexFileName in selectedSet}")
        LogUtils.d(TAG, "bookCacheBooksFileName: $bookCacheBooksFileName, 是否在 selectedSet: ${bookCacheBooksFileName in selectedSet}")
        LogUtils.d(TAG, "bookChapterCache.json: bookChapterCache.json, 是否在 selectedSet: ${"bookChapterCache.json" in selectedSet}")
        
        if (
            bookCacheFolderName in selectedSet ||
            bookCacheIndexFileName in selectedSet ||
            bookCacheBooksFileName in selectedSet ||
            "bookChapterCache.json" in selectedSet
        ) {
            LogUtils.d(TAG, "满足书籍缓存恢复条件，开始恢复")
            progress(bookCacheFolderName)
            restoreBookCache(path, execution.refresh, execution.timer)
        } else {
            LogUtils.d(TAG, "不满足书籍缓存恢复条件，跳过")
        }

        if (!BackupConfig.ignoreReadConfig) {
            progress("applyRestoreConfig")
            ReadBookConfig.apply {
                comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
                readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
                shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
                hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
                hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
                autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
            }
        }

    }

    /**
     * 核心恢复逻辑
     * 
     * 执行步骤：
     * 1. 恢复数据库数据（书籍、书签、书源等）
     * 2. 恢复自定义配置文件（主题、阅读样式等）
     * 3. 恢复SharedPreferences配置
     * 4. 应用配置变更
     * 
     * @param path 备份文件解压后的目录路径
     */
    private suspend fun restore(
        path: String,
        onProgress: ((String) -> Unit)? = null,
        execution: RestoreExecution
    ) {
        val aes = BackupAES()
        fun progress(fileName: String) {
            onProgress?.invoke(BackupInfoHelper.getDisplayName(fileName))
        }

        // 恢复书架数据
        progress("bookshelf.json")
        when (val result = readBooksResult(path, execution.timer)) {
            is BackupJsonResult.Valid -> replaceBooks(result.data, execution.timer)
            BackupJsonResult.Missing,
            BackupJsonResult.Invalid -> Unit
        }

        // 恢复书签
        progress("bookmark.json")
        when (val result = readBackupListResult<Bookmark>(path, "bookmark.json", execution.timer)) {
            is BackupJsonResult.Valid -> replaceBookmarks(result.data, execution.timer)
            BackupJsonResult.Missing,
            BackupJsonResult.Invalid -> Unit
        }

        // 恢复书籍分组
        progress("bookGroup.json")
        restoreBookGroups(path, execution.timer)

        // 恢复书源（兼容旧版本格式）
        progress("bookSource.json")
        when (val result = readBookSourcesResult(path, execution.timer)) {
            is BackupJsonResult.Valid -> replaceBookSources(result.data, execution.timer)
            BackupJsonResult.Missing,
            BackupJsonResult.Invalid -> Unit
        }

        // 恢复RSS源
        progress("rssSources.json")
        restoreRssSources(path, execution.timer)

        // 恢复RSS收藏
        progress("rssStar.json")
        restoreRssStars(path, execution.timer)

        // 恢复源订阅
        progress("sourceSub.json")
        restoreSourceSubs(path, execution.timer)

        // 恢复搜索引擎规则
        progress("webSearchEngines.json")
        val enginesFile = File(path, "webSearchEngines.json")
        if (enginesFile.exists()) {
            try {
                val enginesJson = enginesFile.readText()
                val engines = GSON.fromJsonArray<SearchEngine>(enginesJson).getOrNull()
                if (engines != null) {
                    SearchEngineHelper.saveSearchEngines(appCtx, engines)
                }
            } catch (e: Exception) {
                AppLog.put("恢复搜索引擎规则出错\n${e.localizedMessage}", e)
            }
        }

        // 恢复首页数据
        progress("homepage.json")
        restoreHomepage(path, execution.timer)

        // 恢复替换规则
        progress("replaceRule.json")
        restoreReplaceRules(path, execution.timer)

        // 恢复搜索历史
        progress(HighlightRuleStore.backupFileName)
        File(path, HighlightRuleStore.backupFileName).takeIf { it.exists() }?.runCatching {
            GSON.fromJsonObject<HighlightRuleStore.BackupData>(readText()).getOrNull()?.let {
                HighlightRuleStore.restoreBackupData(appCtx, it, path)
            }
        }?.onFailure {
            AppLog.put("鎭㈠楂樹寒瑙勫垯鍑洪敊\n${it.localizedMessage}", it)
        }
        progress("searchHistory.json")
        restoreSearchHistory(path, execution.timer)

        // 恢复TXT目录规则
        progress("txtTocRule.json")
        restoreTxtTocRules(path, execution.timer)

        // 恢复词典规则
        progress("dictRule.json")
        restoreDictRules(path, execution.timer)

        // 恢复键盘辅助（先删除再插入，保证与备份数据一致）
        progress("keyboardAssists.json")
        restoreKeyboardAssists(path, execution.timer)

        progress(CoverGalleryRepository.backupDirName)
        restoreCoverGallery(path, execution.refresh, execution.timer)

        // 恢复阅读记录逻辑包
        progress("readRecord.json")
        restoreReadRecordBundle(path, BackupFileMappingPolicy.readRecordFileNames, execution.timer)

        // 恢复服务器配置（需要解密）
        progress("servers.json")
        restoreServers(path, aes, execution.timer)

        // 恢复直链上传配置
        progress(DirectLinkUpload.ruleFileName)
        DirectLinkUpload.delConfig()
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }

        // 恢复书评数据
        progress("bookReview.json")
        val bookReviewFile = java.io.File(path, "bookReview.json")
        if (bookReviewFile.exists()) {
            val destPath = io.legado.app.utils.FileUtils.getPath(
                splitties.init.appCtx.filesDir, "bookReview.json"
            )
            bookReviewFile.copyTo(java.io.File(destPath), overwrite = true)
        }

        // 恢复主题配置（可配置忽略）
        if (!BackupConfig.ignoreThemeConfig) {
            progress(ThemeConfig.configFileName)
            ThemeConfig.replaceConfigs(emptyList())
            File(path, ThemeConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                val configs = GSON.fromJsonArray<ThemeConfig.Config>(readText()).getOrNull()
                FileUtils.delete(ThemeConfig.configFilePath)
                copyTo(File(ThemeConfig.configFilePath))
                ThemeConfig.replaceConfigs(configs)
            }?.onFailure {
                AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
            }
        }

        // 恢复封面规则配置
        progress(BookCover.configFileName)
        BookCover.delCoverRule()
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
            // 清除封面缓存，确保使用新配置生成封面
            CoverImageView.clearAllCache()
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }

        // 恢复阅读界面配置（可配置忽略）
        if (!BackupConfig.ignoreReadConfig) {
            progress("backgroundImages")
            execution.timer.measure("read_background_copy") {
                restoreReadConfigBackgrounds(
                    path,
                    setOf(ReadBookConfig.configFileName, ReadBookConfig.shareConfigFileName),
                    ::progress
                )
            }
        }

        // 修正阅读背景图片路径
        if (!BackupConfig.ignoreReadConfig) {
            fixReadConfigBackgroundPaths(
                setOf(ReadBookConfig.configFileName, ReadBookConfig.shareConfigFileName)
            )
        }

        // 恢复SharedPreferences配置（应用主配置）
        progress("config.xml")
        readBackupPrefs(path, "config")?.let { map ->
            if (!BackupConfig.ignoreThemeConfig) {
                clearThemeRestorePrefs()
            }
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.shouldRestorePreference(key)) {
                    when (key) {
                        // WebDav密码需要解密
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                // 解密失败时，如果本地密码为空则使用备份中的值
                                if (appCtx.getPrefString(PreferKey.webDavPassword)
                                        .isNullOrBlank()
                                ) {
                                    edit.putString(key, value.toString())
                                }
                            }
                        }

                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
            }
            edit.apply()
        }

        // 修正主题背景图片路径
        if (!BackupConfig.ignoreThemeConfig) {
            progress("themeBackgroundImages")
            execution.timer.measure("theme_background_copy") {
                restoreThemeBackgrounds(path, clearExisting = true)
            }
        }
        progress(runtimeSourceCacheFileName)
        restoreRuntimeSourceCaches(path, execution.timer)
        progress(bookCacheFolderName)
        restoreBookCache(path, execution.refresh, execution.timer)
        if (!BackupConfig.ignoreThemeConfig) {
            fixThemeBackgroundPaths()
            fixThemeConfigBackgroundPaths()
        }


        // 应用阅读配置
        if (!BackupConfig.ignoreReadConfig) {
            progress("applyRestoreConfig")
            ReadBookConfig.apply {
                comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
                readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
                shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
                hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
                hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
                autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
            }
        }

    }

    private suspend fun applyRestoreUi() {
        appCtx.toastOnUi(R.string.restore_success)
        if (!BackupConfig.ignoreThemeConfig) {
            withContext(Main) {
                if (!BuildConfig.DEBUG) {
                    LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
                }
                if (shouldApplyRestoredThemeImmediately(
                        AppConfig.isEInkMode,
                        BackupConfig.ignoreThemeConfig
                    )
                ) {
                    delay(100)
                    ThemeConfig.applyDayNight(appCtx)
                }
            }
        }
    }

    /**
     * 从JSON文件读取列表数据
     * 
     * @param T 数据类型
     * @param path 备份目录路径
     * @param fileName JSON文件名
     * @return 解析后的列表，文件不存在或解析失败返回null
     */
    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

    private inline fun <reified T> readBackupListResult(
        path: String,
        fileName: String,
        timer: RestoreStageTimer? = null
    ): BackupJsonResult<List<T>> {
        val file = File(path, fileName)
        if (!file.exists()) return BackupJsonResult.Missing
        return runCatching {
            val parse = {
                file.inputStream().use { input ->
                    GSON.fromJsonArray<T>(input).getOrThrow()
                }
            }
            if (timer == null) parse() else timer.measure("json_parse", block = parse)
        }.fold(
            onSuccess = { BackupJsonResult.Valid(it) },
            onFailure = {
                AppLog.put("备份文件解析失败：$fileName")
                BackupJsonResult.Invalid
            }
        )
    }

    private fun readBooksResult(
        path: String,
        timer: RestoreStageTimer? = null
    ): BackupJsonResult<List<Book>> {
        return when (val result = readBackupListResult<Book>(path, "bookshelf.json", timer)) {
            is BackupJsonResult.Valid -> runCatching {
                result.data.onEach { book ->
                    book.upType()
                    if (book.isLocal) {
                        book.coverUrl = LocalBook.getCoverPath(book)
                    }
                }.filterNot { book -> BackupConfig.ignoreLocalBook && book.isLocal }
            }.fold(
                onSuccess = { BackupJsonResult.Valid(it) },
                onFailure = {
                    AppLog.put("备份文件校验失败：bookshelf.json")
                    BackupJsonResult.Invalid
                }
            )
            BackupJsonResult.Missing -> BackupJsonResult.Missing
            BackupJsonResult.Invalid -> BackupJsonResult.Invalid
        }
    }

    private fun defaultBookGroups(): List<BookGroup> = listOf(
        BookGroup(BookGroup.IdAll, appCtx.getString(R.string.all), order = -10, show = true),
        BookGroup(
            BookGroup.IdLocal,
            appCtx.getString(R.string.local),
            order = -9,
            enableRefresh = false,
            show = true
        ),
        BookGroup(BookGroup.IdAudio, appCtx.getString(R.string.audio), order = -8, show = true),
        BookGroup(
            BookGroup.IdNetNone,
            appCtx.getString(R.string.net_no_group),
            order = -7,
            show = true
        ),
        BookGroup(
            BookGroup.IdLocalNone,
            appCtx.getString(R.string.local_no_group),
            order = -6,
            show = false
        ),
        BookGroup(BookGroup.IdVideo, appCtx.getString(R.string.video), order = -5, show = true),
        BookGroup(
            BookGroup.IdError,
            appCtx.getString(R.string.update_book_fail),
            order = -1,
            show = true
        )
    )

    private suspend fun restoreBookGroups(path: String, timer: RestoreStageTimer? = null) {
        when (val result = readBackupListResult<BookGroup>(path, "bookGroup.json", timer)) {
            is BackupJsonResult.Valid -> {
                val defaults = defaultBookGroups()
                val existingIds = result.data.asSequence().map { it.groupId }.toSet()
                val missingDefaults = defaults.filterNot { it.groupId in existingIds }
                measureDbWrite(timer, result.data.size + missingDefaults.size) {
                    appDb.withTransaction {
                    appDb.bookGroupDao.deleteAll()
                    appDb.bookGroupDao.insert(*(result.data + missingDefaults).toTypedArray())
                    }
                }
            }
            BackupJsonResult.Missing,
            BackupJsonResult.Invalid -> Unit
        }
    }

    private fun readBookSourcesResult(
        path: String,
        timer: RestoreStageTimer? = null
    ): BackupJsonResult<List<BookSource>> {
        val file = File(path, "bookSource.json")
        if (!file.exists()) return BackupJsonResult.Missing
        val json = runCatching { file.readText() }.getOrElse {
            AppLog.put("备份文件读取失败：bookSource.json")
            return BackupJsonResult.Invalid
        }
        runCatching {
            val parse = { GSON.fromJsonArray<BookSource>(json).getOrThrow() }
            if (timer == null) parse() else timer.measure("json_parse", block = parse)
        }.getOrNull()?.let { return BackupJsonResult.Valid(it) }

        return runCatching {
            if (timer == null) ImportOldData.parseOldSources(json)
            else timer.measure("json_parse", block = { ImportOldData.parseOldSources(json) })
        }.fold(
            onSuccess = { BackupJsonResult.Valid(it) },
            onFailure = {
                AppLog.put("备份文件解析失败：bookSource.json")
                BackupJsonResult.Invalid
            }
        )
    }

    private suspend fun replaceBooks(books: List<Book>, timer: RestoreStageTimer? = null) {
        measureDbWrite(timer, books.size) {
            appDb.withTransaction {
                appDb.bookDao.deleteAll()
                appDb.bookDao.insert(*books.toTypedArray())
            }
        }
    }

    private suspend fun replaceBookmarks(
        bookmarks: List<Bookmark>,
        timer: RestoreStageTimer? = null
    ) {
        measureDbWrite(timer, bookmarks.size) {
            appDb.withTransaction {
                appDb.bookmarkDao.deleteAll()
                appDb.bookmarkDao.insert(*bookmarks.toTypedArray())
            }
        }
    }

    private suspend fun replaceBookSources(
        sources: List<BookSource>,
        timer: RestoreStageTimer? = null
    ) {
        measureDbWrite(timer, sources.size) {
            appDb.withTransaction {
                appDb.bookSourceDao.deleteAll()
                appDb.bookSourceDao.insert(*sources.toTypedArray())
            }
        }
    }

    private suspend fun <T> measureDbWrite(
        timer: RestoreStageTimer?,
        itemCount: Int,
        block: suspend () -> T
    ): T = if (timer == null) block() else timer.measureSuspend("db_write", itemCount, block)

    private suspend fun <T> replaceListResult(
        result: BackupJsonResult<List<T>>,
        timer: RestoreStageTimer? = null,
        replace: suspend (List<T>) -> Unit
    ) {
        if (result is BackupJsonResult.Valid) {
            measureDbWrite(timer, result.data.size) {
                appDb.withTransaction { replace(result.data) }
            }
        }
    }

    private suspend fun restoreRssSources(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<RssSource>(path, "rssSources.json", timer), timer) { items ->
            appDb.rssSourceDao.deleteAll()
            appDb.rssSourceDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreRssStars(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<RssStar>(path, "rssStar.json", timer), timer) { items ->
            appDb.rssStarDao.deleteAll()
            appDb.rssStarDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreSourceSubs(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<RuleSub>(path, "sourceSub.json", timer), timer) { items ->
            appDb.ruleSubDao.deleteAll()
            appDb.ruleSubDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreReplaceRules(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<ReplaceRule>(path, "replaceRule.json", timer), timer) { items ->
            appDb.replaceRuleDao.deleteAll()
            appDb.replaceRuleDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreSearchHistory(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<SearchKeyword>(path, "searchHistory.json", timer), timer) { items ->
            appDb.searchKeywordDao.deleteAll()
            appDb.searchKeywordDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreTxtTocRules(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<TxtTocRule>(path, "txtTocRule.json", timer), timer) { items ->
            appDb.txtTocRuleDao.deleteAll()
            appDb.txtTocRuleDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreDictRules(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<DictRule>(path, "dictRule.json", timer), timer) { items ->
            appDb.dictRuleDao.deleteAll()
            appDb.dictRuleDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreKeyboardAssists(path: String, timer: RestoreStageTimer? = null) {
        replaceListResult(readBackupListResult<KeyboardAssist>(path, "keyboardAssists.json", timer), timer) { items ->
            appDb.keyboardAssistsDao.deleteAll()
            appDb.keyboardAssistsDao.insert(*items.toTypedArray())
        }
    }

    private data class HomepageRestoreData(
        val modules: List<HomepageModule>,
        val customSets: List<HomepageCustomSet>
    )

    private fun readHomepageResult(
        path: String,
        timer: RestoreStageTimer? = null
    ): BackupJsonResult<HomepageRestoreData> {
        val file = File(path, "homepage.json")
        if (!file.exists()) return BackupJsonResult.Missing
        val parse = {
            val objectResult = GSON.fromJsonObject<Map<String, JsonElement>>(file.readText())
                .getOrThrow()
            val modulesElement = objectResult["modules"]?.takeIf { it.isJsonArray }
                ?: error("homepage modules is not an array")
            val customSetsElement = objectResult["customSets"]?.takeIf { it.isJsonArray }
                ?: error("homepage customSets is not an array")
            val modulesResult = GSON.fromJsonArray<HomepageModule>(modulesElement.toString())
                .fold(
                    onSuccess = { BackupJsonResult.Valid(it) },
                    onFailure = { BackupJsonResult.Invalid }
                )
            val customSetsResult = GSON.fromJsonArray<HomepageCustomSet>(customSetsElement.toString())
                .fold(
                    onSuccess = { BackupJsonResult.Valid(it) },
                    onFailure = { BackupJsonResult.Invalid }
                )
            check(RestoreDataPlan.shouldReplaceHomepage(modulesResult, customSetsResult))
            val modules = (modulesResult as BackupJsonResult.Valid).data
            val customSets = (customSetsResult as BackupJsonResult.Valid).data
            HomepageRestoreData(modules, customSets)
        }
        return runCatching {
            if (timer == null) parse() else timer.measure("json_parse", block = parse)
        }.fold(
            onSuccess = { BackupJsonResult.Valid(it) },
            onFailure = {
                AppLog.put("备份文件解析失败：homepage.json")
                BackupJsonResult.Invalid
            }
        )
    }

    private suspend fun restoreHomepage(path: String, timer: RestoreStageTimer? = null) {
        when (val result = readHomepageResult(path, timer)) {
            is BackupJsonResult.Valid -> measureDbWrite(
                timer,
                result.data.modules.size + result.data.customSets.size
            ) {
                appDb.withTransaction {
                    appDb.homepageModuleDao.deleteAll()
                    appDb.homepageModuleDao.upsertAll(result.data.modules)
                    appDb.homepageCustomSetDao.deleteAll()
                    result.data.customSets.forEach { appDb.homepageCustomSetDao.upsert(it) }
                }
            }
            BackupJsonResult.Missing,
            BackupJsonResult.Invalid -> Unit
        }
    }

    private fun readServersResult(
        path: String,
        aes: BackupAES,
        timer: RestoreStageTimer? = null
    ): BackupJsonResult<List<Server>> {
        val file = File(path, "servers.json")
        if (!file.exists()) return BackupJsonResult.Missing
        val parse = {
            var json = file.readText()
            if (!json.isJsonArray()) json = aes.decryptStr(json)
            GSON.fromJsonArray<Server>(json).getOrThrow()
        }
        return runCatching {
            if (timer == null) parse() else timer.measure("json_parse", block = parse)
        }.fold(
            onSuccess = { BackupJsonResult.Valid(it) },
            onFailure = {
                AppLog.put("备份文件解析失败：servers.json")
                BackupJsonResult.Invalid
            }
        )
    }

    private suspend fun restoreServers(
        path: String,
        aes: BackupAES,
        timer: RestoreStageTimer? = null
    ) {
        replaceListResult(readServersResult(path, aes, timer), timer) { items ->
            appDb.serverDao.deleteAll()
            appDb.serverDao.insert(*items.toTypedArray())
        }
    }

    private suspend fun restoreReadRecordBundle(
        path: String,
        selectedFiles: Set<String>,
        timer: RestoreStageTimer? = null
    ) {
        val selected = RestoreDataPlan.selectedReadRecordFiles(selectedFiles)
        if (selected.isEmpty()) return

        val recordResult = if ("readRecord.json" in selected) {
            readBackupListResult<ReadRecord>(path, "readRecord.json", timer)
        } else {
            BackupJsonResult.Missing
        }
        val detailResult = if ("readRecordDetail.json" in selected) {
            readBackupListResult<ReadRecordDetail>(path, "readRecordDetail.json", timer)
        } else {
            BackupJsonResult.Missing
        }
        val sessionResult = if ("readRecordSession.json" in selected) {
            readBackupListResult<ReadRecordSession>(path, "readRecordSession.json", timer)
        } else {
            BackupJsonResult.Missing
        }

        val results = listOf(recordResult, detailResult, sessionResult)
        if (!RestoreDataPlan.shouldApplyReadRecordBundle(results)) return

        val records = (recordResult as? BackupJsonResult.Valid)?.data.orEmpty()
        val details = (detailResult as? BackupJsonResult.Valid)?.data.orEmpty()
        val sessions = (sessionResult as? BackupJsonResult.Valid)?.data.orEmpty()

        measureDbWrite(timer, records.size + details.size + sessions.size) {
            appDb.withTransaction {
                if (recordResult is BackupJsonResult.Valid) appDb.readRecordDao.clear()
                if (detailResult is BackupJsonResult.Valid) appDb.readRecordDao.clearDetails()
                if (sessionResult is BackupJsonResult.Valid) appDb.readRecordDao.clearSessions()

                if (records.isNotEmpty() || details.isNotEmpty() || sessions.isNotEmpty()) {
                    ReadRecordRepository(appDb.readRecordDao).apply {
                        importRecords(records, details, sessions)
                        repairRecords { bookName ->
                            appDb.bookDao.getBookByName(bookName)?.author?.trim()?.ifBlank { null }
                        }
                    }
                }
            }
        }

        if (records.isNotEmpty() || details.isNotEmpty() || sessions.isNotEmpty()) {
            appCtx.putPrefInt(
                PreferKey.readRecordRepairVersion,
                ReadRecordRepository.CURRENT_REPAIR_VERSION
            )
        }
    }

    private suspend fun restoreRuntimeSourceCaches(
        path: String,
        timer: RestoreStageTimer? = null
    ) {
        val result = readBackupListResult<Cache>(path, runtimeSourceCacheFileName, timer)
        if (result !is BackupJsonResult.Valid) return
        measureDbWrite(timer, result.data.size) {
            appDb.withTransaction {
                appDb.cacheDao.deleteAllRuntimeSourceCaches()
                appDb.cacheDao.insert(*result.data.toTypedArray())
            }
        }
        AppCacheManager.clearSourceVariables()
    }

    private suspend fun restoreCoverGallery(
        path: String,
        refresh: RestoreRefreshCoordinator,
        timer: RestoreStageTimer? = null
    ) {
        val galleryDir = File(path, CoverGalleryRepository.backupDirName)
        if (!galleryDir.exists() || !galleryDir.isDirectory) return
        if (timer == null) {
            restoreCoverGalleryInternal(path, refresh)
        } else {
            timer.measureSuspend("cover_file_copy") {
                restoreCoverGalleryInternal(path, refresh)
            }
        }
    }

    private suspend fun restoreCoverGalleryInternal(
        path: String,
        refresh: RestoreRefreshCoordinator
    ) {
        val galleryDir = File(path, CoverGalleryRepository.backupDirName)
        if (!galleryDir.exists() || !galleryDir.isDirectory) return
        val oldGroupIds = appDb.coverGalleryDao.allGroups.map { it.id }

        appDb.coverGalleryDao.deleteAllImages()
        appDb.coverGalleryDao.deleteAllGroups()

        appDb.cacheDao.deleteRuntimeSourceCachesByPrefix(CoverGalleryRepository.randomSeedKeyPrefix)
        oldGroupIds.forEach {
            CacheManager.deleteMemory(CoverGalleryRepository.randomSeedKeyPrefix + it)
        }

        val targetDir = appCtx.externalFiles.getFile("covers").createFolderIfNotExist()
        val usedImageNames = hashSetOf<String>()
        galleryDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEachIndexed { groupIndex, groupDir ->
                val groupId = appDb.coverGalleryDao.insertGroup(
                    CoverGalleryGroup(
                        name = groupDir.name,
                        order = groupIndex
                    )
                )
                val images = groupDir.listFiles()
                    ?.filter { it.isFile && it.isCoverGalleryImageFile() }
                    ?.sortedBy { it.name }
                    ?.mapIndexed { imageIndex, imageFile ->
                        val targetFile = File(
                            targetDir,
                            uniqueCoverGalleryImageName(imageFile.name, usedImageNames)
                        )
                        imageFile.copyTo(targetFile, overwrite = true)
                        CoverGalleryImage(
                            groupId = groupId,
                            path = targetFile.absolutePath,
                            order = imageIndex
                        )
                    }
                    .orEmpty()
                if (images.isNotEmpty()) {
                    appDb.coverGalleryDao.insertImages(*images.toTypedArray())
                }
            }

        BookCover.upDefaultCover()
        refresh.requestBookshelfRefresh()
    }

    private fun File.isCoverGalleryImageFile(): Boolean {
        return extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }

    private fun uniqueCoverGalleryImageName(
        fileName: String,
        usedImageNames: MutableSet<String>
    ): String {
        val nameWithoutExtension = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var candidate = fileName
        var suffix = 2
        while (!usedImageNames.add(candidate)) {
            candidate = if (extension.isBlank()) {
                "$nameWithoutExtension-$suffix"
            } else {
                "$nameWithoutExtension-$suffix.$extension"
            }
            suffix++
        }
        return candidate
    }

    private fun readBackupPrefs(path: String, fileName: String): Map<String, Any>? {
        val file = File(path, "$fileName.xml")
        if (!file.exists()) return null
        return runCatching {
            val map = linkedMapOf<String, Any>()
            file.inputStream().use { input ->
                val parser = Xml.newPullParser()
                parser.setInput(input, "utf-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                        val name = parser.getAttributeValue(null, "name")
                        if (!name.isNullOrBlank()) {
                            when (parser.name) {
                                "string" -> map[name] = parser.nextText()
                                "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()
                                    ?.let { map[name] = it }
                                "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()
                                    ?.let { map[name] = it }
                                "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()
                                    ?.let { map[name] = it }
                                "boolean" -> parser.getAttributeValue(null, "value")?.toBooleanStrictOrNull()
                                    ?.let { map[name] = it }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            map
        }.onFailure {
            AppLog.put("$fileName.xml\n璇诲彇閰嶇疆鍑洪敊\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun restoreReadConfigBackgrounds(
        path: String,
        selectedFiles: Set<String>,
        progress: (String) -> Unit
    ) {
        data class PendingConfig(
            val fileName: String,
            val sourceFile: File,
            val configs: List<ReadBookConfig.Config>?,
            val shareConfig: ReadBookConfig.Config?
        )

        val pending = mutableListOf<PendingConfig>()
        fun addPending(fileName: String, sourceFile: File, isList: Boolean) {
            if (!sourceFile.exists()) return
            runCatching {
                if (isList) {
                    PendingConfig(
                        fileName,
                        sourceFile,
                        GSON.fromJsonArray<ReadBookConfig.Config>(sourceFile.readText()).getOrThrow(),
                        null
                    )
                } else {
                    PendingConfig(
                        fileName,
                        sourceFile,
                        null,
                        GSON.fromJsonObject<ReadBookConfig.Config>(sourceFile.readText()).getOrThrow()
                    )
                }
            }.onSuccess { pending.add(it) }
                .onFailure { AppLog.put("读取阅读配置出错\n${it.localizedMessage}", it) }
        }

        if (ReadBookConfig.configFileName in selectedFiles) {
            progress(ReadBookConfig.configFileName)
            addPending(
                ReadBookConfig.configFileName,
                File(path, ReadBookConfig.configFileName),
                isList = true
            )
        }
        if (ReadBookConfig.shareConfigFileName in selectedFiles) {
            progress(ReadBookConfig.shareConfigFileName)
            val shareFile = File(path, ReadBookConfig.shareConfigFileName).takeIf { it.exists() }
                ?: File(path, "readShareConfig.json")
            addPending(ReadBookConfig.shareConfigFileName, shareFile, isList = false)
        }

        val referencedNames = linkedSetOf<String>()
        pending.forEach { item ->
            item.configs.orEmpty().forEach { collectBgNames(it, referencedNames) }
            item.shareConfig?.let { collectBgNames(it, referencedNames) }
        }

        val tempDir = appCtx.externalCache.getFile("readBackgroundRestore")
        FileUtils.delete(tempDir)
        tempDir.mkdirs()
        referencedNames.forEach { bgName ->
            findReadBackgroundBackup(path, bgName)?.let { backupFile ->
                runCatching { backupFile.copyTo(File(tempDir, bgName), overwrite = true) }
                    .onFailure { AppLog.put("暂存阅读背景出错\n${it.localizedMessage}", it) }
            }
        }

        val bgDir = appCtx.externalFiles.getFile(ReadBackgroundBackupPolicy.backupDirectoryName)
        bgDir.mkdirs()
        val availableNames = bgDir.listFiles()
            ?.filter { it.isFile }
            ?.mapTo(linkedSetOf()) { it.name }
            ?: linkedSetOf()
        tempDir.listFiles()?.filter { it.isFile }?.forEach { stagedFile ->
            runCatching {
                stagedFile.copyTo(File(bgDir, stagedFile.name), overwrite = true)
                availableNames.add(stagedFile.name)
            }.onFailure {
                AppLog.put("恢复阅读背景出错\n${it.localizedMessage}", it)
            }
        }

        pending.forEach { item ->
            val normalizedJson = item.configs?.let { configs ->
                GSON.toJson(configs.map { sanitizeReadConfig(it, availableNames) })
            } ?: item.shareConfig?.let {
                GSON.toJson(sanitizeReadConfig(it, availableNames))
            } ?: return@forEach
            runCatching {
                val targetFile = File(
                    appCtx.filesDir,
                    item.fileName
                )
                FileUtils.delete(targetFile)
                targetFile.writeText(normalizedJson)
                if (item.fileName == ReadBookConfig.configFileName) {
                    ReadBookConfig.initConfigs()
                } else {
                    ReadBookConfig.initShareConfig()
                }
            }.onFailure { AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it) }
        }
        FileUtils.delete(tempDir)
    }

    private fun findReadBackgroundBackup(path: String, bgName: String): File? =
        File(path, "${ReadBackgroundBackupPolicy.backupDirectoryName}${File.separator}$bgName")
            .takeIf { it.exists() && it.isFile }
            ?: File(path, bgName).takeIf { it.exists() && it.isFile }

    private fun collectBgNames(
        config: ReadBookConfig.Config,
        bgNames: MutableSet<String>
    ) {
        if (config.bgType == 2) {
            bgNames.add(File(config.bgStr).name)
        }
        if (config.bgTypeNight == 2) {
            bgNames.add(File(config.bgStrNight).name)
        }
        if (config.bgTypeEInk == 2) {
            bgNames.add(File(config.bgStrEInk).name)
        }
    }

    private fun clearThemeBackgrounds() {
        listOf(PreferKey.bgImage, PreferKey.bgImageN).forEach { prefKey ->
            val bgDir = appCtx.externalFiles.getFile(prefKey)
            FileUtils.delete(bgDir)
            bgDir.mkdirs()
        }
    }

    private fun fixReadConfigBackgroundPaths(selectedFiles: Set<String>) {
        var updated = false
        if (ReadBookConfig.configFileName in selectedFiles) {
            ReadBookConfig.configList.forEach { config ->
                if (fixReadConfigBackgroundPath(config)) {
                    updated = true
                }
            }
        }
        if (ReadBookConfig.shareConfigFileName in selectedFiles) {
            runCatching { ReadBookConfig.shareConfig }.getOrNull()?.let { shareConfig ->
                if (fixReadConfigBackgroundPath(shareConfig)) {
                    updated = true
                }
            }
        }
        if (updated) {
            ReadBookConfig.save()
        }
    }

    private fun sanitizeReadConfig(
        source: ReadBookConfig.Config,
        availableNames: Set<String>
    ): ReadBookConfig.Config {
        val config = source.copy()
        fun restoredPath(path: String): String? {
            val name = ReadBackgroundBackupPolicy.resolveRestoredName(
                path,
                restoredNames = availableNames,
                existingNames = availableNames
            ) ?: return null
            return appCtx.externalFiles.getFile(
                ReadBackgroundBackupPolicy.backupDirectoryName,
                name
            ).absolutePath
        }

        if (config.bgType == 2) {
            restoredPath(config.bgStr)?.let { config.bgStr = it } ?: run {
                config.bgType = 0
                config.bgStr = ReadBackgroundBackupPolicy.fallbackColor(0)
            }
        }
        if (config.bgTypeNight == 2) {
            restoredPath(config.bgStrNight)?.let { config.bgStrNight = it } ?: run {
                config.bgTypeNight = 0
                config.bgStrNight = ReadBackgroundBackupPolicy.fallbackColor(1)
            }
        }
        if (config.bgTypeEInk == 2) {
            restoredPath(config.bgStrEInk)?.let { config.bgStrEInk = it } ?: run {
                config.bgTypeEInk = 0
                config.bgStrEInk = ReadBackgroundBackupPolicy.fallbackColor(2)
            }
        }
        return config
    }

    private fun fixReadConfigBackgroundPath(config: ReadBookConfig.Config): Boolean {
        var updated = false
        if (config.bgType == 2) {
            val fixedPath = fixReadBgPath(config.bgStr)
            if (fixedPath != config.bgStr) {
                config.bgStr = fixedPath
                updated = true
            }
        }
        if (config.bgTypeNight == 2) {
            val fixedPath = fixReadBgPath(config.bgStrNight)
            if (fixedPath != config.bgStrNight) {
                config.bgStrNight = fixedPath
                updated = true
            }
        }
        if (config.bgTypeEInk == 2) {
            val fixedPath = fixReadBgPath(config.bgStrEInk)
            if (fixedPath != config.bgStrEInk) {
                config.bgStrEInk = fixedPath
                updated = true
            }
        }
        return updated
    }

    private fun fixReadBgPath(bgPath: String): String {
        if (bgPath.isBlank()) return bgPath
        val bgName = File(bgPath).name
        val localFile = appCtx.externalFiles.getFile("bg", bgName)
        return if (localFile.exists()) {
            localFile.absolutePath
        } else {
            bgPath
        }
    }

    private fun restoreThemeBackgrounds(backupPath: String, clearExisting: Boolean) {
        if (clearExisting) {
            clearThemeBackgrounds()
        }
        // 从 config.xml 中读取主题背景图片路径
        val configPrefs = readBackupPrefs(backupPath, "config")
        
        // 恢复白天主题背景
        (configPrefs?.get(PreferKey.bgImage) as? String)?.let { bgPath ->
            restoreThemeBgFile(backupPath, bgPath, PreferKey.bgImage)
        }
        
        // 恢复夜间主题背景
        (configPrefs?.get(PreferKey.bgImageN) as? String)?.let { bgPath ->
            restoreThemeBgFile(backupPath, bgPath, PreferKey.bgImageN)
        }
        File(backupPath, ThemeConfig.configFileName).takeIf { it.exists() }?.runCatching {
            GSON.fromJsonArray<ThemeConfig.Config>(readText()).getOrThrow()
        }?.getOrNull()?.forEach { config ->
            val bgPath = config.backgroundImgPath ?: return@forEach
            val prefKey = if (config.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage
            restoreThemeBgFile(backupPath, bgPath, prefKey)
        }
    }
    
    private fun restoreThemeBgFile(backupPath: String, bgPath: String, prefKey: String) {
        if (bgPath.isBlank()) return
        
        val bgFile = if (bgPath.startsWith("http")) {
            // 在线图片，文件名从 URL 计算
            val name = ThemeConfig.getUrlToFile(bgPath)
            appCtx.externalFiles.getFile(prefKey, name)
        } else if (bgPath.contains(File.separator)) {
            // 本地路径，提取文件名
            val name = File(bgPath).name
            appCtx.externalFiles.getFile(prefKey, name)
        } else {
            // 已经是文件名
            appCtx.externalFiles.getFile(prefKey, bgPath)
        }
        
        // 从备份目录复制文件
        val bgName = if (bgPath.startsWith("http")) {
            ThemeConfig.getUrlToFile(bgPath)
        } else {
            File(bgPath).name
        }
        val backupFile = File(backupPath, "$prefKey${File.separator}$bgName")
            .takeIf { it.exists() && it.isFile }
            ?: File(backupPath, bgName).takeIf { it.exists() && it.isFile }
        if (backupFile != null) {
            val targetDir = appCtx.externalFiles.getFile(prefKey)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            backupFile.copyTo(File(targetDir, bgName), overwrite = true)
            LogUtils.d(TAG, "恢复主题背景: $bgName -> ${bgFile.absolutePath}")
        }
    }

    private fun clearThemeRestorePrefs() {
        appCtx.defaultSharedPreferences.edit {
            BackupPreferencePolicy.themePrefKeys.forEach(::remove)
        }
    }

    private fun fixThemeBackgroundPaths() {
        // 修正白天主题背景路径
        appCtx.getPrefString(PreferKey.bgImage)?.let { bgPath ->
            val fixedPath = fixThemeBgPath(bgPath, PreferKey.bgImage)
            if (fixedPath != bgPath) {
                appCtx.putPrefString(PreferKey.bgImage, fixedPath)
                LogUtils.d(TAG, "修正白天主题背景路径: $bgPath -> $fixedPath")
            }
        }
        
        // 修正夜间主题背景路径
        appCtx.getPrefString(PreferKey.bgImageN)?.let { bgPath ->
            val fixedPath = fixThemeBgPath(bgPath, PreferKey.bgImageN)
            if (fixedPath != bgPath) {
                appCtx.putPrefString(PreferKey.bgImageN, fixedPath)
                LogUtils.d(TAG, "修正夜间主题背景路径: $bgPath -> $fixedPath")
            }
        }
    }

    private fun fixThemeConfigBackgroundPaths() {
        var updated = false
        ThemeConfig.configList.forEachIndexed { index, config ->
            val bgPath = config.backgroundImgPath ?: return@forEachIndexed
            val prefKey = if (config.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage
            val fixedPath = fixThemeBgPath(bgPath, prefKey)
            if (fixedPath != bgPath) {
                ThemeConfig.configList[index] = config.copy(backgroundImgPath = fixedPath)
                updated = true
                LogUtils.d(TAG, "淇涓婚閰嶇疆鑳屾櫙璺緞: $bgPath -> $fixedPath")
            }
        }
        if (updated) {
            ThemeConfig.save()
        }
    }
    
    private fun fixThemeBgPath(bgPath: String, prefKey: String): String {
        if (bgPath.isBlank()) return bgPath
        // 在线图片路径不需要修正
        if (bgPath.startsWith("http")) return bgPath
        // 已经是文件名，不需要修正
        if (!bgPath.contains(File.separator)) return bgPath
        
        // 提取文件名，拼接新设备路径
        val bgName = File(bgPath).name
        val newFile = appCtx.externalFiles.getFile(prefKey, bgName)
        if (newFile.exists()) {
            return newFile.absolutePath
        }
        // 如果新路径不存在，返回文件名（ThemeConfig.getBgImage 会自动处理）
        return bgName
    }

    /**
     * 恢复书籍缓存
     * 
     * 流程：
     * 1. 恢复章节目录（如果有）
     * 2. 读取缓存索引文件
     * 3. 遍历索引，匹配当前设备上的书籍
     * 4. 获取当前书籍的章节列表
     * 5. 根据章节标题匹配，重命名章节文件
     * 6. 复制缓存文件到对应位置
     * 
     * 匹配策略：
     * 1. 优先按章节序号精确匹配
     * 2. 其次按章节标题匹配
     */
    private fun restoreBookCache(
        path: String,
        refresh: RestoreRefreshCoordinator,
        timer: RestoreStageTimer? = null
    ) {
        val hasCacheInput = listOf(
            bookCacheFolderName,
            bookCacheIndexFileName,
            bookCacheBooksFileName,
            "bookChapterCache.json"
        ).any { File(path, it).exists() }
        if (!hasCacheInput) return
        if (timer == null) {
            restoreBookCacheInternal(path, refresh)
        } else {
            timer.measure("book_cache_copy") {
                restoreBookCacheInternal(path, refresh)
            }
        }
    }

    private fun restoreBookCacheInternal(path: String, refresh: RestoreRefreshCoordinator) {
        LogUtils.d(TAG, "开始恢复书籍缓存，路径: $path")
        
        if (BackupConfig.ignoreBookCache) {
            LogUtils.d(TAG, "忽略书籍缓存恢复（配置项已禁用）")
            AppLog.put("书籍缓存恢复被忽略，请在恢复配置中启用")
            return
        }
        
        // 先恢复章节目录
        val indexFile = File(path, bookCacheIndexFileName)
        if (!indexFile.exists()) {
            LogUtils.d(TAG, "书籍缓存索引文件不存在: ${indexFile.absolutePath}")
            AppLog.put("书籍缓存索引文件不存在，无法恢复书籍缓存")
            
            // 尝试从 bookCacheBooks.json 直接恢复书籍信息
            val booksFile = File(path, bookCacheBooksFileName)
            if (booksFile.exists()) {
                LogUtils.d(TAG, "尝试从 bookCacheBooks.json 直接恢复书籍信息")
                try {
                    ensureDefaultBookGroups()
                    val books = fileToListT<Book>(path, bookCacheBooksFileName)
                        .orEmpty()
                        .mapNotNull { it.sanitizeForCacheRestore() }
                    
                    if (books.isNotEmpty()) {
                        LogUtils.d(TAG, "从 bookCacheBooks.json 读取到 ${books.size} 本书")
                        
                        val localBooks = appDb.bookDao.all
                        LogUtils.d(TAG, "当前数据库中有 ${localBooks.size} 本书")
                        
                        val missingBooks = books.filter { book ->
                            val exists = localBooks.any { it.bookUrl == book.bookUrl || BookMatcher.textMatches(it.name, book.name) }
                            LogUtils.d(TAG, "书籍《${book.name}》${if (exists) "已存在" else "不存在"}")
                            !exists
                        }.map { book ->
                            book.copy(
                                group = 0,
                                type = book.type and BookType.notShelf.inv()
                            )
                        }
                        
                        if (missingBooks.isNotEmpty()) {
                            appDb.bookDao.insert(*missingBooks.toTypedArray())
                            LogUtils.d(TAG, "从 bookCacheBooks.json 恢复书籍: ${missingBooks.size}")
                            AppLog.put("从书籍缓存恢复 ${missingBooks.size} 本书到书架")
                            
                            // 发送书架刷新事件
                            refresh.requestBookshelfRefresh()
                        } else {
                            LogUtils.d(TAG, "所有书籍已存在，无需恢复")
                        }
                    }
                } catch (e: Exception) {
                    LogUtils.d(TAG, "从 bookCacheBooks.json 恢复失败: ${e.message}")
                    AppLog.put("从 bookCacheBooks.json 恢复失败\n${e.localizedMessage}", e)
                }
            }
            return
        }
        
        LogUtils.d(TAG, "找到书籍缓存索引文件: ${indexFile.absolutePath}, 大小: ${indexFile.length()}")
        
        val cacheIndexList = runCatching {
            val json = indexFile.readText()
            LogUtils.d(TAG, "索引文件内容长度: ${json.length}")
            parseBookCacheIndexList(json)
        }.getOrNull() ?: run {
            LogUtils.d(TAG, "解析书籍缓存索引失败")
            AppLog.put("书籍缓存索引文件解析失败")
            return
        }
        
        if (cacheIndexList.isEmpty()) {
            LogUtils.d(TAG, "书籍缓存索引为空")
            AppLog.put("书籍缓存索引为空")
            return
        }
        
        LogUtils.d(TAG, "解析到 ${cacheIndexList.size} 个书籍缓存索引")
        cacheIndexList.forEach { index ->
            LogUtils.d(TAG, "  - 《${index.bookName}》作者: ${index.author}, 目录: ${index.folderName}, 章节数: ${index.chapters.size}")
        }
        
        restoreBookCacheBooks(path, cacheIndexList, refresh)
        restoreBookChapterCache(path)

        val backupCacheDir = resolveBackupCacheDir(path, cacheIndexList)
        if (backupCacheDir == null) {
            LogUtils.d(TAG, "备份缓存目录不存在")
            return
        }
        
        val targetCacheDir = File(BookHelp.cachePath)
        if (!targetCacheDir.exists()) {
            targetCacheDir.mkdirs()
        }
        
        val allBooks = appDb.bookDao.all
        var restoredCount = 0
        var chapterRestoredCount = 0
        
        cacheIndexList.forEach { cacheIndex ->
            val matchedBook = findMatchingBook(cacheIndex, allBooks)
            if (matchedBook == null) {
                LogUtils.d(TAG, "未找到匹配书籍: ${cacheIndex.bookName}")
                return@forEach
            }
            
            val sourceCacheDir = File(backupCacheDir, cacheIndex.folderName)
            if (!sourceCacheDir.exists()) {
                LogUtils.d(TAG, "备份缓存目录不存在: ${cacheIndex.folderName}")
                return@forEach
            }
            
            val targetFolderName = matchedBook.getFolderName()
            val targetBookDir = File(targetCacheDir, targetFolderName)
            if (!targetBookDir.exists()) {
                targetBookDir.mkdirs()
            }
            
            // 获取当前书籍的章节列表
            val currentChapters = appDb.bookChapterDao.getChapterList(matchedBook.bookUrl)
            val currentChapterByIndex = currentChapters.associateBy { it.index }
            val currentChapterByTitle = currentChapters.associateBy { it.title }
            
            // 恢复章节文件，根据需要重命名
            val copiedSourceNames = hashSetOf<String>()
            cacheIndex.chapters.forEach { chapterInfo ->
                val sourceFile = File(sourceCacheDir, chapterInfo.fileName)
                if (!sourceFile.exists()) {
                    return@forEach
                }
                
                // 查找匹配的当前章节
                val targetChapter = currentChapterByIndex[chapterInfo.index]
                    ?: currentChapterByTitle[chapterInfo.title]
                
                if (targetChapter == null) {
                    LogUtils.d(TAG, "未找到匹配章节: ${chapterInfo.title}")
                    return@forEach
                }
                
                // 计算目标文件名
                val targetFileName = targetChapter.getFileName()
                val targetFile = File(targetBookDir, targetFileName)
                
                // 复制文件（如果文件名不同则重命名）
                sourceFile.copyTo(targetFile, overwrite = true)
                copiedSourceNames.add(sourceFile.name)
                chapterRestoredCount++
            }
            sourceCacheDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".nb") && it.name !in copiedSourceNames }
                ?.forEach { sourceFile ->
                    sourceFile.copyTo(File(targetBookDir, sourceFile.name), overwrite = true)
                    chapterRestoredCount++
                }
            
            // 复制图片文件夹（如果有）
            val sourceImageDir = File(sourceCacheDir, "images")
            if (sourceImageDir.exists()) {
                val targetImageDir = File(targetBookDir, "images")
                sourceImageDir.copyRecursively(targetImageDir, overwrite = true)
            }
            
            restoredCount++
            LogUtils.d(TAG, "恢复书籍缓存: ${matchedBook.name} -> $targetFolderName")
        }
        
        LogUtils.d(TAG, "书籍缓存恢复完成，共恢复 $restoredCount 本书，$chapterRestoredCount 个章节")
    }
    
    /**
     * 恢复章节目录
     * 从 bookChapterCache.json 恢复章节目录数据
     * 
     * @param path 备份文件解压后的目录路径
     */
    private fun restoreBookCacheBooks(
        path: String,
        cacheIndexList: List<BookCacheIndex>,
        refresh: RestoreRefreshCoordinator
    ) {
        LogUtils.d(TAG, "开始恢复书籍缓存书架信息")
        
        ensureDefaultBookGroups()
        LogUtils.d(TAG, "已确保默认书籍分组存在")
        
        val backupBooks = fileToListT<Book>(path, bookCacheBooksFileName)
            .orEmpty()
            .mapNotNull { it.sanitizeForCacheRestore() }
        
        LogUtils.d(TAG, "从 $bookCacheBooksFileName 读取到 ${backupBooks.size} 本书")
        
        val books = backupBooks.ifEmpty {
            LogUtils.d(TAG, "使用缓存索引生成最小书籍记录")
            cacheIndexList.map {
                Book(
                    bookUrl = it.bookUrl,
                    name = it.bookName,
                    author = it.author,
                    originName = it.bookName
                )
            }
        }
        
        if (books.isEmpty()) {
            LogUtils.d(TAG, "没有需要恢复的书籍")
            return
        }

        val localBooks = appDb.bookDao.all
        LogUtils.d(TAG, "当前数据库中有 ${localBooks.size} 本书")
        
        val missingBooks = books
            .filter { book ->
                val matched = findMatchingBook(
                    BookCacheIndex(
                        bookUrl = book.bookUrl,
                        bookName = book.name,
                        author = book.author,
                        folderName = book.getFolderName()
                    ),
                    localBooks
                )
                val exists = matched != null
                LogUtils.d(TAG, "书籍《${book.name}》${if (exists) "已存在 (匹配: ${matched?.name})" else "不存在，将恢复"}")
                !exists
            }
            .map { book ->
                book.copy(
                    group = 0,
                    type = book.type and BookType.notShelf.inv()
                )
            }
        
        if (missingBooks.isNotEmpty()) {
            LogUtils.d(TAG, "准备插入 ${missingBooks.size} 本缺失书籍")
            missingBooks.forEach { book ->
                LogUtils.d(TAG, "  - 《${book.name}》作者: ${book.author}, bookUrl: ${book.bookUrl}, type: ${book.type}, group: ${book.group}")
            }
            
            appDb.bookDao.insert(*missingBooks.toTypedArray())
            LogUtils.d(TAG, "恢复书籍缓存书架信息: ${missingBooks.size}")
            AppLog.put("从书籍缓存恢复 ${missingBooks.size} 本书到书架")
            
            // 发送书架刷新事件
            refresh.requestBookshelfRefresh()
        } else {
            LogUtils.d(TAG, "所有书籍已存在，无需恢复")
        }
    }

    private fun ensureDefaultBookGroups() {
        val defaults = defaultBookGroups()
            .filter { appDb.bookGroupDao.getByID(it.groupId) == null }

        if (defaults.isNotEmpty()) {
            appDb.bookGroupDao.insert(*defaults.toTypedArray())
        }
    }

    private fun resolveBackupCacheDir(path: String, cacheIndexList: List<BookCacheIndex>): File? {
        val cacheDir = File(path, bookCacheFolderName)
        if (cacheDir.exists()) {
            return cacheDir
        }
        return File(path).takeIf { rootDir ->
            cacheIndexList.any { File(rootDir, it.folderName).exists() }
        }
    }

    private fun restoreBookChapterCache(path: String) {
        val chapterFile = File(path, "bookChapterCache.json")
        if (!chapterFile.exists()) {
            LogUtils.d(TAG, "章节目录文件不存在")
            return
        }
        
        val chapters = fileToListT<BookChapter>(path, "bookChapterCache.json")
        if (chapters.isNullOrEmpty()) {
            LogUtils.d(TAG, "章节目录为空")
            return
        }
        
        // 按 bookUrl 分组
        val chaptersByBook = chapters.groupBy { it.bookUrl }
        var restoredBookCount = 0
        var restoredChapterCount = 0
        
        chaptersByBook.forEach { (bookUrl, chapterList) ->
            // 检查书籍是否存在
            val book = appDb.bookDao.getBook(bookUrl)
            if (book == null) {
                // 尝试通过缓存索引中的书名匹配
                val cacheIndexFile = File(path, bookCacheIndexFileName)
                if (cacheIndexFile.exists()) {
                    val cacheIndexList = runCatching {
                        parseBookCacheIndexList(cacheIndexFile.readText())
                    }.getOrNull()
                    
                    val cacheIndex = cacheIndexList?.find { it.bookUrl == bookUrl }
                    if (cacheIndex != null) {
                        val matchedBook = appDb.bookDao.all.find { BookMatcher.textMatches(it.name, cacheIndex.bookName) }
                        if (matchedBook != null) {
                            // 更新章节的 bookUrl
                            val updatedChapters = chapterList.map { chapter ->
                                chapter.copy(bookUrl = matchedBook.bookUrl)
                            }
                            // 删除旧的章节，插入新的
                            appDb.bookChapterDao.delByBook(matchedBook.bookUrl)
                            appDb.bookChapterDao.insert(*updatedChapters.toTypedArray())
                            restoredBookCount++
                            restoredChapterCount += updatedChapters.size
                            LogUtils.d(TAG, "恢复章节目录: ${matchedBook.name}, ${updatedChapters.size} 章")
                        }
                    }
                }
            } else {
                // 书籍存在，直接恢复章节
                appDb.bookChapterDao.delByBook(bookUrl)
                appDb.bookChapterDao.insert(*chapterList.toTypedArray())
                restoredBookCount++
                restoredChapterCount += chapterList.size
                LogUtils.d(TAG, "恢复章节目录: ${book.name}, ${chapterList.size} 章")
            }
        }
        
        LogUtils.d(TAG, "章节目录恢复完成，共 $restoredBookCount 本书，$restoredChapterCount 章")
    }
    
    /**
     * 查找匹配的书籍
     * 
     * @param cacheIndex 缓存索引信息
     * @param allBooks 所有书籍列表
     * @return 匹配的书籍，未找到返回null
     */
    private fun findMatchingBook(
        cacheIndex: BookCacheIndex,
        allBooks: List<Book>
    ): Book? {
        // 优先按 bookUrl 精确匹配
        allBooks.find { it.bookUrl == cacheIndex.bookUrl }?.let { return it }
        
        // 其次按 书名+作者 匹配
        val normalizedAuthor = cacheIndex.author.trim()
        allBooks.filter {
            BookMatcher.textMatches(it.name, cacheIndex.bookName) &&
            (it.author?.trim() ?: "") == normalizedAuthor
        }.firstOrNull()?.let { return it }

        // 最后按书名模糊匹配（作者可能为空或不一致）
        allBooks.filter { BookMatcher.textMatches(it.name, cacheIndex.bookName) }.firstOrNull()?.let { return it }
        
        return null
    }

    private fun parseBookCacheIndexList(json: String): List<BookCacheIndex>? {
        return runCatching {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) {
                return@runCatching null
            }
            root.asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val bookUrl = obj.stringOrBlank("bookUrl")
                val bookName = obj.stringOrBlank("bookName")
                val folderName = obj.stringOrBlank("folderName")
                if (folderName.isBlank() || (bookUrl.isBlank() && bookName.isBlank())) {
                    return@mapNotNull null
                }
                BookCacheIndex(
                    bookUrl = bookUrl,
                    bookName = bookName,
                    author = obj.stringOrBlank("author"),
                    folderName = folderName,
                    chapters = obj.arrayOrEmpty("chapters").mapNotNull { chapterElement ->
                        val chapter = chapterElement.asJsonObjectOrNull() ?: return@mapNotNull null
                        val fileName = chapter.stringOrBlank("fileName")
                        if (fileName.isBlank()) {
                            return@mapNotNull null
                        }
                        ChapterCacheInfo(
                            index = chapter.intOrZero("index"),
                            title = chapter.stringOrBlank("title"),
                            titleMD5 = chapter.stringOrBlank("titleMD5"),
                            fileName = fileName
                        )
                    }
                )
            }.sanitizeBookCacheIndexes()
        }.onFailure {
            AppLog.put("$bookCacheIndexFileName\n读取解析出错\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return takeIf { it.isJsonObject }?.asJsonObject
    }

    private fun JsonObject.stringOrBlank(name: String): String {
        val element = get(name) ?: return ""
        return runCatching {
            if (element.isJsonNull) "" else element.asString ?: ""
        }.getOrDefault("")
    }

    private fun JsonObject.intOrZero(name: String): Int {
        val element = get(name) ?: return 0
        return runCatching {
            if (element.isJsonNull) 0 else element.asInt
        }.getOrDefault(0)
    }

    private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> {
        val element = get(name) ?: return emptyList()
        return if (element.isJsonArray) element.asJsonArray.toList() else emptyList()
    }

    private fun List<BookCacheIndex>.sanitizeBookCacheIndexes(): List<BookCacheIndex> {
        LogUtils.d(TAG, "开始清理书籍缓存索引，原始数量: ${this.size}")
        
        return mapNotNull { cacheIndex ->
            @Suppress("USELESS_CAST")
            val bookUrl = (cacheIndex.bookUrl as String?) ?: ""
            @Suppress("USELESS_CAST")
            val bookName = (cacheIndex.bookName as String?) ?: ""
            @Suppress("USELESS_CAST")
            val folderName = (cacheIndex.folderName as String?) ?: ""
            
            LogUtils.d(TAG, "处理索引: bookUrl='$bookUrl', bookName='$bookName', folderName='$folderName'")
            
            if (folderName.isBlank() || (bookUrl.isBlank() && bookName.isBlank())) {
                LogUtils.d(TAG, "跳过无效书籍缓存索引: bookUrl=$bookUrl, bookName=$bookName, folderName=$folderName")
                return@mapNotNull null
            }
            @Suppress("USELESS_CAST")
            val chapters = (cacheIndex.chapters as List<ChapterCacheInfo>?)
                .orEmpty()
                .mapNotNull { chapterInfo ->
                    @Suppress("USELESS_CAST")
                    val fileName = (chapterInfo.fileName as String?) ?: ""
                    if (fileName.isBlank()) {
                        return@mapNotNull null
                    }
                    @Suppress("USELESS_CAST")
                    val title = (chapterInfo.title as String?) ?: ""
                    @Suppress("USELESS_CAST")
                    val titleMD5 = (chapterInfo.titleMD5 as String?) ?: ""
                    chapterInfo.copy(
                        title = title,
                        titleMD5 = titleMD5,
                        fileName = fileName
                    )
                }
            @Suppress("USELESS_CAST")
            cacheIndex.copy(
                bookUrl = bookUrl,
                bookName = bookName,
                author = (cacheIndex.author as String?) ?: "",
                folderName = folderName,
                chapters = chapters
            )
        }
    }

    private fun Book.sanitizeForCacheRestore(): Book? {
        @Suppress("USELESS_CAST")
        bookUrl = (bookUrl as String?) ?: ""
        @Suppress("USELESS_CAST")
        name = (name as String?) ?: ""
        @Suppress("USELESS_CAST")
        author = (author as String?) ?: ""
        @Suppress("USELESS_CAST")
        originName = (originName as String?) ?: name
        if (bookUrl.isBlank() && name.isBlank()) {
            LogUtils.d(TAG, "跳过无效缓存书籍信息")
            return null
        }
        return this
    }

}
