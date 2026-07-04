package io.legado.app.ui.main.bookshelf.style2

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ItemBookshelfGridGroupBinding
import io.legado.app.databinding.ItemBookshelfList2Binding
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.databinding.ItemBookshelfListGroupBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.visible
import io.legado.app.utils.dpToPx
import splitties.views.onLongClick

@Suppress("UNUSED_PARAMETER")
class BooksAdapterList(context: Context, callBack: CallBack) :
    BaseBooksAdapter<RecyclerView.ViewHolder>(context, callBack) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> {
                // 根据folderLayout选择文件夹布局
                if (AppConfig.folderLayout >= 2) {
                    GroupGridViewHolder(ItemBookshelfGridGroupBinding.inflate(inflater, parent, false))
                } else {
                    GroupViewHolder(ItemBookshelfListGroupBinding.inflate(inflater, parent, false))
                }
            }
            else -> {
                if (AppConfig.bookLayout == 0) { BookViewHolder(ItemBookshelfListBinding.inflate(inflater, parent, false)) }
                else { BookViewHolder2(ItemBookshelfList2Binding.inflate(inflater, parent, false)) }
            }

        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (holder) {
            is BookViewHolder -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is BookViewHolder2 -> (getItem(position) as? Book)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is GroupViewHolder -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }

            is GroupGridViewHolder -> (getItem(position) as? BookGroup)?.let {
                holder.registerListener(it)
                holder.onBind(it, position, payloads)
            }
        }
    }

    inner class BookViewHolder(val binding: ItemBookshelfListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, position: Int) = binding.run {
            // 根据配置控制书籍外边框显示和间距
            if (AppConfig.showBookBorder) {
                root.background = context.resources.getDrawable(io.legado.app.R.drawable.card_border_background, null)
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
                    4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx()
                )
            } else {
                root.background = null
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
            }
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            flHasNew.visible()
            ivAuthor.visible()
            ivLast.visible()
            ivRead.visible()
            upRefresh(this, item)
            // 显示简介和标签（仅在列表视图启用"显示更多信息"时）
            upMoreInfo(binding, item)
        }

        fun onBind(item: Book, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "author" -> tvAuthor.text = item.author
                            "dur" -> tvRead.text = item.durChapterTitle
                            "last" -> tvLast.text = item.latestChapterTitle
                            "cover" -> ivCover.load(
                                item,
                                false
                            )

                            "refresh" -> upRefresh(this, item)
                            "moreInfo" -> upMoreInfo(binding, item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfListBinding, item: Book) {
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }

        /** 更新简介和标签的显示状态 */
        private fun upMoreInfo(binding: ItemBookshelfListBinding, item: Book) {
            // 显示标签（使用 FlexboxLayout，每个标签有外框）
            if (AppConfig.showMoreInfoInList && AppConfig.showTagsInList) {
                binding.flexboxTags.visible()
                updateTagViews(binding.flexboxTags, item.customTag ?: item.kind ?: "")
            } else {
                binding.flexboxTags.gone()
            }
            // 显示简介（使用配置的行数）
            if (AppConfig.showMoreInfoInList && AppConfig.showIntroInList) {
                binding.tvIntro.visible()
                binding.tvIntro.text = item.getDisplayIntroPlainText()
                // 根据配置设置简介的最大行数
                binding.tvIntro.maxLines = AppConfig.introLinesInList
            } else {
                binding.tvIntro.gone()
            }
        }

        /** 更新 FlexboxLayout 中的标签视图 */
        private fun updateTagViews(flexboxLayout: FlexboxLayout, tagsText: String) {
            flexboxLayout.removeAllViews()
            if (tagsText.isBlank()) return

            // 使用 splitNotBlank 方法分隔标签（与书籍详情页一致，使用逗号和换行符）
            val tags = tagsText.splitNotBlank(",", "\n")
            for (tag in tags) {
                val tagView = createTagView(tag)
                flexboxLayout.addView(tagView)
            }
        }

        /** 创建单个标签视图（带外框样式） */
        private fun createTagView(tag: String): TextView {
            return TextView(context).apply {
                text = tag
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(context.resources.getColor(io.legado.app.R.color.tv_text_summary, null))
                // 根据书籍外边框状态同步标签外框：有边框时使用带描边的标签背景，无边框时仅显示纯文本
                if (AppConfig.showBookBorder) {
                    setBackgroundResource(io.legado.app.R.drawable.bg_tag)
                }
                // 设置内边距
                setPadding(8, 4, 8, 4)
                // 设置 FlexboxLayout.LayoutParams
                layoutParams = FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    FlexboxLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    // 标签之间的间距
                    setMargins(4, 2, 4, 2)
                }
            }
        }

    }

    /**
    紧凑列表布局
     */
    inner class BookViewHolder2(val binding: ItemBookshelfList2Binding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: Book, position: Int) = binding.run {
            // 根据配置控制书籍外边框显示和间距
            if (AppConfig.showBookBorder) {
                root.background = context.resources.getDrawable(io.legado.app.R.drawable.card_border_background, null)
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(
                    4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx()
                )
            } else {
                root.background = null
                (root.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, 0, 0)
            }
            tvName.text = item.name
            tvAuthor.text = item.author
            tvRead.text = item.durChapterTitle
            tvLast.text = item.latestChapterTitle
            ivCover.load(item, false)
            flHasNew.visible()
            ivAuthor.visible()
            ivLast.visible()
            upRefresh(this, item)
        }

        fun onBind(item: Book, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvName.text = item.name
                            "author" -> tvAuthor.text = item.author
                            "dur" -> tvRead.text = item.durChapterTitle
                            "last" -> tvLast.text = item.latestChapterTitle
                            "cover" -> ivCover.load(
                                item,
                                false
                            )

                            "refresh" -> upRefresh(this, item)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

        private fun upRefresh(binding: ItemBookshelfList2Binding, item: Book) {
            if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
                binding.bvUnread.invisible()
                binding.rlLoading.visible()
            } else {
                binding.rlLoading.gone()
                if (AppConfig.showUnread) {
                    binding.bvUnread.setHighlight(item.lastCheckCount > 0)
                    binding.bvUnread.setBadgeCount(item.getUnreadChapterNum())
                } else {
                    binding.bvUnread.invisible()
                }
            }
        }

    }

    inner class GroupViewHolder(val binding: ItemBookshelfListGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, position: Int) = binding.run {
            tvName.text = item.groupName
            ivCover.load(item.cover)
            flHasNew.gone()
            ivAuthor.gone()
            ivLast.gone()
            ivRead.gone()
            tvAuthor.gone()
            tvLast.gone()
            tvRead.gone()
        }

        fun onBind(item: BookGroup, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

    inner class GroupGridViewHolder(val binding: ItemBookshelfGridGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun onBind(item: BookGroup, position: Int) = binding.run {
            tvName.text = item.groupName
            ivCover.load(item.cover)
        }

        fun onBind(item: BookGroup, position: Int, payloads: MutableList<Any>) = binding.run {
            if (payloads.isEmpty()) {
                onBind(item, position)
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "groupName" -> tvName.text = item.groupName
                            "cover" -> ivCover.load(item.cover)
                        }
                    }
                }
            }
        }

        fun registerListener(item: Any) {
            binding.root.setOnClickListener {
                callBack.onItemClick(item)
            }
            binding.root.onLongClick {
                callBack.onItemLongClick(item)
            }
        }

    }

}