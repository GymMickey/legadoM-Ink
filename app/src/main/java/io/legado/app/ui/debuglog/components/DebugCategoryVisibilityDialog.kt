package io.legado.app.ui.debuglog.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.debug.DebugCategory
// 调试专属日志分类选择器
// 用于选择要显示的调试专属日志分类
// 例如：用户可以选择只显示 `APP` 分类的日志，而忽略 `NETWORK` 分类的日志
@Composable
fun DebugCategoryVisibilityDialog(
    onDismiss: () -> Unit
) {
    var enabled by remember { mutableStateOf(AppConfig.debugLogOnlyEnabled) }
    var selectedCategories by remember { mutableStateOf(AppConfig.debugLogOnlyCategories) }
    val categories = DebugCategory.entries.filter { it != DebugCategory.ALL }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调试专属日志") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "启用调试专属模式",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            AppConfig.debugLogOnlyEnabled = it
                        }
                    )
                }

                HorizontalDivider()

                categories.forEach { category ->
                    val checked = category in selectedCategories
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = category.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = checked,
                            onCheckedChange = {
                                val next = if (it) {
                                    selectedCategories + category
                                } else {
                                    selectedCategories - category
                                }
                                selectedCategories = next
                                AppConfig.debugLogOnlyCategories = next
                            },
                            enabled = enabled
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}
