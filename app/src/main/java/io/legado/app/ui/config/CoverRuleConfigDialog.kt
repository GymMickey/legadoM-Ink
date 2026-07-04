package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogCoverRuleConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.BookCover
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

class CoverRuleConfigDialog : BaseDialogFragment(R.layout.dialog_cover_rule_config) {

    val binding by viewBinding(DialogCoverRuleConfigBinding::bind)

    // 保存全屏编辑前的焦点EditText引用
    private var lastFocusedEditText: EditText? = null

    // 全屏编辑结果回调
    private val fullScreenEditResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val text = data?.getStringExtra("text")
            val cursorPosition = data?.getIntExtra("cursorPosition", -1) ?: -1
            lastFocusedEditText?.let { editText ->
                if (text != null) {
                    editText.setText(text)
                }
                if (cursorPosition >= 0) {
                    editText.setSelection(cursorPosition.coerceAtMost(editText.text.length))
                }
                editText.requestFocus()
            }
        }
        lastFocusedEditText = null
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_fullscreen_edit -> {
                    onFullScreenEditClicked()
                    true
                }
                else -> false
            }
        }
        initData()
        binding.tvCancel.onClick {
            dismissAllowingStateLoss()
        }
        binding.tvOk.onClick {
            val enable = binding.cbEnable.isChecked
            val searchUrl = binding.editSearchUrl.text?.toString()
            val coverRule = binding.editCoverUrlRule.text?.toString()
            if (searchUrl.isNullOrBlank() || coverRule.isNullOrBlank()) {
                toastOnUi("搜索url和cover规则不能为空")
            } else {
                BookCover.CoverRule(enable, searchUrl, coverRule).let { config ->
                    BookCover.saveCoverRule(config)
                }
                dismissAllowingStateLoss()
            }
        }
        binding.tvFooterLeft.onClick {
            BookCover.delCoverRule()
            dismissAllowingStateLoss()
        }
    }

    /**
     * 处理全屏编辑按钮点击
     * 将当前光标焦点的文本框内容打开到 CodeEditActivity 进行全屏编辑
     */
    private fun onFullScreenEditClicked() {
        val view = dialog?.window?.decorView?.findFocus()
        if (view is EditText) {
            lastFocusedEditText = view
            val currentText = view.text.toString()
            val intent = Intent(requireContext(), CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("cursorPosition", view.selectionStart)
            }
            fullScreenEditResult.launch(intent)
        } else {
            // 默认编辑封面规则字段
            lastFocusedEditText = binding.editCoverUrlRule
            val currentText = binding.editCoverUrlRule.text.toString()
            val intent = Intent(requireContext(), CodeEditActivity::class.java).apply {
                putExtra("text", currentText)
                putExtra("cursorPosition", binding.editCoverUrlRule.selectionStart)
            }
            fullScreenEditResult.launch(intent)
        }
    }

    private fun initData() {
        lifecycleScope.launch {
            val rule = withContext(IO) {
                BookCover.getCoverRule()
            }
            Log.e("coverRule", GSON.toJson(rule))
            binding.cbEnable.isChecked = rule.enable
            binding.editSearchUrl.setText(rule.searchUrl)
            binding.editCoverUrlRule.setText(rule.coverRule)
        }
    }

}