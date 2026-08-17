package io.legado.app.ui.font

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefStringSet
import java.io.File

internal object ImportedFontStore {

    fun primaryDirectory(context: Context): File {
        return File(context.applicationContext.externalFiles, "font")
    }

    fun directories(context: Context): List<File> {
        val appContext = context.applicationContext
        return buildList {
            add(primaryDirectory(appContext))
            add(File(appContext.filesDir, "font"))
            appContext.getExternalFilesDirs(null)
                .filterNotNull()
                .forEach { add(File(it, "font")) }
        }.distinctBy { normalizedPath(it) }
    }

    fun remember(context: Context, files: Collection<File>) {
        val managedFiles = files.filter { isInManagedDirectory(context, it) }
        if (managedFiles.isEmpty()) return
        val paths = storedPaths(context)
        var changed = false
        managedFiles.forEach { file ->
            if (file.isFile && paths.add(normalizedPath(file))) {
                changed = true
            }
        }
        if (changed) savePaths(context, paths)
    }

    fun files(context: Context): List<File> {
        val paths = storedPaths(context)
        val existingPaths = paths.filterTo(linkedSetOf()) { File(it).isFile }
        if (existingPaths != paths) savePaths(context, existingPaths)
        return existingPaths.map(::File)
    }

    fun isManaged(context: Context, file: File): Boolean {
        val path = normalizedPath(file)
        return path in storedPaths(context) || isInManagedDirectory(context, file)
    }

    fun delete(context: Context, file: File): Boolean {
        if (!isManaged(context, file)) return false
        val path = normalizedPath(file)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            val paths = storedPaths(context)
            if (paths.remove(path)) savePaths(context, paths)
        }
        return deleted
    }

    private fun isInManagedDirectory(context: Context, file: File): Boolean {
        val parentPath = file.parentFile?.let(::normalizedPath) ?: return false
        return directories(context).any { normalizedPath(it) == parentPath }
    }

    private fun storedPaths(context: Context): LinkedHashSet<String> {
        return context.applicationContext
            .getPrefStringSet(PreferKey.importedFontFiles, mutableSetOf())
            ?.toCollection(linkedSetOf())
            ?: linkedSetOf()
    }

    private fun savePaths(context: Context, paths: Set<String>) {
        context.applicationContext.putPrefStringSet(
            PreferKey.importedFontFiles,
            paths.toMutableSet()
        )
    }

    private fun normalizedPath(file: File): String {
        return kotlin.runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }
}
