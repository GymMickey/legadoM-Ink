package io.legado.app.ui.debuglog.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.debug.DebugEventCenter
import io.legado.app.data.repository.debug.FlowLogRecorder
import io.legado.app.data.repository.debug.RssExecutionRecorder
import io.legado.app.model.debug.DebugCategory
import io.legado.app.model.debug.DebugEvent
import io.legado.app.model.debug.DebugLevel
import io.legado.app.model.debug.DebugLogUtils
import io.legado.app.model.debug.FlowLogItem
import io.legado.app.model.debug.FlowStage
import io.legado.app.model.debug.RssExecutionRecord
import io.legado.app.model.debug.RssRuleExecutionRecord
import io.legado.app.model.debug.SourceSubCategory
import io.legado.app.model.debug.ToastContext
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import splitties.init.appCtx

/**
 * 调试日志 ViewModel
 *
 * 负责管理调试日志界面的所有业务逻辑，包括：
 * - 接收并存储来自 DebugEventCenter 的日志事件
 * - 管理日志分类、子分类、阶段等筛选状态
 * - 提供日志刷新、清空、导出等功能
 * - 维护日志采集的暂停/继续状态
 *
 * @param application Application 实例
 */
class DebugLogViewModel(application: Application) : BaseViewModel(application) {

    /**
     * UI 状态数据类
     *
     * @property logs 当前日志列表
     * @property flowLogs 当前流程日志列表
     * @property selectedLog 选中的日志详情
     * @property selectedFlowLog 选中的流程日志详情
     * @property isLoading 是否正在加载
     * @property isEmpty 是否为空
     * @property isPaused 是否暂停采集
     */
    data class UiState(
        val logs: List<DebugEvent> = emptyList(),
        val flowLogs: List<FlowLogItem> = emptyList(),
        val selectedLog: DebugEvent? = null,
        val selectedFlowLog: FlowLogItem? = null,
        val isLoading: Boolean = false,
        val isEmpty: Boolean = false,
        val isPaused: Boolean = false
    )

    /** UI 状态 */
    private val _uiState = MutableStateFlow(UiState())

    /** 对外暴露的 UI 状态只读流 */
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 所有日志的内存缓存 */
    @Volatile
    private var _allLogs = listOf<DebugEvent>()

    /** 当前选中的分类 */
    private val _selectedCategory = MutableStateFlow(DebugCategory.ALL)
    val selectedCategory: StateFlow<DebugCategory> = _selectedCategory.asStateFlow()

    /** 当前选中的子分类（用于书源分类的细分） */
    private val _selectedSubCategory = MutableStateFlow<SourceSubCategory?>(null)
    val selectedSubCategory: StateFlow<SourceSubCategory?> = _selectedSubCategory.asStateFlow()

    /** 可用书源列表（用于实体显示的书源选择器） */
    private val _bookSources = MutableStateFlow<List<BookSource>>(emptyList())
    val bookSources: StateFlow<List<BookSource>> = _bookSources.asStateFlow()

    /** 当前选中查看的书源 URL */
    private val _selectedBookSourceUrl = MutableStateFlow<String?>(null)
    val selectedBookSourceUrl: StateFlow<String?> = _selectedBookSourceUrl.asStateFlow()

    /** 当前选中的完整书源对象（含嵌套规则实体） */
    private val _selectedBookSource = MutableStateFlow<BookSource?>(null)
    val selectedBookSource: StateFlow<BookSource?> = _selectedBookSource.asStateFlow()

    /** 可用订阅源列表（用于实体显示的订阅源选择器） */
    private val _rssSources = MutableStateFlow<List<RssSource>>(emptyList())
    val rssSources: StateFlow<List<RssSource>> = _rssSources.asStateFlow()

    /** 当前选中查看的订阅源 URL */
    private val _selectedRssSourceUrl = MutableStateFlow<String?>(null)
    val selectedRssSourceUrl: StateFlow<String?> = _selectedRssSourceUrl.asStateFlow()

    /** 当前选中的完整订阅源对象 */
    private val _selectedRssSource = MutableStateFlow<RssSource?>(null)
    val selectedRssSource: StateFlow<RssSource?> = _selectedRssSource.asStateFlow()

    /** 当前选中的流程阶段 */
    private val _selectedFlowStage = MutableStateFlow<FlowStage?>(null)
    val selectedFlowStage: StateFlow<FlowStage?> = _selectedFlowStage.asStateFlow()

