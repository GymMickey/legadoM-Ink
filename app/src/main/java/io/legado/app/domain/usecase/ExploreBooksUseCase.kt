package io.legado.app.domain.usecase

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExploreBooksUseCase {

    companion object {
        /** 排名类模块自动加载的最大书本数 */
        const val MAX_RANKING_BOOKS = 20

        /** 排名类模块自动加载的最大页数 */
        const val MAX_RANKING_PAGES = 3
    }

    suspend fun execute(
        sourceUrl: String,
        moduleUrl: String?,
        args: String?,
        page: Int = 1,
        key: String? = null,
    ): ExploreResult = withContext(Dispatchers.IO) {
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: throw SourceNotFound(sourceUrl)
        val url = resolveUrl(source, moduleUrl, key)
        val books = WebBook.exploreBookAwait(source, url, page)
        ExploreResult(url, books)
    }

    suspend fun executeForRanking(
        sourceUrl: String,
        moduleUrl: String?,
        args: String?
    ): List<SearchBook> = withContext(Dispatchers.IO) {
        val source = appDb.bookSourceDao.getBookSource(sourceUrl)
            ?: throw SourceNotFound(sourceUrl)
        val url = resolveUrl(source, moduleUrl, null)
        var books: List<SearchBook> = WebBook.exploreBookAwait(source, url, 1)
        var page = 1
        while (books.size < MAX_RANKING_BOOKS && page < MAX_RANKING_PAGES) {
            page++
            val next = try {
                WebBook.exploreBookAwait(source, url, page)
            } catch (_: Exception) {
                emptyList()
            }
            if (next.isEmpty()) break
            books = (books + next)
        }
        books.take(MAX_RANKING_BOOKS)
    }

    private fun resolveUrl(
        source: BookSource,
        moduleUrl: String?,
        key: String?,
    ): String {
        return moduleUrl
            ?: source.exploreUrl
            ?: throw NoExploreUrl(source.bookSourceUrl)
    }

    data class ExploreResult(val resolvedUrl: String, val books: List<SearchBook>)

    class SourceNotFound(url: String) : Exception("Source not found: ${url.take(60)}")
    class NoExploreUrl(url: String) : Exception("No explore URL for source: ${url.take(60)}")
}
