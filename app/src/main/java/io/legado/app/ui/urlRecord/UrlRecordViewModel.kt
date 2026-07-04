package io.legado.app.ui.urlRecord

import android.app.Application
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.constant.PreferKey
import io.legado.app.utils.putPrefBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * URL记录界面ViewModel
 * 
 * 负责管理URL记录的数据状态和业务逻辑：
 * - 观察URL记录数据变化
 * - 管理域名、来源、方法列表用于筛选
 * - 处理搜索和多条件筛选逻辑
 * - 执行记录清除操作
 */
class UrlRecordViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<UrlRecordUIState>(UrlRecordUIState.Loading)
    val uiState: StateFlow<UrlRecordUIState> = _uiState.asStateFlow()

    val recordCount = MutableStateFlow(0)
    
    private val _domains = MutableStateFlow<List<String>>(emptyList())
    val domains: StateFlow<List<String>> = _domains.asStateFlow()

    private val _sourceNames = MutableStateFlow<List<String>>(emptyList())
    val sourceNames: StateFlow<List<String>> = _sourceNames.asStateFlow()

    private val _methods = MutableStateFlow<List<String>>(emptyList())
    val methods: StateFlow<List<String>> = _methods.asStateFlow()

    private val _isRecordEnabled = MutableStateFlow(AppConfig.recordUrl)
    val isRecordEnabled: StateFlow<Boolean> = _isRecordEnabled.asStateFlow()

    private var observeCoroutine: Coroutine<Unit>? = null
    private var domainCoroutine: Coroutine<Unit>? = null
    private var sourceNameCoroutine: Coroutine<Unit>? = null
    private var methodCoroutine: Coroutine<Unit>? = null
    
    var currentDomain: String? = null
        private set

    var currentSourceName: String? = null
        private set

    var currentMethod: String? = null
        private set

    var currentSuccess: Boolean? = null
        private set

    var searchViewQuery: String? = null
        private set

    init {
        observeRecords()
        observeDomains()
        observeSourceNames()
        observeMethods()
    }

    /**
     * 观察域名列表变化
     */
    private fun observeDomains() {
        domainCoroutine = execute {
            appDb.urlRecordDao.flowAllDomains()
                .catch { e ->
                    e.printStackTrace()
                }
                .collect { domainList ->
                    _domains.value = domainList
                }
        }
    }

    /**
     * 观察来源名称列表变化
     */
    private fun observeSourceNames() {
        sourceNameCoroutine = execute {
            appDb.urlRecordDao.flowAllSourceNames()
                .catch { e ->
                    e.printStackTrace()
                }
                .collect { sourceList ->
                    _sourceNames.value = sourceList
                }
        }
    }

    /**
     * 观察HTTP方法列表变化
     */
    private fun observeMethods() {
        methodCoroutine = execute {
            appDb.urlRecordDao.flowAllMethods()
                .catch { e ->
                    e.printStackTrace()
                }
                .collect { methodList ->
                    _methods.value = methodList
                }
        }
    }

    /**
     * 观察URL记录数据（支持多条件筛选）
     */
    fun observeRecords() {
        observeCoroutine?.cancel()
        observeCoroutine = execute {
            appDb.urlRecordDao.flowFilter(
                domain = currentDomain,
                sourceName = currentSourceName,
                method = currentMethod,
                success = currentSuccess,
                keyword = searchViewQuery
            )
                .onStart {
                    _uiState.value = UrlRecordUIState.Loading
                }
                .catch { e ->
                    _uiState.value = UrlRecordUIState.Error(e.message ?: "加载失败")
                }
                .collect { records ->
                    recordCount.value = records.size
                    _uiState.value = if (records.isEmpty()) {
                        UrlRecordUIState.Empty
                    } else {
                        UrlRecordUIState.Success(records)
                    }
                }
        }
    }

    /**
     * 设置域名筛选
     */
    fun filterByDomain(domain: String?) {
        currentDomain = domain
        observeRecords()
    }

    /**
     * 设置来源名称筛选
     */
    fun filterBySourceName(sourceName: String?) {
        currentSourceName = sourceName
        observeRecords()
    }

    /**
     * 设置HTTP方法筛选
     */
    fun filterByMethod(method: String?) {
        currentMethod = method
        observeRecords()
    }

    /**
     * 设置状态筛选
     * @param success true=成功(2xx), false=失败, null=全部
     */
    fun filterByStatus(success: Boolean?) {
        currentSuccess = success
        observeRecords()
    }

    /**
     * 设置搜索关键词
     */
    fun setSearchQuery(query: String?) {
        searchViewQuery = query
        observeRecords()
    }

    /**
     * 清除所有筛选条件
     */
    fun clearAllFilters() {
        currentDomain = null
        currentSourceName = null
        currentMethod = null
        currentSuccess = null
        searchViewQuery = null
        observeRecords()
    }

    /**
     * 是否有激活的筛选条件
     */
    fun hasActiveFilters(): Boolean {
        return currentDomain != null || 
               currentSourceName != null || 
               currentMethod != null || 
               currentSuccess != null ||
               !searchViewQuery.isNullOrEmpty()
    }

    /**
     * 清除所有URL记录
     */
    suspend fun clearAll(): Int {
        return withContext(Dispatchers.IO) {
            appDb.urlRecordDao.deleteAll()
        }
    }

    /**
     * 删除指定天数之前的记录
     */
    suspend fun deleteOldRecords(days: Int): Int {
        return withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            appDb.urlRecordDao.deleteOldRecords(timestamp)
        }
    }

    /**
     * 获取指定天数之前的记录数
     */
    suspend fun getOldRecordsCount(days: Int): Int {
        return withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
            appDb.urlRecordDao.getOldRecordsCount(timestamp)
        }
    }

    /**
     * 设置URL记录开关
     */
    fun setRecordUrl(enabled: Boolean) {
        AppConfig.recordUrl = enabled
        appCtx.putPrefBoolean(PreferKey.recordUrl, enabled)
        _isRecordEnabled.value = enabled
    }
}
