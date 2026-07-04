package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfListBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toTimeAgo
import io.legado.app.utils.visible
import io.legado.app.utils.dpToPx
import splitties.views.onLongClick

class BooksAdapterList(
    context: Context,
    private val fragment: Fragment,
    private val callBack: CallBack,
    private val lifecycle: Lifecycle
) : BaseBooksAdapter<ItemBookshelfListBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfListBinding {
        return ItemBookshelfListBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfListBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
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
            upRefresh(binding, item)
            upLastUpdateTime(binding, item)
            // 显示简介和标签（仅在列表视图启用"显示更多信息"时）
            upMoreInfo(binding, item)
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
                            false,
                            fragment,
                            lifecycle
                        )

                        "refresh" -> upRefresh(binding, item)
                        "lastUpdateTime" -> upLastUpdateTime(binding, item)
                        "moreInfo" -> upMoreInfo(binding, item)
                    }
                }
            }
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

    private fun upLastUpdateTime(binding: ItemBookshelfListBinding, item: Book) {
        if (AppConfig.showLastUpdateTime && !item.isLocal) {
            val time = item.latestChapterTime.toTimeAgo()
            if (binding.tvLastUpdateTime.text != time) {
                binding.tvLastUpdateTime.text = time
            }
        } else {
            binding.tvLastUpdateTime.text = ""
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

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfListBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}