    /** 是否暂停采集 */
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    /** 搜索关键词 */
    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    /** 订阅源执行记录 */
    private val _rssExecutionRecords = MutableStateFlow<List<RssExecutionRecord>>(emptyList())
    val rssExecutionRecords: StateFlow<List<RssExecutionRecord>> = _rssExecutionRecords.asStateFlow()

    /** 订阅源规则执行记录 */
    private val _rssRuleRecords = MutableStateFlow<List<RssRuleExecutionRecord>>(emptyList())
    val rssRuleRecords: StateFlow<List<RssRuleExecutionRecord>> = _rssRuleRecords.asStateFlow()

    /**
     * 筛选后的日志列表
     *
     * 根据选中的分类、子分类和搜索关键词进行多层过滤：
     * 1. 按分类过滤（SOURCE 分类包含 SOURCE 和 RULE 两类日志）
     * 2. 按子分类过滤（仅 SOURCE 分类支持）
     * 3. 按搜索关键词过滤（匹配消息、详情、URL、书源名）
     */
    val filteredLogs = combine(_uiState, _selectedCategory, _selectedSubCategory, _searchQuery) { uiState, category, subCategory, query ->
        var result = uiState.logs

        // 按分类过滤
        if (category != DebugCategory.ALL) {
            result = result.filter {
                when (category) {
                    // SOURCE 分类包含 SOURCE 和 RULE 两类
                    DebugCategory.SOURCE -> {
                        it.category == DebugCategory.SOURCE || it.category == DebugCategory.RULE
                    }
                    else -> it.category == category
                }
            }
        }

        // 按子分类过滤（SOURCE 和 RSS 分类支持）
        if (subCategory != null && (category == DebugCategory.SOURCE || category == DebugCategory.RSS)) {
            result = result.filter { it.subCategory == subCategory }
        }

        // 按搜索关键词过滤
        query?.let { q ->
            if (q.isNotBlank()) {
                result = result.filter { log ->
                    log.message.contains(q, ignoreCase = true) ||
                    log.detail?.contains(q, ignoreCase = true) == true ||
                    log.url?.contains(q, ignoreCase = true) == true ||
                    log.sourceName?.contains(q, ignoreCase = true) == true
                }
            }
        }

        result
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    /**
     * 各分类的日志数量
     *
     * 用于在分类标签页显示每个分类的日志数量统计。
     * 数量会随日志列表的更新而自动更新。
     */
    val categoryCounts = _uiState.map { uiState ->
        val logs = uiState.logs
        mapOf(
            DebugCategory.ALL to logs.size,
            DebugCategory.APP to logs.count { it.category == DebugCategory.APP },
            DebugCategory.NETWORK to logs.count { it.category == DebugCategory.NETWORK },
            DebugCategory.SOURCE to logs.count { it.category == DebugCategory.SOURCE || it.category == DebugCategory.RULE },
            DebugCategory.RSS to logs.count { it.category == DebugCategory.RSS },
            DebugCategory.TOAST to logs.count { it.category == DebugCategory.TOAST },
            DebugCategory.CHECK to logs.count { it.category == DebugCategory.CHECK },
            DebugCategory.CRASH to logs.count { it.category == DebugCategory.CRASH },
            DebugCategory.RULE to logs.count { it.category == DebugCategory.RULE },
            DebugCategory.READER to logs.count { it.category == DebugCategory.READER }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyMap()
    )

    /**
     * 筛选后的流程日志列表
     *
     * 根据选中的流程阶段和搜索关键词进行过滤：
     * 1. 按流程阶段过滤
     * 2. 按搜索关键词过滤（匹配消息、详情、URL、书源名、规则、结果）
     */
    val filteredFlowLogs = combine(_uiState, _selectedFlowStage, _searchQuery, _selectedCategory) { uiState, stage, query, category ->
        var result = uiState.flowLogs

        // 按源类型过滤（书源只看书源流程日志，订阅源只看订阅源流程日志）
        result = when (category) {
            DebugCategory.SOURCE -> result.filter { it.sourceType == io.legado.app.model.debug.SourceType.BOOK }
            DebugCategory.RSS -> result.filter { it.sourceType == io.legado.app.model.debug.SourceType.RSS }
            else -> result
        }

        // 按流程阶段过滤
        if (stage != null) {
            result = result.filter { it.stage == stage }
        }

        // 按搜索关键词过滤
        query?.let { q ->
            if (q.isNotBlank()) {
                result = result.filter { log ->
                    log.message.contains(q, ignoreCase = true) ||
                    log.detail?.contains(q, ignoreCase = true) == true ||
                    log.url?.contains(q, ignoreCase = true) == true ||
                    log.sourceName?.contains(q, ignoreCase = true) == true ||
                    log.rule?.contains(q, ignoreCase = true) == true ||
                    log.result?.contains(q, ignoreCase = true) == true
                }
            }
        }

        result
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyList()
    )

    /** 单次事件流（用于 Toast 等一次性事件） */
    private val _singleEvents = MutableSharedFlow<String>()
    val singleEvents: SharedFlow<String> = _singleEvents.asSharedFlow()

    /**
     * 初始化
     *
     * 在 ViewModel 创建时执行初始化操作：
     * 1. 加载历史日志
     * 2. 订阅实时日志事件流
     * 3. 订阅流程日志流（内部会立即刷新一次流程日志）
     */
    init {
        loadHistoryLogs()
        subscribeToEventFlow()
        subscribeToFlowLogs()
        subscribeToRssExecutionRecords()
        subscribeToRssRuleRecords()
    }

    private fun subscribeToRssExecutionRecords() {
        RssExecutionRecorder.recordsFlow
            .onEach { records ->
                _rssExecutionRecords.value = records
            }
            .launchIn(viewModelScope)
        refreshRssExecutionRecords()
    }

    fun refreshRssExecutionRecords() {
        _rssExecutionRecords.value = RssExecutionRecorder.getCurrentRecords()
    }

    /**
     * 订阅规则执行记录流
     *
     * 监听 RssExecutionRecorder.ruleRecordsFlow，
     * 实时接收规则执行记录更新并同步到 UI 状态
     */
    private fun subscribeToRssRuleRecords() {
        RssExecutionRecorder.ruleRecordsFlow
            .onEach { records ->
                _rssRuleRecords.value = records
            }
            .launchIn(viewModelScope)
        refreshRssRuleRecords()
    }

    /**
     * 刷新规则执行记录
     *
     * 从 RssExecutionRecorder 获取当前所有规则执行记录
     */
    fun refreshRssRuleRecords() {
        _rssRuleRecords.value = RssExecutionRecorder.getCurrentRuleRecords()
    }

    /**
     * 订阅流程日志
     *
     * 从 FlowLogRecorder 接收流程日志更新，
     * 并更新到 UI 状态中。
     */
    private fun subscribeToFlowLogs() {
        FlowLogRecorder.logs
            .onEach { logs ->
                _uiState.value = _uiState.value.copy(flowLogs = logs)
            }
            .launchIn(viewModelScope)
        // 订阅建立时立即加载当前数据，避免等待 SharedFlow 首次 emit
        refreshFlowLogs()
    }

    /**
     * 刷新流程日志
     *
     * 手动从 FlowLogRecorder 获取最新流程日志，
     * 用于打开界面时加载最新数据。
     */
    fun refreshFlowLogs() {
        val currentLogs = FlowLogRecorder.getCurrentLogs()
        _uiState.value = _uiState.value.copy(flowLogs = currentLogs)
    }

    /**
     * 刷新日志
     *
     * 手动从 DebugEventCenter 获取最新日志，
     * 用于打开界面时或切换分类时加载最新数据。
     */
    fun refreshLogs() {
        val newLogs = DebugEventCenter.getRecentLogs(DebugEventCenter.MAX_EVENTS)
        synchronized(this) {
            _allLogs = newLogs
        }
        _uiState.value = _uiState.value.copy(
            logs = newLogs,
            isEmpty = newLogs.isEmpty()
        )
    }

    /**
     * 选择分类
     *
     * 当选择非 SOURCE 分类时，自动清除子分类选择。
     *
     * @param category 选中的分类
     */
    fun selectCategory(category: DebugCategory) {
        _selectedCategory.value = category
        if (category != DebugCategory.SOURCE && category != DebugCategory.RSS) {
            _selectedSubCategory.value = null
        }
    }

    /**
     * 选择子分类
     *
     * @param subCategory 选中的子分类，null 表示全部
     */
    fun selectSubCategory(subCategory: SourceSubCategory?) {
        _selectedSubCategory.value = subCategory
        if (subCategory == SourceSubCategory.ENTITY) {
            when (_selectedCategory.value) {
                DebugCategory.SOURCE -> if (_bookSources.value.isEmpty()) loadBookSources()
                DebugCategory.RSS -> if (_rssSources.value.isEmpty()) loadRssSources()
                else -> {}
            }
        }
    }

    /**
     * 加载书源列表
     *
     * 从数据库获取所有已启用的书源，按自定义排序排列。
     * 仅在首次切换到 ENTITY 子分类时加载。
     */
    fun loadBookSources() {
        execute {
            appDb.bookSourceDao.all
                .filter { it.enabled }
                .sortedBy { it.customOrder }
        }.onSuccess { sources ->
            _bookSources.value = sources
        }.onError { e ->
            e.printStackTrace()
            showToast("加载书源列表失败：${e.message}")
        }
    }

    /**
     * 选择要查看的书源
     *
     * @param bookSourceUrl 书源 URL
     */
    fun selectBookSource(bookSourceUrl: String) {
        val source = _bookSources.value.firstOrNull { it.bookSourceUrl == bookSourceUrl }
        _selectedBookSourceUrl.value = if (source != null) bookSourceUrl else null
        _selectedBookSource.value = source
    }

    /**
     * 加载订阅源列表
     *
     * 从数据库获取所有已启用的订阅源，按自定义排序排列。
     */
    fun loadRssSources() {
        execute {
            appDb.rssSourceDao.all
                .filter { it.enabled }
                .sortedBy { it.customOrder }
        }.onSuccess { sources ->
            _rssSources.value = sources
        }.onError { e ->
            e.printStackTrace()
            showToast("加载订阅源列表失败：${e.message}")
        }
    }

    /**
     * 选择要查看的订阅源
     *
     * @param sourceUrl 订阅源 URL
     */
    fun selectRssSource(sourceUrl: String) {
        val source = _rssSources.value.firstOrNull { it.sourceUrl == sourceUrl }
        _selectedRssSourceUrl.value = if (source != null) sourceUrl else null
        _selectedRssSource.value = source
    }

    /**
     * 选择流程阶段
     *
     * @param stage 选中的阶段，null 表示全部
     */
    fun selectFlowStage(stage: FlowStage?) {
        _selectedFlowStage.value = stage
    }

    /**
     * 设置搜索关键词
     *
     * @param query 搜索关键词，null 或空字符串表示清除搜索
     */
    fun setSearchQuery(query: String?) {
        _searchQuery.value = query
    }

    /**
     * 选择日志
     *
     * 用于显示日志详情弹窗。
     *
     * @param log 选中的日志
     */
    fun selectLog(log: DebugEvent) {
        _uiState.value = _uiState.value.copy(selectedLog = log)
    }

    /**
     * 选择流程日志
     *
     * 用于显示流程日志详情弹窗。
     *
     * @param log 选中的流程日志
     */
    fun selectFlowLog(log: FlowLogItem) {
        _uiState.value = _uiState.value.copy(selectedFlowLog = log)
    }

    /**
     * 清除选择
     *
     * 关闭详情弹窗。
     */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedLog = null, selectedFlowLog = null)
    }

