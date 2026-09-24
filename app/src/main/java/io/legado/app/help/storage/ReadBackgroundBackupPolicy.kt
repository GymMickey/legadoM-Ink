package io.legado.app.help.storage

import java.io.File
import java.security.MessageDigest

/**
 * 阅读背景备份的纯文件名策略。
 * 不访问 Android 环境，只负责稳定命名和恢复时的安全匹配。
 */
internal object ReadBackgroundBackupPolicy {

    const val backupDirectoryName = "bg"
    const val readConfigFileName = "readConfig.json"
    const val shareConfigFileName = "shareReadConfig.json"

    fun selectedReadConfigNames(selectedFiles: Collection<String>): Set<String> = buildSet {
        if (readConfigFileName in selectedFiles) add(readConfigFileName)
        if (shareConfigFileName in selectedFiles || "readShareConfig.json" in selectedFiles) {
            add(shareConfigFileName)
        }
    }

    fun assignBackupNames(sourcePaths: Collection<String>): Map<String, String> {
        val normalizedPaths = sourcePaths
            .map { File(it).absolutePath }
            .distinct()
            .sorted()
        val result = linkedMapOf<String, String>()
        val usedNames = hashSetOf<String>()
        normalizedPaths
            .groupBy { safeFileName(File(it).name) }
            .toSortedMap()
            .forEach { (baseName, paths) ->
                if (paths.size == 1) {
                    val path = paths.single()
                    result[path] = uniqueName(baseName, usedNames)
                } else {
                    paths.forEach { path ->
                        result[path] = uniqueName(withPathHash(baseName, path), usedNames)
                    }
                }
            }
        return result
    }

    fun resolveRestoredName(
        requestedName: String,
        restoredNames: Set<String>,
        existingNames: Set<String>
    ): String? {
        val name = safeFileName(File(requestedName).name)
        return when {
            name in restoredNames -> name
            name in existingNames -> name
            else -> null
        }
    }

    fun fallbackColor(backgroundIndex: Int): String = when (backgroundIndex) {
        1 -> "#000000"
        2 -> "#FFFFFF"
        else -> "#EEEEEE"
    }

    private fun safeFileName(fileName: String): String =
        fileName.ifBlank { "background-image" }
            .replace('\\', '_')
            .replace('/', '_')

    private fun withPathHash(fileName: String, sourcePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourcePath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)
        val extension = fileName.substringAfterLast('.', "")
        val stem = fileName.substringBeforeLast('.', fileName)
        return if (extension.isBlank()) {
            "$stem-$digest"
        } else {
            "$stem-$digest.$extension"
        }
    }

    private fun uniqueName(candidate: String, usedNames: MutableSet<String>): String {
        if (usedNames.add(candidate)) return candidate
        val extension = candidate.substringAfterLast('.', "")
        val stem = candidate.substringBeforeLast('.', candidate)
        var index = 2
        while (true) {
            val name = if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension"
            if (usedNames.add(name)) return name
            index++
        }
    }
}
