package io.legado.app.help.storage

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.io.File

/**
 * 书籍缓存选择配置
 * 
 * 用于存储用户选择要备份缓存的具体书籍
 */
object BookCacheSelectorConfig {

    private val configPath = FileUtils.getPath(appCtx.filesDir, "bookCacheSelector.json")

    private var selectedBookUrls: MutableSet<String> = load()

    private fun load(): MutableSet<String> {
        val set = HashSet<String>()
        val file = FileUtils.createFileIfNotExist(configPath)
        if (file.exists() && file.length() > 0) {
            val json = file.readText()
            GSON.fromJsonObject<Set<String>>(json).getOrNull()?.let {
                set.addAll(it)
            }
        }
        return set
    }

    /**
     * 获取所有有缓存的书籍
     */
    fun getBooksWithCache(): List<Book> {
        val folderNames = getCacheFolderNames()
        if (folderNames.isEmpty()) {
            return emptyList()
        }

        return appDb.bookDao.all.filter { book ->
            book.getFolderName() in folderNames
        }
    }

    fun hasBooksWithCache(): Boolean {
        return getCacheFolderNames().isNotEmpty()
    }

    private fun getCacheFolderNames(): Set<String> {
        val cacheDir = File(BookHelp.cachePath)
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return emptySet()
        }
        return cacheDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * 判断书籍是否被选中
     */
    fun isSelected(book: Book): Boolean {
        return selectedBookUrls.contains(book.bookUrl)
    }

    /**
     * 设置书籍选中状态
     */
    fun setSelected(book: Book, selected: Boolean) {
        if (selected) {
            selectedBookUrls.add(book.bookUrl)
        } else {
            selectedBookUrls.remove(book.bookUrl)
        }
    }

    /**
     * 全选
     */
    fun selectAll() {
        getBooksWithCache().forEach {
            selectedBookUrls.add(it.bookUrl)
        }
    }

    /**
     * 全不选
     */
    fun deselectAll() {
        selectedBookUrls.clear()
    }

    /**
     * 获取选中的书籍列表
     */
    fun getSelectedBooks(): List<Book> {
        val booksWithCache = getBooksWithCache()
        return booksWithCache.filter { isSelected(it) }
    }

    /**
     * 是否有选中的书籍
     */
    fun hasSelection(): Boolean {
        return selectedBookUrls.isNotEmpty()
    }

    /**
     * 是否全选
     */
    fun isAllSelected(): Boolean {
        val booksWithCache = getBooksWithCache()
        return booksWithCache.isNotEmpty() && booksWithCache.all { isSelected(it) }
    }

    /**
     * 是否全不选
     */
    fun isNoneSelected(): Boolean {
        if (selectedBookUrls.isEmpty()) {
            return true
        }
        return getBooksWithCache().none { isSelected(it) }
    }

    /**
     * 保存配置
     */
    fun save() {
        val json = GSON.toJson(selectedBookUrls.toSet())
        FileUtils.createFileIfNotExist(configPath).writeText(json)
    }
}
