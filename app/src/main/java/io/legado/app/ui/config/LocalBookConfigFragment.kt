package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.model.localBook.AutoImportManager
import io.legado.app.service.WebService
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.QRCodeUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.first
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalBookConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
            upPreferenceSummary(PreferKey.defaultBookTreeUri, treeUri.toString())
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_local_book)
        AppConfig.defaultBookTreeUri?.let {
            upPreferenceSummary(PreferKey.defaultBookTreeUri, it)
        }
        updateLastScanTime()
        updateLocalBookCount()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.local_book_manage)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.defaultBookTreeUri -> localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }
            "scanNow" -> doScanNow()
            "startWifiTransfer" -> showWifiTransferDialog()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.defaultBookTreeUri -> {
                upPreferenceSummary(key, AppConfig.defaultBookTreeUri)
            }
        }
    }

    private fun doScanNow() {
        Coroutine.async {
            AutoImportManager.scanAndImport(appCtx)
        }.onSuccess { count ->
            val msg = when {
                count < 0 -> getString(R.string.default_book_tree_uri_not_set)
                count == 0 -> getString(R.string.scan_local_no_new_books)
                else -> getString(R.string.scan_local_done, count)
            }
            toastOnUi(msg)
            appCtx.getSharedPreferences("auto_scan", 0)
                .edit().putLong("last_auto_scan_time", System.currentTimeMillis()).apply()
            updateLastScanTime()
            updateLocalBookCount()
        }
    }

    private fun updateLastScanTime() {
        val pref = findPreference<Preference>("lastScanTime") ?: return
        val lastTime = appCtx.getSharedPreferences("auto_scan", 0)
            .getLong("last_auto_scan_time", 0L)
        pref.summary = if (lastTime > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastTime))
        } else {
            getString(R.string.never)
        }
    }

    private fun updateLocalBookCount() {
        val pref = findPreference<Preference>("localBookCount") ?: return
        Coroutine.async {
            appDb.bookDao.flowLocal().first().size
        }.onSuccess { count ->
            pref.summary = getString(R.string.local_book_count_summary, count)
        }
    }

    private fun showWifiTransferDialog() {
        if (!WebService.isRun) {
            WebService.start(requireContext())
        }
        val ip = getLocalIpAddress()
        val port = AppConfig.webPort
        val url = if (ip != null) "http://$ip:$port/wifi/" else null

        requireContext().alert(titleResource = R.string.wifi_transfer_title) {
            if (url != null) {
                val ctx = requireContext()
                val imageView = android.widget.ImageView(ctx).apply {
                    val size = (resources.displayMetrics.density * 200).toInt()
                    val bitmap = QRCodeUtils.createQRCode(url, size)
                    setImageBitmap(bitmap)
                    val padding = (resources.displayMetrics.density * 16).toInt()
                    setPadding(padding, padding, padding, 0)
                    adjustViewBounds = true
                }
                val layout = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    addView(imageView)
                    val textView = android.widget.TextView(ctx).apply {
                        text = url
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                        val tp = (resources.displayMetrics.density * 8).toInt()
                        setPadding(0, tp, 0, 0)
                    }
                    addView(textView)
                }
                customView { layout }
            } else {
                setMessage("Port: $port (IP unknown)")
            }
            okButton { }
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.find { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        preference.summary = value
    }
}
