package io.legado.app.ui.widget.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.storage.BackupSelectorConfig
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment
import io.legado.app.ui.widget.components.dialog.MultiSelectDialogContent
import io.legado.app.ui.widget.components.dialog.MultiSelectGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份选择器对话框 - Compose实现
 * 
 * 功能说明:
 * 提供一个界面让用户选择要备份的项目
 * 显示文件名、大小、数量等详细信息
 */
class BackupSelectorDialog : BaseComposeDialogFragment() {

    @Composable
    override fun DialogContent() {
        BackupSelectorDialogContent(
            onDismiss = { dismiss() }
        )
    }
}

/**
 * 备份选择器对话框内容
 */
@Composable
fun BackupSelectorDialogContent(
    onDismiss: () -> Unit
) {
    // 异步加载分组数据（含文件大小统计，涉及 I/O 操作）
    var groups by remember { mutableStateOf<List<MultiSelectGroup>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BackupSelectorConfig.getMultiSelectGroups()
        }.let { result ->
            groups = result
            isLoading = false
        }
    }

    // 已选中的key集合
    var selectedKeys by remember {
        mutableStateOf(
            BackupSelectorConfig.allItems
                .filter { BackupSelectorConfig.isSelected(it.key) }
                .map { it.key }
                .toSet()
        )
    }

    if (isLoading) {
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
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.large,
                color = pageCardContainerColor()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    MultiSelectDialogContent(
        title = stringResource(R.string.backup_selector),
        groups = groups,
        selectedKeys = selectedKeys,
        totalSizeCalculator = { selectedItems ->
            BackupSelectorConfig.calculateTotalSize(selectedItems)
        },
        onSelectionChange = { key, isSelected ->
            selectedKeys = if (isSelected) {
                selectedKeys + key
            } else {
                selectedKeys - key
            }
            BackupSelectorConfig.setSelected(key, isSelected)
        },
        onDismiss = {
            BackupSelectorConfig.save()
            onDismiss()
        },
        onSelectAll = {
            BackupSelectorConfig.selectAll()
            selectedKeys = BackupSelectorConfig.allItems.map { it.key }.toSet()
        },
        onDeselectAll = {
            BackupSelectorConfig.deselectAll()
            selectedKeys = emptySet()
        }
    )
}
