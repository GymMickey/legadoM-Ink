package io.legado.app.ui.urlRecord

import io.legado.app.data.entities.UrlRecord

/**
 * URL记录界面UI状态
 * 
 * 使用密封类封装所有可能的UI状态，确保类型安全和状态完整性。
 * 遵循单向数据流架构，ViewModel通过StateFlow暴露不可变状态。
 */
sealed class UrlRecordUIState {
    
    /**
     * 加载中状态
     * 数据正在从数据库加载，显示加载动画
     */
    object Loading : UrlRecordUIState()
    
    /**
     * 成功状态
     * 数据加载成功，显示记录列表
     * @property records URL记录列表
     */
    data class Success(val records: List<UrlRecord>) : UrlRecordUIState()
    
    /**
     * 错误状态
     * 数据加载失败，显示错误信息
     * @property message 错误信息
     */
    data class Error(val message: String) : UrlRecordUIState()
    
    /**
     * 空数据状态
     * 数据库中没有记录，显示空数据提示
     */
    object Empty : UrlRecordUIState()
}