    /**
     * 复制流程日志详情
     *
     * 将流程日志详情复制到剪贴板，包含：
     * - 阶段、书源名
     * - 时间、消息
     * - 书源URL、操作、请求URL、请求方法
     * - 状态码、耗时、规则、结果、详情、异常
     *
     * @param log 要复制的流程日志
     */
    fun copyFlowLogDetail(log: FlowLogItem) {
        val text = buildString {
            appendLine("[${log.stage.displayName}] ${log.sourceName ?: "未知书源"}")
            appendLine("时间: ${formatTime(log.startTime)}")
            appendLine("消息: ${log.message}")
            log.sourceUrl?.let { appendLine("书源URL: $it") }
            log.operation?.let { appendLine("操作: $it") }
            log.url?.let { appendLine("请求URL: $it") }
            log.method?.let { appendLine("请求方法: $it") }
            log.statusCode?.let { appendLine("状态码: $it") }
            log.duration?.let { appendLine("耗时: ${it}ms") }
            log.cookies?.let { appendLine("Cookie: $it") }
            // 导出请求头（Cookie 已单独展示，此处跳过避免重复）
            if (!log.requestHeaders.isNullOrEmpty()) {
                appendLine("请求头:")
                log.requestHeaders.forEach { (key, value) ->
                    if (key != "Cookie") {
                        appendLine("  $key: $value")
                    }
                }
            }
            log.rule?.let { appendLine("规则: $it") }
            log.originalValue?.let { appendLine("原始数据: $it") }
            log.result?.let { appendLine("结果: $it") }
            log.detail?.let { appendLine("\n详情:\n$it") }
            log.error?.let { appendLine("\n异常:\n${it.stackTraceToString()}") }
        }

        copyToClipboard(text)
        showToast("已复制到剪贴板")
    }

