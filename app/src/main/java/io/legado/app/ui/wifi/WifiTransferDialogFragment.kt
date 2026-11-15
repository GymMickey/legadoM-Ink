package io.legado.app.ui.wifi

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.service.WebService
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * WiFi 传书对话框
 * 替代 HomepageFragment / LocalBookConfigFragment 中的重复 AlertDialog
 * 显示二维码 + 地址 + 剪贴板预览
 */
class WifiTransferDialogFragment : BaseDialogFragment(R.layout.dialog_wifi_transfer) {

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // 首次使用提示：检查默认书籍目录是否已设置
        // 未设置则拦截弹窗，引导用户先去设置
        if (AppConfig.defaultBookTreeUri == null) {
            android.widget.Toast.makeText(
                requireContext(),
                "请先在\"本地书籍管理\"中设置默认书籍目录",
                android.widget.Toast.LENGTH_LONG
            ).show()
            dismiss()
            return
        }

        // 启动 WebService（如果未运行）
        if (!WebService.isRun) {
            WebService.start(requireContext())
        }

        val ip = getLocalIpAddress()
        val port = AppConfig.webPort
        val url = if (ip != null) "http://$ip:$port/wifi/" else null

        // 1. 二维码
        val qrImage = view.findViewById<ImageView>(R.id.iv_qr_code)
        if (url != null) {
            val size = (resources.displayMetrics.density * 80).toInt()
            val bitmap = QRCodeUtils.createQRCode(url, size)
            qrImage.setImageBitmap(bitmap)
        }

        // 2. WiFi 地址
        val urlText = view.findViewById<TextView>(R.id.tv_wifi_url)
        if (url != null) {
            urlText.text = url
        } else {
            urlText.text = getString(R.string.wifi_transfer_msg, "Port: $port (IP unknown)")
        }

        // 上传历史 RecyclerView
        val rvUploadHistory = view.findViewById<RecyclerView>(R.id.rv_upload_history)
        val tvUploadEmpty = view.findViewById<TextView>(R.id.tv_upload_empty)

        if (AppConfig.isEInkMode) {
            rvUploadHistory.itemAnimator = null
        }

        rvUploadHistory.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        val uploadAdapter = UploadHistoryAdapter()
        rvUploadHistory.adapter = uploadAdapter

        // 清空上传历史
        val tvClearUploads = view.findViewById<TextView>(R.id.tv_clear_uploads)
        tvClearUploads.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { appDb.wifiUploadRecordDao.deleteAll() }
                toastOnUi("已清空")
            }
        }

        lifecycleScope.launch {
            appDb.wifiUploadRecordDao.flowAll().collect { records ->
                uploadAdapter.submitList(records)
                val hasData = records.isNotEmpty()
                rvUploadHistory.visibility = if (hasData) View.VISIBLE else View.GONE
                tvUploadEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
            }
        }

        // 3. 剪贴板预览 RecyclerView
        val rvPreview = view.findViewById<RecyclerView>(R.id.rv_clipboard_preview)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_clipboard_empty)

        // E-Ink 模式禁用 RecyclerView 动画
        if (AppConfig.isEInkMode) {
            rvPreview.itemAnimator = null
        }

        rvPreview.layoutManager = LinearLayoutManager(requireContext())

        val adapter = ClipboardAdapter(
            onCopy = { item ->
                requireContext().sendToClip(item.content)
                toastOnUi("已复制")
            },
            onDelete = { item ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { appDb.wifiClipboardDao.delete(item) }
                }
            }
        )
        rvPreview.adapter = adapter

        // 观察数据（取前 5 条预览）
        lifecycleScope.launch {
            appDb.wifiClipboardDao.flowAll().collect { items ->
                val preview = items.take(5)
                adapter.submitList(preview)
                val hasData = preview.isNotEmpty()
                rvPreview.visibility = if (hasData) View.VISIBLE else View.GONE
                tvEmpty.visibility = if (hasData) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                (resources.displayMetrics.heightPixels * 0.75).toInt()
            )
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.find { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}