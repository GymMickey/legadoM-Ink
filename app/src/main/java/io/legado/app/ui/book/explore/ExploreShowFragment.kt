package io.legado.app.ui.book.explore

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.databinding.FragmentExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.domain.model.BookShelfState
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.EInkVisuals
import io.legado.app.lib.theme.EInkScreenPaginator
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.FrameLayout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.BookBottomSheet

/**
 * Fragment显示单个分类的书籍列表
 * 参考 RssArticlesFragment 的实现
 */
class ExploreShowFragment() : VMBaseFragment<ExploreShowFragmentViewModel>(R.layout.fragment_explore_show),
    ExploreShowAdapter.CallBack {

    constructor(exploreKindName: String, exploreUrl: String, sourceUrl: String) : this() {
        arguments = Bundle().apply {
            putString("exploreKindName", exploreKindName)
            putString("exploreUrl", exploreUrl)
            putString("sourceUrl", sourceUrl)
        }
    }

    private var isResumed = false
    private val binding by viewBinding(FragmentExploreShowBinding::bind)
    private val activityViewModel by activityViewModels<ExploreShowViewModel>()
    override val viewModel by viewModels<ExploreShowFragmentViewModel>()
    private val orientation by lazy { resources.configuration.orientation }

    private val adapter: ExploreShowAdapter by lazy {
        ExploreShowAdapter(requireContext(), this).apply {
            layoutMode = activityViewModel.layoutMode
            columnCount = activityViewModel.columnCount
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private val loadMoreViewTop: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var oldPage = -1
    private var isClearAll = false
    private var lastLoadTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var loadRetryScheduled = false
    private var fullRefresh = true  // 添加fullRefresh标记，参考订阅源实现
    private val einkScreenPaginator = EInkScreenPaginator<io.legado.app.data.entities.SearchBook>()
    private var einkPageReady = false
    private var einkPageLoading = false
    private var einkNextPageRequested = false
    private var einkExploreItems: List<io.legado.app.data.entities.SearchBook> = emptyList()
    private var einkMaxExploreItemHeight = 0
    private var einkExploreMeasureWidth = -1
    private var einkExploreMeasureHeight = -1
    private var einkExploreMeasureLayoutKey = ""
    private var einkExploreMeasureScheduled = false
    private var pendingEinkPage: Int? = null
    private var pendingEinkAnchor: String? = null

    /** 书籍底部弹窗状态 */
    private var showBookSheet by mutableStateOf(false)
    private var selectedBook by mutableStateOf<io.legado.app.data.entities.SearchBook?>(null)
    private var selectedBookShelfState by mutableStateOf(BookShelfState.NOT_IN_SHELF)
    private var bookSheetComposeView: ComposeView? = null

    /** 当前被屏蔽的书籍数量（内部使用） */
    private var _blockedCount by mutableIntStateOf(0)
    /** 公开的屏蔽书籍数量getter */
    fun getBlockedCount(): Int = _blockedCount
    /** 屏蔽进度悬浮芯片 ComposeView */
    private var blockProgressComposeView: ComposeView? = null

    val isGridLayout: Boolean
        get() = activityViewModel.layoutMode != ExploreShowActivity.LAYOUT_LIST

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments, activityViewModel.bookSource)
        pendingEinkPage = activityViewModel.screenPages[viewModel.stableExploreUrl]
        pendingEinkAnchor = activityViewModel.screenPageAnchors[viewModel.stableExploreUrl]
        initView()
        initData()
    }

    /**
     * 保存 RecyclerView 当前滚动位置到 Activity ViewModel，跨 Fragment 重建恢复
     */
    private fun saveScrollToViewModel() {
        if (AppConfig.isEInkMode) {
            saveEInkScreenState()
            return
        }
        val lm = binding.recyclerView.layoutManager ?: return
        val pos = when (lm) {
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            is StaggeredGridLayoutManager -> {
                val positions = IntArray(lm.spanCount)
                lm.findFirstVisibleItemPositions(positions)
                positions.minOrNull()?.coerceAtLeast(0) ?: 0
            }
            else -> return
        }
        if (pos > 0) {
            activityViewModel.scrollPositions[viewModel.stableExploreUrl] = pos
        }
    }

    private fun initView() = binding.run {
        recyclerView.setEdgeEffectColor(primaryColor)
        if (AppConfig.isEInkMode) {
            einkPagination.root.applyNavigationBarPadding()
        } else {
            recyclerView.applyNavigationBarPadding()
        }
        einkPagination.btnPrevious.setOnClickListener {
            if (einkScreenPaginator.previousPage()) {
                showEInkExplorePage(scrollToTop = true)
            }
        }
        einkPagination.btnNext.setOnClickListener {
            when {
                einkScreenPaginator.nextPage() -> showEInkExplorePage(scrollToTop = true)
                einkScreenPaginator.needsMore(loadMoreView.hasMore) &&
                        !einkPageLoading && !viewModel.isLoading -> {
                    einkPageLoading = true
                    einkNextPageRequested = true
                    loadMoreView.hasMore()
                    loadMoreView.startLoad()
                    updateEinkExplorePageBar()
                    viewModel.loadMore()
                }
            }
        }
        einkPagination.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (AppConfig.isEInkMode && einkPageReady) {
                updateEinkExploreRecyclerInset()
                scheduleEinkExplorePageMeasure()
            }
        }
        recyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (AppConfig.isEInkMode && einkPageReady) {
                updateEinkExploreRecyclerInset()
                scheduleEinkExplorePageMeasure()
            }
        }

        // 设置布局管理器
        applyLayoutManager()

        recyclerView.adapter = adapter
        EInkVisuals.applyScreen(binding.root)
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        adapter.addHeaderView {
            ViewLoadMoreBinding.bind(loadMoreViewTop)
        }

        if (AppConfig.isEInkMode) {
            loadMoreView.visibility = View.GONE
            loadMoreViewTop.visibility = View.GONE
        }

        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }

        // 初始状态下隐藏loadMoreViewTop（第一页不需要向上翻页）
        val topLayoutParams = loadMoreViewTop.layoutParams
            ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0)
        topLayoutParams.height = 0
        loadMoreViewTop.layoutParams = topLayoutParams

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (AppConfig.isEInkMode) return
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })

        // 预加载模式：立即开始加载
        if (activityViewModel.isPreload) {
            refreshLayout.post {
                viewModel.loadBooks()
            }
            return@run
        }

        // 非预加载模式：等待Fragment可见后加载
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.loadBooks()
                this@launch.cancel()
            }
        }
    }

    private fun initData() {
        viewModel.booksData.observe(viewLifecycleOwner) { books ->
            upData(books)
        }
        viewModel.addBooksData.observe(viewLifecycleOwner) { books ->
            upDataTop(books)
        }
        viewModel.errorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(viewLifecycleOwner) {
            loadMoreViewTop.error(it)
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            if (!hasMore) {
                loadMoreView.noMore()
            }
            if (AppConfig.isEInkMode) {
                einkPageLoading = false
                if (!hasMore) einkNextPageRequested = false
                updateEinkExplorePageBar()
            }
        }
        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            (activity as? ExploreShowActivity)?.updatePageMenu(page, showPageMenu())
        }
        activityViewModel.upAdapterLiveData.observe(viewLifecycleOwner) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, Bundle().apply { putString(it, null) })
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
    }

    override fun onPause() {
        saveScrollToViewModel()
        isResumed = false
        super.onPause()
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (AppConfig.isEInkMode) return
        if (viewModel.isLoading) return
        fullRefresh = false  // 滚动加载时设置为false，参考订阅源实现
        
        val now = SystemClock.elapsedRealtime()
        val currentCount = activityViewModel.columnCount
        if (activityViewModel.layoutMode != ExploreShowActivity.LAYOUT_LIST && currentCount > 3 && now - lastLoadTime < ExploreShowActivity.LOAD_COOLDOWN_MS) {
            scheduleLoadRetry(ExploreShowActivity.LOAD_COOLDOWN_MS - (now - lastLoadTime))
            return
        }
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadRetryScheduled = false
            lastLoadTime = now
            loadMoreView.hasMore()
            viewModel.loadMore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if (AppConfig.isEInkMode) return
        if (viewModel.isLoading) return
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.loadPrevious()
        }
    }

    private fun scheduleLoadRetry(delayMs: Long) {
        if (loadRetryScheduled) return
        loadRetryScheduled = true
        handler.postDelayed({
            loadRetryScheduled = false
            scrollToBottom()
        }, delayMs)
    }

    private fun saveEInkScreenState() {
        if (!einkPageReady || einkScreenPaginator.isEmpty) return
        activityViewModel.screenPages[viewModel.stableExploreUrl] = einkScreenPaginator.currentPage
        einkScreenPaginator.currentItems.firstOrNull()?.bookUrl?.let { anchor ->
            activityViewModel.screenPageAnchors[viewModel.stableExploreUrl] = anchor
        }
    }

    private fun resetEinkExplorePagination() {
        if (!AppConfig.isEInkMode) return
        einkPageReady = false
        einkPageLoading = false
        einkNextPageRequested = false
        einkExploreItems = emptyList()
        einkMaxExploreItemHeight = 0
        einkExploreMeasureWidth = -1
        einkExploreMeasureHeight = -1
        einkExploreMeasureLayoutKey = ""
        pendingEinkPage = null
        pendingEinkAnchor = null
        einkScreenPaginator.setItems(emptyList(), resetToFirst = true)
        adapter.setItems(emptyList())
        binding.einkPagination.root.visibility = View.GONE
        updateEinkExploreRecyclerInset()
    }

    private fun updateEinkExploreItems(books: List<io.legado.app.data.entities.SearchBook>) {
        einkExploreItems = books
        einkScreenPaginator.setItems(books)
        if (einkNextPageRequested && einkScreenPaginator.canNext) {
            einkScreenPaginator.nextPage()
            einkNextPageRequested = false
        }
        if (einkPageReady) {
            showEInkExplorePage()
        } else {
            // Keep the initial result visible until a real child height is available.
            adapter.setItems(books)
            scheduleEinkExplorePageMeasure()
        }
    }

    private fun scheduleEinkExplorePageMeasure() {
        if (einkExploreMeasureScheduled) return
        einkExploreMeasureScheduled = true
        binding.recyclerView.post {
            einkExploreMeasureScheduled = false
            recalculateEinkExplorePageSize()
        }
    }

    private fun recalculateEinkExplorePageSize() {
        if (!AppConfig.isEInkMode || binding.recyclerView.height <= 0) return
        val layoutManager = binding.recyclerView.layoutManager ?: return
        val layoutKey = "${activityViewModel.layoutMode}:" + when (layoutManager) {
            is GridLayoutManager -> "grid:${layoutManager.spanCount}"
            is StaggeredGridLayoutManager -> "waterfall:${layoutManager.spanCount}"
            else -> "list"
        }
        if (einkExploreMeasureWidth != binding.recyclerView.width ||
            einkExploreMeasureHeight != binding.recyclerView.height ||
            einkExploreMeasureLayoutKey != layoutKey
        ) {
            einkMaxExploreItemHeight = 0
            einkExploreMeasureWidth = binding.recyclerView.width
            einkExploreMeasureHeight = binding.recyclerView.height
            einkExploreMeasureLayoutKey = layoutKey
        }
        val itemHeight = (0 until binding.recyclerView.childCount)
            .mapNotNull { binding.recyclerView.getChildAt(it) }
            .map { layoutManager.getDecoratedMeasuredHeight(it) }
            .filter { it > 0 }
            .maxOrNull() ?: return
        einkMaxExploreItemHeight = maxOf(einkMaxExploreItemHeight, itemHeight)
        if (einkMaxExploreItemHeight <= 0) return
        val availableHeight = binding.recyclerView.height -
                binding.recyclerView.paddingTop - binding.recyclerView.paddingBottom
        if (availableHeight <= 0) return
        var rows = (availableHeight / einkMaxExploreItemHeight).coerceAtLeast(1)
        val columns = when (layoutManager) {
            is GridLayoutManager -> layoutManager.spanCount
            is StaggeredGridLayoutManager -> layoutManager.spanCount
            else -> 1
        }
        val wasPageReady = einkPageReady
        if (wasPageReady && binding.recyclerView.canScrollVertically(1)) {
            // Keep a complete visible page; for grids this removes one complete row.
            rows = (rows - 1).coerceAtLeast(1)
        }
        val capacity = (rows * columns).coerceAtLeast(1)
        val oldPageSize = einkScreenPaginator.itemsPerPage
        einkScreenPaginator.setItemsPerPage(capacity)
        if (pendingEinkAnchor != null || pendingEinkPage != null) {
            val anchorIndex = pendingEinkAnchor?.let { anchor ->
                einkExploreItems.indexOfFirst { it.bookUrl == anchor }
            } ?: -1
            if (anchorIndex >= 0) {
                einkScreenPaginator.goToItemIndex(anchorIndex)
            } else {
                einkScreenPaginator.goToPage(pendingEinkPage ?: 1)
            }
            pendingEinkAnchor = null
            pendingEinkPage = null
        }
        einkPageReady = true
        if (!wasPageReady || oldPageSize != capacity) {
            showEInkExplorePage(scrollToTop = oldPageSize != capacity)
        } else {
            updateEinkExplorePageBar()
        }
    }

    private fun showEInkExplorePage(scrollToTop: Boolean = false) {
        if (!AppConfig.isEInkMode || !einkPageReady) return
        adapter.setItems(einkScreenPaginator.currentItems)
        updateEinkExplorePageBar()
        if (scrollToTop && adapter.isNotEmpty()) {
            binding.recyclerView.scrollToPosition(0)
        }
        scheduleEinkExplorePageMeasure()
    }

    private fun updateEinkExplorePageBar() {
        val pageBar = binding.einkPagination
        val show = AppConfig.isEInkMode && einkPageReady && !einkScreenPaginator.isEmpty
        if (!show) {
            pageBar.root.visibility = View.GONE
            updateEinkExploreRecyclerInset()
            return
        }
        pageBar.root.visibility = View.VISIBLE
        pageBar.tvPageIndicator.text =
            "${einkScreenPaginator.currentPage} / ${einkScreenPaginator.totalPages}"
        val canInteract = !einkPageLoading && !viewModel.isLoading
        pageBar.btnPrevious.isEnabled = canInteract && einkScreenPaginator.canPrevious
        pageBar.btnNext.isEnabled = canInteract &&
                (einkScreenPaginator.canNext || einkScreenPaginator.needsMore(loadMoreView.hasMore))
        val enabledColor = android.graphics.Color.BLACK
        val disabledColor = android.graphics.Color.DKGRAY
        pageBar.btnPrevious.setTextColor(if (pageBar.btnPrevious.isEnabled) enabledColor else disabledColor)
        pageBar.btnNext.setTextColor(if (pageBar.btnNext.isEnabled) enabledColor else disabledColor)
        pageBar.tvPageIndicator.setTextColor(enabledColor)
        pageBar.root.post { updateEinkExploreRecyclerInset() }
    }

    private fun updateEinkExploreRecyclerInset() {
        if (!AppConfig.isEInkMode || !isAdded) return
        val pageBarHeight = if (binding.einkPagination.root.visibility == View.VISIBLE) {
            binding.einkPagination.root.height
        } else {
            0
        }
        binding.recyclerView.setPadding(
            binding.recyclerView.paddingLeft,
            binding.recyclerView.paddingTop,
            binding.recyclerView.paddingRight,
            pageBarHeight
        )
    }

    private fun upData(books: List<io.legado.app.data.entities.SearchBook>) {
        if (AppConfig.isEInkMode) {
            updateEinkExploreItems(books)
            loadMoreView.stopLoad()
            _blockedCount = viewModel.getBlockedCount()
            updateBlockProgressChip()
            (activity as? ExploreShowActivity)?.updateBlockedCount()
            return
        }
        // stopLoad() 延迟到数据更新之后调用，防止 RecyclerView 布局过程中的
        // 滚动回调触发 scrollToBottom() 时，isLoading 已被过早复位导致重复加载
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            val oldCount = adapter.getActualItemCount()
            // 使用fullRefresh标记，参考订阅源实现
            if (oldCount == 0 || fullRefresh) {
                adapter.setItems(books)
                fullRefresh = false  // 设置完成后重置标记
                // 恢复跨 Fragment 重建保存的滚动位置
                val savedPos = activityViewModel.scrollPositions.remove(viewModel.stableExploreUrl)
                if (savedPos != null && savedPos > 0) {
                    val lm = binding.recyclerView.layoutManager
                    when (lm) {
                        is LinearLayoutManager -> lm.scrollToPositionWithOffset(savedPos, 0)
                        is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(savedPos, 0)
                    }
                }
            } else if (oldCount > books.size) {
                // 屏蔽规则过滤后书籍数量减少，重置整个列表
                // 参考 RssArticlesFragment 的实现方式
                adapter.setItems(books)
            } else {
                // 有新增书籍，添加新项目
                val newItems = books.subList(oldCount, books.size)
                adapter.addItems(newItems)
            }
            if (isClearAll) {
                val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
                layoutManager?.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            }
        }
        // 数据更新完成后停止加载动画（noMore() 分支内部已调用 stopLoad()，此处对 else 分支确保 isLoading 复位）
        loadMoreView.stopLoad()
        // 更新屏蔽计数
        _blockedCount = viewModel.getBlockedCount()
        updateBlockProgressChip()
        (activity as? ExploreShowActivity)?.updateBlockedCount()
    }

    private fun upDataTop(books: List<io.legado.app.data.entities.SearchBook>) {
        // 先添加数据到列表顶部（此时 isLoading 仍为 true，防止 addItems() 触发的
        // RecyclerView 布局回调重复进入 scrollToTop() 导致加载图标卡死）
        adapter.addItems(0, books)

        // 滚动到合适的位置
        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
        if (layoutManager != null && layoutManager.findFirstVisibleItemPosition() <= 1) {
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }

        // 数据更新完成后停止加载动画
        loadMoreViewTop.stopLoad()

        // 如果已经是第一页，隐藏loadMoreViewTop
        if (oldPage <= 1) {
            val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
            loadMoreViewTop.noMore()  // 没有更多上一页数据
        }
        // 还有上一页数据时，hasMore会保持之前在scrollToTop()中通过hasMore()方法设置的true状态
    }

    fun getCurrentPage(): Int {
        return viewModel.pageLiveData.value ?: viewModel.page
    }

    fun showPageMenu(): Boolean {
        return true // 发现页总是支持页数跳转
    }

    fun showPagePicker() {
        val currentPage = getCurrentPage()
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.change_page))
            .setMinValue(1)
            .setMaxValue(999)
            .setValue(currentPage)
            .show { targetPage ->
                if (targetPage != currentPage) {
                    fullRefresh = true  // 页数跳转时设置为true，参考订阅源实现
                    resetEinkExplorePagination()
                    
                    // loadMoreViewTop已经在initView中添加为header，不需要重复添加
                    // 只需要根据目标页数控制其显示状态
                    if (targetPage != 1) {
                        val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
                        if (layoutParams != null && layoutParams.height == 0) {
                            layoutParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
                            loadMoreViewTop.layoutParams = layoutParams
                        }
                    } else {
                        // 如果跳转到第一页，隐藏loadMoreViewTop
                        val layoutParams = loadMoreViewTop.layoutParams as? FrameLayout.LayoutParams
                        if (layoutParams != null) {
                            layoutParams.height = 0
                            loadMoreViewTop.layoutParams = layoutParams
                        }
                    }
                    oldPage = targetPage
                    viewModel.skipPage(targetPage)
                    isClearAll = true
                    adapter.clearItems()
                    // 重置loadMoreView状态，防止自动触发loadMore导致页数错误
                    loadMoreView.hasMore()
                    loadMoreView.startLoad()
                    viewModel.loadBooks(targetPage)
                    binding.recyclerView.scrollToPosition(0)
                }
            }
    }

    override fun getBookShelfState(book: io.legado.app.data.entities.SearchBook): BookShelfState {
        return activityViewModel.getBookShelfState(book)
    }

    override fun showBookInfo(book: io.legado.app.data.entities.SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    override fun onBookLongClick(book: io.legado.app.data.entities.SearchBook) {
        selectedBook = book
        selectedBookShelfState = activityViewModel.getBookShelfState(book)
        showBookSheet = true
        updateBookSheetView()
    }

    /**
     * 更新书籍底部弹窗的显示状态
     */
    private fun updateBookSheetView() {
        val contentView = binding.refreshLayout
        if (showBookSheet) {
            if (bookSheetComposeView == null) {
                bookSheetComposeView = ComposeView(requireContext()).also { composeView ->
                    composeView.setContent {
                        LegadoTheme {
                            BookBottomSheet(
                                show = showBookSheet,
                                book = selectedBook,
                                shelfState = selectedBookShelfState,
                                onDismiss = {
                                    showBookSheet = false
                                    updateBookSheetView()
                                },
                                onAddToShelf = { book ->
                                    activityViewModel.addToShelf(book)
                                },
                                onShowInfo = { book ->
                                    startActivity<BookInfoActivity> {
                                        putExtra("name", book.name)
                                        putExtra("author", book.author)
                                        putExtra("bookUrl", book.bookUrl)
                                    }
                                }
                            )
                        }
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    contentView.addView(composeView, params)
                }
            }
        } else {
            bookSheetComposeView?.let {
                contentView.removeView(it)
                bookSheetComposeView = null
            }
        }
    }

    /**
     * 更新屏蔽进度悬浮芯片的显示状态
     */
    private fun updateBlockProgressChip() {
        val contentView = binding.refreshLayout
        if (activityViewModel.showBlockProgress && _blockedCount > 0) {
            if (blockProgressComposeView == null) {
                blockProgressComposeView = ComposeView(requireContext()).also { composeView ->
                    composeView.setContent {
                        LegadoTheme {
                            Surface(
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shadowElevation = if (AppConfig.isEInkMode) 0.dp else 4.dp,
                                border = if (AppConfig.isEInkMode) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                } else null,
                                onClick = {
                                    (activity as? ExploreShowActivity)?.showBlockRuleConfig()
                                }
                            ) {
                                Text(
                                    text = getString(R.string.explore_block_rule_progress_text, _blockedCount),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    }
                    contentView.addView(composeView, params)
                }
            }
        } else {
            blockProgressComposeView?.let {
                contentView.removeView(it)
                blockProgressComposeView = null
            }
        }
    }

    /**
     * 屏蔽规则变化后重新过滤当前书籍列表
     * 由 ExploreShowActivity 调用
     */
    fun applyBlockRules() {
        if (!isAdded) return
        viewModel.applyBlockRules()
    }

    /**
     * 获取当前书籍数量
     * 由 ExploreShowActivity 调用
     */
    fun getBooksCount(): Int = viewModel.getBooksCount()

    /**
     * 获取所有书籍列表（用于屏蔽规则配置）
     * 由 ExploreShowActivity 调用
     */
    fun getAllBooks(): List<io.legado.app.data.entities.SearchBook> = viewModel.getAllBooks()

    /**
     * 应用布局管理器
     */
    private fun applyLayoutManager() {
        // 检查Fragment生命周期，避免访问已销毁的binding
        if (!isAdded || view == null) return
        
        val count = activityViewModel.columnCount
        
        // 保存当前滚动位置，切换布局后恢复
        val savedPosition = if (AppConfig.isEInkMode) 0 else saveScrollPosition()
        
        // 清除旧的ItemDecoration和padding，避免重复添加
        while (binding.recyclerView.itemDecorationCount > 0) {
            binding.recyclerView.removeItemDecorationAt(0)
        }
        binding.recyclerView.setPadding(0, 0, 0, 0)
        
        binding.recyclerView.layoutManager = when {
            activityViewModel.layoutMode == ExploreShowActivity.LAYOUT_LIST || count <= 1 -> {
                binding.recyclerView.itemAnimator = if (AppConfig.isEInkMode) null
                    else androidx.recyclerview.widget.DefaultItemAnimator()
                binding.recyclerView.addItemDecoration(VerticalDivider(requireContext()))
                LinearLayoutManager(requireContext())
            }
            activityViewModel.layoutMode == ExploreShowActivity.LAYOUT_WATERFALL -> {
                binding.recyclerView.itemAnimator = null
                binding.recyclerView.setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                binding.recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        // 移除顶部边距，确保第一行书籍紧贴Tab
                        outRect.set(8, 0, 8, 12)
                    }
                })
                // 根据用户选择的列数设置，考虑横屏和竖屏
                val waterfallCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    (count + 1).coerceAtMost(5) // 横屏可以多一列，最多5列
                } else {
                    count.coerceAtLeast(2) // 竖屏至少2列
                }
                StaggeredGridLayoutManager(waterfallCount, StaggeredGridLayoutManager.VERTICAL)
            }
            else -> {
                binding.recyclerView.itemAnimator = if (AppConfig.isEInkMode) null
                    else androidx.recyclerview.widget.DefaultItemAnimator()
                binding.recyclerView.setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                // 确保 adapter.columnCount 与 GridLayoutManager spanCount 同步
                // 否则 getSpanSize() 中 header/footer 占用的列数可能小于实际列数
                adapter.columnCount = count
                GridLayoutManager(requireContext(), count).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int): Int {
                            return if (position < adapter.getHeaderCount()
                                || position >= adapter.getActualItemCount() + adapter.getHeaderCount()
                            ) {
                                count
                            } else {
                                1
                            }
                        }
                    }
                }
            }
        }
        
        // 恢复滚动位置（延迟到布局完成）
        if (savedPosition > 0) {
            binding.recyclerView.post {
                val lm = binding.recyclerView.layoutManager
                when (lm) {
                    is LinearLayoutManager -> lm.scrollToPositionWithOffset(savedPosition, 0)
                    is StaggeredGridLayoutManager -> lm.scrollToPositionWithOffset(savedPosition, 0)
                }
            }
        }
        if (AppConfig.isEInkMode) {
            binding.recyclerView.post {
                updateEinkExploreRecyclerInset()
                scheduleEinkExplorePageMeasure()
            }
        }
    }

    /**
     * 保存当前 RecyclerView 滚动位置
     * @return 第一个可见 item 的 adapter 位置，无数据时返回 0
     */
    private fun saveScrollPosition(): Int {
        val lm = binding.recyclerView.layoutManager ?: return 0
        return when (lm) {
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            is StaggeredGridLayoutManager -> {
                val positions = IntArray(lm.spanCount)
                lm.findFirstVisibleItemPositions(positions)
                positions.minOrNull()?.coerceAtLeast(0) ?: 0
            }
            else -> 0
        }
    }

    /**
     * 更新布局模式（由Activity调用）
     */
    fun updateLayoutMode(mode: Int, columnCount: Int) {
        // 检查Fragment生命周期，避免在Fragment未attached时执行
        if (!isAdded) return
        
        adapter.layoutMode = mode
        adapter.columnCount = columnCount
        applyLayoutManager()
    }
}
