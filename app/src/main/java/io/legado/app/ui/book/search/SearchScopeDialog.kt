package io.legado.app.ui.book.search

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogSearchScopeBinding
import io.legado.app.databinding.ItemCheckBoxBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchScopeDialog : BaseDialogFragment(R.layout.dialog_search_scope) {

    private val binding by viewBinding(DialogSearchScopeBinding::bind)
    private var sourceFlowJob: Job? = null
    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback
    var groups: List<String> = emptyList()
    val screenSources = arrayListOf<BookSourcePart>()
    var screenText: String? = null

    val adapter by lazy {
        RecyclerAdapter()
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.8f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setFastScrollEnabled(true)
        binding.recyclerView.isVerticalScrollBarEnabled = false
        initMenu()
        initSearchView()
        initOtherView()
        initData()
    }

    private fun initMenu() {
        binding.toolBar.inflateMenu(R.menu.book_search_scope)
        binding.toolBar.menu.applyTint(requireContext())
    }

    private fun initSearchView() {
        val searchView = binding.toolBar.menu.findItem(R.id.menu_screen).actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                screenText = newText
                upData()
                return false
            }

        })
    }

    private fun initOtherView() {
        binding.rgScope.setOnCheckedChangeListener { _, checkedId ->
            binding.toolBar.menu.findItem(R.id.menu_screen)?.isVisible = checkedId == R.id.rb_source
            updateSelectAllText()
            upData()
        }
        binding.tvAllSource.setOnClickListener {
            toggleSelectAll()
        }
        binding.tvOk.setOnClickListener {
            if (binding.rbGroup.isChecked) {
                // 临时分组展开为临时书源
                lifecycleScope.launch {
                    val allSources = withContext(IO) {
                        val result = arrayListOf<BookSourcePart>()
                        adapter.selectGroups.forEach { group ->
                            result.addAll(appDb.bookSourceDao.getPartByGroup(group))
                        }
                        result
                    }
                    if (allSources.isNotEmpty()) {
                        callback.onSearchScopeOk(SearchScope.fromSources(allSources))
                    } else {
                        callback.onSearchScopeOk(SearchScope(""))
                    }
                    dismiss()
                }
            } else {
                // 临时书源
                if (adapter.selectSources.isNotEmpty()) {
                    callback.onSearchScopeOk(SearchScope.fromSources(adapter.selectSources))
                } else {
                    callback.onSearchScopeOk(SearchScope(""))
                }
                dismiss()
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            groups = withContext(IO) {
                appDb.bookSourceDao.allGroups()
            }
            upData()
        }
    }

    /**
     * 切换全选 / 取消全选
     */
    private fun toggleSelectAll() {
        if (binding.rbGroup.isChecked) {
            if (adapter.selectGroups.size == groups.size) {
                adapter.selectGroups.clear()
            } else {
                adapter.selectGroups.clear()
                adapter.selectGroups.addAll(groups)
            }
        } else {
            if (adapter.selectSources.size == screenSources.size) {
                adapter.selectSources.clear()
            } else {
                adapter.selectSources.clear()
                adapter.selectSources.addAll(screenSources)
            }
        }
        updateSelectAllText()
        adapter.notifyItemRangeChanged(0, adapter.itemCount, "up")
    }

    /**
     * 全选/取消全选
     */
    private fun updateSelectAllText() {
        val isAllSelected = if (binding.rbGroup.isChecked) {
            groups.isNotEmpty() && adapter.selectGroups.size == groups.size
        } else {
            screenSources.isNotEmpty() && adapter.selectSources.size == screenSources.size
        }
        binding.tvAllSource.text = if (isAllSelected) {
            getString(R.string.un_select_all)
        } else {
            getString(R.string.select_all)
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun upData() {
        if (binding.rbSource.isChecked) {
            upBookSource(screenText)
        } else {
            updateSelectAllText()
            adapter.notifyDataSetChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun upBookSource(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.bookSourceDao.flowAll()
                }
                else -> {
                    appDb.bookSourceDao.flowSearch(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                lifecycle,
                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).flowOn(IO).conflate().collect { data ->
                screenSources.clear()
                screenSources.addAll(data)
                updateSelectAllText()
                adapter.notifyDataSetChanged()
                delay(500)
            }
        }
    }

    inner class RecyclerAdapter : RecyclerView.Adapter<ItemViewHolder>() {
        val selectGroups = arrayListOf<String>()
        val selectSources = arrayListOf<BookSourcePart>()
        override fun getItemViewType(position: Int): Int {
            return 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            return ItemViewHolder(ItemCheckBoxBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(
            holder: ItemViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(holder, position, payloads)
            } else {
                when (holder.binding) {
                    is ItemCheckBoxBinding -> {
                        if (binding.rbSource.isChecked) {
                            screenSources.getOrNull(position)?.let {
                                holder.binding.checkBox.isChecked = selectSources.contains(it)
                                holder.binding.checkBox.text = it.bookSourceName
                                setSourceAlpha(holder.binding, it.enabled)
                            }
                        } else {
                            groups.getOrNull(position)?.let {
                                holder.binding.checkBox.isChecked = selectGroups.contains(it)
                                holder.binding.checkBox.text = it
                                holder.binding.root.alpha = 1.0f
                            }
                        }
                    }
                }
            }
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            when (holder.binding) {
                is ItemCheckBoxBinding -> {
                    if (binding.rbSource.isChecked) {
                        // 书源
                        screenSources.getOrNull(position)?.let { source ->
                            holder.binding.checkBox.isChecked = selectSources.contains(source)
                            holder.binding.checkBox.text = source.bookSourceName
                            setSourceAlpha(holder.binding, source.enabled)
                            holder.binding.checkBox.setOnUserCheckedChangeListener { isChecked ->
                                if (isChecked) {
                                    selectSources.add(source)
                                } else {
                                    selectSources.remove(source)
                                }
                                updateSelectAllText()
                                holder.itemView.post {
                                    notifyItemRangeChanged(0, itemCount, "up")
                                }
                            }
                        }
                    } else {
                        // 分组
                        groups.getOrNull(position)?.let { group ->
                            holder.binding.checkBox.isChecked = selectGroups.contains(group)
                            holder.binding.checkBox.text = group
                            holder.binding.root.alpha = 1.0f
                            holder.binding.checkBox.setOnUserCheckedChangeListener { isChecked ->
                                if (isChecked) {
                                    selectGroups.add(group)
                                } else {
                                    selectGroups.remove(group)
                                }
                                updateSelectAllText()
                                holder.itemView.post {
                                    notifyItemRangeChanged(0, itemCount, "up")
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount(): Int {
            return if (binding.rbSource.isChecked) {
                screenSources.size
            } else {
                groups.size
            }
        }

        /**
         * 未启用书源降低透明度作为视觉区分
         */
        private fun setSourceAlpha(binding: ItemCheckBoxBinding, enabled: Boolean) {
            binding.root.alpha = if (enabled) 1.0f else 0.5f
        }

    }

    interface Callback {
        fun onSearchScopeOk(searchScope: SearchScope)
    }
}