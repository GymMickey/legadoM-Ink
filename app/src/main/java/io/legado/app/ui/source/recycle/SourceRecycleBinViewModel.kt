package io.legado.app.ui.source.recycle

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SourceRecycleBinViewModel(application: Application) : BaseViewModel(application) {

    private val _filter = MutableStateFlow(SourceRecycleBinFilter.ALL)
    val filter: StateFlow<SourceRecycleBinFilter> = _filter.asStateFlow()
    private val _enabled = MutableStateFlow(AppConfig.sourceRecycleBinEnabled)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: Flow<List<SourceRecycleBin>> = _filter.flatMapLatest { filter ->
        if (filter.type == null) {
            appDb.sourceRecycleBinDao.flowAll()
        } else {
            appDb.sourceRecycleBinDao.flowByType(filter.type)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            SourceRecycleBinHelp.cleanupExpired()
        }
    }

    fun setFilter(filter: SourceRecycleBinFilter) {
        _filter.value = filter
    }

    fun setEnabled(enabled: Boolean) {
        AppConfig.sourceRecycleBinEnabled = enabled
        _enabled.value = enabled
    }

    fun checkConflict(item: SourceRecycleBin, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val conflict = SourceRecycleBinHelp.hasConflict(item)
            withContext(Dispatchers.Main) {
                onResult(conflict)
            }
        }
    }

    fun checkConflict(items: List<SourceRecycleBin>, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val conflict = items.any { SourceRecycleBinHelp.hasConflict(it) }
            withContext(Dispatchers.Main) {
                onResult(conflict)
            }
        }
    }

    fun restore(item: SourceRecycleBin, overwrite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            SourceRecycleBinHelp.restore(item, overwrite)
            context.toastOnUi(context.getString(R.string.source_recycle_bin_restored))
        }
    }

    fun restore(items: List<SourceRecycleBin>, overwrite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            items.forEach { SourceRecycleBinHelp.restore(it, overwrite) }
            context.toastOnUi(context.getString(R.string.source_recycle_bin_restored))
        }
    }

    fun delete(item: SourceRecycleBin) {
        viewModelScope.launch(Dispatchers.IO) {
            appDb.sourceRecycleBinDao.delete(item)
            context.toastOnUi(context.getString(R.string.source_recycle_bin_deleted))
        }
    }

    fun delete(items: List<SourceRecycleBin>) {
        viewModelScope.launch(Dispatchers.IO) {
            appDb.sourceRecycleBinDao.delete(*items.toTypedArray())
            context.toastOnUi(context.getString(R.string.source_recycle_bin_deleted))
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            appDb.sourceRecycleBinDao.deleteAll()
            context.toastOnUi(context.getString(R.string.source_recycle_bin_cleared))
        }
    }
}

enum class SourceRecycleBinFilter(
    val labelRes: Int,
    val type: String?
) {
    ALL(R.string.all, null),
    BOOK_SOURCE(R.string.book_source, SourceRecycleBinHelp.TYPE_BOOK_SOURCE),
    RSS_SOURCE(R.string.rss_source, SourceRecycleBinHelp.TYPE_RSS_SOURCE),
    REPLACE_RULE(R.string.replace_rule, SourceRecycleBinHelp.TYPE_REPLACE_RULE),
    TXT_TOC_RULE(R.string.txt_toc_rule, SourceRecycleBinHelp.TYPE_TXT_TOC_RULE),
    DICT_RULE(R.string.dict_rule, SourceRecycleBinHelp.TYPE_DICT_RULE),
    HIGHLIGHT_RULE(R.string.highlight_rule_config, SourceRecycleBinHelp.TYPE_HIGHLIGHT_RULE),
    SEARCH_ENGINE(R.string.search_engine_rule, SourceRecycleBinHelp.TYPE_SEARCH_ENGINE)
}
