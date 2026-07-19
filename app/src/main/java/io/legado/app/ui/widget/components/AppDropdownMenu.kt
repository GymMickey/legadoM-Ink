package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.help.config.AppConfig

/**
 * E-Ink 适配的下拉菜单组件。
 * E-Ink 模式：使用 Popup + Surface 静态显示（无 fade+scale 动画）
 * 普通模式：直接代理到 Material3 DropdownMenu
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    if (AppConfig.isEInkMode) {
        if (expanded) {
            val density = LocalDensity.current
            val pixelOffset = with(density) {
                IntOffset(offset.x.roundToPx(), offset.y.roundToPx())
            }
            Popup(
                onDismissRequest = onDismissRequest,
                offset = pixelOffset,
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    modifier = modifier,
                    shape = MaterialTheme.shapes.extraSmall,
                    shadowElevation = 0.dp,
                    color = containerColor
                ) {
                    Column {
                        content()
                    }
                }
            }
        }
    } else {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            offset = offset,
            containerColor = containerColor,
            content = content
        )
    }
}