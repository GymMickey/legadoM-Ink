package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogUnderlineWidthBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.viewbindingdelegate.viewBinding

class UnderlineWidthDialog : BaseDialogFragment(R.layout.dialog_underline_width) {

    private val binding by viewBinding(DialogUnderlineWidthBinding::bind)

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
        (activity as? ReadBookActivity)?.bottomDialog?.let { it + 1 }
        initView()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as? ReadBookActivity)?.bottomDialog?.let { it - 1 }
    }

    private fun initView() {
        val context = requireContext()
        val bg = context.bottomBackground
        val isLight = ColorUtils.isColorLight(bg)
        val textColor = context.getPrimaryTextColor(isLight)
        val secondaryTextColor = context.getSecondaryTextColor(isLight)

        binding.rootView.setBackgroundColor(bg)
        binding.tvTitle.setTextColor(textColor)
        binding.tvValue.setTextColor(textColor)
        binding.tvOffsetValue.setTextColor(textColor)

        // 下划线粗细：范围 0.5~5.0dp，共10档
        val currentWidth = ReadBookConfig.durConfig.underlineWidth
        binding.tvValue.text = currentWidth.toString()
        binding.seekBar.progress = ((currentWidth / 0.5f) - 1).toInt().coerceIn(0, 9)

        // 下划线距离：范围 0~20dp，共21档
        val currentOffset = ReadBookConfig.durConfig.underlineOffset
        binding.tvOffsetValue.text = currentOffset.toString()
        binding.seekBarOffset.progress = currentOffset.toInt().coerceIn(0, 20)

        val previewView = UnderlinePreviewView(context)
        previewView.setUnderlineOffset(currentOffset)
        (binding.previewView as? ViewGroup)?.addView(previewView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 粗细 SeekBar：progress 0-9 对应 0.5-5.0dp
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val width = (progress + 1) * 0.5f
                binding.tvValue.text = width.toString()
                previewView.setUnderlineWidth(width)
                previewView.invalidate()
                ReadBookConfig.durConfig.underlineWidth = width
                postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 距离 SeekBar：progress 0-20 对应 0-20dp
        binding.seekBarOffset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val offset = progress.toFloat()
                binding.tvOffsetValue.text = offset.toString()
                previewView.setUnderlineOffset(offset)
                previewView.invalidate()
                ReadBookConfig.durConfig.underlineOffset = offset
                postEvent(EventBus.UP_CONFIG, arrayListOf(6, 9, 11))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /**
     * 下划线预览视图，实时显示粗细和距离效果
     */
    private class UnderlinePreviewView(context: android.content.Context) : View(context) {
        private var underlineWidth = 2f   // 下划线粗细(dp)
        private var underlineOffset = 2f  // 下划线距离(dp)
        private val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }

        fun setUnderlineWidth(width: Float) {
            underlineWidth = width
        }

        fun setUnderlineOffset(offset: Float) {
            underlineOffset = offset
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val textPaint = paint
            textPaint.color = ReadBookConfig.textColor
            textPaint.textSize = 36f
            textPaint.style = Paint.Style.FILL
            
            val text = "预览文字效果"
            val textHeight = textPaint.textSize
            val y = height / 2f + textHeight / 3
            canvas.drawText(text, 20f, y, textPaint)

            val underlinePaint = Paint(paint).apply {
                strokeWidth = underlineWidth.dpToPx().toFloat()
                style = Paint.Style.STROKE
            }
            val textWidth = textPaint.measureText(text)
            val lineY = y + underlineOffset.dpToPx()
            canvas.drawLine(20f, lineY, 20f + textWidth, lineY, underlinePaint)
        }
    }
}
