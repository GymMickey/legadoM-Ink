package io.legado.app.ui.widget.dialog

import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import androidx.core.view.isVisible
import io.legado.app.R
import io.legado.app.databinding.DialogWaitBinding
import io.legado.app.help.config.AppConfig


@Suppress("unused")
class WaitDialog(context: Context) : Dialog(context) {

    val binding = DialogWaitBinding.inflate(layoutInflater)

    init {
        setCanceledOnTouchOutside(false)
        setContentView(binding.root)
        if (AppConfig.isEInkMode) {
            binding.pb.isVisible = false
            binding.root.setBackgroundResource(R.drawable.bg_eink_border_dialog)
        }
    }

    override fun show() {
        super.show()
        if (AppConfig.isEInkMode) {
            window?.run {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                val attr = attributes
                attr.dimAmount = 0f
                attr.windowAnimations = 0
                attributes = attr
                setBackgroundDrawableResource(R.color.transparent)
            }
        }
    }

    fun setText(text: String): WaitDialog {
        binding.tvMsg.text = text
        return this
    }

    fun setText(res: Int): WaitDialog {
        binding.tvMsg.setText(res)
        return this
    }

}