    /**
     * 切换暂停/继续状态
     *
     * 控制是否暂停接收新的日志事件。
     */
    fun togglePause() {
        val newPauseState = !_isPaused.value
        _isPaused.value = newPauseState
        _uiState.value = _uiState.value.copy(isPaused = newPauseState)

        if (newPauseState) {
            showToast("已暂停采集")
        } else {
            showToast("已继续采集")
        }
    }

    /**
     * 清空所有日志
     *
     * 同时清空 DebugEventCenter 中的日志和本地缓存。
     */
    fun clearLogs() {
        execute {
            DebugEventCenter.clear()
            RssExecutionRecorder.clear()
            synchronized(this) {
                _allLogs = emptyList()
            }
            _uiState.value = UiState(
                logs = emptyList(),
                isEmpty = true,
                isPaused = _isPaused.value
            )
        }.onSuccess {
            showToast("已清空所有日志")
        }.onError { e ->
            e.printStackTrace()
            showToast("清空失败：${e.message}")
        }
    }

    /**
     * 清除搜索
     *
     * 重置搜索关键词。
     */
    fun clearSearch() {
        _searchQuery.value = null
    }

    /**
     * 复制日志详情
     *
     * 将日志详情复制到剪贴板，包含：
     * - 级别、分类
     * - 时间、消息
     * - URL、方法、状态码、耗时、书源名
     * - 详情、异常
     * - Toast 上下文（如果是 Toast 分类）
     *
     * @param log 要复制的日志
     */
    fun copyLogDetail(log: DebugEvent) {
        val text = buildString {
            appendLine("[${log.level.displayName}] ${log.category.displayName}")
            appendLine("时间: ${formatTime(log.time)}")
            appendLine("消息: ${log.message}")
            
            if (log.category == DebugCategory.TOAST) {
                val toastContext = ToastContext.fromTagsMap(log.tags)
                toastContext.activityName?.let { appendLine("界面: $it") }
                toastContext.sourceName?.let { appendLine("源名称: $it") }
                toastContext.sourceType?.let { appendLine("源类型: ${it.displayName}") }
                toastContext.ruleType?.let { appendLine("规则类型: ${it.displayName}") }
                toastContext.ruleLine?.let { appendLine("规则行号: 第${it}行") }
            } else {
                log.url?.let { appendLine("URL: $it") }
                log.method?.let { appendLine("方法: $it") }
                log.statusCode?.let { appendLine("状态码: $it") }
                log.duration?.let { appendLine("耗时: ${it}ms") }
                log.sourceName?.let { appendLine("书源: $it") }
            }
            
            log.detail?.let { appendLine("\n详情:\n$it") }
            log.throwable?.let { appendLine("\n异常:\n${it.stackTraceToString()}") }
        }

        copyToClipboard(text)
        showToast("已复制到剪贴板")
    }

