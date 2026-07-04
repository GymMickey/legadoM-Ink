/**
 * 书源检测ViewModel
 *
 * 功能说明：
 * 管理书源检测的状态、进度和结果
 * 提供检测控制方法（开始、停止、暂停）
 * 维护检测结果列表和统计数据
 * 从数据库加载检测结果，确保持久化显示
 *
 * 架构说明：
 * 使用sealed class封装UIState，确保类型安全和状态一致性
 * 使用StateFlow管理UI状态，确保线程安全
 * 与CheckSourceService集成，通过EventBus接收检测消息
 * 提供数据过滤和排序功能
 * 支持重新检测和结果持久化
 */
package io.legado.app.ui.book.source.check

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.CheckSource
import io.legado.app.model.CheckSourceResultEvent
import io.legado.app.model.Debug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 检测状态枚举
 */
enum class CheckState {
    IDLE,
    CHECKING,
    PAUSED,
    COMPLETED
}

/**
 * 检测进度数据类
 *
 * @param current 当前检测数量
 * @param total 总数量
 * @param currentSourceName 当前检测的书源名称
 */
data class CheckProgress(
    val current: Int = 0,
    val total: Int = 0,
    val currentSourceName: String = ""
) {
    val progress: Float
        get() = if (total > 0) current.toFloat() / total else 0f

    val progressText: String
        get() = "$current / $total"
}

/**
 * 错误类型枚举
 */
enum class ErrorType {
    NONE,
    TIMEOUT,
    NETWORK_ERROR,
    PARSE_ERROR,
    SCRIPT_ERROR,
    DOMAIN_ERROR,
    SEARCH_ERROR,
    DISCOVERY_ERROR,
    INFO_ERROR,
    TOC_ERROR,
    CONTENT_ERROR
}

/**
 * 检测结果数据类
 *
 * @param sourceUrl 书源URL
 * @param sourceName 书源名称
 * @param isSuccess 是否成功
 * @param respondTime 响应时间（毫秒）
 * @param errorMessage 错误信息
 * @param errorType 错误类型
 * @param checkTime 检测时间
 */
data class CheckResult(
    val sourceUrl: String,
    val sourceName: String,
    val isSuccess: Boolean,
    val respondTime: Long = 0,
    val errorMessage: String? = null,
    val errorType: ErrorType = ErrorType.NONE,
    val checkTime: Long = System.currentTimeMillis()
) {
    fun getRespondTimeText(): String {
        return if (respondTime > 0) {
            "${respondTime}ms"
        } else {
            "-"
        }
    }

    fun getStatusText(): String {
        return if (isSuccess) {
            "成功"
        } else {
            errorMessage ?: "失败"
        }
    }
}

/**
 * 检测统计数据类
 *
 * @param totalCount 总数量
 * @param successCount 成功数量
 * @param failedCount 失败数量
 * @param timeoutCount 超时数量
 * @param avgRespondTime 平均响应时间
 */
data class CheckStatistics(
    val totalCount: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val timeoutCount: Int = 0,
    val avgRespondTime: Long = 0
) {
    val successRate: Float
        get() = if (totalCount > 0) successCount.toFloat() / totalCount else 0f

    val successRateText: String
        get() = if (totalCount > 0) {
            String.format("%.1f%%", successRate * 100)
        } else {
            "0%"
        }

    val avgRespondTimeText: String
        get() = if (avgRespondTime > 0) {
            "${avgRespondTime}ms"
        } else {
            "-"
        }
}

/**
 * 结果过滤器
 */
enum class ResultFilter {
    ALL,
    SUCCESS,
    FAILED
}

/**
 * 书源检测UI状态 - 使用sealed class确保类型安全
 */
sealed class CheckSourceUIState {
    data class Idle(
        val results: List<CheckResult> = emptyList(),
        val statistics: CheckStatistics = CheckStatistics()
    ) : CheckSourceUIState()

    data class Checking(
        val progress: CheckProgress,
        val currentMessage: String,
        val results: List<CheckResult>,
        val statistics: CheckStatistics
    ) : CheckSourceUIState()

