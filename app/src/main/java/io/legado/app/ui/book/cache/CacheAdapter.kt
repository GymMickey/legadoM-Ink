package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemDownloadBinding
import androidx.core.content.ContextCompat
import io.legado.app.help.book.isLocal
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.CacheBook
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.gone
import io.legado.app.utils.visible

class CacheAdapter(context: Context, private val callBack: CallBack) :
    DiffRecyclerAdapter<Book, ItemDownloadBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<Book>
        get() = object : DiffUtil.ItemCallback<Book>() {
            override fun areItemsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.bookUrl == newItem.bookUrl
            }

            override fun areContentsTheSame(oldItem: Book, newItem: Book): Boolean {
                return oldItem.name == newItem.name
                        && oldItem.author == newItem.author
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemDownloadBinding {
        return ItemDownloadBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemDownloadBinding,
        item: Book,
        payloads: MutableList<Any>
    ) {
        binding.run {
            // 基于实际背景色明暗计算文字颜色，确保所有主题下文字清晰可读
            val isLightBg = ColorUtils.isColorLight(context.backgroundColor)
            if (payloads.isEmpty()) {
                ivCover.load(item, false)
                tvName.text = item.name
                tvName.setTextColor(
                    if (isLightBg) ContextCompat.getColor(context, R.color.md_light_primary_text)
                    else ContextCompat.getColor(context, R.color.md_dark_primary_text)
                )
                tvAuthor.text = context.getString(R.string.author_show, item.getRealAuthor())
                tvAuthor.setTextColor(
                    if (isLightBg) ContextCompat.getColor(context, R.color.md_light_secondary)
                    else ContextCompat.getColor(context, R.color.md_dark_secondary)
                )
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    val cs = callBack.cacheChapters[item.bookUrl]
                    if (cs == null) {
                        tvDownload.setText(R.string.loading)
                    } else {
                        val sizeText = formatCacheSize(callBack.cacheSizes[item.bookUrl] ?: 0L)
                        tvDownload.text =
                            "${context.getString(R.string.download_count, cs.size, item.totalChapterNum)} · $sizeText"
                    }
                }
            } else {
                if (item.isLocal) {
                    tvDownload.setText(R.string.local_book)
                } else {
                    val cacheSize = callBack.cacheChapters[item.bookUrl]?.size ?: 0
                    val sizeText = formatCacheSize(callBack.cacheSizes[item.bookUrl] ?: 0L)
                    tvDownload.text =
                        "${context.getString(R.string.download_count, cacheSize, item.totalChapterNum)} · $sizeText"
                }
            }
            tvDownload.setTextColor(
                if (isLightBg) ContextCompat.getColor(context, R.color.md_light_secondary)
                else ContextCompat.getColor(context, R.color.md_dark_secondary)
            )
            upDownloadIv(ivDownload, item)
            upExportInfo(tvMsg, progressExport, item)
        }
    }

    private fun formatCacheSize(size: Long): String {
        return if (size > 0) ConvertUtils.formatFileSize(size) else "0"
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemDownloadBinding) {
        binding.run {
            ivDownload.setOnClickListener {
                getItem(holder.layoutPosition)?.let { book ->
                    CacheBook.cacheBookMap[book.bookUrl]?.let {
                        if (!it.isStop()) {
                            CacheBook.remove(context, book.bookUrl)
                        } else {
                            CacheBook.start(context, book, 0, book.lastChapterIndex)
                        }
                    } ?: let {
                        CacheBook.start(context, book, 0, book.lastChapterIndex)
                    }
                }
            }
            tvExport.setOnClickListener {
                callBack.export(holder.layoutPosition)
            }
            ivClearCache.setOnClickListener {
                callBack.clearCache(holder.layoutPosition)
            }
        }
    }

    private fun upDownloadIv(iv: ImageView, book: Book) {
        if (book.isLocal) {
            iv.gone()
        } else {
            iv.visible()
            CacheBook.cacheBookMap[book.bookUrl]?.let {
                if (!it.isStop()) {
                    iv.setImageResource(R.drawable.ic_stop_black_24dp)
                } else {
                    iv.setImageResource(R.drawable.ic_play_24dp)
                }
            } ?: let {
                iv.setImageResource(R.drawable.ic_play_24dp)
            }
        }
    }

    private fun upExportInfo(msgView: TextView, progressView: ProgressBar, book: Book) {
        val msg = callBack.exportMsg(book.bookUrl)
        if (msg != null) {
            msgView.text = msg
            msgView.visible()
            progressView.gone()
            return
        }
        msgView.gone()
        val progress = callBack.exportProgress(book.bookUrl)
        if (progress != null) {
            progressView.max = book.totalChapterNum
            progressView.progress = progress
            progressView.visible()
            return
        }
        progressView.gone()
    }

    interface CallBack {
        val cacheChapters: HashMap<String, HashSet<String>>
        val cacheSizes: HashMap<String, Long>
        fun export(position: Int)
        fun clearCache(position: Int)
        fun exportProgress(bookUrl: String): Int?
        fun exportMsg(bookUrl: String): String?
    }
}
