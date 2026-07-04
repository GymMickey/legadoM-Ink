package io.legado.app.ui.book.cacheSelector

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BookCacheItem(
    val book: Book,
    val cacheSize: Long,
    val formattedSize: String,
    val isSelected: Boolean
)

data class ChapterCacheInfo(
    val index: Int,
    val title: String,
    val titleMD5: String,
    val fileName: String
)

data class BookCacheIndex(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val folderName: String,
    val chapters: List<ChapterCacheInfo> = emptyList()
)

sealed class BookCacheSelectorUiState {
    object Loading : BookCacheSelectorUiState()
    data class Exporting(val message: String) : BookCacheSelectorUiState()
    object Idle : BookCacheSelectorUiState()
    data class Error(val message: String) : BookCacheSelectorUiState()
}

class BookCacheSelectorViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow<BookCacheSelectorUiState>(BookCacheSelectorUiState.Loading)
    val uiState: StateFlow<BookCacheSelectorUiState> = _uiState.asStateFlow()

    private val _bookItems = MutableStateFlow<List<BookCacheItem>>(emptyList())
    val bookItems: StateFlow<List<BookCacheItem>> = _bookItems.asStateFlow()

    private val _selectedCount = MutableStateFlow(0)
    val selectedCount: StateFlow<Int> = _selectedCount.asStateFlow()

    private val _totalSelectedSize = MutableStateFlow(0L)
    val totalSelectedSize: StateFlow<Long> = _totalSelectedSize.asStateFlow()

    init {
        loadBooks()
    }

    fun loadBooks() {
        execute {
            _uiState.value = BookCacheSelectorUiState.Loading
            try {
                val books = withContext(Dispatchers.IO) {
                    BookCacheSelectorConfig.getBooksWithCache()
                }

                val items = books.map { book ->
                    async(Dispatchers.IO) {
                        val size = calculateBookCacheSize(book)
                        BookCacheItem(
                            book = book,
                            cacheSize = size,
                            formattedSize = ConvertUtils.formatFileSize(size),
                            isSelected = BookCacheSelectorConfig.isSelected(book)
                        )
                    }
                }.awaitAll()

                _bookItems.value = items
                updateSelectionSummary()
                _uiState.value = BookCacheSelectorUiState.Idle
            } catch (e: Exception) {
                _uiState.value = BookCacheSelectorUiState.Idle
            }
        }
    }

    fun toggleSelect(book: Book) {
        val current = _bookItems.value.toMutableList()
        val index = current.indexOfFirst { it.book.bookUrl == book.bookUrl }
        if (index == -1) return

        val item = current[index]
        val newSelected = !item.isSelected
        current[index] = item.copy(isSelected = newSelected)
        BookCacheSelectorConfig.setSelected(book, newSelected)
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun selectAll() {
        val current = _bookItems.value.map { item ->
            BookCacheSelectorConfig.setSelected(item.book, true)
            item.copy(isSelected = true)
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun selectBooks(books: List<Book>) {
        val bookUrls = books.map { it.bookUrl }.toHashSet()
        if (bookUrls.isEmpty()) return
        val current = _bookItems.value.map { item ->
            if (item.book.bookUrl in bookUrls) {
                BookCacheSelectorConfig.setSelected(item.book, true)
                item.copy(isSelected = true)
            } else {
                item
            }
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun deselectAll() {
        val current = _bookItems.value.map { item ->
            BookCacheSelectorConfig.setSelected(item.book, false)
            item.copy(isSelected = false)
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun deselectBooks(books: List<Book>) {
        val bookUrls = books.map { it.bookUrl }.toHashSet()
        if (bookUrls.isEmpty()) return
        val current = _bookItems.value.map { item ->
            if (item.book.bookUrl in bookUrls) {
                BookCacheSelectorConfig.setSelected(item.book, false)
                item.copy(isSelected = false)
            } else {
                item
            }
        }
        _bookItems.value = current
        updateSelectionSummary()
    }

    fun isAllSelected(): Boolean {
        return _bookItems.value.isNotEmpty() && _bookItems.value.all { it.isSelected }
    }

    fun isAllSelected(items: List<BookCacheItem>): Boolean {
        return items.isNotEmpty() && items.all { it.isSelected }
    }

    fun saveSelection() {
        BookCacheSelectorConfig.save()
    }

    fun exportSelectedBooks(context: Context, targetUri: Uri) {
        execute {
            _uiState.value = BookCacheSelectorUiState.Exporting("准备导出...")

            val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
            if (selectedBooks.isEmpty()) {
                _uiState.value = BookCacheSelectorUiState.Idle
                return@execute
            }

            val cacheDir = File(BookHelp.cachePath)
            if (!cacheDir.exists()) {
                _uiState.value = BookCacheSelectorUiState.Idle
                return@execute
            }

            val timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            )
            val zipName = "书籍缓存${timestamp}.zip"

            // 临时目录：先拷贝缓存文件 + 索引，再打包
            val tempDir = File(appCtx.cacheDir, "book_cache_export")
                .createFolderIfNotExist()

            try {
                val bookCacheIndexList = mutableListOf<BookCacheIndex>()
                val bookCacheBooks = mutableListOf<Book>()
                val allChapters = mutableListOf<BookChapter>()
                val zipSources = mutableListOf<File>()
                // book_cache 子目录
                val tempCacheDir = File(tempDir, "book_cache").createFolderIfNotExist()

                selectedBooks.forEachIndexed { index, book ->
                    _uiState.value = BookCacheSelectorUiState.Exporting(
                        "导出中 (${index + 1}/${selectedBooks.size}): ${book.name}"
                    )

                    try {
                        val folderName = book.getFolderName()
                        val bookFolder = File(cacheDir, folderName)
                        if (!bookFolder.exists() || !bookFolder.isDirectory) return@forEachIndexed

                        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
                        val chapterMap = chapterList.associateBy { it.index }
                        allChapters.addAll(chapterList)

                        val chapterCacheInfos = mutableListOf<ChapterCacheInfo>()
                        bookFolder.listFiles()?.forEach { file ->
                            if (file.isFile && file.name.endsWith(".nb")) {
                                parseChapterFileName(file.name, chapterMap)?.let {
                                    chapterCacheInfos.add(it)
                                }
                            }
                        }

                        bookCacheIndexList.add(
                            BookCacheIndex(
                                bookUrl = book.bookUrl,
                                bookName = book.name,
                                author = book.author ?: "",
                                folderName = folderName,
                                chapters = chapterCacheInfos.sortedBy { it.index }
                            )
                        )

                        // 拷贝到临时目录
                        val targetBookDir = File(tempCacheDir, folderName)
                            .createFolderIfNotExist()
                        bookFolder.copyRecursively(targetBookDir, overwrite = true)
                        bookCacheBooks.add(book)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // 写索引文件到临时目录
                if (tempCacheDir.exists()) {
                    zipSources.add(tempCacheDir)
                }

                if (bookCacheIndexList.isNotEmpty()) {
                    val indexFile = File(tempDir, "bookCacheIndex.json")
                    indexFile.writeText(GSON.toJson(bookCacheIndexList))
                    zipSources.add(indexFile)
                    val booksFile = File(tempDir, "bookCacheBooks.json")
                    booksFile.writeText(GSON.toJson(bookCacheBooks))
                    zipSources.add(booksFile)
                }
                if (allChapters.isNotEmpty()) {
                    val chapterFile = File(tempDir, "bookChapterCache.json")
                    chapterFile.writeText(GSON.toJson(allChapters))
                    zipSources.add(chapterFile)
                }

                // 打包为 zip
                _uiState.value = BookCacheSelectorUiState.Exporting("正在打包...")
                val tempZip = File(tempDir, zipName)
                ZipUtils.zipFiles(zipSources, tempZip)

                // 写入目标位置
                _uiState.value = BookCacheSelectorUiState.Exporting("正在写入文件...")
                writeZipToTarget(context, targetUri, tempZip, zipName)
            } finally {
                tempDir.deleteRecursively()
            }

            _uiState.value = BookCacheSelectorUiState.Idle
        }
    }

    private fun writeZipToTarget(
        context: Context, targetUri: Uri, zipFile: File, zipName: String
    ) {
        if (targetUri.isContentScheme()) {
            val targetDir = DocumentFile.fromTreeUri(context, targetUri)!!
            val docFile = targetDir.findFile(zipName)
                ?: targetDir.createFile("application/zip", zipName)
                ?: return
            context.contentResolver.openOutputStream(docFile.uri)!!.use { output ->
                zipFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        } else {
            zipFile.copyTo(File(targetUri.path!!, zipName), overwrite = true)
        }
    }

    private fun parseChapterFileName(
        fileName: String,
        chapterMap: Map<Int, BookChapter>
    ): ChapterCacheInfo? {
        if (!fileName.endsWith(".nb")) return null

        val nameWithoutExt = fileName.removeSuffix(".nb")
        val parts = nameWithoutExt.split("-")
        if (parts.size != 2) return null

        val index = parts[0].toIntOrNull() ?: return null
        val titleMD5 = parts[1]
        val chapter = chapterMap[index] ?: return null

        return ChapterCacheInfo(
            index = index,
            title = chapter.title,
            titleMD5 = titleMD5,
            fileName = fileName
        )
    }

    private fun updateSelectionSummary() {
        val items = _bookItems.value
        val selected = items.filter { it.isSelected }
        _selectedCount.value = selected.size
        _totalSelectedSize.value = selected.sumOf { it.cacheSize }
    }

    private fun calculateBookCacheSize(book: Book): Long {
        val cacheDir = File(BookHelp.cachePath, book.getFolderName())
        return calculateDirSize(cacheDir)
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        try {
            val stack = ArrayDeque<File>()
            stack.addLast(dir)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                current.listFiles()?.forEach { file: File ->
                    if (file.isFile) {
                        size += file.length()
                    } else if (file.isDirectory) {
                        stack.addLast(file)
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return size
    }
}
