package io.legado.app.ui.widget.dialog

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.DialogFragment
import io.legado.app.help.CustomHelpDoc
import io.legado.app.help.CustomHelpDocGroup
import io.legado.app.help.CustomHelpDocManager
import io.legado.app.help.HelpDocManager
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog
import io.legado.app.utils.toastOnUi

class CustomHelpDocManageDialog : DialogFragment() {

    var onChanged: (() -> Unit)? = null
    private var pendingImportGroupPath: String? = null
    private var refreshContent: (() -> Unit)? = null
    private lateinit var importFileLauncher: ActivityResultLauncher<Intent>
    private lateinit var importFolderLauncher: ActivityResultLauncher<Uri?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importFileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uris = data?.clipData?.toUriList()
                ?: data?.data?.let { listOf(it) }
                ?: emptyList()
            if (uris.isNotEmpty()) {
                importSelectedDocs(uris)
            } else {
                pendingImportGroupPath = null
            }
        }
        importFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                val modeFlags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { requireContext().contentResolver.takePersistableUriPermission(it, modeFlags) }
                importSelectedDocs(listOf(it))
            } ?: run { pendingImportGroupPath = null }
        }
    }

    private fun importSelectedDocs(uris: List<Uri>) {
        val groupPath = pendingImportGroupPath ?: return
        val result = uris.map { uri ->
            CustomHelpDocManager.importSelectedResult(requireContext(), groupPath, uri)
        }.mergeImportResults()
        if (result.importedCount > 0) {
            HelpDocManager.refreshCustomGroups(requireContext())
            onChanged?.invoke()
            refreshContent?.invoke()
        }
        requireContext().toastOnUi(result.message())
        pendingImportGroupPath = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                LegadoTheme {
                    CustomHelpDocManageContent(
                        onDismiss = { dismissAllowingStateLoss() },
                        onRefreshContentChanged = { refreshContent = it },
                        onImportDoc = { group ->
                            pendingImportGroupPath = group.folderPath
                        },
                        onCancelImport = {
                            pendingImportGroupPath = null
                        },
                        onSelectFile = {
                            importFileLauncher.launch(
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            )
                        },
                        onSelectFolder = {
                            importFolderLauncher.launch(null)
                        },
                        onChanged = {
                            HelpDocManager.refreshCustomGroups(requireContext())
                            onChanged?.invoke()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomHelpDocManageContent(
    onDismiss: () -> Unit,
    onRefreshContentChanged: ((() -> Unit)?) -> Unit,
    onImportDoc: (CustomHelpDocGroup) -> Unit,
    onCancelImport: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectFolder: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf(CustomHelpDocManager.scanCustomDocs(context, true)) }
    var selectedGroupPath by remember { mutableStateOf<String?>(null) }
    var inputDialog by remember { mutableStateOf<CustomHelpDocInput?>(null) }
    var deleteDialog by remember { mutableStateOf<CustomHelpDocDelete?>(null) }
    var showAddDocDialog by remember { mutableStateOf(false) }
    val selectedGroup = selectedGroupPath?.let { path ->
        groups.firstOrNull { it.folderPath == path }
    }

    fun refresh() {
        groups = CustomHelpDocManager.scanCustomDocs(context, true)
        if (selectedGroupPath != null && groups.none { it.folderPath == selectedGroupPath }) {
            selectedGroupPath = null
        }
        onChanged()
    }

    DisposableEffect(Unit) {
        onRefreshContentChanged { refresh() }
        onDispose { onRefreshContentChanged(null) }
    }

    inputDialog?.let { dialog ->
        NameInputDialog(
            title = dialog.title,
            initialName = dialog.initialName,
            onDismiss = { inputDialog = null },
            onConfirm = { name ->
                val success = dialog.onConfirm(name)
                context.toastOnUi(if (success) "已保存" else "保存失败")
                if (success) {
                    inputDialog = null
                    refresh()
                }
            }
        )
    }

    deleteDialog?.let { dialog ->
        DeleteConfirmDialog(
            title = dialog.title,
            message = dialog.message,
            onDismiss = { deleteDialog = null },
            onConfirm = {
                val success = dialog.onConfirm()
                context.toastOnUi(if (success) "已删除" else "删除失败")
                if (success) {
                    deleteDialog = null
                    refresh()
                }
            }
        )
    }

    if (showAddDocDialog) {
        AddDocDialog(
            onDismiss = {
                showAddDocDialog = false
                onCancelImport()
            },
            onSelectFile = {
                showAddDocDialog = false
                onSelectFile()
            },
            onSelectFolder = {
                showAddDocDialog = false
                onSelectFolder()
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = selectedGroup?.displayName ?: "自定义分组",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (selectedGroupPath == null) {
                                    onDismiss()
                                } else {
                                    selectedGroupPath = null
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (selectedGroupPath == null) "关闭" else "返回"
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                selectedGroup?.let { group ->
                                    onImportDoc(group)
                                    showAddDocDialog = true
                                } ?: run {
                                    inputDialog = CustomHelpDocInput("添加分组") { name ->
                                        CustomHelpDocManager.createGroup(context, name)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = if (selectedGroup == null) "添加分组" else "添加文件"
                            )
                        }
                    }
                )
                HorizontalDivider()
                if (selectedGroup == null) {
                    GroupList(
                        groups = groups,
                        onOpen = { selectedGroupPath = it.folderPath },
                        onRename = { group ->
                            inputDialog = CustomHelpDocInput("修改分组名", group.displayName) { name ->
                                CustomHelpDocManager.renameGroup(group.folderPath, name)
                            }
                        },
                        onDelete = { group ->
                            deleteDialog = CustomHelpDocDelete(
                                title = "删除分组",
                                message = "确定删除“${group.displayName}”及其中全部文件？"
                            ) {
                                CustomHelpDocManager.deleteGroup(group.folderPath)
                            }
                        }
                    )
                } else {
                    DocList(
                        group = selectedGroup,
                        onRename = { doc ->
                            inputDialog = CustomHelpDocInput("修改文件名", doc.displayName) { name ->
                                CustomHelpDocManager.renameDoc(doc.filePath, name)
                            }
                        },
                        onDelete = { doc ->
                            deleteDialog = CustomHelpDocDelete(
                                title = "删除文件",
                                message = "确定删除“${doc.getFullFileName()}”？"
                            ) {
                                CustomHelpDocManager.deleteDoc(doc.filePath)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<CustomHelpDocGroup>,
    onOpen: (CustomHelpDocGroup) -> Unit,
    onRename: (CustomHelpDocGroup) -> Unit,
    onDelete: (CustomHelpDocGroup) -> Unit
) {
    LazyColumn(modifier = Modifier.heightIn(min = 320.dp, max = 560.dp)) {
        items(groups, key = { it.folderPath }) { group ->
            ListItem(
                leadingContent = {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                },
                headlineContent = {
                    Text(
                        text = group.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = { Text("${group.docs.size} 个文件") },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onRename(group) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "修改分组名")
                        }
                        IconButton(onClick = { onDelete(group) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除分组")
                        }
                        IconButton(onClick = { onOpen(group) }) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "进入分组")
                        }
                    }
                },
                modifier = Modifier.clickable { onOpen(group) }
            )
            HorizontalDivider()
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    text = "还没有自定义分组",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DocList(
    group: CustomHelpDocGroup,
    onRename: (CustomHelpDoc) -> Unit,
    onDelete: (CustomHelpDoc) -> Unit
) {
    LazyColumn(modifier = Modifier.heightIn(min = 320.dp, max = 560.dp)) {
        items(group.docs, key = { it.filePath }) { doc ->
            ListItem(
                leadingContent = {
                    Icon(Icons.Filled.Description, contentDescription = null)
                },
                headlineContent = {
                    Text(
                        text = doc.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                supportingContent = { Text(doc.getFullFileName()) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { onRename(doc) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "修改文件名")
                        }
                        IconButton(onClick = { onDelete(doc) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除文件")
                        }
                    }
                }
            )
            HorizontalDivider()
        }
        if (group.docs.isEmpty()) {
            item {
                Text(
                    text = "这个分组还没有文件",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AddDocDialog(
    onDismiss: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectFolder: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加文件") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("选择文件") },
                    supportingContent = { Text("可一次选择多个 md/txt 文件") },
                    modifier = Modifier.clickable(onClick = onSelectFile)
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("选择文件夹") },
                    supportingContent = { Text("导入文件夹内的 md/txt 文件") },
                    modifier = Modifier.clickable(onClick = onSelectFolder)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun NameInputDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim()) }
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AppConfirmDialog(
        title = title,
        text = message,
        confirmText = "删除",
        destructive = true,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss
    )
}

private fun ClipData.toUriList(): List<Uri> {
    return buildList {
        for (index in 0 until itemCount) {
            getItemAt(index)?.uri?.let { add(it) }
        }
    }
}

private fun List<CustomHelpDocManager.CustomHelpDocImportResult>.mergeImportResults():
        CustomHelpDocManager.CustomHelpDocImportResult {
    return CustomHelpDocManager.CustomHelpDocImportResult(
        importedCount = sumOf { it.importedCount },
        skippedReasons = flatMap { it.skippedReasons },
        failedReasons = flatMap { it.failedReasons }
    )
}

private data class CustomHelpDocInput(
    val title: String,
    val initialName: String = "",
    val onConfirm: (String) -> Boolean
)

private data class CustomHelpDocDelete(
    val title: String,
    val message: String,
    val onConfirm: () -> Boolean
)
