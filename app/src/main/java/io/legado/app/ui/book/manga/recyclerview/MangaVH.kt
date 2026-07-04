package io.legado.app.ui.book.manga.recyclerview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.glide.progress.ProgressManager
import io.legado.app.model.BookCover
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadManga
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

open class MangaVH<VB : ViewBinding>(val binding: VB, private val context: Context) :
    RecyclerView.ViewHolder(binding.root) {

    protected lateinit var mLoading: ProgressBar
    protected lateinit var mImage: AppCompatImageView
    protected lateinit var mProgress: TextView
    protected lateinit var mFlProgress: FrameLayout
    protected var mRetry: Button? = null

    private val minHeight = context.resources.displayMetrics.heightPixels * 2 / 3
    // 图片下载到 BookHelp 缓存的协程任务，ViewHolder 回收时需取消
    var imageLoadJob: Job? = null

    fun initComponent(
        loading: ProgressBar,
        image: AppCompatImageView,
        progress: TextView,
        button: Button? = null,
        flProgress: FrameLayout,
    ) {
        mLoading = loading
        mImage = image
        mRetry = button
        mProgress = progress
        mFlProgress = flProgress
    }

    @SuppressLint("CheckResult")
    fun loadImageWithRetry(
        imageUrl: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        mFlProgress.isVisible = true
        mLoading.isVisible = true
        mRetry?.isGone = true
        mProgress.isVisible = true
        ProgressManager.removeListener(imageUrl)
        ProgressManager.addListener(imageUrl) { _, percentage, _, _ ->
            @SuppressLint("SetTextI18n")
            mProgress.text = "$percentage%"
        }
        try {
            mImage.tag = imageUrl
            val book = ReadManga.book
            if (book != null && !book.isLocal) {
                // 优先从 BookHelp 文件缓存加载，与文本模式 (ReadBook) 共享同一份图片缓存
                val vFile = BookHelp.getImage(book, imageUrl)
                if (vFile.exists()) {
                    // 缓存命中，直接从本地文件加载
                    loadImageFromUri(vFile.absolutePath, isHorizontal, isLastImage, transformation)
                } else {
                    // 缓存未命中，先下载到 BookHelp 缓存再加载，确保切换文本模式时无需重新下载
                    imageLoadJob?.cancel()
                    imageLoadJob = CoroutineScope(Dispatchers.Main).launch {
                        if (context is Activity && (context.isDestroyed || context.isFinishing)) {
                            return@launch
                        }
                        ImageProvider.cacheImage(book, imageUrl, ReadManga.bookSource)
                        if (context is Activity && (context.isDestroyed || context.isFinishing)) {
                            return@launch
                        }
                        val cachedFile = BookHelp.getImage(book, imageUrl)
                        if (cachedFile.exists()) {
                            loadImageFromUri(cachedFile.absolutePath, isHorizontal, isLastImage, transformation)
                        } else {
                            loadImageFromUri(imageUrl, isHorizontal, isLastImage, transformation)
                        }
                    }
                }
            } else {
                // 本地书籍直接从原始路径加载
                loadImageFromUri(imageUrl, isHorizontal, isLastImage, transformation)
            }
        } catch (e: Exception) {
            e.printOnDebug()
        }
    }

    // 统一的 Glide 图片加载入口，支持文件路径和网络 URL 两种来源
    @SuppressLint("CheckResult")
    private fun loadImageFromUri(
        uri: String,
        isHorizontal: Boolean,
        isLastImage: Boolean,
        transformation: Transformation<Bitmap>?
    ) {
        if (context is Activity) {
            if (context.isDestroyed || context.isFinishing) {
                return
            }
        }
        BookCover.loadManga(
            context,
            uri,
            sourceOrigin = ReadManga.book?.origin,
            transformation = transformation
        ).addListener(object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                mFlProgress.isVisible = true
                mLoading.isGone = true
                mRetry?.isVisible = true
                mProgress.isGone = true
                itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean {
                mFlProgress.isGone = true
                if (!isHorizontal) {
                    itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                        gravity = Gravity.NO_GRAVITY
                    }
                    if (isLastImage) {
                        mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        itemView.minimumHeight = minHeight
                    } else {
                        mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                            height = ViewGroup.LayoutParams.MATCH_PARENT
                        }
                        itemView.minimumHeight = 0
                    }
                    mImage.scaleType = ImageView.ScaleType.FIT_XY
                } else {
                    itemView.updateLayoutParams<ViewGroup.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    itemView.minimumHeight = 0
                    mImage.updateLayoutParams<FrameLayout.LayoutParams> {
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                        gravity = Gravity.CENTER
                    }
                    mImage.scaleType = ImageView.ScaleType.FIT_CENTER
                }
                return false
            }
        }).into(mImage)
    }
}
