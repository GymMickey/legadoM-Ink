package io.legado.app.domain.usecase

import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook

class SaveSearchBooksUseCase {

    suspend fun save(book: SearchBook) = save(listOf(book))

    suspend fun save(books: List<SearchBook>) {
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
    }
}
