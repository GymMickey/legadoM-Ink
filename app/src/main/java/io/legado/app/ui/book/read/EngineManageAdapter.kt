package io.legado.app.ui.book.read

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.help.source.SourceRecycleBinHelp
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.book.read.websearch.SearchEngine
import io.legado.app.ui.book.read.websearch.SearchEngineHelper
import io.legado.app.utils.dpToPx
import java.util.Collections

/**
 * 搜索引擎管理适配器
 * 
 * 用于管理搜索引擎列表的 RecyclerView 适配器
 * 支持拖拽排序、编辑、删除等操作
 */
class EngineManageAdapter(
    private val context: android.content.Context,
    private val items: MutableList<SearchEngine>,
    private val panelControlColor: Int,
    private val panelTextColor: Int,
    private val panelSecondaryTextColor: Int,
    private val accentTextColor: Int,
    private val onPersist: (List<SearchEngine>) -> Unit,
    private val onRefreshButtons: () -> Unit,
    private val onShowEditDialog: (Int, SearchEngine, () -> Unit) -> Unit
) : RecyclerView.Adapter<EngineManageAdapter.EngineViewHolder>() {

    /** 拖拽回调 */
    val itemTouchCallback = object : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = true

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false
            }
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(
            viewHolder: RecyclerView.ViewHolder?,
            actionState: Int
        ) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                persistItems()
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            persistItems()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EngineViewHolder {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(panelControlColor)
            }
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8.dpToPx()
            }
        }
        return EngineViewHolder(root)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: EngineViewHolder, position: Int) {
        val engine = items[position]
        val isDefault = SearchEngineHelper.isDefaultEngine(context, position, engine)
        holder.titleView.text = engine.title
        holder.urlView.text = engine.url
        holder.defaultTag.visibility = if (isDefault) android.view.View.VISIBLE else android.view.View.GONE
        holder.defaultButton.text = if (isDefault) "默认" else "设默认"
        holder.defaultButton.setOnClickListener {
            SearchEngineHelper.saveDefaultEngineUrl(context, engine.url)
            onRefreshButtons()
            notifyDataSetChanged()
        }
        holder.editButton.setOnClickListener {
            onShowEditDialog(position, engine) {
                replaceItems(items)
            }
        }
        holder.deleteButton.setOnClickListener {
            confirmDelete(position, engine)
        }
    }

    /** 替换列表数据 */
    fun replaceItems(newItems: List<SearchEngine>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** 持久化当前列表 */
    private fun persistItems() {
        onPersist(items.toList())
    }

    /** 确认删除对话框 */
    private fun confirmDelete(position: Int, engine: SearchEngine) {
        AlertDialog.Builder(context)
            .setTitle("删除搜索引擎")
            .setMessage("确认删除\"${engine.title}\"？")
            .setPositiveButton(R.string.delete) { _, _ ->
                if (position !in items.indices) {
                    return@setPositiveButton
                }
                SourceRecycleBinHelp.recycleSearchEngines(listOf(engine))
                items.removeAt(position)
                onPersist(items.toList())
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size - position)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** ViewHolder */
    inner class EngineViewHolder(root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val titleView = TextView(root.context).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(panelTextColor)
        }
        val defaultTag = TextView(root.context).apply {
            text = "默认"
            textSize = 12f
            setTextColor(accentTextColor)
            gravity = Gravity.CENTER
            setPadding(8.dpToPx(), 2.dpToPx(), 8.dpToPx(), 2.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(context.accentColor)
            }
        }
        val urlView = TextView(root.context).apply {
            textSize = 12f
            setTextColor(panelSecondaryTextColor)
            maxLines = 2
        }
        val defaultButton = TextView(root.context).actionText()
        val editButton = TextView(root.context).actionText().apply { text = "编辑" }
        val deleteButton = TextView(root.context).actionText().apply {
            text = "删除"
            setTextColor(Color.rgb(210, 64, 64))
        }

        init {
            val titleRow = LinearLayout(root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(titleView, LinearLayout.LayoutParams(0, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(defaultTag, LinearLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            val actionRow = LinearLayout(root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, 8.dpToPx(), 0, 0)
                addView(defaultButton)
                addView(editButton)
                addView(deleteButton)
            }
            root.addView(titleRow)
            root.addView(urlView, LinearLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            root.addView(actionRow, LinearLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }

        private fun TextView.actionText(): TextView {
            textSize = 14f
            setTextColor(context.accentColor)
            setPadding(12.dpToPx(), 6.dpToPx(), 0, 6.dpToPx())
            return this
        }
    }
}