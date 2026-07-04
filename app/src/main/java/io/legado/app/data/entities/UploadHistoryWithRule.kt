package io.legado.app.data.entities

data class UploadHistoryWithRule(
    val id: Long,
    val fileName: String,
    val fileSize: Long,
    val contentType: String,
    val uploadTime: Long,
    val duration: Long,
    val downloadUrl: String,
    val expireTime: Long?,
    val ruleId: Long,
    val ruleSummary: String?,
    val success: Boolean,
    val errorMsg: String?
) {
    fun toUploadHistory(): UploadHistory {
        return UploadHistory(
            id = id,
            fileName = fileName,
            fileSize = fileSize,
            contentType = contentType,
            uploadTime = uploadTime,
            duration = duration,
            downloadUrl = downloadUrl,
            expireTime = expireTime,
            ruleId = ruleId,
            ruleSummary = ruleSummary ?: "",
            success = success,
            errorMsg = errorMsg
        )
    }
    
    fun getDisplayRuleSummary(): String {
        val summary = ruleSummary ?: ""
        return if (summary.length > 6) {
            summary.take(6) + "…"
        } else {
            summary
        }
    }
}
