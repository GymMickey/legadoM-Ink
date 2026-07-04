package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.legado.app.R
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.websearch.SearchEngine
import io.legado.app.ui.book.read.websearch.SearchEngineHelper
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.openUrl
import io.legado.app.utils.setDarkeningAllowed
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import kotlin.math.roundToInt

/**
 * 网页搜索面板
 * 
 * 在阅读界面长按文本后，通过网页搜索选中内容
 * 支持多个搜索引擎（必应、百度等），用户可自定义
 */
class ReadWebSearchPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    // 颜色属性
    private val panelBackgroundColor: Int
        get() = context.backgroundColor
    private val panelTextColor: Int
        get() = if (ColorUtils.isColorLight(panelBackgroundColor)) Color.BLACK else Color.WHITE
    private val panelSecondaryTextColor: Int
        get() = if (ColorUtils.isColorLight(panelBackgroundColor)) Color.DKGRAY else Color.LTGRAY
    private val panelControlColor: Int
        get() = if (ColorUtils.isColorLight(panelBackgroundColor)) {
            Color.argb(18, 0, 0, 0)
        } else {
            Color.argb(32, 255, 255, 255)
        }
    private val accentTextColor: Int
        get() = if (ColorUtils.isColorLight(context.accentColor)) Color.BLACK else Color.WHITE

    // UI 组件
    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(panelBackgroundColor)
        isClickable = true
    }
    private val handle = View(context).apply {
        setBackgroundColor(Color.argb(96, 128, 128, 128))
    }
    private val searchEdit = EditText(context).apply {
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        setTextColor(panelTextColor)
        setHintTextColor(panelSecondaryTextColor)
        hint = context.getString(R.string.web_search)
        textSize = 16f
        setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
        setBackgroundColor(panelControlColor)
    }
    private val backButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_arrow_back)
        setColorFilter(panelTextColor)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "返回"
        setOnClickListener {
            if (canGoBack()) {
                goBack()
            }
        }
    }
    private val moreButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_more_vert)
        setColorFilter(panelTextColor)
        setBackgroundColor(Color.TRANSPARENT)
        contentDescription = "更多"
        setOnClickListener { showMoreMenu() }
    }
    private val engineRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 8.dpToPx())
    }
    private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        gone()
    }

    // WebView
    private var pooledWebView: PooledWebView? = null
    private val webView: WebView
        get() = pooledWebView!!.realWebView

    // 搜索引擎数据
    private var engines = SearchEngineHelper.loadSearchEngines(context)
    private var selectedEngineIndex = 0
    private var isSwitchingEngine = false
    private var lastLoadedEngineUrl: String? = null

    // 拖动状态
    private var startRawY = 0f
    private var startHeight = 0
    private val collapsedRatio = 0.58f
    private val expandedRatio = 0.92f
    private val minRatioBeforeDismiss = 0.35f

    init {
        visibility = GONE
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { close() }
        addView(
            sheet,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM)
        )
        buildSheet()
    }

    /**
     * 打开搜索面板
     * 
     * @param query 搜索关键词
     */
    fun open(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return
        }
        ensureWebView()
        webView.resumeTimers()
        webView.onResume()
        selectedEngineIndex = SearchEngineHelper.defaultEngineIndex(context, engines)
        visible()
        bringToFront()
        setSheetHeight((resources.displayMetrics.heightPixels * collapsedRatio).roundToInt())
        searchEdit.setText(normalizedQuery)
        searchEdit.setSelection(searchEdit.text.length)
        loadSearch(normalizedQuery)
    }

    /**
     * 关闭搜索面板
     */
    fun close() {
        pooledWebView?.realWebView?.stopLoading()
        visibility = GONE
    }

    /**
     * 销毁时释放 WebView
     */
    fun onDestroy() {
        pooledWebView?.let(WebViewPool::release)
        pooledWebView = null
    }

    /**
     * 是否可以返回上一页
     */
    fun canGoBack(): Boolean {
        return isShown && pooledWebView != null && webView.canGoBack()
    }

    /**
     * 返回上一页
     */
    fun goBack() {
        if (canGoBack()) {
            webView.goBack()
        }
    }

    /**
     * 构建面板 UI
     */
    private fun buildSheet() {
        sheet.setOnClickListener { }
        sheet.addView(
            FrameLayout(context).apply {
                addView(
                    handle,
                    LayoutParams(42.dpToPx(), 4.dpToPx(), Gravity.CENTER)
                )
                setOnTouchListener(::onDragTouch)
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 22.dpToPx())
        )
        sheet.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(backButton, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
                addView(searchEdit, LinearLayout.LayoutParams(0, 44.dpToPx(), 1f))
                addView(moreButton, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 44.dpToPx()).apply {
                marginStart = 12.dpToPx()
                marginEnd = 12.dpToPx()
            }
        )
        sheet.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(engineRow)
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48.dpToPx())
        )
        sheet.addView(
            progressBar,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 2.dpToPx())
        )
        refreshEngineButtons()
        searchEdit.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                loadSearch(searchEdit.text.toString())
                true
            } else {
                false
            }
        }
    }

    /**
     * 显示更多菜单
     */
    private fun showMoreMenu() {
        PopupMenu(context, moreButton).apply {
            menu.add(R.string.refresh).setOnMenuItemClickListener {
                pooledWebView?.realWebView?.reload()
                true
            }
            menu.add(R.string.edit).setOnMenuItemClickListener {
                showEngineListDialog()
                true
            }
        }.show()
    }

    /**
     * 确保 WebView 已初始化
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (pooledWebView != null) {
            return
        }
        pooledWebView = WebViewPool.acquire(context)
        webView.apply {
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (isSwitchingEngine) {
                        webView.clearHistory()
                        isSwitchingEngine = false
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return shouldOverrideUrlLoading(request?.url)
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return shouldOverrideUrlLoading(url?.toUri())
                }

                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }

                private fun shouldOverrideUrlLoading(uri: Uri?): Boolean {
                    return when (uri?.scheme) {
                        "http", "https" -> false
                        null -> true
                        else -> {
                            context.openUrl(uri)
                            true
                        }
                    }
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.gone(newProgress >= 100)
                    if (newProgress < 100) {
                        progressBar.visible()
                    }
                }
            }
            setBackgroundColor(Color.WHITE)
            settings.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
                setDarkeningAllowed(false)
            }
        }
        sheet.addView(
            webView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    /**
     * 刷新搜索引擎按钮
     */
    private fun refreshEngineButtons() {
        engineRow.removeAllViews()
        selectedEngineIndex = selectedEngineIndex.coerceIn(0, (engines.size - 1).coerceAtLeast(0))
        engines.forEachIndexed { index, engine ->
            engineRow.addView(createEngineButton(index, engine))
        }
        updateEngineButtons()
    }

    /**
     * 创建搜索引擎按钮
     */
    private fun createEngineButton(index: Int, engine: SearchEngine): TextView {
        return TextView(context).apply {
            text = engine.title
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
            setOnClickListener {
                selectedEngineIndex = index
                updateEngineButtons()
                SearchEngineHelper.saveDefaultEngineUrl(context, engine.url)
                loadSearch(searchEdit.text.toString())
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                34.dpToPx()
            ).apply {
                marginEnd = 8.dpToPx()
            }
        }
    }

    /**
     * 更新搜索引擎按钮样式
     */
    private fun updateEngineButtons() {
        for (index in 0 until engineRow.childCount) {
            val child = engineRow.getChildAt(index) as? TextView ?: continue
            val selected = index == selectedEngineIndex
            child.setTextColor(if (selected) accentTextColor else panelTextColor)
            child.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            child.setBackgroundColor(if (selected) context.accentColor else panelControlColor)
        }
    }

    /**
     * 加载搜索
     */
    private fun loadSearch(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return
        }
        searchEdit.setText(normalizedQuery)
        searchEdit.setSelection(searchEdit.text.length)
        val engine = engines.getOrNull(selectedEngineIndex) ?: return
        if (lastLoadedEngineUrl != engine.url) {
            isSwitchingEngine = true
            lastLoadedEngineUrl = engine.url
        }
        webView.loadUrl(engine.buildUrl(normalizedQuery))
    }

    /**
     * 显示搜索引擎管理对话框
     */
    private fun showEngineListDialog() {
        val dialog = BottomSheetDialog(context)
        val adapter = EngineManageAdapter(
            context = context,
            items = engines.toMutableList(),
            panelControlColor = panelControlColor,
            panelTextColor = panelTextColor,
            panelSecondaryTextColor = panelSecondaryTextColor,
            accentTextColor = accentTextColor,
            onPersist = { newEngines ->
                engines = newEngines
                SearchEngineHelper.ensureValidDefaultEngine(context, engines)
                SearchEngineHelper.saveSearchEngines(context, engines)
                selectedEngineIndex = SearchEngineHelper.defaultEngineIndex(context, engines)
                refreshEngineButtons()
                loadSearch(searchEdit.text.toString())
            },
            onRefreshButtons = {
                refreshEngineButtons()
            },
            onShowEditDialog = { index, engine, onChanged ->
                showEngineItemDialog(index, engine, onChanged)
            }
        )
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        ItemTouchHelper(adapter.itemTouchCallback).attachToRecyclerView(recyclerView)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundColor(panelBackgroundColor)
            addView(
                TextView(context).apply {
                    text = "搜索引擎"
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(panelTextColor)
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            addView(
                TextView(context).apply {
                    text = "长按拖动排序，URL 使用 {query} 表示选中文字"
                    textSize = 13f
                    setTextColor(panelSecondaryTextColor)
                    setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )
            addView(
                recyclerView,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            )
            addView(
                Button(context).apply {
                    text = "添加搜索引擎"
                    setTextColor(panelTextColor)
                    setOnClickListener {
                        showEngineItemDialog(
                            index = -1,
                            engine = SearchEngine("新搜索", SearchEngine.BING_TEMPLATE.url),
                            onChanged = { adapter.replaceItems(engines) }
                        )
                    }
                },
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 44.dpToPx()).apply {
                    topMargin = 8.dpToPx()
                }
            )
        }
        dialog.setContentView(content)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams = bottomSheet?.layoutParams?.apply {
                height = (resources.displayMetrics.heightPixels * 0.82f).roundToInt()
            }
            bottomSheet?.let { sheetView ->
                BottomSheetBehavior.from(sheetView).state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    /**
     * 显示搜索引擎编辑对话框
     */
    private fun showEngineItemDialog(
        index: Int,
        engine: SearchEngine,
        onChanged: (() -> Unit)? = null
    ) {
        val nameEdit = EditText(context).apply {
            setSingleLine(true)
            hint = "名称"
            setTextColor(panelTextColor)
            setHintTextColor(panelSecondaryTextColor)
            setText(engine.title)
        }
        val urlEdit = EditText(context).apply {
            setSingleLine(false)
            minLines = 2
            hint = "搜索 URL，使用 {query} 表示关键词"
            setTextColor(panelTextColor)
            setHintTextColor(panelSecondaryTextColor)
            setText(engine.url)
        }
        val templateRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                Button(context).apply {
                    text = "必应模板"
                    setTextColor(panelTextColor)
                    setOnClickListener {
                        nameEdit.setText(SearchEngine.BING_TEMPLATE.title)
                        urlEdit.setText(SearchEngine.BING_TEMPLATE.url)
                    }
                },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 6.dpToPx()
                }
            )
            addView(
                Button(context).apply {
                    text = "百度模板"
                    setTextColor(panelTextColor)
                    setOnClickListener {
                        nameEdit.setText(SearchEngine.BAIDU_TEMPLATE.title)
                        urlEdit.setText(SearchEngine.BAIDU_TEMPLATE.url)
                    }
                },
                LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 6.dpToPx()
                }
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 8.dpToPx(), 24.dpToPx(), 0)
            addView(nameEdit, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(urlEdit, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(templateRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val builder = AlertDialog.Builder(context)
            .setTitle(if (index >= 0) R.string.edit else R.string.add)
            .setView(container)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.cancel, null)
        if (index >= 0) {
            builder.setNeutralButton(R.string.delete, null)
        }
        val dialog = builder.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newEngine = SearchEngine(
                title = nameEdit.text.toString().trim(),
                url = urlEdit.text.toString().trim()
            )
            if (newEngine.title.isBlank() || newEngine.url.isBlank()) {
                context.toastOnUi(R.string.non_null_name_url)
                return@setOnClickListener
            }
            if (!newEngine.url.contains(SearchEngine.QUERY_PLACEHOLDER)) {
                context.toastOnUi("搜索 URL 必须包含 ${SearchEngine.QUERY_PLACEHOLDER}")
                return@setOnClickListener
            }
            engines = engines.toMutableList().apply {
                if (index >= 0) {
                    set(index, newEngine)
                } else {
                    add(newEngine)
                    selectedEngineIndex = lastIndex
                }
            }
            if (index >= 0 && SearchEngineHelper.getDefaultEngineUrl(context).isNullOrBlank()) {
                SearchEngineHelper.saveDefaultEngineUrl(context, newEngine.url)
            }
            SearchEngineHelper.saveSearchEngines(context, engines)
            refreshEngineButtons()
            loadSearch(searchEdit.text.toString())
            onChanged?.invoke()
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val engineTitle = engines.getOrNull(index)?.title.orEmpty()
            AlertDialog.Builder(context)
                .setTitle("删除搜索引擎")
                .setMessage("确认删除\"$engineTitle\"？")
                .setPositiveButton(R.string.delete) { _, _ ->
                    engines = engines.toMutableList().apply {
                        if (index in indices) {
                            removeAt(index)
                        }
                    }
                    selectedEngineIndex = selectedEngineIndex.coerceIn(0, (engines.size - 1).coerceAtLeast(0))
                    SearchEngineHelper.ensureValidDefaultEngine(context, engines)
                    SearchEngineHelper.saveSearchEngines(context, engines)
                    refreshEngineButtons()
                    onChanged?.invoke()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /**
     * 拖动触摸处理
     */
    private fun onDragTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRawY = event.rawY
                startHeight = sheet.layoutParams.height
                view.parent.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val delta = startRawY - event.rawY
                setSheetHeight((startHeight + delta).toInt())
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.parent.requestDisallowInterceptTouchEvent(false)
                settleSheet()
                return true
            }
        }
        return false
    }

    /**
     * 确定面板高度
     */
    private fun settleSheet() {
        val screenHeight = resources.displayMetrics.heightPixels
        val currentHeight = sheet.layoutParams.height
        if (currentHeight < screenHeight * minRatioBeforeDismiss) {
            close()
            return
        }
        val targetRatio = if (currentHeight > screenHeight * 0.72f) expandedRatio else collapsedRatio
        setSheetHeight((screenHeight * targetRatio).roundToInt())
    }

    /**
     * 设置面板高度
     */
    private fun setSheetHeight(height: Int) {
        val screenHeight = resources.displayMetrics.heightPixels
        val minHeight = (screenHeight * 0.18f).roundToInt()
        val maxHeight = (screenHeight * expandedRatio).roundToInt()
        val targetHeight = height.coerceIn(minHeight, maxHeight)
        sheet.layoutParams = sheet.layoutParams.apply {
            this.height = targetHeight
        }
    }
}