    /**
     * 导出筛选后的日志
     *
     * 根据当前筛选条件导出日志为文本格式。
     *
     * @return 导出的日志文本
     */
    fun exportFilteredLogs(): String {
        val logs = run {
            val current = _uiState.value
            var result = current.logs

            if (_selectedCategory.value != DebugCategory.ALL) {
                result = result.filter { it.category == _selectedCategory.value }
            }

            _searchQuery.value?.let { query ->
                if (!query.isBlank()) {
                    result = result.filter { it.message.contains(query, ignoreCase = true) }
                }
            }

            result
        }

        return buildString {
            appendLine("=== 调试日志导出 ===")
            appendLine("导出时间: ${formatTime(System.currentTimeMillis())}")
            appendLine("总条数: ${logs.size}\n")

            logs.forEachIndexed { index, event ->
                appendLine("--- [${index + 1}] ---")
                appendLine("[${event.level.displayName}] [${event.category.displayName}]")
                appendLine("时间: ${formatTime(event.time)}")
                appendLine("消息: ${event.message}")

                event.url?.let { appendLine("URL: $it") }
                event.method?.let { appendLine("方法: $it") }
                event.statusCode?.let { appendLine("状态码: $it") }
                event.duration?.let { appendLine("耗时: ${it}ms") }
                event.sourceName?.let { appendLine("书源: $it") }

                event.detail?.let { appendLine("\n详情:\n$it") }
                event.throwable?.let { appendLine("\n异常:\n${it.stackTraceToString()}") }

                appendLine()
            }
        }
    }

