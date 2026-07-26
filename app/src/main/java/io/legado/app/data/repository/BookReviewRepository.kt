package io.legado.app.data.repository

import io.legado.app.data.entities.Book
import io.legado.app.model.BookReview
import io.legado.app.model.BookReviewData
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx

object BookReviewRepository {

    private const val FILE_NAME = "bookReview.json"
    private val filePath = FileUtils.getPath(appCtx.filesDir, FILE_NAME)
    private val mutex = Mutex()

    // === 内部 I/O ===

    /** 从文件读取全部数据，文件不存在返回空 BookReviewData */
    private fun load(): BookReviewData {
        val file = FileUtils.createFileIfNotExist(filePath)
        if (file.length() == 0L) return BookReviewData(version = 1, reviews = emptyList())
        val json = file.readText()
        if (json.isBlank()) return BookReviewData(version = 1, reviews = emptyList())
        return GSON.fromJsonObject<BookReviewData>(json).getOrNull()
            ?: BookReviewData(version = 1, reviews = emptyList())
    }

    /** 写入全部数据到文件 */
    private fun saveAll(data: BookReviewData) {
        val json = GSON.toJson(data)
        FileUtils.createFileIfNotExist(filePath).writeText(json)
    }

    // === CRUD ===

    /** 获取全部书评 */
    suspend fun getAll(): List<BookReview> = mutex.withLock {
        load().reviews
    }

    /** 按 ID 查询 */
    suspend fun getById(id: String): BookReview? = mutex.withLock {
        load().reviews.find { it.id == id }
    }

    /**
     * 按 Book 匹配书评（一书一评）
     *
     * 1. bookUrl 精确匹配
     * 2. name + author fallback（author 非空才参与，避免误匹配）
     */
    suspend fun getByBook(book: Book): BookReview? = mutex.withLock {
        val reviews = load().reviews
        reviews.find { it.bookUrl == book.bookUrl }
            ?: reviews.find {
                it.bookName == book.name
                && it.bookAuthor.isNotBlank()
                && it.bookAuthor == book.author
            }
    }

    /**
     * 保存书评
     *
     * - id 已存在 → 更新（覆盖 bookUrl/bookName/bookAuthor/content/rating/updateTime）
     * - id 不存在 → 新增
     */
    suspend fun save(review: BookReview): BookReview = mutex.withLock {
        val data = load()
        val reviews = data.reviews.toMutableList()
        val existingIndex = reviews.indexOfFirst { it.id == review.id }
        if (existingIndex >= 0) {
            reviews[existingIndex] = review
        } else {
            reviews.add(review)
        }
        saveAll(data.copy(reviews = reviews))
        review
    }

    /** 硬删除 */
    suspend fun delete(id: String) = mutex.withLock {
        val data = load()
        val filtered = data.reviews.filter { it.id != id }
        if (filtered.size != data.reviews.size) {
            saveAll(data.copy(reviews = filtered))
        }
    }
}
