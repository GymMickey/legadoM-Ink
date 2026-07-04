package io.legado.app.ui.upload

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.DirectLinkUploadRule
import io.legado.app.data.entities.UploadHistory
import io.legado.app.data.entities.UploadHistoryWithRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.model.upload.DirectLinkUploadRepository
import io.legado.app.model.upload.UploadStats
import io.legado.app.utils.GSON
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DirectLinkUploadViewModel(application: Application) : BaseViewModel(application) {

    private val repository = DirectLinkUploadRepository()

    val rules = repository.getRules()
    val histories = repository.getHistoriesWithRule()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedRule = MutableStateFlow<DirectLinkUploadRule?>(null)
    val selectedRule: StateFlow<DirectLinkUploadRule?> = _selectedRule.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    private val _stats = MutableStateFlow(UploadStats(0, 0, 0, 0))
    val stats: StateFlow<UploadStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            repository.migrateFromOldConfig()
            repository.importDefaultRules()
            loadStats()
        }
    }

    fun addRule(rule: DirectLinkUploadRule) {
        execute {
            repository.addRule(rule)
        }.onError {
            _uiState.value = UiState.Error("添加规则失败: ${it.localizedMessage}")
        }
    }

    fun updateRule(rule: DirectLinkUploadRule) {
        execute {
            repository.updateRule(rule)
        }.onError {
            _uiState.value = UiState.Error("更新规则失败: ${it.localizedMessage}")
        }
    }

    fun deleteRule(rule: DirectLinkUploadRule) {
        execute {
            repository.deleteRule(rule)
        }.onError {
            _uiState.value = UiState.Error("删除规则失败: ${it.localizedMessage}")
        }
    }

    fun selectRule(rule: DirectLinkUploadRule) {
        _selectedRule.value = rule
    }

    fun setDefaultRule(ruleId: Long) {
        execute {
            repository.setDefaultRule(ruleId)
        }.onError {
            _uiState.value = UiState.Error("设置默认规则失败: ${it.localizedMessage}")
        }
    }

    fun uploadFile(
        fileName: String,
        file: Any,
        contentType: String,
        rule: DirectLinkUploadRule? = null
    ) {
        execute {
            _uploadState.value = UploadState.Uploading(0)
            
            val uploadRule = rule ?: repository.getDefaultRule()
                ?: throw IllegalStateException("没有可用的上传规则")

            val startTime = System.currentTimeMillis()
            
            try {
                val downloadUrl = DirectLinkUpload.upLoad(
                    fileName = fileName,
                    file = file,
                    contentType = contentType,
                    rule = DirectLinkUpload.Rule(
                        uploadUrl = uploadRule.uploadUrl,
                        downloadUrlRule = uploadRule.downloadUrlRule,
                        summary = uploadRule.summary,
                        compress = uploadRule.compress
                    )
                )

                val duration = System.currentTimeMillis() - startTime
                
                val history = UploadHistory(
                    fileName = fileName,
                    fileSize = getFileSize(file),
                    contentType = contentType,
                    duration = duration,
                    downloadUrl = downloadUrl,
                    ruleId = uploadRule.id,
                    ruleSummary = uploadRule.summary,
                    success = true
                )
                
                repository.addHistory(history)
                repository.incrementUploadCount(uploadRule.id)
                
                _uploadState.value = UploadState.Success(downloadUrl)
                loadStats()
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                
                val history = UploadHistory(
                    fileName = fileName,
                    fileSize = getFileSize(file),
                    contentType = contentType,
                    duration = duration,
                    downloadUrl = "",
                    ruleId = uploadRule.id,
                    ruleSummary = uploadRule.summary,
                    success = false,
                    errorMsg = e.localizedMessage
                )
                
                repository.addHistory(history)
                
                _uploadState.value = UploadState.Error(e.localizedMessage ?: "上传失败")
            }
        }.onError {
            _uploadState.value = UploadState.Error(it.localizedMessage ?: "上传失败")
        }
    }

    fun testRule(rule: DirectLinkUploadRule) {
        execute {
            _uploadState.value = UploadState.Testing
            
            val result = DirectLinkUpload.upLoad(
                fileName = "test.json",
                file = "{}",
                contentType = "application/json",
                rule = DirectLinkUpload.Rule(
                    uploadUrl = rule.uploadUrl,
                    downloadUrlRule = rule.downloadUrlRule,
                    summary = rule.summary,
                    compress = rule.compress
                )
            )
            
            _uploadState.value = UploadState.TestSuccess(result)
        }.onError {
            _uploadState.value = UploadState.TestError(it.localizedMessage ?: "测试失败")
        }
    }

    fun deleteHistory(history: UploadHistory) {
        execute {
            repository.deleteHistory(history)
            loadStats()
        }.onError {
            _uiState.value = UiState.Error("删除历史记录失败: ${it.localizedMessage}")
        }
    }

    fun clearAllHistories() {
        execute {
            val count = repository.clearAllHistories()
            loadStats()
            _uiState.value = UiState.Success("已清除 $count 条历史记录")
        }.onError {
            _uiState.value = UiState.Error("清除历史记录失败: ${it.localizedMessage}")
        }
    }

    fun deleteOldHistories(days: Int) {
        execute {
            val count = repository.deleteOldHistories(days)
            loadStats()
            _uiState.value = UiState.Success("已清除 $count 条历史记录")
        }.onError {
            _uiState.value = UiState.Error("清除历史记录失败: ${it.localizedMessage}")
        }
    }

    fun importDefaultRules() {
        execute {
            repository.importDefaultRules()
            _uiState.value = UiState.Success("导入默认规则成功")
        }.onError {
            _uiState.value = UiState.Error("导入默认规则失败: ${it.localizedMessage}")
        }
    }

    /**
     * 拷贝规则到剪贴板
     * 将规则转换为JSON格式并复制到系统剪贴板
     * 
     * @param rule 要拷贝的规则
     * @return JSON字符串
     */
    fun copyRule(rule: DirectLinkUploadRule): String {
        return GSON.toJson(rule)
    }

    /**
     * 从剪贴板粘贴规则
     * 解析JSON格式的规则并添加到数据库
     * 
     * @param json JSON格式的规则字符串
     * @return 是否粘贴成功
     */
    fun pasteRule(json: String): Boolean {
        return try {
            val rule = GSON.fromJson(json, DirectLinkUploadRule::class.java)
            // 重置ID和管理字段
            val newRule = rule.copy(
                id = 0,
                isDefault = false,
                uploadCount = 0,
                lastUsedTime = 0,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis()
            )
            addRule(newRule)
            true
        } catch (e: Exception) {
            _uiState.value = UiState.Error("粘贴规则失败: ${e.localizedMessage}")
            false
        }
    }

    fun resetUploadState() {
        _uploadState.value = UploadState.Idle
    }

    private suspend fun loadStats() {
        _stats.value = repository.getUploadStats()
    }

    private fun getFileSize(file: Any): Long {
        return when (file) {
            is java.io.File -> file.length()
            is ByteArray -> file.size.toLong()
            is String -> file.toByteArray().size.toLong()
            else -> 0L
        }
    }

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Success(val message: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    sealed class UploadState {
        object Idle : UploadState()
        object Testing : UploadState()
        data class TestSuccess(val downloadUrl: String) : UploadState()
        data class TestError(val message: String) : UploadState()
        data class Uploading(val progress: Int) : UploadState()
        data class Success(val downloadUrl: String) : UploadState()
        data class Error(val message: String) : UploadState()
    }
}
