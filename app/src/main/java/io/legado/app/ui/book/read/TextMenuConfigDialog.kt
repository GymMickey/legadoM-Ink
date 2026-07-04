package io.legado.app.ui.book.read

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.ui.theme.pageCardContainerColor
import io.legado.app.ui.theme.pageTopBarContainerColor
import io.legado.app.ui.widget.components.dialog.BaseComposeDialogFragment
import io.legado.app.utils.toastOnUi

/**
 * 文本菜单项配置对话框 - Compose实现
 * 
 * 功能说明：
 * 提供一个界面让用户选择要显示/隐藏的文本菜单项
 */
class TextMenuConfigDialog : BaseComposeDialogFragment() {

    @Composable
    override fun DialogContent() {
        TextMenuConfigDialogContent(
            onDismiss = { dismiss() }
        )
    }
}

/**
 * 文本菜单配置对话框内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextMenuConfigDialogContent(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val menuItems = remember { TextMenuConfig.getAllMenuItems() }
    var hiddenIds by remember { 
        mutableStateOf(TextMenuConfig.getHiddenMenuItemIds(context))
    }
    var customTitles by remember {
        mutableStateOf(TextMenuConfig.getCustomMenuTitles(context))
    }
    var visibleCount by remember {
        mutableIntStateOf(TextMenuConfig.getTextMenuVisibleCount(context))
    }
    var editingItem by remember { mutableStateOf<TextMenuConfig.MenuItemInfo?>(null) }
    var showProcessTextConfig by remember { mutableStateOf(false) }
    val topBarColor = pageTopBarContainerColor()
    val cardColor = pageCardContainerColor()

    if (showProcessTextConfig) {
        ProcessTextConfigContent(
            onDismiss = { showProcessTextConfig = false }
        )
    } else {
        editingItem?.let { item ->
            EditMenuTitleDialog(
                title = customTitles[item.id] ?: TextMenuConfig.getDefaultMenuTitle(context, item),
                defaultTitle = TextMenuConfig.getDefaultMenuTitle(context, item),
                onDismiss = { editingItem = null },
                onConfirm = { newTitle ->
                    TextMenuConfig.setCustomMenuTitle(context, item.id, newTitle)
                    customTitles = TextMenuConfig.getCustomMenuTitles(context)
                    editingItem = null
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
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.large,
                color = cardColor
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.text_menu_config),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "关闭"
                                )
                            }
                        },
                        actions = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                IconButton(onClick = { showProcessTextConfig = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "更多选项"
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = topBarColor,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Text(
                        text = stringResource(R.string.text_menu_config_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    TextMenuVisibleCountRow(
                        count = visibleCount,
                        onCountChange = { count ->
                            TextMenuConfig.setTextMenuVisibleCount(context, count)
                            visibleCount = TextMenuConfig.getTextMenuVisibleCount(context)
                        }
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(menuItems) { item ->
                            MenuItemRow(
                                item = item,
                                title = customTitles[item.id]
                                    ?: TextMenuConfig.getDefaultMenuTitle(context, item),
                                isChecked = item.id !in hiddenIds,
                                onEditClick = { editingItem = item },
                                onCheckedChange = { checked ->
                                    val newHiddenIds = hiddenIds.toMutableSet()
                                    if (checked) {
                                        newHiddenIds.remove(item.id)
                                    } else {
                                        newHiddenIds.add(item.id)
                                    }
                                    TextMenuConfig.setHiddenMenuItemIds(context, newHiddenIds)
                                    hiddenIds = newHiddenIds
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                TextMenuConfig.resetToDefault(context)
                                hiddenIds = emptySet()
                                customTitles = emptyMap()
                                visibleCount = TextMenuConfig.DEFAULT_VISIBLE_COUNT
                                context.toastOnUi("已重置为默认配置")
                            }
                        ) {
                            Text(text = stringResource(R.string.reset_to_default))
                        }

                        TextButton(onClick = onDismiss) {
                            Text(text = stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 其他应用菜单配置界面
 */
@Composable
fun TextMenuVisibleCountRow(
    count: Int,
    onCountChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.text_menu_visible_count),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.text_menu_visible_count_desc, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onCountChange(count - 1) },
                enabled = count > TextMenuConfig.MIN_VISIBLE_COUNT
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.reduce)
                )
            }

            Box(
                modifier = Modifier.widthIn(min = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = { onCountChange(count + 1) },
                enabled = count < TextMenuConfig.MAX_VISIBLE_COUNT
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.plus)
                )
            }
        }
    }
}

