package io.legado.app.ui.font

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogFontSelectBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.applyTint
import io.legado.app.utils.cnCompare
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.list
import io.legado.app.utils.putPrefString
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 字体选择对话框
 */
class FontSelectDialog : BaseDialogFragment(R.layout.dialog_font_select),
    Toolbar.OnMenuItemClickListener,
    FontAdapter.CallBack {
    private val fontRegex = Regex("(?i).*\\.[ot]tf")
    private val binding by viewBinding(DialogFontSelectBinding::bind)
    private var importJob: Job? = null
    private var waitDialog: WaitDialog? = null
    private val adapter by lazy {
        val curFontPath = callBack?.curFontPath ?: ""
        FontAdapter(requireContext(), curFontPath, this)
    }
    private val selectFontDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                putPrefString(PreferKey.fontFolder, uri.toString())
                val doc = DocumentFile.fromTreeUri(requireContext(), uri)
                if (doc != null) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    RealPathUtil.getPath(requireContext(), uri)?.let { path ->
                        loadFontFilesByPermission(path)
                    }
                }
            } else {
                uri.path?.let { path ->
                    putPrefString(PreferKey.fontFolder, path)
                    loadFontFilesByPermission(path)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        if (AppConfig.isEInkMode) {
            binding.toolBar.popupTheme = R.style.AppTheme_PopupOverlay_EInk
        }
        binding.toolBar.setTitle(R.string.select_font)
        binding.toolBar.inflateMenu(R.menu.font_select)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = adapter

        // App 自己保存的字体不依赖外部目录权限，必须优先显示。
        loadLocalFontFiles()
        val fontPath = getPrefString(PreferKey.fontFolder)
        if (!fontPath.isNullOrEmpty()) {
            if (fontPath.isContentScheme()) {
                val doc = DocumentFile.fromTreeUri(requireContext(), Uri.parse(fontPath))
                if (doc?.canRead() == true) {
                    loadFontFiles(FileDoc.fromDocumentFile(doc))
                } else {
                    putPrefString(PreferKey.fontFolder, "")
                }
            } else {
                loadFontFilesByPermission(fontPath)
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_default -> {
                val requireContext = requireContext()
                alert(titleResource = R.string.system_typeface) {
                    items(
                        requireContext.resources.getStringArray(R.array.system_typefaces).toList()
                    ) { _, i ->
                        AppConfig.systemTypefaces = i
                        onDefaultFontChange()
                        dismissAllowingStateLoss()
                    }
                }
            }
            R.id.menu_other -> {
                openFolder()
            }
            R.id.menu_import_url -> {
                showImportUrlDialog()
            }
        }
        return true
    }

    private fun showImportUrlDialog() {
        if (importJob?.isActive == true) return
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setHint(R.string.font_url_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editView.setSingleLine()
        }
        alert(titleResource = R.string.import_font_from_url) {
            customView { alertBinding.root }
            okButton {
                val url = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (FontImportFileUtils.parseHttpUrl(url) == null) {
                    toastOnUi(R.string.invalid_font_url)
                } else {
                    importFont(url)
                }
            }
            cancelButton()
        }
    }

    private fun importFont(url: String) {
        if (importJob?.isActive == true) return
        binding.toolBar.menu.findItem(R.id.menu_import_url)?.isEnabled = false
        val applicationContext = requireContext().applicationContext
        waitDialog = WaitDialog(requireContext()).apply {
            setText(R.string.importing_font)
            setOnCancelListener { importJob?.cancel() }
            show()
        }
        importJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fontFile = FontUrlImporter.import(applicationContext, url)
                callBack?.selectFont(fontFile.absolutePath)
                toastOnUi(getString(R.string.font_import_success, fontFile.name))
                dismissAllowingStateLoss()
            } catch (e: CancellationException) {
                if (view != null) {
                    toastOnUi(R.string.font_import_cancelled)
                }
                throw e
            } catch (e: FontImportException) {
                AppLog.put("URL 字体导入失败\n${e.failure}", e)
                showImportError(e)
            } finally {
                waitDialog?.dismiss()
                waitDialog = null
                view?.findViewById<Toolbar>(R.id.tool_bar)
                    ?.menu
                    ?.findItem(R.id.menu_import_url)
                    ?.isEnabled = true
                importJob = null
            }
        }
    }

    private fun showImportError(error: FontImportException) {
        val message = when (error.failure) {
            FontImportFailure.INVALID_URL -> getString(R.string.invalid_font_url)
            FontImportFailure.NETWORK -> getString(R.string.font_network_error)
            FontImportFailure.TIMEOUT -> getString(R.string.font_download_timeout)
            FontImportFailure.HTTP -> getString(
                R.string.font_http_error,
                error.httpCode ?: 0
            )
            FontImportFailure.REDIRECT -> getString(R.string.font_redirect_error)
            FontImportFailure.TOO_LARGE -> getString(R.string.font_too_large)
            FontImportFailure.SAVE -> getString(R.string.font_save_failed)
            FontImportFailure.INVALID_FONT -> getString(R.string.font_invalid_file)
        }
        toastOnUi(message)
    }

    private fun openFolder() {
        lifecycleScope.launch {
            val defaultPath = "SD${File.separator}Fonts"
            selectFontDir.launch {
                otherActions = arrayListOf(SelectItem(defaultPath, -1))
            }
        }
    }

    private fun getLocalFonts(): ArrayList<FileDoc> {
        val context = requireContext().applicationContext
        val fontDocs = LinkedHashMap<String, FileDoc>()
        ImportedFontStore.files(context).forEach { file ->
            if (file.name.matches(fontRegex)) {
                fontDocs[file.absolutePath] = FileDoc.fromFile(file)
            }
        }

        ImportedFontStore.directories(context).forEach { directory ->
            directory.listFiles()?.forEach { file ->
                if (file.isFile && file.name.matches(fontRegex)) {
                    fontDocs[file.absolutePath] = FileDoc.fromFile(file)
                }
            }
        }

        val currentPath = callBack?.curFontPath.orEmpty()
        if (currentPath.isNotEmpty()) {
            kotlin.runCatching {
                val currentDoc = if (currentPath.isContentScheme()) {
                    FileDoc.fromFile(currentPath)
                } else {
                    File(currentPath)
                        .takeIf { it.isFile }
                        ?.also { ImportedFontStore.remember(context, listOf(it)) }
                        ?.let(FileDoc::fromFile)
                }
                if (currentDoc != null &&
                    currentDoc.name.matches(fontRegex) &&
                    currentDoc.size > 0L
                ) {
                    fontDocs[currentDoc.toString()] = currentDoc
                }
            }.onFailure {
                AppLog.put("读取当前字体文件失败\n${it.localizedMessage}", it)
            }
        }

        return ArrayList(fontDocs.values)
    }

    private fun loadLocalFontFiles() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fontItems = withContext(Dispatchers.IO) {
                    mergeFontItems(ArrayList(), getLocalFonts())
                }
                adapter.setItems(fontItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("加载 App 字体文件失败\n${e.localizedMessage}", e)
            }
        }
    }

    private fun loadFontFilesByPermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                loadFontFiles(
                    FileDoc.fromFile(File(path))
                )
            }
            .request()
    }

    private fun loadFontFiles(fileDoc: FileDoc) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val fontItems = withContext(Dispatchers.IO) {
                    val folderItems = fileDoc.list {
                        it.name.matches(fontRegex)
                    } ?: ArrayList()
                    mergeFontItems(folderItems, getLocalFonts())
                }
                adapter.setItems(fontItems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("加载字体文件失败\n${e.localizedMessage}", e)
                toastOnUi("getFontFiles:${e.localizedMessage}")
            }
        }
    }

    private fun mergeFontItems(
        items1: ArrayList<FileDoc>,
        items2: ArrayList<FileDoc>
    ): List<FileDoc> {
        val items = ArrayList(items1)
        items2.forEach { item2 ->
            var isInFirst = false
            items1.forEach for1@{ item1 ->
                if (item2.name == item1.name) {
                    isInFirst = true
                    return@for1
                }
            }
            if (!isInFirst) {
                items.add(item2)
            }
        }
        return items.sortedWith { o1, o2 ->
            o1.name.cnCompare(o2.name)
        }
    }

    override fun onFontSelect(docItem: FileDoc) {
        execute {
            callBack?.selectFont(docItem.toString())
        }.onSuccess {
            dismissAllowingStateLoss()
        }
    }

    override fun canDeleteFont(docItem: FileDoc): Boolean {
        val file = docItem.asFile() ?: return false
        return ImportedFontStore.isManaged(requireContext(), file)
    }

    override fun onFontDelete(docItem: FileDoc) {
        alert(
            getString(R.string.delete_imported_font),
            getString(R.string.delete_imported_font_confirm, docItem.name)
        ) {
            yesButton { deleteImportedFont(docItem) }
            noButton()
        }
    }

    private fun deleteImportedFont(docItem: FileDoc) {
        val file = docItem.asFile() ?: return
        val applicationContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    ImportedFontStore.delete(applicationContext, file)
                }.getOrDefault(false)
            }
            if (deleted) {
                if (file.absolutePath == callBack?.curFontPath) {
                    callBack?.selectFont("")
                }
                loadLocalFontFiles()
                toastOnUi(R.string.font_delete_success)
            } else {
                toastOnUi(R.string.font_delete_failed)
            }
        }
    }

    private fun onDefaultFontChange() {
        callBack?.selectFont("")
    }

    private val callBack: CallBack?
        get() = (parentFragment as? CallBack) ?: (activity as? CallBack)

    override fun onDestroyView() {
        importJob?.cancel()
        waitDialog?.dismiss()
        waitDialog = null
        super.onDestroyView()
    }

    interface CallBack {
        fun selectFont(path: String)
        val curFontPath: String
    }
}
