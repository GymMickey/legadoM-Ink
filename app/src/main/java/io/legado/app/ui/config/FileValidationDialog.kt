package io.legado.app.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.help.storage.BackupInfoHelper
import io.legado.app.help.storage.ValidationResult
import io.legado.app.help.storage.ValidationState
/**
 * 文件选择恢复内容验证对话框
 */
@Composable
fun FileValidationDialog(
    files: List<BackupInfoHelper.BackupFileInfo>,
    validationResults: Map<String, ValidationResult>,
    onValidate: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onInfoClick: (ValidationResult) -> Unit
) {
    val checkedStates = remember { mutableStateMapOf<String, Boolean>().apply { files.forEach { put(it.fileName, true) } } }

    // 计算已选数量和大小
    val selectedCount = files.count { checkedStates[it.fileName] == true }
    val selectedSize = files.filter { checkedStates[it.fileName] == true }.sumOf { it.size }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RectangleShape,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.fvd_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(onClick = onValidate) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.fvd_detect_format))
                        }
                    }
                    Text(
                        text = stringResource(
                            R.string.fvd_selected_info,
                            selectedCount,
                            files.size,
                            BackupInfoHelper.formatSize(selectedSize)
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 56.dp)
                ) {
                    items(files, key = { it.fileName }) { file ->
                        val result = validationResults[file.fileName]
                        FileValidationItem(
                            file = file,
                            isChecked = checkedStates[file.fileName] ?: true,
                            result = result,
                            onCheckedChange = { checked -> checkedStates[file.fileName] = checked },
                            onInfoClick = { result?.let { onInfoClick(it) } }
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            files.forEach { checkedStates[it.fileName] = true }
                        }
                    ) {
                        Text(stringResource(R.string.fvd_select_all))
                    }
                    TextButton(
                        onClick = {
                            files.forEach { checkedStates[it.fileName] = false }
                        }
                    ) {
                        Text(stringResource(R.string.fvd_deselect_all))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val selectedFiles = files.filter { checkedStates[it.fileName] == true }.map { it.fileName }
                        onConfirm(selectedFiles)
                    }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileValidationItem(
    file: BackupInfoHelper.BackupFileInfo,
    isChecked: Boolean,
    result: ValidationResult?,
    onCheckedChange: (Boolean) -> Unit,
    onInfoClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isChecked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isChecked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary
            )
        )

        Text(
            text = "${file.displayName} (${BackupInfoHelper.formatSize(file.size)})",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        )

        when (result?.state) {
            ValidationState.VALID -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = stringResource(R.string.fvd_valid),
                    tint = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
            }
            ValidationState.WARNING -> {
                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = stringResource(R.string.fvd_view_details),
                        tint = androidx.compose.ui.graphics.Color(0xFFFF9800),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = stringResource(R.string.fvd_warning),
                    tint = androidx.compose.ui.graphics.Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
            }
            ValidationState.ERROR -> {
                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = stringResource(R.string.fvd_view_details),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = stringResource(R.string.fvd_invalid),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
            }
            ValidationState.VALIDATING -> {
                Icon(
                    imageVector = Icons.Default.HourglassEmpty,
                    contentDescription = stringResource(R.string.fvd_validating),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
            }
            else -> {}
        }
    }
}

@Composable
fun ValidationErrorDetailDialog(
    result: ValidationResult,
    onDismiss: () -> Unit
) {
    val message = buildAnnotatedString {
        append(result.message)
        if (result.details.isNotBlank()) {
            append("\n\n${result.details}")
        }
        if (result.missingFields.isNotEmpty()) {
            append("\n\n${stringResource(R.string.fvd_missing_fields, result.missingFields.joinToString(", "))}")
        }
        append("\n\n")
        withStyle(SpanStyle(color = androidx.compose.ui.graphics.Color.Unspecified)) {
            if (result.canRestore) {
                append("✅ ")
            } else {
                append("❌ ")
            }
        }
        if (result.canRestore) {
            append(stringResource(R.string.fvd_can_restore))
        } else {
            append(stringResource(R.string.fvd_cannot_restore))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        title = {
            Text(text = result.fileName)
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
