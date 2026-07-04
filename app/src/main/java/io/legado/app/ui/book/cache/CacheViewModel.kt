package io.legado.app.ui.book.cache

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.sendValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.collections.set


class CacheViewModel(application: Application) : BaseViewModel(application) {
    val upAdapterLiveData = MutableLiveData<String>()

    private var loadChapterCoroutine: Coroutine<Unit>? = null
    // 缓存每本书已缓存的章节URL集合
    val cacheChapters = hashMapOf<String, HashSet<String>>()
    // 缓存每本书的缓存文件大小
    val cacheSizes = hashMapOf<String, Long>()
    private val bookRepository = BookRepository()
    
    // 用于检测是否是相同的书籍列表，避免重复加载
    private var lastLoadedBooksKey: String? = null
    // 防止并发加载的标志
    private var isLoading = false

    /**
     * 加载书籍缓存文件信息
     * 优化点：
     * 1. 使用booksKey检测相同列表，避免重复计算
     * 2. 使用isLoading防止并发加载
     * 3. 每本书独立async并行：DB查询 + 文件扫描 + UI刷新，先完成的先显示
     * 4. 并行DB查询替代串行forEach
     */
    fun loadCacheFiles(books: List<Book>) {
        if (isLoading) return

        val booksKey = books.map { it.bookUrl }.sorted().joinToString(",")
        if (booksKey == lastLoadedBooksKey) return

        loadChapterCoroutine?.cancel()
        loadChapterCoroutine = execute {
            isLoading = true
            try {
                val newBooks = books.filter { !it.isLocal && !cacheChapters.contains(it.bookUrl) }
                if (newBooks.isEmpty()) {
                    lastLoadedBooksKey = booksKey
                    return@execute
                }

                // 每本书独立async并行处理，先完成的先刷新UI
                newBooks.map { book ->
                    async(Dispatchers.IO) {
                        try {
                            // 查询该书章节
                            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
                            // 扫描该书缓存文件
                            val cacheNames = BookHelp.getCacheFiles(setOf(book.getFolderName()))[book.getFolderName()]
                                ?: hashSetOf()
                            // 匹配已缓存章节
                            val chapterCaches = hashSetOf<String>()
                            if (cacheNames.isNotEmpty()) {
                                book.totalChapterNum = chapters.size
                                chapters.forEach { chapter ->
                                    if (cacheNames.contains(chapter.getFileName()) || chapter.isVolume) {
                                        chapterCaches.add(chapter.url)
                                    }
                                }
                            }
                            // 计算该书缓存文件夹大小
                            val cacheSize = File(BookHelp.cachePath, book.getFolderName())
                                .takeIf { it.exists() }
                                ?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                            Triple(book.bookUrl, chapterCaches, cacheSize)
                        } catch (e: Exception) {
                            Triple(book.bookUrl, hashSetOf<String>(), 0L)
                        }
                    }
                }.awaitAll().forEach { (bookUrl, chapterCaches, cacheSize) ->
                    cacheChapters[bookUrl] = chapterCaches
                    cacheSizes[bookUrl] = cacheSize
                    upAdapterLiveData.sendValue(bookUrl)
                }

                lastLoadedBooksKey = booksKey
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun getBookCover(bookName: String, bookAuthor: String): String? {
        return bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
    }

    /**
     * 清理单本书的缓存状态
     * 清理后重置lastLoadedBooksKey，确保下次重新计算
     */
    fun clearCache(bookUrl: String) {
        cacheChapters[bookUrl] = hashSetOf()
        cacheSizes[bookUrl] = 0L
        lastLoadedBooksKey = null
    }

    /**
     * 清理所有缓存状态
     * 清理后重置lastLoadedBooksKey，确保下次重新计算
     */
    fun clearAllCache() {
        cacheChapters.clear()
        cacheSizes.clear()
        lastLoadedBooksKey = null
    }

}