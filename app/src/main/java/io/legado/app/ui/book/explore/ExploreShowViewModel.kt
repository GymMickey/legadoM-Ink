package io.legado.app.ui.book.explore

import android.app.Application
import android.content.Intent
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookShelfState
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.source.exploreKinds
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * Activity级别的ViewModel，管理书源信息和共享数据
 * 参考 RssSortViewModel 的实现
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreShowViewModel(application: Application) : BaseViewModel(application) {

    val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val upAdapterLiveData = MutableLiveData<String>()
    val exploreKindsData = MutableLiveData<List<ExploreKind>>()
    val addAllToShelfResult = MutableLiveData<Int>()

    var bookSource: BookSource? = null
    var currentSourceUrl: String = ""

    /** 各分类的滚动位置缓存（key = exploreUrl, value = adapter position），跨 Fragment 重建恢复 */
    val scrollPositions = ConcurrentHashMap<String, Int>()

    /** 布局模式，按书源持久化 */
    var layoutMode: Int
        get() = appCtx.getPrefInt("${PreferKey.exploreGridMode}_${currentSourceUrl}", 0)
        set(value) = appCtx.putPrefInt("${PreferKey.exploreGridMode}_${currentSourceUrl}", value)

    /** 列数，按书源持久化 */
    var columnCount: Int
        get() = appCtx.getPrefInt("${PreferKey.exploreShowColumn}_${currentSourceUrl}", 2)
        set(value) = appCtx.putPrefInt("${PreferKey.exploreShowColumn}_${currentSourceUrl}", value)

    /** 是否显示分类Tab，按书源持久化 */
    var showCategoryTab: Boolean
        get() = appCtx.getPrefBoolean("${PreferKey.exploreShowCategoryTab}_${currentSourceUrl}", false)
        set(value) = appCtx.putPrefBoolean("${PreferKey.exploreShowCategoryTab}_${currentSourceUrl}", value)

    /** 预加载模式，按书源持久化 */
    var isPreload: Boolean
        get() = appCtx.getPrefInt("${PreferKey.exploreShowPreload}_${currentSourceUrl}", 0) == 1
        set(value) = appCtx.putPrefInt("${PreferKey.exploreShowPreload}_${currentSourceUrl}", if (value) 1 else 0)

    /** 是否显示屏蔽进度 */
    var showBlockProgress: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.blockRuleShowProgress, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.blockRuleShowProgress, value)

    // 实时监听数据库对比书名作者，判断书是否在书架上
    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }
                    .forEach {
                        keys.add("${it.name}-${it.author}")
                        keys.add(it.name)
                        keys.add(it.bookUrl)
                    }
                keys
            }.catch {
                AppLog.put("发现列表界面获取书籍数据失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
                upAdapterLiveData.postValue("isInBookshelf")
            }
        }.onError {
            AppLog.put("加载书架数据失败", it)
        }
    }

    /**
     * ViewModel初始化数据
     */
    fun initData(intent: Intent) {
        execute {
            val sourceUrl = intent.getStringExtra("sourceUrl")
            currentSourceUrl = sourceUrl ?: ""
            if (bookSource == null && sourceUrl != null) {
                bookSource = appDb.bookSourceDao.getBookSource(sourceUrl)
            }
            // 加载所有发现分类（用于Tab显示）
            loadExploreKinds()
        }
    }

    /**
     * 加载书源的所有发现分类
     */
    private suspend fun loadExploreKinds() {
        val source = bookSource
        if (source == null) {
            exploreKindsData.postValue(emptyList())
            return
        }
        withContext(IO) {
            kotlin.runCatching {
                source.exploreKinds().filter { !it.url.isNullOrBlank() }
            }.onSuccess { kinds ->
                exploreKindsData.postValue(kinds)
            }.onFailure {
                exploreKindsData.postValue(emptyList())
            }
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return bookshelf.contains(key) || bookshelf.contains(bookUrl)
    }

    fun getBookShelfState(book: SearchBook): BookShelfState {
        val name = book.name
        val author = book.author
        val bookUrl = book.bookUrl
        val key = if (author.isNotBlank()) "$name-$author" else name
        return when {
            bookshelf.contains(bookUrl) -> BookShelfState.IN_SHELF
            bookshelf.contains(key) -> BookShelfState.SAME_NAME_AUTHOR
            else -> BookShelfState.NOT_IN_SHELF
        }
    }

    fun addToShelf(book: SearchBook) {
        execute {
            val bookEntity = book.toBook()
            appDb.bookDao.insert(bookEntity)
            val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
            bookshelf.add(key)
            bookshelf.add(book.bookUrl)
            upAdapterLiveData.postValue("isInBookshelf")
        }.onError {
            AppLog.put("加入书架失败", it)
        }
    }

    fun addAllToShelf(groupId: Long) {
        execute {
            // 从当前可见的Fragment获取书籍列表
            // 这个操作由Activity调用，通过Fragment的ViewModel获取书籍
            // Activity会在调用前先获取当前Fragment的书籍数据
            addAllToShelfResult.postValue(0) // 暂时返回0，实际数量由Activity传递
        }
    }

    override fun onCleared() {
        super.onCleared()
        scrollPositions.clear()
        ExploreShowFragmentViewModel.clearDataCache()
    }
}