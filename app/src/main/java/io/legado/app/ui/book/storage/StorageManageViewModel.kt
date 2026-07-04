package io.legado.app.ui.book.storage

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import io.legado.app.base.BaseViewModel
import io.legado.app.help.storage.CacheDetail
import io.legado.app.help.storage.StorageCalculator
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

// ### 业务层
// 2. StorageManageViewModel.kt

// - 作用 ：ViewModel，管理UI状态和业务逻辑
// - 主要功能 ：
//   - 使用 StateFlow 管理缓存列表、总大小、UI状态
//   - loadCacheInfo() - 加载所有缓存信息
//   - toggleExpand() - 展开/折叠缓存详情
//   - clearCache() - 清理单个缓存类型
//   - clearAllCache() - 一键清理所有缓存

enum class CacheType {
    BOOK_CACHE,
    EPUB_CACHE,
    TEMP_CACHE,
    TTS_CACHE,
    ACACHE_DISK,
    DB_CACHE,
    LOG_CACHE,
    WEBVIEW_CACHE
}

data class CacheItem(
    val id: String,
    val name: String,
    val description: String,
    val size: Long,
    val formattedSize: String,
    val path: String? = null,
    val icon: ImageVector,
    val iconColor: Long,
    val canExpand: Boolean = false,
    val expandBadge: String? = null,
    val details: List<CacheDetail>? = null,
    val isExpanded: Boolean = false
)

sealed class StorageUiState {
    object Idle : StorageUiState()
    object Loading : StorageUiState()
    data class Clearing(val target: String) : StorageUiState()
    data class Error(val message: String) : StorageUiState()
}

class StorageManageViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<StorageUiState>(StorageUiState.Loading)
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    private val _cacheItems = MutableStateFlow<List<CacheItem>>(emptyList())
    val cacheItems: StateFlow<List<CacheItem>> = _cacheItems.asStateFlow()

    private val _totalSize = MutableStateFlow(0L)
    val totalSize: StateFlow<Long> = _totalSize.asStateFlow()

    init {
        loadCacheInfo()
    }

    /**
     * 加载所有缓存信息
     * 使用async并行计算各类缓存大小，显著提升加载速度
     * 原来串行执行需要等待每个计算完成，现在所有计算同时进行
     */
    fun loadCacheInfo() {
        execute {
            _uiState.value = StorageUiState.Loading
            try {
                // 并行启动所有缓存计算任务
                val bookCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateBookCacheSize() }
                val bookCountDeferred = async(Dispatchers.IO) { StorageCalculator.countCachedBooks() }
                val epubCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateEpubCacheSize() }
                val tempCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateTempCacheSize() }
                val ttsCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateTtsCacheSize() }
                val ttsCountDeferred = async(Dispatchers.IO) { StorageCalculator.countTtsEngines() }
                val aCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateACacheSize() }
                val aCacheCountDeferred = async(Dispatchers.IO) { StorageCalculator.countACacheItems() }
                val dbCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateDbCacheSize() }
                val dbCacheCountDeferred = async(Dispatchers.IO) { StorageCalculator.countDbCacheItems() }
                val logCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateLogCacheSize() }
                val webViewCacheDeferred = async(Dispatchers.IO) { StorageCalculator.calculateWebViewCacheSize() }
                val webViewCacheCountDeferred = async(Dispatchers.IO) { StorageCalculator.countWebViewCacheDirs() }
                
                // 等待所有并行任务完成
                val bookSize = bookCacheDeferred.await()
                val bookCount = bookCountDeferred.await()
                val epubSize = epubCacheDeferred.await()
                val tempSize = tempCacheDeferred.await()
                val ttsSize = ttsCacheDeferred.await()
                val ttsCount = ttsCountDeferred.await()
                val aCacheSize = aCacheDeferred.await()
                val aCacheCount = aCacheCountDeferred.await()
                val dbSize = dbCacheDeferred.await()
                val dbCacheCount = dbCacheCountDeferred.await()
                val logSize = logCacheDeferred.await()
                val webViewSize = webViewCacheDeferred.await()
                val webViewCount = webViewCacheCountDeferred.await()
                
                val items = mutableListOf<CacheItem>()
                items.add(createCacheItem(CacheType.BOOK_CACHE, bookSize, true, "${bookCount}本"))
                items.add(createCacheItem(CacheType.EPUB_CACHE, epubSize, false, null))
                items.add(createCacheItem(CacheType.TEMP_CACHE, tempSize, false, null))
                items.add(createCacheItem(CacheType.TTS_CACHE, ttsSize, true, "${ttsCount}个引擎"))
                items.add(createCacheItem(CacheType.ACACHE_DISK, aCacheSize, true, "${aCacheCount}项"))
                items.add(createCacheItem(CacheType.DB_CACHE, dbSize, true, "${dbCacheCount}项"))
                items.add(createCacheItem(CacheType.WEBVIEW_CACHE, webViewSize, true, "${webViewCount}项"))
                items.add(createCacheItem(CacheType.LOG_CACHE, logSize, false, null))
                
                _cacheItems.value = items
                _totalSize.value = items.sumOf { it.size }
                _uiState.value = StorageUiState.Idle
            } catch (e: Exception) {
                _uiState.value = StorageUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun toggleExpand(cacheType: CacheType) {
        execute {
            val currentItems = _cacheItems.value.toMutableList()
            val index = currentItems.indexOfFirst { it.id == cacheType.name }
            if (index == -1) return@execute
            
            val item = currentItems[index]
            if (item.isExpanded) {
                currentItems[index] = item.copy(isExpanded = false)
            } else {
                val details = loadCacheDetails(cacheType)
                currentItems[index] = item.copy(
                    isExpanded = true,
                    details = details
                )
            }
            _cacheItems.value = currentItems
        }
    }

    fun clearCache(cacheType: CacheType, detailId: String? = null) {
        execute {
            val target = detailId ?: getCacheName(cacheType)
            _uiState.value = StorageUiState.Clearing(target)
            try {
                when (cacheType) {
                    CacheType.BOOK_CACHE -> StorageCalculator.clearBookCache(detailId)
                    CacheType.EPUB_CACHE -> StorageCalculator.clearEpubCache()
                    CacheType.TEMP_CACHE -> StorageCalculator.clearTempCache()
                    CacheType.TTS_CACHE -> StorageCalculator.clearTtsCache(detailId)
                    CacheType.ACACHE_DISK -> StorageCalculator.clearACacheAccurate(detailId)
                    CacheType.DB_CACHE -> StorageCalculator.clearDbCacheByPrefix(detailId)
                    CacheType.LOG_CACHE -> StorageCalculator.clearLogCache()
                    CacheType.WEBVIEW_CACHE -> StorageCalculator.clearWebViewCache(detailId)
                }
                loadCacheInfo()
            } catch (e: Exception) {
                _uiState.value = StorageUiState.Error(e.message ?: "清理失败")
            }
        }
    }

    fun clearAllCache() {
        execute {
            _uiState.value = StorageUiState.Clearing("所有缓存")
            try {
                StorageCalculator.clearBookCache()
                StorageCalculator.clearEpubCache()
                StorageCalculator.clearTempCache()
                StorageCalculator.clearTtsCache()
                StorageCalculator.clearACache()
                StorageCalculator.clearDbCache()
                StorageCalculator.clearLogCache()
                StorageCalculator.clearWebViewCache()
                loadCacheInfo()
            } catch (e: Exception) {
                _uiState.value = StorageUiState.Error(e.message ?: "清理失败")
            }
        }
    }

    private fun createCacheItem(
        type: CacheType, 
        size: Long, 
        canExpand: Boolean, 
        expandBadge: String?
    ): CacheItem {
        return CacheItem(
            id = type.name,
            name = getCacheName(type),
            description = getCacheDescription(type),
            size = size,
            formattedSize = ConvertUtils.formatFileSize(size),
            path = getCachePath(type),
            icon = getCacheIcon(type),
            iconColor = getCacheIconColor(type),
            canExpand = canExpand,
            expandBadge = expandBadge
        )
    }

    private suspend fun loadCacheDetails(type: CacheType): List<CacheDetail> {
        return withContext(Dispatchers.IO) {
            when (type) {
                CacheType.BOOK_CACHE -> StorageCalculator.calculateBookCacheDetails()
                CacheType.TTS_CACHE -> StorageCalculator.calculateTtsCacheDetails()
                CacheType.ACACHE_DISK -> StorageCalculator.calculateACacheDetailsAccurate()
                CacheType.DB_CACHE -> StorageCalculator.calculateDbCacheDetailsAccurate()
                CacheType.WEBVIEW_CACHE -> StorageCalculator.calculateWebViewCacheDetails()
                else -> emptyList()
            }
        }
    }

    private fun getCachePath(type: CacheType): String {
        return when (type) {
            CacheType.BOOK_CACHE -> appCtx.externalFiles.getFile("book_cache").absolutePath
            CacheType.EPUB_CACHE -> appCtx.externalFiles.getFile("epub").absolutePath
            CacheType.TEMP_CACHE -> appCtx.externalCache.absolutePath
            CacheType.TTS_CACHE -> appCtx.cacheDir.getFile("httpTTS").absolutePath
            CacheType.ACACHE_DISK -> File(appCtx.cacheDir, "ACache").absolutePath
            CacheType.DB_CACHE -> appCtx.getDatabasePath("legado.db").absolutePath
            CacheType.WEBVIEW_CACHE -> listOf(
                appCtx.getDir("webview", android.content.Context.MODE_PRIVATE).absolutePath,
                appCtx.getDir("hws_webview", android.content.Context.MODE_PRIVATE).absolutePath
            ).joinToString("\n")
            CacheType.LOG_CACHE -> appCtx.externalCache.getFile("log").absolutePath
        }
    }

    fun getCacheName(type: CacheType): String {
        return when (type) {
            CacheType.BOOK_CACHE -> "书籍内容缓存"
            CacheType.EPUB_CACHE -> "Epub 解压缓存"
            CacheType.TEMP_CACHE -> "临时文件缓存"
            CacheType.TTS_CACHE -> "TTS 语音缓存"
            CacheType.ACACHE_DISK -> "ACache 磁盘缓存"
            CacheType.DB_CACHE -> "数据库缓存"
            CacheType.LOG_CACHE -> "日志文件"
            CacheType.WEBVIEW_CACHE -> "WebView 缓存"
        }
    }

    private fun getCacheDescription(type: CacheType): String {
        return when (type) {
            CacheType.BOOK_CACHE -> "章节文本、漫画图片等阅读内容"
            CacheType.EPUB_CACHE -> "Epub 格式书籍的解压临时文件"
            CacheType.TEMP_CACHE -> "下载临时文件、解压临时目录等"
            CacheType.TTS_CACHE -> "在线朗读引擎下载的语音文件"
            CacheType.ACACHE_DISK -> "书源变量、用户信息等运行时缓存"
            CacheType.DB_CACHE -> "CacheDao 存储的临时数据记录"
            CacheType.LOG_CACHE -> "应用运行日志、错误日志等"
            CacheType.WEBVIEW_CACHE -> "WebView 页面数据、本地缓存、Cookie 等持久化内容"
        }
    }

    private fun getCacheIcon(type: CacheType): ImageVector {
        return when (type) {
            CacheType.BOOK_CACHE -> Icons.Filled.Book
            CacheType.EPUB_CACHE -> Icons.Filled.Description
            CacheType.TEMP_CACHE -> Icons.Filled.Folder
            CacheType.TTS_CACHE -> Icons.Filled.Settings
            CacheType.ACACHE_DISK -> Icons.Filled.Save
            CacheType.DB_CACHE -> Icons.Filled.List
            CacheType.LOG_CACHE -> Icons.Filled.Info
            CacheType.WEBVIEW_CACHE -> Icons.Filled.Description
        }
    }

    fun getCacheIconColor(type: CacheType): Long {
        return when (type) {
            CacheType.BOOK_CACHE -> 0xFF3B82F6
            CacheType.EPUB_CACHE -> 0xFF8B5CF6
            CacheType.TEMP_CACHE -> 0xFFF59E0B
            CacheType.TTS_CACHE -> 0xFFEC4899
            CacheType.ACACHE_DISK -> 0xFF10B981
            CacheType.DB_CACHE -> 0xFF6366F1
            CacheType.LOG_CACHE -> 0xFF64748B
            CacheType.WEBVIEW_CACHE -> 0xFF0EA5E9
        }
    }
}