    data class Paused(
        val progress: CheckProgress,
        val results: List<CheckResult>,
        val statistics: CheckStatistics
    ) : CheckSourceUIState()

    data class Completed(
        val results: List<CheckResult>,
        val statistics: CheckStatistics
    ) : CheckSourceUIState()
}

/**
 * 书源检测ViewModel
 *
 * @param application Application上下文
 */
class CheckSourceViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<CheckSourceUIState>(CheckSourceUIState.Idle())
    val uiState: StateFlow<CheckSourceUIState> = _uiState.asStateFlow()

    var resultFilter by mutableStateOf(ResultFilter.ALL)
        private set

    var filteredResults by mutableStateOf<List<CheckResult>>(emptyList())
        private set

    var availableSources by mutableStateOf<List<BookSource>>(emptyList())
        private set

    var selectedSourceUrls by mutableStateOf<Set<String>>(emptySet())
        private set

    private var selectedSources: List<BookSource> = emptyList()
    private val checkResultMap = mutableMapOf<String, CheckResult>()
    private var lastCheckedUrls: List<String> = emptyList()
    private var progressUpdateJob: Coroutine<Unit>? = null

    init {
        loadAvailableSources()
        loadResultsFromDatabase()
    }

    private fun loadAvailableSources() {
        execute {
            val sources = appDb.bookSourceDao.allEnabled
            withContext(Dispatchers.Main) {
                availableSources = sources
                if (selectedSourceUrls.isEmpty()) {
                    selectedSourceUrls = sources.map { it.bookSourceUrl }.toSet()
                } else {
                    val availableUrls = sources.map { it.bookSourceUrl }.toSet()
                    selectedSourceUrls = selectedSourceUrls.intersect(availableUrls)
                }
            }
        }
    }

    private fun loadResultsFromDatabase() {
        execute {
            try {
                AppLog.put("从数据库加载书源检测结果")
                val sources = appDb.bookSourceDao.allEnabled

                if (sources.isNotEmpty()) {
                    val loadedResults = mutableListOf<CheckResult>()
                    var hasResults = false

                    sources.forEach { source ->
                        val debugMessage = Debug.debugMessageMap[source.bookSourceUrl]
                        if (debugMessage != null) {
                            hasResults = true
                            val respondTime = Debug.getRespondTime(source.bookSourceUrl)

                            val result = CheckResult(
                                sourceUrl = source.bookSourceUrl,
                                sourceName = source.bookSourceName,
                                isSuccess = debugMessage.contains("成功"),
                                respondTime = respondTime,
                                errorMessage = if (debugMessage.contains("成功")) null else debugMessage,
                                errorType = parseErrorType(debugMessage)
                            )

                            checkResultMap[source.bookSourceUrl] = result
                            loadedResults.add(result)
                        }
                    }

                    if (hasResults) {
                        withContext(Dispatchers.Main) {
                            val results = loadedResults.sortedByDescending { it.checkTime }
                            val statistics = calculateStatistics(results)
                            _uiState.value = CheckSourceUIState.Completed(results, statistics)
                            applyFilter(results)
                            AppLog.put("从数据库加载了${loadedResults.size}个检测结果")
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.put("加载检测结果失败: ${e.message}")
            }
        }
    }

    fun startCheckEnabledSources() {
        execute {
            val sources = appDb.bookSourceDao.allEnabled
            withContext(Dispatchers.Main) {
                if (sources.isNotEmpty()) {
                    startCheck(sources)
                }
            }
        }
    }

    fun startCheckSelectedSources() {
        execute {
            val selectedUrls = selectedSourceUrls
            val sources = selectedUrls.mapNotNull { appDb.bookSourceDao.getBookSource(it) }
            withContext(Dispatchers.Main) {
                if (sources.isNotEmpty()) {
                    startCheck(sources)
                }
            }
        }
    }

    fun toggleSourceSelection(sourceUrl: String) {
        selectedSourceUrls = if (selectedSourceUrls.contains(sourceUrl)) {
            selectedSourceUrls - sourceUrl
        } else {
            selectedSourceUrls + sourceUrl
        }
    }

    fun selectAllSources() {
        selectedSourceUrls = availableSources.map { it.bookSourceUrl }.toSet()
    }

    fun clearSourceSelection() {
        selectedSourceUrls = emptySet()
    }

    fun startCheck(sources: List<BookSource>) {
        val currentState = _uiState.value
        if (currentState is CheckSourceUIState.Checking) {
            AppLog.put("书源检测已在进行中，忽略重复启动请求")
            return
        }

        AppLog.put("开始书源检测，共${sources.size}个书源")

        selectedSources = sources
        lastCheckedUrls = sources.map { it.bookSourceUrl }
        checkResultMap.clear()

        val initialProgress = CheckProgress(total = sources.size)
        val initialStatistics = CheckStatistics(totalCount = sources.size)

        _uiState.value = CheckSourceUIState.Checking(
            progress = initialProgress,
            currentMessage = "开始检测...",
            results = emptyList(),
            statistics = initialStatistics
        )

        val sourceUrls = sources.map { it.bookSourceUrl }
        CheckSource.start(getApplication(), sourceUrls)
        AppLog.put("已启动CheckSourceService服务，共${sources.size}个书源")

        startProgressUpdateTask()
    }

    fun stopCheck() {
        AppLog.put("停止书源检测")
        CheckSource.stop(getApplication())
        progressUpdateJob?.cancel()
        progressUpdateJob = null

        val currentState = _uiState.value
        val results = if (currentState is CheckSourceUIState.Checking) {
            currentState.results
        } else {
            emptyList()
        }
        val statistics = calculateStatistics(results)

        _uiState.value = CheckSourceUIState.Completed(results, statistics)
        applyFilter(results)
        AppLog.put("书源检测已停止，共检测${statistics.totalCount}个书源，成功${statistics.successCount}个")
    }

    fun pauseCheck() {
        AppLog.put("暂停书源检测")
        val currentState = _uiState.value
        if (currentState is CheckSourceUIState.Checking) {
            _uiState.value = CheckSourceUIState.Paused(
                progress = currentState.progress,
                results = currentState.results,
                statistics = currentState.statistics
            )
        }
    }

    fun resumeCheck() {
        AppLog.put("恢复书源检测")
        CheckSource.resume(getApplication())
        val currentState = _uiState.value
        if (currentState is CheckSourceUIState.Paused) {
            _uiState.value = CheckSourceUIState.Checking(
                progress = currentState.progress,
                currentMessage = "检测继续中...",
                results = currentState.results,
                statistics = currentState.statistics
            )
        }
    }

    fun reCheck() {
        if (lastCheckedUrls.isEmpty()) {
            AppLog.put("没有上次检测的书源列表，无法重新检测")
            return
        }

        execute {
            val sources = lastCheckedUrls.mapNotNull { url ->
                appDb.bookSourceDao.getBookSource(url)
            }

            if (sources.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    startCheck(sources)
                }
            } else {
                AppLog.put("无法找到上次检测的书源")
            }
        }
    }

    fun reCheck(sourceUrl: String) {
        execute {
            appDb.bookSourceDao.getBookSource(sourceUrl)?.let { source ->
                withContext(Dispatchers.Main) {
                    startCheck(listOf(source))
                }
            } ?: AppLog.put("无法找到要重新检测的书源: $sourceUrl")
        }
    }

    fun updateCheckMessage(message: String) {
        val currentState = _uiState.value
        if (currentState is CheckSourceUIState.Checking) {
            parseProgressMessage(message, currentState)
        }
    }

    fun onCheckResult(event: CheckSourceResultEvent) {
        val result = event.toCheckResult()
        checkResultMap[event.sourceUrl] = result

        val results = checkResultMap.values.sortedByDescending { it.checkTime }
        val statistics = calculateStatistics(results)

        when (val state = _uiState.value) {
            is CheckSourceUIState.Checking -> {
                val progress = state.progress.copy(
                    current = results.size.coerceAtMost(state.progress.total),
                    currentSourceName = event.sourceName
                )
                _uiState.value = CheckSourceUIState.Checking(
                    progress = progress,
                    currentMessage = event.message ?: "校验成功",
                    results = results,
                    statistics = statistics
                )
            }
            is CheckSourceUIState.Paused -> {
                _uiState.value = CheckSourceUIState.Paused(
                    progress = state.progress,
                    results = results,
                    statistics = statistics
                )
            }
            else -> {
                _uiState.value = CheckSourceUIState.Completed(results, statistics)
            }
        }

        applyFilter(results)
    }

    fun onCheckComplete() {
        AppLog.put("书源检测完成")
        progressUpdateJob?.cancel()
        progressUpdateJob = null

        val currentState = _uiState.value
        val results = checkResultMap.values.sortedByDescending { it.checkTime }
        val statistics = calculateStatistics(results)

        _uiState.value = CheckSourceUIState.Completed(results, statistics)
        applyFilter(results)
        AppLog.put("书源检测完成统计：总数${statistics.totalCount}，成功${statistics.successCount}，失败${statistics.failedCount}，成功率${statistics.successRateText}")
    }

    fun applyResultFilter(filter: ResultFilter) {
        resultFilter = filter
        val results = when (val state = _uiState.value) {
            is CheckSourceUIState.Idle -> state.results
            is CheckSourceUIState.Checking -> state.results
            is CheckSourceUIState.Paused -> state.results
            is CheckSourceUIState.Completed -> state.results
        }
        applyFilter(results)
    }

    fun clearResults() {
        AppLog.put("清空书源检测结果")
        progressUpdateJob?.cancel()
        progressUpdateJob = null
        checkResultMap.clear()
        lastCheckedUrls = emptyList()
        _uiState.value = CheckSourceUIState.Idle()
        filteredResults = emptyList()
    }

    private fun parseProgressMessage(message: String, currentState: CheckSourceUIState.Checking) {
        val regex = """(.+)\s+(\d+)/(\d+)""".toRegex()
        val match = regex.find(message)

        if (match != null) {
            val (sourceName, current, total) = match.destructured
            val newProgress = currentState.progress.copy(
                current = current.toIntOrNull() ?: currentState.progress.current,
                total = total.toIntOrNull() ?: currentState.progress.total,
                currentSourceName = sourceName.trim()
            )

            updateCurrentSourceResult(sourceName.trim())

            val results = checkResultMap.values.sortedByDescending { it.checkTime }
            val statistics = calculateStatistics(results)

            _uiState.value = CheckSourceUIState.Checking(
                progress = newProgress,
                currentMessage = message,
                results = results,
                statistics = statistics
            )
            applyFilter(results)
        }
    }

    private fun updateCurrentSourceResult(sourceName: String) {
        val source = selectedSources.find { it.bookSourceName == sourceName }
        if (source != null) {
            val debugMessage = Debug.debugMessageMap[source.bookSourceUrl]
            val respondTime = Debug.getRespondTime(source.bookSourceUrl)

            val result = CheckResult(
                sourceUrl = source.bookSourceUrl,
                sourceName = source.bookSourceName,
                isSuccess = debugMessage?.contains("成功") == true,
                respondTime = respondTime,
                errorMessage = if (debugMessage?.contains("成功") == true) null else debugMessage,
                errorType = parseErrorType(debugMessage)
            )

            checkResultMap[source.bookSourceUrl] = result
        }
    }

    private fun parseErrorType(message: String?): ErrorType {
        if (message == null) return ErrorType.NONE

        return when {
            message.contains("超时") -> ErrorType.TIMEOUT
            message.contains("网络") -> ErrorType.NETWORK_ERROR
            message.contains("解析") -> ErrorType.PARSE_ERROR
            message.contains("脚本") || message.contains("js") -> ErrorType.SCRIPT_ERROR
            message.contains("域名") -> ErrorType.DOMAIN_ERROR
            message.contains("搜索") -> ErrorType.SEARCH_ERROR
            message.contains("发现") -> ErrorType.DISCOVERY_ERROR
            message.contains("详情") -> ErrorType.INFO_ERROR
            message.contains("目录") -> ErrorType.TOC_ERROR
            message.contains("正文") -> ErrorType.CONTENT_ERROR
            else -> ErrorType.NONE
        }
    }

    private fun parseEventErrorType(errorType: String): ErrorType {
        return when (errorType) {
            "TIMEOUT" -> ErrorType.TIMEOUT
            "NETWORK_ERROR" -> ErrorType.NETWORK_ERROR
            "PARSE_ERROR" -> ErrorType.PARSE_ERROR
            "SCRIPT_ERROR" -> ErrorType.SCRIPT_ERROR
            "DOMAIN_ERROR" -> ErrorType.DOMAIN_ERROR
            "SEARCH_ERROR" -> ErrorType.SEARCH_ERROR
            "DISCOVERY_ERROR" -> ErrorType.DISCOVERY_ERROR
            "INFO_ERROR" -> ErrorType.INFO_ERROR
            "TOC_ERROR" -> ErrorType.TOC_ERROR
            "CONTENT_ERROR" -> ErrorType.CONTENT_ERROR
            else -> ErrorType.NONE
        }
    }

    private fun CheckSourceResultEvent.toCheckResult(): CheckResult {
        return CheckResult(
            sourceUrl = sourceUrl,
            sourceName = sourceName,
            isSuccess = isSuccess,
            respondTime = respondTime,
            errorMessage = if (isSuccess) null else message,
            errorType = parseEventErrorType(errorType),
            checkTime = checkTime
        )
    }

    private fun applyFilter(results: List<CheckResult>) {
        filteredResults = when (resultFilter) {
            ResultFilter.ALL -> results
            ResultFilter.SUCCESS -> results.filter { it.isSuccess }
            ResultFilter.FAILED -> results.filter { !it.isSuccess }
        }
    }

    private fun calculateStatistics(results: List<CheckResult>): CheckStatistics {
        val successCount = results.count { it.isSuccess }
        val failedCount = results.count { !it.isSuccess }
        val timeoutCount = results.count { it.errorType == ErrorType.TIMEOUT }
        val avgTime = if (results.isNotEmpty()) {
            results.map { it.respondTime }.average().toLong()
        } else {
            0
        }

        return CheckStatistics(
            totalCount = results.size,
            successCount = successCount,
            failedCount = failedCount,
            timeoutCount = timeoutCount,
            avgRespondTime = avgTime
        )
    }

    private fun startProgressUpdateTask() {
        progressUpdateJob?.cancel()
        progressUpdateJob = execute {
            while (isActive) {
                delay(300)

                val currentState = _uiState.value
                if (currentState !is CheckSourceUIState.Checking) {
                    break
                }

                selectedSources.forEach { source ->
                    val debugMessage = Debug.debugMessageMap[source.bookSourceUrl]
                    if (debugMessage != null && !checkResultMap.containsKey(source.bookSourceUrl)) {
                        val respondTime = Debug.getRespondTime(source.bookSourceUrl)

                        val result = CheckResult(
                            sourceUrl = source.bookSourceUrl,
                            sourceName = source.bookSourceName,
                            isSuccess = debugMessage.contains("成功"),
                            respondTime = respondTime,
                            errorMessage = if (debugMessage.contains("成功")) null else debugMessage,
                            errorType = parseErrorType(debugMessage)
                        )

                        checkResultMap[source.bookSourceUrl] = result
                    }
                }

                val results = checkResultMap.values.sortedByDescending { it.checkTime }
                val statistics = calculateStatistics(results)

                withContext(Dispatchers.Main) {
                    val state = _uiState.value
                    if (state is CheckSourceUIState.Checking) {
                        _uiState.value = CheckSourceUIState.Checking(
                            progress = state.progress,
                            currentMessage = state.currentMessage,
                            results = results,
                            statistics = statistics
                        )
                        applyFilter(results)
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }
}
