/**
 * 首页 ViewModel（E-Ink 精简版）
 *
 * 阅读仪表盘的数据层——直接查询本地 DAO，不再需要模块管理系统。
 * 复用已有数据流：BookDao.flowAll()、ReadRecordDao.getTotalReadTime()。
 */
package io.legado.app.ui.main.homepage

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookMatcher
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class HomepageViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val CUSTOM_SET_URL_PREFIX = "custom://"
        private const val HOMEPAGE_CANDIDATE_LIMIT = 32

        /** 判断 URL 是否为自定义集 */
        fun isCustomSetUrl(url: String) = url.startsWith(CUSTOM_SET_URL_PREFIX)
        /** 从 URL 中提取自定义集 ID */
        fun customSetIdFromUrl(url: String): String = url.removePrefix(CUSTOM_SET_URL_PREFIX)

        /**
         * 判断模块是否为无限流类型（瀑布流或无限网格）
         * 无限流模块每个集仅允许存在一个
         */
        fun isInfinite(type: String?, layoutConfig: String?): Boolean {
            return type == HomepageModuleType.Waterfall.key
                    || type == HomepageModuleType.InfiniteGrid.key
        }
    }

    private val _effects = MutableSharedFlow<HomepageEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private val _isBackingUp = MutableStateFlow(false)
    private val _restoreUiState = MutableStateFlow(HomepageRestoreUiState())
    private val homepageRefreshSignal = MutableStateFlow(0L)
    private val readRecordRepository = ReadRecordRepository(appDb.readRecordDao)

    private data class HomepageRestoreUiState(
        val isRestoring: Boolean = false,
        val phase: HomepageRestorePhase? = null,
    )

    private data class HomepageStats(
        val totalBooksRead: Int = 0,
        val totalReadTimeMs: Long = 0L
    )

    private data class HomepageBookSelectionState(
        val lastReadBook: Book? = null,
        val recentBooks: List<Book> = emptyList(),
        val isLoaded: Boolean = false
    )

    private val homepageBooksState: StateFlow<HomepageBookSelectionState> = homepageRefreshSignal
        .flatMapLatest {
            combine(
                appDb.bookDao.flowHomepageBooks(HOMEPAGE_CANDIDATE_LIMIT),
                appDb.readRecordDao.observeHomepageReadBookKeys(HOMEPAGE_CANDIDATE_LIMIT)
            ) { summaries, readBookKeys -> summaries to readBookKeys }
        }
        .mapLatest { (summaries, readBookKeys) ->
            withContext(Default) {
                val readKeySet = readBookKeys.map {
                    "${BookMatcher.normalize(it.bookName)}|${BookMatcher.normalize(it.bookAuthor)}"
                }.toSet()
                val selection = selectHomepageBooks(summaries, readKeySet)
                HomepageBookSelectionState(
                    lastReadBook = selection.lastReadBook?.toBook(),
                    recentBooks = selection.recentBooks.map { it.toBook() },
                    isLoaded = true
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageBookSelectionState())

    private val homepageStatsState: StateFlow<HomepageStats> = combine(
        readRecordRepository.getTotalReadTime(),
        appDb.readRecordDao.observeCount()
    ) { totalTime, readBooksCount ->
        HomepageStats(totalBooksRead = readBooksCount, totalReadTimeMs = totalTime)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageStats())

    private val webDavConfiguredState = AppWebDav.status
        .map { AppWebDav.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppWebDav.isConfigured)

    /** Book cards are independent from the slower aggregate statistics query. */
    val dashboardState: StateFlow<HomepageDashboardState> = combine(
        homepageBooksState,
        homepageStatsState,
        webDavConfiguredState,
        _isBackingUp,
        _restoreUiState
    ) { books, stats, webDavConfigured, backingUp, restoreState ->
        HomepageDashboardState(
            lastReadBook = books.lastReadBook,
            totalBooksRead = stats.totalBooksRead,
            totalReadTimeMs = stats.totalReadTimeMs,
            recentBooks = books.recentBooks,
            webDavConfigured = webDavConfigured,
            isBackingUp = backingUp,
            isRestoring = restoreState.isRestoring,
            restorePhase = restoreState.phase,
            lastBackupTime = LocalConfig.lastBackup,
            isBooksLoaded = books.isLoaded
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomepageDashboardState())

    // ==================== 操作 ====================

    fun onBackup() {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                withContext(Dispatchers.IO) {
                    Backup.backupLocked(getApplication(), null)
                }
                _effects.emit(HomepageEffect.ShowSnackbar(context.getString(R.string.homepage_backup_done)))
            } catch (e: Exception) {
                _effects.emit(HomepageEffect.ShowSnackbar(context.getString(R.string.homepage_backup_failed, e.message)))
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun onRestore() {
        viewModelScope.launch {
            // 按 lastModify 取真正最新的备份，避免文件名字母序与时间序不一致
            val latestBackupName = withContext(Dispatchers.IO) {
                try {
                    AppWebDav.lastBackUp().getOrNull()?.displayName
                } catch (e: Exception) {
                    null
                }
            }
            if (!latestBackupName.isNullOrEmpty()) {
                _effects.emit(HomepageEffect.ShowRestoreDialog(latestBackupName))
            } else {
                _effects.emit(HomepageEffect.ShowSnackbar(
                    context.getString(R.string.homepage_webdav_no_backup)
                ))
            }
        }
    }

    fun restoreFromWebDav(backupName: String) {
        if (_restoreUiState.value.isRestoring) return
        _restoreUiState.value = HomepageRestoreUiState(
            isRestoring = true,
            phase = HomepageRestorePhase.DOWNLOADING_BACKUP
        )
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppWebDav.downloadAndUnzipBackup(backupName)
                }
                _restoreUiState.value = HomepageRestoreUiState(
                    isRestoring = true,
                    phase = HomepageRestorePhase.RESTORING
                )
                withContext(Dispatchers.IO) {
                    Restore.restoreLocked(Backup.backupPath)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("WebDAV restore failed (${e.javaClass.simpleName})")
                val reason = e.localizedMessage
                    ?.takeIf(String::isNotBlank)
                    ?.take(160)
                    ?: e.javaClass.simpleName
                _effects.emit(
                    HomepageEffect.ShowSnackbar(
                        context.getString(R.string.homepage_webdav_restore_failed, reason)
                    )
                )
            } finally {
                _restoreUiState.value = HomepageRestoreUiState()
            }
        }
    }

    fun refreshReadingProgress() {
        homepageRefreshSignal.value += 1L
    }

    fun onBookClick(book: Book) {
        viewModelScope.launch {
            _effects.emit(
                HomepageEffect.NavigateToBookInfo(
                    name = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    origin = book.origin,
                    coverPath = book.getDisplayCover(),
                )
            )
        }
    }
}
