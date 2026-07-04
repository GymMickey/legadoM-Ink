package io.legado.app.ui.rss.article

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssArticle
import io.legado.app.databinding.FragmentRssArticlesBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.rss.read.ReadRss
import io.legado.app.model.blockrule.BlockRuleStore
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class RssArticlesFragment() : VMBaseFragment<RssArticlesViewModel>(R.layout.fragment_rss_articles),
    BaseRssArticlesAdapter.CallBack {

    constructor(sortName: String, sortUrl: String, searchKey: String?) : this() {
        arguments = Bundle().apply {
            putString("sortName", sortName)
            putString("sortUrl", sortUrl)
            putString("searchKey", searchKey)
        }
    }

    private var isResumed = false

    private val binding by viewBinding(FragmentRssArticlesBinding::bind)
    private val activityViewModel by activityViewModels<RssSortViewModel>()
    override val viewModel by viewModels<RssArticlesViewModel>()
    private val isPreload by lazy { activityViewModel.rssSource?.preload ?: false }
    private val orientation by lazy { resources.configuration.orientation }
    private val adapter: BaseRssArticlesAdapter<*> by lazy {
        when (activityViewModel.articleStyle) {
            1 -> RssArticlesAdapter1(requireContext(), this@RssArticlesFragment)
            2 -> RssArticlesAdapter2(requireContext(), this@RssArticlesFragment)
            4 -> RssArticlesAdapter4(requireContext(), this@RssArticlesFragment)
            3 -> RssArticlesAdapter3(requireContext(), this@RssArticlesFragment)
            else -> RssArticlesAdapter(requireContext(), this@RssArticlesFragment)
        }
    }
    private val loadMoreView: LoadMoreView by lazy {
        LoadMoreView(requireContext())
    }
    private var articlesFlowJob: Job? = null
    /** 原始未过滤的文章列表，用于屏蔽规则变化时重新过滤 */
    var rawArticles: List<RssArticle> = emptyList()
        private set
    /** 当前被屏蔽的文章数量 */
    private var blockedCount: Int = 0
    override val isGridLayout: Boolean
        get() = activityViewModel.articleStyle == 2
    private var fullRefresh = true

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.init(arguments)
        initView()
        initData()
    }

    private fun initView() = binding.run {
        refreshLayout.setColorSchemeColors(accentColor)
        recyclerView.setEdgeEffectColor(primaryColor)
        recyclerView.applyNavigationBarPadding()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        val layoutManager = when (activityViewModel.articleStyle) {
            3 -> {
                recyclerView.setPadding(20, 0, 20, 0)
                recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                    ) {
                        outRect.set(20, 30, 20, 30)
                    }
                })
                recyclerView.itemAnimator = null
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL)
                } else {
                    StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
                }
            }

            2 -> {
                recyclerView.setPadding(8, 0, 8, 0)
                GridLayoutManager(requireContext(), 2)
            }

            4 -> {
                recyclerView.setPadding(4, 0, 4, 0)
                GridLayoutManager(requireContext(), 3)
            }

            else -> {
                recyclerView.addItemDecoration(VerticalDivider(requireContext()))
                LinearLayoutManager(requireContext())
            }
        }
        recyclerView.layoutManager = layoutManager
        recyclerView.adapter = adapter
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        refreshLayout.setOnRefreshListener {
            loadArticles()
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                    return
                }
                if (layoutManager is StaggeredGridLayoutManager) {
                    val visibleItemCount = layoutManager.childCount
                    val totalItemCount = layoutManager.itemCount
                    val firstVisibleItemPositions = layoutManager.findFirstVisibleItemPositions(null)
                    val firstVisibleItemPosition = firstVisibleItemPositions?.minOrNull() ?: 0
                    if (isPreload && (visibleItemCount + firstVisibleItemPosition) >= (totalItemCount - 5)) {
                        scrollToBottom()
                    }
                }
            }
        })
        if (isPreload) {
            refreshLayout.post {
                refreshLayout.isRefreshing = true
                loadArticles()
            }
            return@run
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                refreshLayout.isRefreshing = true
                loadArticles()
                this@launch.cancel()
            }
        }
    }

    private fun initData() {
        val rssUrl = activityViewModel.url ?: return
        articlesFlowJob?.cancel()
        articlesFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.rssArticleDao.flowByOriginSort(rssUrl, viewModel.sortName)
                .catch {
                    AppLog.put("订阅文章界面获取数据失败\n${it.localizedMessage}", it)
                }.flowOn(IO).collect { newList ->
                    rawArticles = newList
                    val filtered = applyBlockRulesToList(newList)
                    blockedCount = newList.size - filtered.size
                    (activity as? RssSortActivity)?.updateBlockedCount()
                    if (!isResumed || fullRefresh || filtered.isEmpty()) {
                        adapter.setItems(filtered)
                    } else {
                        adapter.setItems(filtered, object : DiffUtil.ItemCallback<RssArticle>() {
                            override fun areItemsTheSame(oldItem: RssArticle, newItem: RssArticle): Boolean {
                                return oldItem.link == newItem.link
                            }

                            override fun areContentsTheSame(oldItem: RssArticle, newItem: RssArticle): Boolean {
                                return oldItem.title == newItem.title &&
                                    oldItem.image == newItem.image &&
                                    oldItem.read == newItem.read
                            }

                            override fun getChangePayload(oldItem: RssArticle, newItem: RssArticle): Any? {
                                return if (oldItem.read != newItem.read) {
                                    "read"
                                } else if (oldItem.title != newItem.title) {
                                    "title"
                                } else {
                                    null
                                }
                            }
                        }, true)
                    }
                    delay(200)
                }
        }
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        adapter.upResumed(isResumed)
    }

    override fun onPause() {
        isResumed = false
        adapter.upResumed(isResumed)
        super.onPause()
    }

    private fun loadArticles() {
        fullRefresh = true
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it)
        }
    }

    private fun loadArticles(targetPage: Int) {
        fullRefresh = true
        activityViewModel.rssSource?.let {
            viewModel.loadArticles(it, targetPage)
        }
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if (viewModel.isLoading) return
        fullRefresh = false
        if ((loadMoreView.hasMore && adapter.getActualItemCount() > 0) || forceLoad) {
            loadMoreView.hasMore()
            activityViewModel.rssSource?.let {
                viewModel.loadMore(it)
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.loadErrorLiveData.observe(viewLifecycleOwner) {
            loadMoreView.error(it)
        }
        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            (activity as? RssSortActivity)?.updatePageMenu(page, showPageMenu())
        }
        viewModel.loadFinallyLiveData.observe(viewLifecycleOwner) { hasMore ->
            binding.refreshLayout.isRefreshing = false
            if (!hasMore) {
                loadMoreView.noMore()
            }
        }
    }

    fun getCurrentPage(): Int {
        return viewModel.pageLiveData.value ?: viewModel.page
    }

    fun showPageMenu(): Boolean {
        return !activityViewModel.rssSource?.ruleNextPage.isNullOrEmpty()
    }

    fun showPagePicker() {
        if (!showPageMenu()) return
        val currentPage = getCurrentPage()
        NumberPickerDialog(requireContext())
            .setTitle(getString(R.string.change_page))
            .setMinValue(1)
            .setMaxValue(999)
            .setValue(currentPage)
            .show { targetPage ->
                if (targetPage != currentPage) {
                    fullRefresh = true
                    viewModel.skipPage(targetPage)
                    loadArticles(targetPage)
                    binding.recyclerView.scrollToPosition(0)
                }
            }
    }

    override fun readRss(rssArticle: RssArticle) {
        fullRefresh = false
        ReadRss.readRss(this, rssArticle, activityViewModel.rssSource)
    }

    /**
     * 对文章列表应用屏蔽规则过滤
     */
    private fun applyBlockRulesToList(articles: List<RssArticle>): List<RssArticle> {
        val sourceUrl = activityViewModel.rssSource?.sourceUrl.orEmpty()
        return BlockRuleStore.filterRssArticles(requireContext(), articles, sourceUrl)
    }

    /**
     * 屏蔽规则变化后重新过滤当前文章列表
     * 由 RssSortActivity 调用
     */
    fun applyBlockRules() {
        if (!isAdded) return
        try {
            val filtered = applyBlockRulesToList(rawArticles)
            blockedCount = rawArticles.size - filtered.size
            adapter.setItems(filtered)
        } catch (_: Exception) {
            // fragment detached during execution, ignore
        }
    }

    /**
     * 获取当前被屏蔽的文章数量
     * 由 RssSortActivity 调用
     */
    fun getBlockedCount(): Int = blockedCount
}
