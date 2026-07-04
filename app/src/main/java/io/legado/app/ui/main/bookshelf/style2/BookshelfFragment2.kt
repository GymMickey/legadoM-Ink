package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private var folderLayout = AppConfig.folderLayout
    private var bookLayout = AppConfig.bookLayout
    private var spanCount = 1
    private lateinit var booksAdapter: BaseBooksAdapter<*>
    private var spanSizeLookup: GridLayoutManager.SpanSizeLookup? = null
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private val bookshelfMargin by lazy { AppConfig.bookshelfMargin }
    private var itemCount = 0

    // 计算最小公倍数
    private fun lcm(a: Int, b: Int): Int {
        return a * b / gcd(a, b)
    }

    // 计算最大公约数
    private fun gcd(a: Int, b: Int): Int {
        return if (b == 0) a else gcd(b, a % b)
    }

    private fun createBooksAdapter(): BaseBooksAdapter<*> {
        return if (AppConfig.bookLayout >= 2) {
            BooksAdapterGrid(requireContext(), this)
        } else {
            BooksAdapterList(requireContext(), this)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        // 初始化适配器
        if (!this::booksAdapter.isInitialized) {
            booksAdapter = createBooksAdapter()
        }
        binding.rvBookshelf.setHasFixedSize(true)
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        upFastScrollerBar()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        // 让文件夹和书籍完全独立，互不影响
        // 使用最小公倍数作为spanCount，两者可以自由选择列数
        val bookSpan = if (bookLayout >= 2) bookLayout else 1
        val folderSpan = if (folderLayout >= 2) folderLayout else 1
        val useGrid = bookSpan > 1 || folderSpan > 1
        
        // 计算最小公倍数
        spanCount = if (useGrid) {
            lcm(bookSpan, folderSpan)
        } else {
            1
        }
        
        val layoutManager = if (useGrid) {
            GridLayoutManager(context, spanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (booksAdapter.getItemViewType(position) == 1) {
                            // 文件夹：folderLayout >= 2 时占 spanCount/folderSpan 列（显示为folderLayout列网格）
                            // folderLayout < 2 时占满一行（列表样式）
                            if (folderLayout >= 2) {
                                spanCount / folderSpan
                            } else {
                                spanCount // 占满一行（列表样式）
                            }
                        } else {
                            // 书籍：bookLayout >= 2 时占 spanCount/bookSpan 列（显示为bookLayout列网格）
                            // bookLayout < 2 时占满一行（列表样式）
                            if (bookLayout >= 2) {
                                spanCount / bookSpan
                            } else {
                                spanCount // 占满一行（列表样式）
                            }
                        }
                    }
                }
                this.spanSizeLookup.isSpanIndexCacheEnabled = true
                this@BookshelfFragment2.spanSizeLookup = this.spanSizeLookup
            }
        } else {
            LinearLayoutManager(context)
        }
        binding.rvBookshelf.layoutManager = layoutManager
        binding.rvBookshelf.adapter = booksAdapter
        /**
         * 采用 layoutManager?.onRestoreInstanceState(layoutState)
         * 恢复滚动位置
         * **/
        binding.rvBookshelf.itemAnimator = null
        // 清除旧的ItemDecoration，避免累积
        while (binding.rvBookshelf.itemDecorationCount > 0) {
            binding.rvBookshelf.removeItemDecorationAt(0)
        }
        binding.rvBookshelf.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val marginFirst = bookshelfMargin + 24
            private val marginNormal = bookshelfMargin
            
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                if (position == RecyclerView.NO_POSITION) return
                
                if (spanCount >= 2 && spanSizeLookup != null) {
                    // 使用spanSizeLookup获取正确的行号（组索引）
                    val rowIndex = spanSizeLookup!!.getSpanGroupIndex(position, spanCount)
                    val lastGroupIndex = if (itemCount > 0) {
                        spanSizeLookup!!.getSpanGroupIndex(itemCount - 1, spanCount)
                    } else 0
                    // 处理单行情况：既是第一行也是最后一行
                    if (rowIndex == 0 && rowIndex == lastGroupIndex) {
                        outRect.set(bookshelfMargin, marginFirst, bookshelfMargin, marginFirst)
                    } else when (rowIndex) {
                        0 -> outRect.set(bookshelfMargin, marginFirst, bookshelfMargin, bookshelfMargin)
                        lastGroupIndex -> outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, marginFirst)
                        else -> outRect.set(bookshelfMargin, bookshelfMargin, bookshelfMargin, bookshelfMargin)
                    }
                } else {
                    // 处理单行情况：既是第一行也是最后一行
                    if (position == 0 && position == itemCount - 1) {
                        outRect.set(0, marginFirst, 0, marginFirst)
                    } else when (position) {
                        0 -> outRect.set(0, marginFirst, 0, marginNormal)
                        itemCount - 1 -> outRect.set(0, marginNormal, 0, marginFirst)
                        else -> outRect.set(0, marginNormal, 0, marginNormal)
                    }
                }
            }
        })
    }

    private fun upFastScrollerBar() {
        val showFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showFastScroller)
        binding.rvBookshelf.isVerticalScrollBarEnabled = !showFastScroller
        if (!showFastScroller) {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            booksAdapter.updateItems(groupId)
            itemCount = getItemCount()
            binding.tvEmptyMsg.isGone = itemCount > 0
            binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.STARTED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                booksAdapter.updateItems(groupId)
                itemCount = getItemCount()
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
            }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            // 检查View是否存在，避免崩溃
            if (view != null && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                initBooksData()
            }
            return true
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            // 更新布局配置
            folderLayout = AppConfig.folderLayout
            bookLayout = AppConfig.bookLayout
            // 如果布局类型改变，重新创建适配器
            val newAdapter = createBooksAdapter()
            if (newAdapter::class != booksAdapter::class) {
                booksAdapter = newAdapter
                booksAdapter.updateItems(groupId)
            }
            // 重新初始化RecyclerView以应用新的布局
            initRecyclerView()
            booksAdapter.notifyDataSetChanged()
            upFastScrollerBar()
        }
    }
}