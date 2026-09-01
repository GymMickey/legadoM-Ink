/*
 * Copyright (C) 2020 w568w
 */
package io.legado.app.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson
import io.legado.app.api.controller.BookController
import io.legado.app.api.controller.BookSourceController
import io.legado.app.api.controller.RssSourceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Export book data to other app.
 */
class ReaderProvider : ContentProvider() {
    private enum class RequestCode {
        SaveBookSource, SaveBookSources, DeleteBookSources, GetBookSource, GetBookSources,
        SaveRssSource, SaveRssSources, DeleteRssSources, GetRssSource, GetRssSources,
        SaveBook, GetBookshelf, RefreshToc, GetChapterList, GetBookContent, GetBookCover,
    }

    private val postBodyKey = "json"
    private val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskRunner = ProviderTaskRunner(providerScope)
    private val sMatcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            providerAuthority?.also { authority ->
                addURI(authority, "bookSource/insert", RequestCode.SaveBookSource.ordinal)
                addURI(authority, "bookSources/insert", RequestCode.SaveBookSources.ordinal)
                addURI(authority, "bookSources/delete", RequestCode.DeleteBookSources.ordinal)
                addURI(authority, "bookSource/query", RequestCode.GetBookSource.ordinal)
                addURI(authority, "bookSources/query", RequestCode.GetBookSources.ordinal)
                addURI(authority, "rssSource/insert", RequestCode.SaveRssSource.ordinal)
                addURI(authority, "rssSources/insert", RequestCode.SaveRssSources.ordinal)
                addURI(authority, "rssSources/delete", RequestCode.DeleteRssSources.ordinal)
                addURI(authority, "rssSource/query", RequestCode.GetRssSource.ordinal)
                addURI(authority, "rssSources/query", RequestCode.GetRssSources.ordinal)
                addURI(authority, "book/insert", RequestCode.SaveBook.ordinal)
                addURI(authority, "books/query", RequestCode.GetBookshelf.ordinal)
                addURI(authority, "book/refreshToc/query", RequestCode.RefreshToc.ordinal)
                addURI(authority, "book/chapter/query", RequestCode.GetChapterList.ordinal)
                addURI(authority, "book/content/query", RequestCode.GetBookContent.ordinal)
                addURI(authority, "book/cover/query", RequestCode.GetBookCover.ordinal)
            }
        }
    }

    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        ShortCuts.buildShortCuts(providerContext)
        return true
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val requestCode = match(uri) ?: return 0
        if (selectionArgs?.isNotEmpty() == true) return 0
        if (selection.isNullOrBlank() || selection.length > MAX_BODY_LENGTH) return 0
        return try {
            val result: ReturnData = executeOnIo(operationTimeout(requestCode)) {
                when (requestCode) {
                    RequestCode.DeleteBookSources -> BookSourceController.deleteSources(selection)
                    RequestCode.DeleteRssSources -> RssSourceController.deleteSources(selection)
                    else -> throw IllegalArgumentException("不支持的删除 URI")
                }
            }
            if (result.isSuccess) 1 else 0
        } catch (_: ProviderTaskRunner.TimeoutException) {
            0
        } catch (_: Exception) {
            0
        }
    }

    override fun getType(uri: Uri): String? = if (match(uri) != null) {
        "application/json"
    } else {
        null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val requestCode = match(uri) ?: return null
        if (values?.keySet()?.any { it != postBodyKey } == true) return null
        val postData = values?.getAsString(postBodyKey)
            ?.takeIf { it.isNotBlank() && it.length <= MAX_BODY_LENGTH }
            ?: return null
        return try {
            executeOnIo(operationTimeout(requestCode)) {
                when (requestCode) {
                    RequestCode.SaveBookSource -> BookSourceController.saveSource(postData)
                    RequestCode.SaveBookSources -> BookSourceController.saveSources(postData)
                    RequestCode.SaveRssSource -> RssSourceController.saveSource(postData)
                    RequestCode.SaveRssSources -> RssSourceController.saveSources(postData)
                    RequestCode.SaveBook -> BookController.saveBook(postData)
                    else -> return@executeOnIo null
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val requestCode = match(uri) ?: return null
        if (!selection.isNullOrBlank() || selectionArgs?.isNotEmpty() == true || !sortOrder.isNullOrBlank()) {
            return SimpleCursor(errorData("不支持的查询参数"))
        }
        val validationError = validateQuery(uri, requestCode)
        if (validationError != null) return SimpleCursor(errorData(validationError))
        val parameters = queryParameters(uri)
        return try {
            val data = executeOnIo(operationTimeout(requestCode)) {
                when (requestCode) {
                    RequestCode.GetBookSource -> BookSourceController.getSource(parameters)
                    RequestCode.GetBookSources -> BookSourceController.sources
                    RequestCode.GetRssSource -> RssSourceController.getSource(parameters)
                    RequestCode.GetRssSources -> RssSourceController.sources
                    RequestCode.GetBookshelf -> BookController.bookshelf
                    RequestCode.GetBookContent -> BookController.getBookContent(parameters)
                    RequestCode.RefreshToc -> BookController.refreshToc(parameters)
                    RequestCode.GetChapterList -> BookController.getChapterList(parameters)
                    RequestCode.GetBookCover -> BookController.getCover(parameters)
                    else -> errorData("不支持的查询 URI")
                }
            }
            SimpleCursor(data)
        } catch (_: ProviderTaskRunner.TimeoutException) {
            SimpleCursor(errorData("请求超时"))
        } catch (_: Exception) {
            SimpleCursor(errorData("请求处理失败"))
        }
    }

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    private val providerAuthority: String?
        get() = context?.applicationInfo?.packageName?.let { "$it.readerProvider" }

    private fun match(uri: Uri): RequestCode? {
        if (uri.scheme != "content" || uri.authority != providerAuthority || uri.fragment != null) {
            return null
        }
        return RequestCode.entries.getOrNull(sMatcher.match(uri))
    }

    private fun validateQuery(uri: Uri, requestCode: RequestCode): String? {
        val allowed = when (requestCode) {
            RequestCode.GetBookSource, RequestCode.GetRssSource,
            RequestCode.GetChapterList, RequestCode.RefreshToc -> setOf("url")
            RequestCode.GetBookContent -> setOf("url", "index")
            RequestCode.GetBookCover -> setOf("path")
            RequestCode.GetBookSources, RequestCode.GetRssSources,
            RequestCode.GetBookshelf -> emptySet()
            else -> return "不支持的查询 URI"
        }
        if (uri.queryParameterNames.any { it !in allowed }) return "存在不支持的查询参数"
        for (name in allowed) {
            val values = uri.getQueryParameters(name)
            if (values.size > 1 || values.any { it.isBlank() || it.length > MAX_QUERY_VALUE_LENGTH }) {
                return "查询参数无效"
            }
        }
        if (requestCode in setOf(
                RequestCode.GetBookSource,
                RequestCode.GetRssSource,
                RequestCode.GetChapterList,
                RequestCode.RefreshToc
            ) && uri.getQueryParameter("url").isNullOrBlank()
        ) return "参数url不能为空"
        if (requestCode == RequestCode.GetBookContent) {
            val index = uri.getQueryParameter("index")?.toIntOrNull()
            if (uri.getQueryParameter("url").isNullOrBlank() || index == null || index < 0) {
                return "参数url或index无效"
            }
        }
        if (requestCode == RequestCode.GetBookCover && uri.getQueryParameter("path").isNullOrBlank()) {
            return "参数path不能为空"
        }
        return null
    }

    private fun queryParameters(uri: Uri): Map<String, List<String>> = buildMap {
        uri.getQueryParameter("url")?.let { put("url", listOf(it)) }
        uri.getQueryParameter("index")?.let { put("index", listOf(it)) }
        uri.getQueryParameter("path")?.let { put("path", listOf(it)) }
    }

    private fun errorData(message: String): ReturnData = ReturnData().setErrorMsg(message)

    private fun operationTimeout(requestCode: RequestCode): Long = when (requestCode) {
        RequestCode.RefreshToc,
        RequestCode.GetChapterList,
        RequestCode.GetBookContent,
        RequestCode.GetBookCover -> NETWORK_OPERATION_TIMEOUT_MS

        else -> LOCAL_OPERATION_TIMEOUT_MS
    }

    /**
     * ContentProvider methods are synchronous. The caller still waits for the result, but
     * controller work (including suspend/network work) is dispatched to IO. This does not make
     * the Binder call asynchronous: the caller still waits up to the operation timeout.
     */
    private fun <T> executeOnIo(timeoutMs: Long, block: suspend () -> T): T =
        taskRunner.execute(timeoutMs, block)

    override fun shutdown() {
        taskRunner.shutdown()
        super.shutdown()
    }

    private companion object {
        const val MAX_BODY_LENGTH = 1024 * 1024
        const val MAX_QUERY_VALUE_LENGTH = 8192
        const val LOCAL_OPERATION_TIMEOUT_MS = 10_000L
        const val NETWORK_OPERATION_TIMEOUT_MS = 30_000L
    }


    /**
     * Simple inner class to deliver json callback data.
     *
     * Only getString() makes sense.
     */
    private class SimpleCursor(data: ReturnData?) : MatrixCursor(arrayOf("result"), 1) {

        private val mData: String = Gson().toJson(data)

        init {
            addRow(arrayOf(mData))
        }

    }
}