package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.removeType

class AddToBookshelfUseCase {

    suspend fun execute(book: SearchBook) {
        val b = book.toBook()
        b.removeType(BookType.notShelf)
        if (b.order == 0) {
            b.order = appDb.bookDao.minOrder - 1
        }
        appDb.bookDao.insert(b)
    }
}
