package io.legado.app.ui.book.bookmark

import android.content.Context
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.databinding.ItemBookmarkBinding
import io.legado.app.utils.gone
import splitties.views.onClick
import splitties.views.onLongClick

class BookmarkAdapter(context: Context, val callback: Callback) :
    RecyclerAdapter<Bookmark, ItemBookmarkBinding>(context) {

    private val collapsedGroups = HashSet<String>()
    private var allItems: List<Bookmark> = emptyList()

    override fun getViewBinding(parent: ViewGroup): ItemBookmarkBinding {
        return ItemBookmarkBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookmarkBinding,
        item: Bookmark,
        payloads: MutableList<Any>
    ) {
        val collapsed = collapsedGroups.contains(getGroupKey(item))
        val density = binding.root.context.resources.displayMetrics.density
        val padding = (8 * density).toInt()
        
        if (collapsed) {
            binding.root.setPadding(0, 0, 0, 0)
            binding.tvChapterName.gone(true)
            binding.tvBookText.gone(true)
            binding.tvContent.gone(true)
        } else {
            binding.root.setPadding(padding, padding, padding, padding)
            binding.tvChapterName.text = item.chapterName
            binding.tvChapterName.gone(false)
            binding.tvBookText.gone(item.bookText.isEmpty())
            binding.tvBookText.text = item.bookText
            binding.tvContent.gone(item.content.isEmpty())
            binding.tvContent.text = item.content
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookmarkBinding) {
        binding.root.onClick {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callback.onItemClick(it, holder.layoutPosition)
            }
        }
        binding.root.onLongClick {
            getItemByLayoutPosition(holder.layoutPosition)?.let {
                callback.onItemLongClick(it, holder.layoutPosition)
            } ?: false
        }
    }

    /**
     * 获取指定位置的分组标题文本（书籍名+作者）
     */
    fun getHeaderText(position: Int): String {
        return with(getItem(position)) {
            "${this?.bookName ?: ""}(${this?.bookAuthor ?: ""})"
        }
    }

    fun isItemHeader(position: Int): Boolean {
        if (position == 0) return true
        val lastItem = getItem(position - 1)
        val curItem = getItem(position)
        return !(lastItem?.bookName == curItem?.bookName
                && lastItem?.bookAuthor == curItem?.bookAuthor)
    }

    /**
     * 获取书签所属分组的关键字（书籍名_作者）
     */
    fun getGroupKey(bookmark: Bookmark): String {
        return "${bookmark.bookName}_${bookmark.bookAuthor}"
    }

    fun isGroupCollapsed(position: Int): Boolean {
        val item = getItem(position) ?: return false
        return collapsedGroups.contains(getGroupKey(item))
    }

    fun toggleGroup(position: Int): Boolean {
        val item = getItem(position) ?: return false
        val groupKey = getGroupKey(item)
        return if (collapsedGroups.contains(groupKey)) {
            collapsedGroups.remove(groupKey)
            true
        } else {
            collapsedGroups.add(groupKey)
            true
        }
    }

    /**
     * 获取所有书籍分组的关键字集合
     */
    fun getAllGroupKeys(): Set<String> {
        return allItems.map { getGroupKey(it) }.toSet()
    }

    /**
     * 折叠所有分组
     * @return 状态是否发生变化（已有分组数与当前折叠数不同时返回true）
     */
    fun collapseAll(): Boolean {
        val allKeys = getAllGroupKeys()
        if (collapsedGroups.size == allKeys.size) {
            return false
        }
        collapsedGroups.clear()
        collapsedGroups.addAll(allKeys)
        return true
    }

    /**
     * 展开所有分组
     * @return 状态是否发生变化（有折叠分组时返回true）
     */
    fun expandAll(): Boolean {
        if (collapsedGroups.isEmpty()) {
            return false
        }
        collapsedGroups.clear()
        return true
    }

    /**
     * 判断是否全部折叠
     */
    fun isAllCollapsed(): Boolean {
        val allKeys = getAllGroupKeys()
        return allKeys.isNotEmpty() && collapsedGroups.size == allKeys.size
    }

    fun setItemsWithCollapse(items: List<Bookmark>) {
        allItems = items
        val filteredItems = mutableListOf<Bookmark>()
        var currentGroupKey: String? = null
        var addedHeaderForGroup = false
        
        for (item in items) {
            val groupKey = getGroupKey(item)
            if (groupKey != currentGroupKey) {
                currentGroupKey = groupKey
                addedHeaderForGroup = false
            }
            if (collapsedGroups.contains(groupKey)) {
                if (!addedHeaderForGroup) {
                    filteredItems.add(item)
                    addedHeaderForGroup = true
                }
            } else {
                filteredItems.add(item)
            }
        }
        setItems(filteredItems)
    }

    fun getGroupPosition(position: Int): Int {
        var groupCount = 0
        for (i in 0..position) {
            if (isItemHeader(i)) {
                groupCount++
            }
        }
        return groupCount - 1
    }

    /**
     * 书签适配器回调接口
     */
    interface Callback {

        /**
         * 书签项点击回调
         */
        fun onItemClick(bookmark: Bookmark, position: Int)

        /**
         * 书签项长按回调
         * @return 是否消费长按事件
         */
        fun onItemLongClick(bookmark: Bookmark, position: Int): Boolean

    }

}