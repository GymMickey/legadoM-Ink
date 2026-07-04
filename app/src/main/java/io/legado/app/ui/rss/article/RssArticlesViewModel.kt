package io.legado.app.ui.rss.article

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.rss.Rss
import io.legado.app.utils.stackTraceStr
import kotlinx.coroutines.Dispatchers.IO

class RssArticlesViewModel(application: Application) : BaseViewModel(application) {
    val loadFinallyLiveData = MutableLiveData<Boolean>()
    val loadErrorLiveData = MutableLiveData<String>()
    val pageLiveData = MutableLiveData<Int>()
    var isLoading = true
    var order = System.currentTimeMillis()
    private var nextPageUrl: String? = null
    private var initialSortUrl: String = ""
    var sortName: String = ""
    var sortUrl: String = ""
    var searchKey: String? = null
    var page = 1

    fun init(bundle: Bundle?) {
        bundle?.let {
            sortName = it.getString("sortName") ?: ""
            sortUrl = it.getString("sortUrl") ?: ""
            initialSortUrl = sortUrl
            searchKey = it.getString("searchKey")
        }
        pageLiveData.value = page
    }

    fun loadArticles(rssSource: RssSource, targetPage: Int = 1) {
        isLoading = true
        page = targetPage.coerceAtLeast(1)
        order = System.currentTimeMillis()
        nextPageUrl = null
        Rss.getArticles(viewModelScope, sortName, initialSortUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            val articles = it.first
            articles.forEach { rssArticle ->
                rssArticle.order = order--
            }
            appDb.rssArticleDao.insert(*articles.toTypedArray())
            if (!rssSource.ruleNextPage.isNullOrEmpty()) {
                appDb.rssArticleDao.clearOld(rssSource.sourceUrl, sortName, order)
            }
            val hasMore = articles.isNotEmpty() && !rssSource.ruleNextPage.isNullOrEmpty()
            pageLiveData.postValue(page)
            loadFinallyLiveData.postValue(hasMore)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    fun loadMore(rssSource: RssSource) {
        isLoading = true
        page++
        val pageUrl = nextPageUrl
        if (pageUrl.isNullOrEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        Rss.getArticles(viewModelScope, sortName, pageUrl, rssSource, page, searchKey).onSuccess(IO) {
            nextPageUrl = it.second
            pageLiveData.postValue(page)
            loadMoreSuccess(it.first)
            isLoading = false
        }.onError {
            loadFinallyLiveData.postValue(false)
            AppLog.put("rss获取内容失败", it)
            loadErrorLiveData.postValue(it.stackTraceStr)
        }
    }

    fun skipPage(targetPage: Int) {
        page = targetPage.coerceAtLeast(1)
        nextPageUrl = null
        pageLiveData.postValue(page)
    }

    private fun loadMoreSuccess(articles: MutableList<RssArticle>) {
        if (articles.isEmpty()) {
            loadFinallyLiveData.postValue(false)
            return
        }
        val firstArticle = articles.first()
        val dbFirstArticle = appDb.rssArticleDao.get(firstArticle.origin, firstArticle.link, firstArticle.sort)
        val lastArticle = articles.last()
        val dbLastArticle = appDb.rssArticleDao.get(lastArticle.origin, lastArticle.link, firstArticle.sort)
        if (dbFirstArticle != null && dbLastArticle != null) {
            loadFinallyLiveData.postValue(false)
        } else {
            articles.forEach {
                it.order = order--
            }
            appDb.rssArticleDao.append(*articles.toTypedArray())
        }
    }
}
