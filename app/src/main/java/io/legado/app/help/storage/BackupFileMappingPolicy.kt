package io.legado.app.help.storage

/**
 * 备份选择器与实际文件之间的稳定映射。
 * 这里只处理逻辑项目和文件名，不执行文件 IO 或数据库操作。
 */
internal object BackupFileMappingPolicy {

    const val legacyShareConfigFileName = "readShareConfig.json"
    const val shareConfigFileName = "shareReadConfig.json"

    val readRecordFileNames = linkedSetOf(
        "readRecord.json",
        "readRecordDetail.json",
        "readRecordSession.json"
    )

    val bookCacheFileNames = linkedSetOf(
        "book_cache",
        "bookCacheIndex.json",
        "bookCacheBooks.json",
        "bookChapterCache.json"
    )

    fun canonicalFileName(fileName: String): String = when (fileName) {
        legacyShareConfigFileName -> shareConfigFileName
        else -> fileName
    }

    /**
     * 将选择器的逻辑项目展开为实际文件集合。
     * 单独传入旧备份文件名时不强行扩大选择范围，避免旧的单文件选择误清其他数据。
     */
    fun expandLogicalSelection(selectedFiles: Collection<String>): Set<String> {
        val canonical = selectedFiles.mapTo(linkedSetOf(), ::canonicalFileName)
        if ("readRecord" in canonical) {
            canonical.remove("readRecord")
            canonical.addAll(readRecordFileNames)
        }
        if ("bookCache" in canonical) {
            canonical.remove("bookCache")
            canonical.addAll(bookCacheFileNames)
        }
        return canonical
    }
}
