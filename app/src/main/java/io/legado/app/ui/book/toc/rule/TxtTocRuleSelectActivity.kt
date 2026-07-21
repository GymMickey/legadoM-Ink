package io.legado.app.ui.book.toc.rule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.databinding.DialogTocRegexBinding
import io.legado.app.databinding.ItemTocRegexBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.localBook.TextFile
import io.legado.app.ui.association.ImportTxtTocRuleDialog
import io.legado.app.ui.association.ImportUrlDialogHelper
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.Utf8BomUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.coroutineContext

/**
 * TXT目录规则选择页（照抄 BookshelfConfigActivity 模式）
 * 支持对本地书籍按每条规则预览章节匹配数
 */
class TxtTocRuleSelectActivity : BaseActivity<DialogTocRegexBinding>(),
    TxtTocRuleEditDialog.Callback {

    override val binding by lazy {
        DialogTocRegexBinding.inflate(layoutInflater)
    }

    private val viewModel: TxtTocRuleViewModel by viewModels()
    private val adapter by lazy { TocRegexAdapter(this) }

    var selectedName: String? = null
    private var durRegex: String? = null
    private val importTocRuleKey = "tocRuleUrl"

    // ====== 章节数计算相关 ======
    private var bookUrl: String? = null
    private var computeJob: Job? = null
    private val ruleCounts = mutableMapOf<Long, Int>() // rule.id → 章节数

    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportTxtTocRuleDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportTxtTocRuleDialog(uri.toString()))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra("bookUrl")
        durRegex = intent.getStringExtra("tocRegex")
        initView()
        initData()
    }

    override fun onDestroy() {
        computeJob?.cancel()
        super.onDestroy()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.txt_toc_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> showDialogFragment(TxtTocRuleEditDialog())
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch(null)
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_help -> showHelp("txtTocRuleHelp")
        }
        return true
    }

    private fun initView() = binding.run {
        recyclerView.addItemDecoration(VerticalDivider(this@TxtTocRuleSelectActivity))
        recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(recyclerView)
        tvCancel.setOnClickListener { finish() }
        tvOk.setOnClickListener {
            adapter.getItems().forEach { tocRule ->
                if (selectedName == tocRule.name) {
                    setResult(RESULT_OK, Intent().putExtra(
                        "tocRegex", tocRule.rule + TextFile.spaceChars + tocRule.replacement
                    ))
                    finish()
                    return@setOnClickListener
                }
            }
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.txtTocRuleDao.observeAll().catch {
                AppLog.put("TXT目录规则获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(Dispatchers.IO).conflate().collect { tocRules ->
                initSelectedName(tocRules)
                adapter.setItems(tocRules, adapter.diffItemCallBack)
                // 规则展示后立即启动章节数计算
                if (!bookUrl.isNullOrBlank() && tocRules.isNotEmpty()) {
                    computeChapterCounts(tocRules)
                }
            }
        }
    }

    // ====== 章节数计算：单次读文件 + 多规则并发匹配 ======
    private fun computeChapterCounts(rules: List<TxtTocRule>) {
        computeJob?.cancel()
        computeJob = lifecycleScope.launch(Dispatchers.IO) {
            val book = appDb.bookDao.getBook(bookUrl!!) ?: return@launch
            val charset = book.fileCharset()

            // 预编译所有正则（语法错误 → null，计入 0）
            val patterns = rules.map { rule ->
                rule.id to try {
                    Pattern.compile(rule.rule, Pattern.MULTILINE)
                } catch (e: PatternSyntaxException) {
                    null
                }
            }
            val counts = mutableMapOf<Long, Int>()
            patterns.forEach { counts[it.first] = 0 }

            // 与 TextFile.analyze() 完全一致的 buffer 逻辑
            val bufferSize = 8 * 1024 * 1024
            val blank = 0x0a.toByte()
            var firstChunk = true

            runCatching {
                LocalBook.getBookInputStream(book).use { bis ->
                    val buffer = ByteArray(bufferSize)
                    var bufferStart = 3
                    bis.read(buffer, 0, 3)
                    if (Utf8BomUtils.hasBom(buffer)) {
                        bufferStart = 0
                    }
                    var length: Int
                    while (bis.read(buffer, bufferStart, bufferSize - bufferStart)
                            .also { length = it } > 0) {
                        coroutineContext.ensureActive()
                        var end = bufferStart + length
                        if (end == bufferSize) {
                            for (i in end - 1 downTo 0) {
                                if (buffer[i] == blank) { end = i; break }
                            }
                        }
                        val blockContent = String(buffer, 0, end, charset)
                        buffer.copyInto(buffer, 0, end, bufferStart + length)
                        bufferStart = bufferStart + length - end

                        // 单块内容对所有正则做匹配
                        for ((id, pattern) in patterns) {
                            if (pattern == null) continue
                            val matcher = pattern.matcher(blockContent)
                            while (matcher.find()) {
                                counts[id] = (counts[id] ?: 0) + 1
                            }
                        }

                        // 首个 chunk 后就刷新 UI（消除空白等待感）
                        // 后续 chunk 每 2 次刷新一次（减少无效刷新）
                        if (firstChunk || (counts.values.sum() % 2 == 0)) {
                            withContext(Dispatchers.Main) {
                                ruleCounts.putAll(counts)
                                adapter.notifyItemRangeChanged(
                                    0, adapter.itemCount,
                                    bundleOf(Pair("upCount", null))
                                )
                            }
                        }
                        firstChunk = false
                    }
                }
            }.onFailure {
                AppLog.put("TXT目录规则章节数计算失败\n${it.localizedMessage}", it)
            }

            // 最终刷新（确保最终值准确）
            withContext(Dispatchers.Main) {
                ruleCounts.putAll(counts)
                adapter.notifyItemRangeChanged(
                    0, adapter.itemCount,
                    bundleOf(Pair("upCount", null))
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    private fun initSelectedName(tocRules: List<TxtTocRule>) {
        if (selectedName == null && durRegex != null) {
            tocRules.forEach {
                if (durRegex == it.rule + TextFile.spaceChars + it.replacement) {
                    selectedName = it.name
                    return@forEach
                }
            }
            if (selectedName == null) selectedName = ""
        }
    }

    override fun saveTxtTocRule(txtTocRule: TxtTocRule) {
        viewModel.save(txtTocRule)
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val defaultUrl = "https://gitee.com/fisher52/YueDuJson/raw/master/myTxtChapterRule.json"
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importTocRuleKey)
            ?.splitNotBlank(",")
            ?.toMutableList()
            ?: mutableListOf()
        if (!cacheUrls.contains(defaultUrl)) {
            cacheUrls.add(0, defaultUrl)
        }
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = ImportUrlDialogHelper.createBinding(
                layoutInflater = layoutInflater,
                context = this@TxtTocRuleSelectActivity,
                lifecycleOwner = this@TxtTocRuleSelectActivity,
                cacheUrls = cacheUrls,
                onUrlsChanged = {
                    aCache.put(importTocRuleKey, it.joinToString(","))
                },
                openBrowser = { url ->
                    startActivity<WebViewActivity> {
                        putExtra("url", url)
                    }
                }
            )
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()?.trim()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importTocRuleKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportTxtTocRuleDialog(it))
                }
            }
            cancelButton()
        }
    }

    // ====== Adapter ======
    inner class TocRegexAdapter(context: Context) :
        RecyclerAdapter<TxtTocRule, ItemTocRegexBinding>(context),
        ItemTouchCallback.Callback {

        val diffItemCallBack = object : DiffUtil.ItemCallback<TxtTocRule>() {
            override fun areItemsTheSame(old: TxtTocRule, new: TxtTocRule) = old.id == new.id
            override fun areContentsTheSame(old: TxtTocRule, new: TxtTocRule): Boolean {
                return old.name == new.name && old.enable == new.enable && old.example == new.example
            }
            override fun getChangePayload(old: TxtTocRule, new: TxtTocRule): Any? {
                val payload = Bundle()
                if (old.name != new.name) payload.putBoolean("upName", true)
                if (old.enable != new.enable) payload.putBoolean("enabled", new.enable)
                if (old.example != new.example) payload.putBoolean("upExample", true)
                return if (payload.isEmpty) null else payload
            }
        }

        override fun getViewBinding(parent: ViewGroup) =
            ItemTocRegexBinding.inflate(inflater, parent, false)

        override fun convert(
            holder: ItemViewHolder,
            b: ItemTocRegexBinding,
            item: TxtTocRule,
            payloads: MutableList<Any>
        ) {
            b.apply {
                if (payloads.isEmpty()) {
                    root.setBackgroundColor(this@TxtTocRuleSelectActivity.backgroundColor)
                    rbRegexName.text = item.name
                    titleExample.text = item.example
                    rbRegexName.isChecked = item.name == selectedName
                    swtEnabled.isChecked = item.enable
                    updateCountView(b, item)
                } else {
                    for (i in payloads.indices) {
                        val bundle = payloads[i] as Bundle
                        bundle.keySet().forEach {
                            when (it) {
                                "upName" -> rbRegexName.text = item.name
                                "upExample" -> titleExample.text = item.example
                                "enabled" -> swtEnabled.isChecked = item.enable
                                "upSelect" -> rbRegexName.isChecked = item.name == selectedName
                                "upCount" -> updateCountView(b, item)
                            }
                        }
                    }
                }
            }
        }

        private fun updateCountView(b: ItemTocRegexBinding, item: TxtTocRule) {
            if (bookUrl.isNullOrBlank()) {
                b.tvChapterCount.visibility = View.GONE
                return
            }
            val count = ruleCounts[item.id]
            b.tvChapterCount.apply {
                visibility = View.VISIBLE
                text = when (count) {
                    null, -1 -> "…"       // 尚未计算
                    else -> "${count}章"
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, b: ItemTocRegexBinding) {
            b.apply {
                rbRegexName.setOnUserCheckedChangeListener { isChecked ->
                    if (isChecked) {
                        selectedName = getItem(holder.layoutPosition)?.name
                        updateItems(0, itemCount - 1, bundleOf("upSelect" to null))
                    }
                }
                swtEnabled.setOnUserCheckedChangeListener { isChecked ->
                    getItem(holder.layoutPosition)?.let {
                        it.enable = isChecked
                        viewModel.update(it)
                    }
                }
                ivEdit.setOnClickListener {
                    showDialogFragment(TxtTocRuleEditDialog(getItem(holder.layoutPosition)?.id))
                }
                ivDelete.setOnClickListener {
                    getItem(holder.layoutPosition)?.let { item ->
                        alert(R.string.draw) {
                            setMessage(getString(R.string.sure_del) + "\n" + item.name)
                            noButton()
                            yesButton { viewModel.del(item) }
                        }
                    }
                }
            }
        }

        private var isMoved = false

        override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
            swapItem(srcPosition, targetPosition)
            isMoved = true
            return super.swap(srcPosition, targetPosition)
        }

        override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.onClearView(recyclerView, viewHolder)
            if (isMoved) {
                for ((index, item) in getItems().withIndex()) {
                    item.serialNumber = index + 1
                }
                viewModel.update(*getItems().toTypedArray())
            }
            isMoved = false
        }
    }

    companion object {
        fun parseResult(data: Intent?): String? = data?.getStringExtra("tocRegex")
    }
}