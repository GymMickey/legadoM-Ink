package io.legado.app.ui.main.homepage

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import io.legado.app.help.config.ThemeConfig
import io.legado.app.data.appDb
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.R
import io.legado.app.help.AppWebDav
import io.legado.app.ui.book.readRecord.ReadRecordActivity
import io.legado.app.utils.startActivity
import io.legado.app.ui.rss.read.ReadRssActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.ui.theme.LegadoThemeWithBackground
import splitties.init.appCtx

/**
 * 首页 Fragment（E-Ink 精简版）
 *
 * 作为首页在 MainActivity 中的容器，使用 ComposeView 承载阅读仪表盘 Compose 界面。
 * 通过 LegadoThemeWithBackground 包裹内容，确保主题颜色统一适配并显示背景图片。
 * 处理书籍点击（跳转 BookInfoActivity）和 WebDAV 设置（跳转 ConfigActivity）的导航逻辑。
 */
class HomepageFragment() : Fragment(), MainFragmentInterface {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_homepage, container, false)
        val backgroundDrawable = loadBackgroundDrawable()
        val composeContainer = root.findViewById<ViewGroup>(R.id.compose_container)
        composeContainer.addView(
            ComposeView(requireContext()).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    LegadoThemeWithBackground(
                        backgroundDrawable = backgroundDrawable
                    ) {
                        @OptIn(DelicateCoroutinesApi::class)
                        val onRestoreConfirm: (String) -> Unit = { backupName ->
                            GlobalScope.launch(Dispatchers.Main) {
                                try {
                                    withContext(Dispatchers.IO) {
                                        AppWebDav.restoreWebDav(backupName)
                                    }
                                    android.widget.Toast.makeText(
                                        appCtx,
                                        appCtx.getString(R.string.restore_success),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        appCtx,
                                        appCtx.getString(R.string.restore_fail, e.message),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        HomepageScreen(
                        onReadRecordClick = {
                            startActivity<ReadRecordActivity>()
                        },
                        onBookshelfClick = {
                            activity?.findViewById<androidx.viewpager.widget.ViewPager>(R.id.view_pager_main)
                                ?.setCurrentItem(1, false)
                        },
                        onBookClick = { name, author, bookUrl, origin, coverPath ->
                            // RSS 订阅源文章 → 直接加载文章 URL
                            if (origin != null && appDb.rssSourceDao.has(origin)) {
                                ReadRssActivity.start(
                                    context = requireContext(),
                                    singleTop = false,
                                    origin = origin,
                                    title = name,
                                    url = bookUrl
                                )
                                return@HomepageScreen
                            }
                            // 书源书籍 → 跳转详情页
                            val intent = Intent(context, BookInfoActivity::class.java).apply {
                                putExtra("name", name)
                                putExtra("author", author)
                                putExtra("bookUrl", bookUrl)
                                origin?.let { putExtra("origin", it) }
                                coverPath?.let { putExtra("coverPath", it) }
                            }
                            startActivity(intent)
                        },
                        onWebDavSettingsClick = {
                            val intent = Intent(context, ConfigActivity::class.java).apply {
                                putExtra("configTag", ConfigTag.BACKUP_CONFIG)
                            }
                            startActivity(intent)
                        },
                        onRestoreConfirm = onRestoreConfirm,
                    )
                }
            }
        })
        return root
    }

    /**
     * 加载主题设置的背景图片
     *
     * 从 ThemeConfig 获取当前主题的背景图片 Drawable，
     * 如果未设置背景图则返回 null，此时使用纯色背景。
     */
    private fun loadBackgroundDrawable(): Drawable? {
        return try {
            val activity = requireActivity()
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay.getMetrics(metrics)
            }
            ThemeConfig.getBgImage(activity, metrics)
        } catch (_: Exception) {
            null
        }
    }
}
