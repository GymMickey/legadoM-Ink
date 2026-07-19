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
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class HomepageViewModel(application: Application) : BaseViewModel(application) {

    companion object {
        private const val CUSTOM_SET_URL_PREFIX = "custom://"

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
    private val readRecordRepository = ReadRecordRepository(appDb.readRecordDao)

    /**
     * 仪表盘状态——组合书架数据与阅读统计。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboardState: StateFlow<HomepageDashboardState> = combine(
        appDb.bookDao.flowHomepageBooks().mapLatest { summaries ->
            // SQL 已过滤 isNotShelf，不再需要 filterNot
            val readSummaries = summaries
            val lastReadSummary = readSummaries.maxByOrNull { it.durChapterTime }
            val recentSummaries = readSummaries
                .sortedByDescending { it.durChapterTime }
                .filter { it.bookUrl != lastReadSummary?.bookUrl }
                .take(10)
            Triple(
                lastReadSummary?.toBook(),
                recentSummaries.map { it.toBook() },
                summaries.map { it.toBook() },
            )
        },
        readRecordRepository.getTotalReadTime(),
        appDb.readRecordDao.observeCount(),
        _isBackingUp
    ) { (lastRead, recent, _), totalTime, readBooksCount, backingUp ->
        HomepageDashboardState(
            lastReadBook = lastRead,
            totalBooksRead = readBooksCount,
            totalReadTimeMs = totalTime,
            recentBooks = recent,
            webDavConfigured = AppWebDav.isOk,
            isBackingUp = backingUp,
            lastBackupTime = LocalConfig.lastBackup,
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
