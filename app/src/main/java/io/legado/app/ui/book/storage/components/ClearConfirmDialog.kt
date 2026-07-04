package io.legado.app.ui.book.storage.components

import androidx.compose.runtime.Composable
import io.legado.app.ui.widget.components.dialog.AppConfirmDialog

// ### UI组件
// 7. ClearConfirmDialog.kt

// - 作用 ：清理确认对话框组件
// - 主要功能 ：
//   - ClearConfirmDialog - 单个缓存清理确认
//   - ClearAllConfirmDialog - 一键清理确认

@Composable
fun ClearConfirmDialog(
    targetName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppConfirmDialog(
        title = "确认清理",
        text = "确定要清理 \"$targetName\" 吗？此操作不可撤销。",
        confirmText = "确定",
        destructive = true,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss
    )
}

@Composable
fun ClearAllConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AppConfirmDialog(
        title = "一键清理",
        text = "确定要清理所有缓存吗？\n\n注意：这不会清理数据库中的书籍、书源等重要数据，仅清理临时缓存文件。",
        confirmText = "确定",
        destructive = true,
        onConfirm = onConfirm,
        onDismissRequest = onDismiss
    )
}
