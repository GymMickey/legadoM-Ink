package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.core.view.get
import com.github.liuyueyi.quick.transfer.constants.TransType
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim
import io.legado.app.databinding.DialogReadBookStyleBinding
import io.legado.app.databinding.ItemReadStyleBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.eink.IReaderPageH
import io.legado.app.lib.theme.EInkVisuals
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.getIndexById
import io.legado.app.utils.longToast
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onLongClick

class ReadStyleDialog : BaseDialogFragment(R.layout.dialog_read_book_style),
    FontSelectDialog.CallBack {

    private val binding by viewBinding(DialogReadBookStyleBinding::bind)
    private val callBack get() = activity as? ReadBookActivity
    private lateinit var styleAdapter: StyleAdapter

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(R.color.background)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        (activity as ReadBookActivity).bottomDialog++
        initView()
        initData()
        initViewEvent()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        ReadBookConfig.save()
        (activity as ReadBookActivity).bottomDialog--
    }

    private fun initView() = binding.run {
        val bg = requireContext().bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = requireContext().getPrimaryTextColor(isLight)
        rootView.setBackgroundColor(bg)
        tvPageAnim.setTextColor(textColor)
        tvBgTs.setTextColor(textColor)
        tvShareLayout.setTextColor(textColor)
        dsbTextSize.valueFormat = {
            (it + 5).toString()
        }
        dsbTextLetterSpacing.valueFormat = {
            ((it - 50) / 100f).toString()
        }
        dsbLineSize.valueFormat = { ((it - 10) / 10f).toString() }
        dsbParagraphSpacing.valueFormat = { (it / 10f).toString() }
        styleAdapter = StyleAdapter()
        rvStyle.adapter = styleAdapter
        updatePageHRow()
        styleAdapter.addFooterView {
            ItemReadStyleBinding.inflate(layoutInflater, it, false).apply {
                ivStyle.setPadding(6.dpToPx(), 6.dpToPx(), 6.dpToPx(), 6.dpToPx())
                ivStyle.setText(null)
                ivStyle.setColorFilter(textColor)
                ivStyle.borderColor = textColor
                ivStyle.setImageResource(R.drawable.ic_add)
                root.setOnClickListener {
                    ReadBookConfig.configList.add(ReadBookConfig.Config())
                    showBgTextConfig(ReadBookConfig.configList.lastIndex)
                }
            }
        }
    }

    private fun initData() {
        binding.cbShareLayout.isChecked = ReadBookConfig.shareLayout
        upView()
        styleAdapter.setItems(ReadBookConfig.configList)
    }

    private fun initViewEvent() = binding.run {
        chineseConverter.onChanged {
            ChineseUtils.unLoad(*TransType.entries.toTypedArray())
            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
        }
        textFontWeightConverter.onChanged {
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 9, 6))
        }
        tvTextFont.setOnClickListener {
            showDialogFragment<FontSelectDialog>()
        }
        tvTextIndent.setOnClickListener {
            context?.selector(
                title = getString(R.string.text_indent),
                items = resources.getStringArray(R.array.indent).toList()
            ) { _, index ->
                ReadBookConfig.paragraphIndent = "　".repeat(index)
                postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
            }
        }
        tvPadding.setOnClickListener {
            dismissAllowingStateLoss()
            callBack?.showPaddingConfig()
        }
        tvTip.setOnClickListener {
            TipConfigDialog().show(childFragmentManager, "tipConfigDialog")
        }
        rgPageAnim.setOnCheckedChangeListener { _, checkedId ->
            ReadBook.book?.setPageAnim(-1)
            ReadBookConfig.pageAnim = binding.rgPageAnim.getIndexById(checkedId)
            callBack?.upPageAnim()
            ReadBook.loadContent(false)
            updatePageHRow()
        }
        layoutPageHInfo.setOnClickListener { showPageHOptions() }
        cbShareLayout.setOnCheckedChangeListener { _, isChecked ->
            ReadBookConfig.shareLayout = isChecked
            upView()
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
        }
        dsbTextSize.onChanged = {
            ReadBookConfig.textSize = it + 5
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbTextLetterSpacing.onChanged = {
            ReadBookConfig.letterSpacing = (it - 50) / 100f
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbLineSize.onChanged = {
            ReadBookConfig.lineSpacingExtra = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
        dsbParagraphSpacing.onChanged = {
            ReadBookConfig.paragraphSpacing = it
            postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
        }
    }

    private fun changeBgTextConfig(index: Int) {
        val oldIndex = ReadBookConfig.styleSelect
        if (index != oldIndex) {
            ReadBookConfig.styleSelect = index
            upView()
            styleAdapter.notifyItemChanged(oldIndex)
            styleAdapter.notifyItemChanged(index)
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    private fun showBgTextConfig(index: Int): Boolean {
        dismissAllowingStateLoss()
        changeBgTextConfig(index)
        callBack?.showBgTextConfig()
        return true
    }

    private fun upView() = binding.run {
        textFontWeightConverter.upUi(ReadBookConfig.textBold)
        ReadBook.pageAnim().let {
            if (it >= 0 && it < rgPageAnim.childCount) {
                rgPageAnim.check(rgPageAnim[it].id)
            }
        }
        ReadBookConfig.let {
            dsbTextSize.progress = it.textSize - 5
            dsbTextLetterSpacing.progress = (it.letterSpacing * 100).toInt() + 50
            dsbLineSize.progress = it.lineSpacingExtra
            dsbParagraphSpacing.progress = it.paragraphSpacing
        }
    }

    private fun updatePageHRow(): Unit = binding.run {
        val visible = AppConfig.isEInkMode
            && ReadBook.pageAnim() == PageAnim.noAnim
            && IReaderPageH.isAvailable()
        layoutPageHRow.visibility = if (visible) View.VISIBLE else View.GONE
        switchPageH.setOnCheckedChangeListener(null)
        if (!visible) return@run
        tvPageHSummary.text = getString(
            R.string.ireader_page_h_config_summary,
            pageHDirectionSummary(),
            pageHSpeedSummary()
        )
        switchPageH.isChecked = AppConfig.iReaderPageHEnabled
        switchPageH.setOnCheckedChangeListener { _, isChecked ->
            onPageHSwitchChanged(isChecked)
        }
    }

    private fun onPageHSwitchChanged(isChecked: Boolean) {
        val wasEnabled = AppConfig.iReaderPageHEnabled
        AppConfig.iReaderPageHEnabled = isChecked
        if (!wasEnabled && isChecked) {
            longToast(R.string.ireader_page_h_enabled_tip)
        }
        updatePageHRow()
    }

    private fun pageHDirectionSummary(): String {
        return if (AppConfig.iReaderPageHDirection == 1) {
            getString(R.string.ireader_page_h_direction_reverse_short)
        } else {
            getString(R.string.ireader_page_h_direction_auto_short)
        }
    }

    private fun pageHSpeedSummary(): String {
        return resources.getStringArray(R.array.ireader_page_h_speed_title)
            .getOrElse(AppConfig.iReaderPageHSpeed) {
                getString(R.string.ireader_page_h_speed_medium)
            }
    }

    private fun showPageHOptions() {
        val content = layoutInflater.inflate(R.layout.dialog_ireader_page_h_options, null, false)
        val directionValue = content.findViewById<android.widget.TextView>(R.id.page_h_direction_value)
        val speedValue = content.findViewById<android.widget.TextView>(R.id.page_h_speed_value)

        fun updateValues() {
            directionValue.text = pageHDirectionSummary()
            speedValue.text = pageHSpeedSummary()
            binding.tvPageHSummary.text = getString(
                R.string.ireader_page_h_config_summary,
                pageHDirectionSummary(),
                pageHSpeedSummary()
            )
        }

        updateValues()
        content.findViewById<View>(R.id.page_h_direction_row).setOnClickListener {
            requireContext().selector(
                getString(R.string.ireader_page_h_direction_title),
                resources.getStringArray(R.array.ireader_page_h_direction_title).toList()
            ) { _, index ->
                AppConfig.iReaderPageHDirection = index
                updateValues()
            }
        }
        content.findViewById<View>(R.id.page_h_speed_row).setOnClickListener {
            requireContext().selector(
                getString(R.string.ireader_page_h_speed_title),
                resources.getStringArray(R.array.ireader_page_h_speed_title).toList()
            ) { _, index ->
                AppConfig.iReaderPageHSpeed = index
                updateValues()
            }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.ireader_page_h_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            if (AppConfig.isEInkMode) {
                dialog.window?.run {
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val attr = attributes
                    attr.dimAmount = 0f
                    attr.windowAnimations = 0
                    attributes = attr
                    setBackgroundDrawableResource(R.drawable.bg_eink_border_dialog)
                }
                EInkVisuals.applyDialog(content)
            }
        }
        dialog.show()
    }

    override val curFontPath: String
        get() = ReadBookConfig.textFont

    override fun selectFont(path: String) {
        if (path != ReadBookConfig.textFont || path.isEmpty()) {
            ReadBookConfig.textFont = path
            ReadBookConfig.save()
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 5))
        }
    }

    inner class StyleAdapter :
        RecyclerAdapter<ReadBookConfig.Config, ItemReadStyleBinding>(requireContext()) {

        override fun getViewBinding(parent: ViewGroup): ItemReadStyleBinding {
            return ItemReadStyleBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReadStyleBinding,
            item: ReadBookConfig.Config,
            payloads: MutableList<Any>
        ) {
            binding.apply {
                ivStyle.setText(item.name.ifBlank { "文字" })
                ivStyle.setTextColor(item.curTextColor())
                ivStyle.setImageDrawable(item.curBgDrawable(100, 150))
                if (ReadBookConfig.styleSelect == holder.layoutPosition) {
                    ivStyle.borderColor = accentColor
                    ivStyle.setTextBold(true)
                } else {
                    ivStyle.borderColor = item.curTextColor()
                    ivStyle.setTextBold(false)
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReadStyleBinding) {
            binding.apply {
                ivStyle.setOnClickListener {
                    if (ivStyle.isInView) {
                        changeBgTextConfig(holder.layoutPosition)
                    }
                }
                ivStyle.onLongClick(ivStyle.isInView) {
                    if (ivStyle.isInView) {
                        showBgTextConfig(holder.layoutPosition)
                    }
                }
            }
        }

    }
}