@Composable
fun EditMenuTitleDialog(
    title: String,
    defaultTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember(title) { mutableStateOf(title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        title = {
            Text(text = stringResource(R.string.text_menu_edit_title))
        },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RectangleShape,
                    label = {
                        Text(text = stringResource(R.string.text_menu_edit_name))
                    },
                    placeholder = {
                        Text(text = defaultTitle)
                    }
                )
                Text(
                    text = stringResource(R.string.text_menu_edit_name_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessTextConfigContent(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hiddenItems by remember { 
        mutableStateOf(TextMenuConfig.getHiddenProcessTextItems(context))
    }
    var customTitles by remember {
        mutableStateOf(TextMenuConfig.getCustomProcessTextTitles(context))
    }
    var editingAppInfo by remember { mutableStateOf<ProcessTextAppInfo?>(null) }
    
    val processTextApps = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getProcessTextApps(context)
        } else {
            emptyList()
        }
    }
    
    val topBarColor = pageTopBarContainerColor()
    val cardColor = pageCardContainerColor()

    editingAppInfo?.let { appInfo ->
        EditMenuTitleDialog(
            title = customTitles[appInfo.key] ?: appInfo.label,
            defaultTitle = appInfo.label,
            onDismiss = { editingAppInfo = null },
            onConfirm = { newTitle ->
                TextMenuConfig.setCustomProcessTextTitle(context, appInfo.key, newTitle)
                customTitles = TextMenuConfig.getCustomProcessTextTitles(context)
                editingAppInfo = null
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
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = cardColor
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.process_text_menu_config),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = topBarColor,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                Text(
                    text = stringResource(R.string.process_text_menu_config_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (processTextApps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_process_text_apps),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                    ) {
                        items(processTextApps) { appInfo ->
                            ProcessTextAppRow(
                                appInfo = appInfo,
                                title = customTitles[appInfo.key] ?: appInfo.label,
                                isChecked = appInfo.key !in hiddenItems,
                                onEditClick = { editingAppInfo = appInfo },
                                onCheckedChange = { checked ->
                                    val newHiddenItems = hiddenItems.toMutableSet()
                                    if (checked) {
                                        newHiddenItems.remove(appInfo.key)
                                    } else {
                                        newHiddenItems.add(appInfo.key)
                                    }
                                    TextMenuConfig.setHiddenProcessTextItems(context, newHiddenItems)
                                    hiddenItems = newHiddenItems
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            TextMenuConfig.resetProcessTextConfig(context)
                            hiddenItems = emptySet()
                            customTitles = emptyMap()
                            context.toastOnUi("已重置为默认配置")
                        }
                    ) {
                        Text(text = stringResource(R.string.reset_to_default))
                    }

                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.close))
                    }
                }
            }
        }
    }
}

/**
 * 其他应用信息
 */
data class ProcessTextAppInfo(
    val key: String,
    val label: String,
    val packageName: String,
    val className: String
)

/**
 * 获取能处理 ACTION_PROCESS_TEXT 的应用列表
 */
@Suppress("DEPRECATION")
private fun getProcessTextApps(context: Context): List<ProcessTextAppInfo> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return emptyList()
    }
    
    val intent = Intent()
        .setAction(Intent.ACTION_PROCESS_TEXT)
        .setType("text/plain")
    
    return try {
        val resolveInfoList = context.packageManager.queryIntentActivities(intent, 0)
        resolveInfoList.map { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            val className = resolveInfo.activityInfo.name
            ProcessTextAppInfo(
                key = TextMenuConfig.getProcessTextItemKey(packageName, className),
                label = resolveInfo.loadLabel(context.packageManager).toString(),
                packageName = packageName,
                className = className
            )
        }.sortedBy { it.label }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * 菜单项行
 */
@Composable
fun MenuItemRow(
    item: TextMenuConfig.MenuItemInfo,
    title: String,
    isChecked: Boolean,
    onEditClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "ID: ${item.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onEditClick) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.text_menu_edit_title)
            )
        }

        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 其他应用菜单项行
 */
@Composable
fun ProcessTextAppRow(
    appInfo: ProcessTextAppInfo,
    title: String,
    isChecked: Boolean,
    onEditClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = appInfo.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onEditClick) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.text_menu_edit_title)
            )
        }

        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange
        )
    }
}