    /**
     * 导出所有日志
     *
     * 导出 DebugEventCenter 中的所有日志。
     *
     * @return 导出的日志文本
     */
    fun exportAllLogs(): String {
        return DebugEventCenter.exportToText()
    }

    /**
     * 加载历史日志
     *
     * 从 DebugEventCenter 获取最近的日志历史记录。
     */
    private fun loadHistoryLogs() {
        _uiState.value = _uiState.value.copy(isLoading = true)

        execute {
            val loadedLogs = DebugEventCenter.getRecentLogs(DebugEventCenter.MAX_EVENTS)
            val deduplicated = synchronized(this) {
                // 去重：移除已被事件流接收的重复事件
                val existingIds = _allLogs.mapTo(mutableSetOf()) { it.id }
                val filtered = if (existingIds.isEmpty()) loadedLogs else loadedLogs.filter { it.id !in existingIds }
                val merged = filtered + _allLogs
                _allLogs = merged
                merged
            }

            _uiState.value = UiState(
                logs = deduplicated,
                isEmpty = deduplicated.isEmpty(),
                isLoading = false,
                isPaused = _isPaused.value
            )
        }.onError { e ->
            e.printStackTrace()
            _uiState.value = _uiState.value.copy(isLoading = false, isEmpty = true)
            showToast("加载历史日志失败：${e.message}")
        }
    }

    /**
     * 订阅日志事件流
     *
     * 从 DebugEventCenter.eventFlow 接收实时日志事件，
     * 并更新到本地缓存和 UI 状态中。
     * 当暂停采集时，会丢弃新事件。
     */
    private fun subscribeToEventFlow() {
        DebugEventCenter.eventFlow
            .filter { !_isPaused.value }
            .onEach { event ->
                val newLogs = synchronized(this) {
                    // 去重：如果事件已存在于历史日志中则跳过
                    if (_allLogs.any { it.id == event.id }) {
                        return@synchronized null
                    }

                    val updatedLogs = mutableListOf(event)
                    updatedLogs.addAll(_allLogs)

                    // 保持日志数量不超过上限
                    if (updatedLogs.size > DebugEventCenter.MAX_EVENTS) {
                        updatedLogs.removeAt(updatedLogs.lastIndex)
                    }

                    val result = updatedLogs.toList()
                    _allLogs = result
                    result
                }

                // 去重返回 null 时不更新 UI
                newLogs ?: return@onEach

                _uiState.value = _uiState.value.copy(
                    logs = newLogs,
                    isEmpty = false,
                    isPaused = _isPaused.value
                )
            }
            .catch { e ->
                e.printStackTrace()
                showToast("接收事件异常：${e.message}")
            }
            .launchIn(viewModelScope)
    }

    /**
     * 显示 Toast 消息
     *
     * @param message 消息内容
     */
    private fun showToast(message: String) {
        appCtx.toastOnUi(message)
    }

    /**
     * 复制到剪贴板
     *
     * @param text 要复制的文本
     */
    private fun copyToClipboard(text: String) {
        val clipboard = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Log", text)
        clipboard.setPrimaryClip(clip)
    }

    /**
     * 格式化时间戳
     *
     * @param timestamp 时间戳（毫秒）
     * @return 格式化的日期时间字符串 yyyy-MM-dd HH:mm:ss.SSS
     */
    private fun formatTime(timestamp: Long): String {
        return DebugLogUtils.formatFullTime(timestamp)
    }

    companion object {
        /** 最大显示日志数量 */
        const val MAX_DISPLAY_LOGS = DebugEventCenter.MAX_EVENTS
    }
}
