package io.legado.app.ui.book.bookmark

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.widget.SearchView
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ActivityAllBookmarkBinding
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.setTintMutate
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 所有书签
 */
class AllBookmarkActivity : VMBaseActivity<ActivityAllBookmarkBinding, AllBookmarkViewModel>(),
    BookmarkAdapter.Callback {

    override val viewModel by viewModels<AllBookmarkViewModel>()
    override val binding by viewBinding(ActivityAllBookmarkBinding::inflate)
    private val adapter by lazy {
        BookmarkAdapter(this, this)
    }
    private lateinit var decoration: BookmarkDecoration
    private val exportDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            when (it.requestCode) {
                1 -> viewModel.exportBookmark(uri)
                2 -> viewModel.exportBookmarkMd(uri)
            }
        }
    }
    private var searchJob: Job? = null
    private var searchView: SearchView? = null
    // 折叠/展开全部按钮的菜单项引用，用于动态更新图标和标题
    private var collapseMenuItem: MenuItem? = null
    private var allBookmarks: List<Bookmark> = emptyList()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        searchData("")
    }

    /**
     * 初始化视图：设置RecyclerView适配器、分组装饰器和触摸监听
     */
    private fun initView() {
        decoration = BookmarkDecoration(adapter)
        binding.recyclerView.addItemDecoration(decoration)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.action == MotionEvent.ACTION_UP) {
                    val headerPosition = decoration.getHeaderPositionForTouch(rv, e)
                    if (headerPosition >= 0) {
                        if (adapter.toggleGroup(headerPosition)) {
                            adapter.setItemsWithCollapse(allBookmarks)
                            rv.post { rv.requestLayout() }
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.bookmark, menu)
        // 获取折叠/展开按钮的引用
        collapseMenuItem = menu.findItem(R.id.menu_collapse_all)
        val searchItem = menu.findItem(R.id.menu_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            applyTint(primaryTextColor)
            queryHint = getString(R.string.search)
            isSubmitButtonEnabled = true
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean {
                    return false
                }

                override fun onQueryTextChange(newText: String?): Boolean {
                    searchData(newText ?: "")
                    return true
                }
            })
        }
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                binding.titleBar.toolbar.title = ""
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                binding.titleBar.toolbar.title = getString(R.string.all_bookmark)
                return true
            }
        })
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun searchData(searchKey: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val flow = if (searchKey.isBlank()) {
                appDb.bookmarkDao.flowAll()
            } else {
                appDb.bookmarkDao.flowSearchAll(searchKey)
            }
            flow.catch {
                AppLog.put("所有书签界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                allBookmarks = it
                adapter.setItemsWithCollapse(it)
                updateCollapseIcon()
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_collapse_all -> {
                // 切换全部折叠/展开状态：当前已全部折叠则展开，否则折叠全部
                val changed = if (adapter.isAllCollapsed()) {
                    adapter.expandAll()
                } else {
                    adapter.collapseAll()
                }
                if (changed) {
                    adapter.setItemsWithCollapse(allBookmarks)
                    binding.recyclerView.post { binding.recyclerView.requestLayout() }
                    updateCollapseIcon()
                }
            }

            R.id.menu_export -> exportDir.launch {
                requestCode = 1
            }

            R.id.menu_export_md -> exportDir.launch {
                requestCode = 2
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    /**
     * 根据当前折叠状态更新菜单按钮的图标和标题
     * 全部折叠时显示展开图标，否则显示折叠图标
     */
    private fun updateCollapseIcon() {
        collapseMenuItem?.let { item ->
            if (adapter.isAllCollapsed()) {
                item.setIcon(R.drawable.ic_expand_less)
                item.setTitle(R.string.expand_all)
            } else {
                item.setIcon(R.drawable.ic_expand_more)
                item.setTitle(R.string.collapse_all)
            }
            item.icon?.setTintMutate(primaryTextColor)
        }
    }

    override fun onItemClick(bookmark: Bookmark, position: Int) {
        lifecycleScope.launch {
            val book = withContext(IO) {
                appDb.bookDao.getBook(bookmark.bookName, bookmark.bookAuthor)
            }
            if (book == null) {
                showDialogFragment(BookmarkDialog(bookmark, position))
            } else {
                startActivityForBook(book) {
                    putExtra("index", bookmark.chapterIndex)
                    putExtra("chapterPos", bookmark.chapterPos)
                }
            }
        }
    }

    /**
     * 书签项长按事件：显示书签编辑对话框
     */
    override fun onItemLongClick(bookmark: Bookmark, position: Int): Boolean {
        showDialogFragment(BookmarkDialog(bookmark, position))
        return true
    }

}
