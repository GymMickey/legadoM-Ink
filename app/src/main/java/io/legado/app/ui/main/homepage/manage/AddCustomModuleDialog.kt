/**
 * 文件：AddCustomModuleDialog.kt
 *
 * 作用：模块添加/编辑对话框，用于创建或编辑自定义首页模块。
 *
 * 主要功能：
 * 1. 提供模块配置的表单输入（标题、URL、类型、参数、布局配置）
 * 2. 支持预填数据，用于编辑已有模块
 * 3. 支持过滤无限流模块类型（当不允许选择时）
 * 4. 确认后通过回调返回模块定义对象
 *
 * 该对话框是首页模块管理中创建和编辑模块的核心交互组件。
 */
package io.legado.app.ui.main.homepage.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.ui.main.homepage.HomepageViewModel

/**
 * 模块添加/编辑对话框
 *
 * 提供表单用于创建或编辑自定义首页模块，包含标题、URL、模块类型、参数和布局配置等字段。
 * 当 [show] 为 false 时直接返回，不渲染任何内容。
 *
 * @param show 是否显示对话框
 * @param prefill 预填的模块定义数据，为 null 时使用默认值，用于编辑模式回填表单
 * @param isEditMode 是否为编辑模式，影响对话框标题显示（"编辑模块"或"添加模块"）
 * @param canSelectInfinite 是否允许选择无限流模块类型，为 false 时过滤掉无限流类型
 * @param onConfirm 确认回调，参数为用户填写的模块定义对象
 * @param onDismiss 取消/关闭对话框的回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCustomModuleDialog(
    show: Boolean,
    prefill: ModuleDef? = null,
    isEditMode: Boolean = false,
    canSelectInfinite: Boolean = true,
    onConfirm: (ModuleDef) -> Unit,
    onDismiss: () -> Unit,
) {
    // 未显示时直接返回，不渲染对话框
    if (!show) return

    // 表单字段状态
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(HomepageModuleType.Grid.key) }
    var args by remember { mutableStateOf("") }
    var layoutConfig by remember { mutableStateOf("") }
    // 模块类型下拉菜单的展开状态
    var typeMenuExpanded by remember { mutableStateOf(false) }

    // 当对话框显示或预填数据变化时，重置表单
    LaunchedEffect(show, prefill) {
        if (show) {
            title = prefill?.title ?: ""
            url = prefill?.url ?: ""
            type = prefill?.type?.ifBlank { HomepageModuleType.Grid.key } ?: HomepageModuleType.Grid.key
            args = prefill?.args ?: ""
            layoutConfig = prefill?.layoutConfig ?: ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // 根据模式显示不同标题
            Text(
                text = if (isEditMode) stringResource(R.string.homepage_edit_module) else stringResource(R.string.homepage_add_module),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 标题输入框
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.homepage_title_label)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // URL 输入框
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.homepage_url)) },
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 模块类型选择：使用 MD3 ExposedDropdownMenuBox
                ExposedDropdownMenuBox(
                    expanded = typeMenuExpanded,
                    onExpandedChange = { typeMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = stringResource(HomepageModuleType.fromKey(type).titleRes),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.homepage_module_type)) },
                        singleLine = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = typeMenuExpanded,
                        onDismissRequest = { typeMenuExpanded = false }
                    ) {
                        HomepageModuleType.entries.forEach { moduleType ->
                            // 跳过未知类型，仅显示有效的模块类型
                            if (moduleType == HomepageModuleType.Unknown) return@forEach
                            // 过滤掉无限流类型（当不允许选择时）
                            val isInfinite = HomepageViewModel.isInfinite(moduleType.key, null)
                            if (isInfinite && !canSelectInfinite) return@forEach
                            DropdownMenuItem(
                                text = { Text(stringResource(moduleType.titleRes)) },
                                onClick = {
                                    type = moduleType.key
                                    typeMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                // 参数输入框
                OutlinedTextField(
                    value = args,
                    onValueChange = { args = it },
                    label = { Text(stringResource(R.string.homepage_args)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                // 布局配置输入框
                OutlinedTextField(
                    value = layoutConfig,
                    onValueChange = { layoutConfig = it },
                    label = { Text(stringResource(R.string.homepage_layout_config)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // 确认按钮：构造模块定义对象并回调
            TextButton(
                onClick = {
                    onConfirm(
                        ModuleDef(
                            key = prefill?.key ?: "",
                            type = type,
                            title = title,
                            args = args.ifBlank { null },
                            layoutConfig = layoutConfig.ifBlank { null },
                            url = url.ifBlank { null },
                            sourceUrl = prefill?.sourceUrl ?: ""
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.homepage_confirm))
            }
        },
        dismissButton = {
            // 取消按钮：关闭对话框
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.homepage_cancel))
            }
        }
    )
}
