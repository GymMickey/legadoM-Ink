package io.legado.app.data.repository

import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.model.BookCover
import io.legado.app.utils.getPrefString
import splitties.init.appCtx

class BookRepository {

    suspend fun getChapterTitle(bookName: String, bookAuthor: String, chapterIndex: Int): String? {
        val book = appDb.bookDao.getBook(bookName, bookAuthor) ?: return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        return chapter.title
    }

    suspend fun getBookCoverByNameAndAuthor(bookName: String, bookAuthor: String): String? {
        val book = appDb.bookDao.getBook(bookName, bookAuthor)
        if (book == null) {
            return getConfiguredDefaultCover()
        }
        BookCover.getGalleryDefaultCover(book.bookUrl)?.let { return it }
        book.getDisplayCover()?.takeIf { it.isNotBlank() }?.let { return it }
        val coverUrl = runCatching {
            BookCover.searchCover(book)
        }.getOrNull()
        if (coverUrl.isNullOrBlank()) {
            return getConfiguredDefaultCover()
        }
        book.customCoverUrl = coverUrl
        book.save()
        return book.getDisplayCover()?.takeIf { it.isNotBlank() } ?: getConfiguredDefaultCover()
    }

    suspend fun getBookDurChapterTitle(bookName: String, bookAuthor: String): String? {
        val book = appDb.bookDao.getBook(bookName, bookAuthor) ?: return null
        return book.durChapterTitle
    }

    suspend fun getAuthorByBookName(bookName: String): String? {
        val book = appDb.bookDao.getBookByName(bookName) ?: return null
        return book.author.ifBlank { null }
    }

    fun getConfiguredDefaultCover(): String? {
        CoverGalleryRepository().getDefaultCoverPath()?.let {
            return it
        }
        val preferenceKey = if (AppConfig.isNightTheme) {
            PreferKey.defaultCoverDark
        } else {
            PreferKey.defaultCover
        }
        return appCtx.getPrefString(preferenceKey)?.takeIf { it.isNotBlank() }
    }
}
