package io.legado.app.model

data class CheckSourceResultEvent(
    val sourceUrl: String,
    val sourceName: String,
    val isSuccess: Boolean,
    val respondTime: Long,
    val message: String?,
    val errorType: String,
    val checkTime: Long = System.currentTimeMillis()
)
