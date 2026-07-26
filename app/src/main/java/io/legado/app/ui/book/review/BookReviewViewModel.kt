package io.legado.app.ui.book.review

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookReviewRepository
import io.legado.app.model.BookReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookReviewViewModel(application: Application) : BaseViewModel(application) {

    // === 全量查询 ===

    /** 获取全部书评（在 IO 线程执行） */
    suspend fun getAll(): List<BookReview> = withContext(Dispatchers.IO) {
        BookReviewRepository.getAll()
    }

    // === 单书查询 ===

    /** 按书匹配书评 */
    suspend fun getByBook(book: Book): BookReview? = withContext(Dispatchers.IO) {
        BookReviewRepository.getByBook(book)
    }

    // === 写入 ===

    /** 保存（新增或更新） */
    suspend fun save(review: BookReview): BookReview = withContext(Dispatchers.IO) {
        BookReviewRepository.save(review)
    }

    // === 删除 ===

    /** 删除 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        BookReviewRepository.delete(id)
    }
}
