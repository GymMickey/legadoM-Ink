package io.legado.app.help.storage

/**
 * 备份 JSON 的读取状态。Missing 与 Invalid 必须和合法空数组区分开。
 */
internal sealed interface BackupJsonResult<out T> {
    data object Missing : BackupJsonResult<Nothing>
    data object Invalid : BackupJsonResult<Nothing>
    data class Valid<T>(val data: T) : BackupJsonResult<T>
}

internal object RestoreDataPlan {

    fun shouldReplace(result: BackupJsonResult<*>): Boolean =
        result is BackupJsonResult.Valid<*>

    /** 首页的两个集合必须一起验证成功，才允许进入同一个替换事务。 */
    fun shouldReplaceHomepage(
        modules: BackupJsonResult<*>,
        customSets: BackupJsonResult<*>
    ): Boolean = modules is BackupJsonResult.Valid<*> && customSets is BackupJsonResult.Valid<*>

    /**
     * 阅读记录会话文件允许在旧备份中缺失，但已存在的文件必须全部解析成功。
     */
    fun shouldApplyReadRecordBundle(
        results: Collection<BackupJsonResult<*>>
    ): Boolean {
        val present = results.filterNot { it is BackupJsonResult.Missing }
        return present.isNotEmpty() && present.all { it is BackupJsonResult.Valid<*> }
    }

    fun selectedReadRecordFiles(selectedFiles: Set<String>): Set<String> =
        selectedFiles.intersect(BackupFileMappingPolicy.readRecordFileNames)
}
