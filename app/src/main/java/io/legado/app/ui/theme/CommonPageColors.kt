package io.legado.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import io.legado.app.help.config.AppConfig

@Composable
fun pageTopBarContainerColor(): Color {
    return if (AppConfig.isEInkMode) Color.White else MaterialTheme.colorScheme.secondary
}

@Composable
fun pageCardContainerColor(): Color {
    return if (AppConfig.isEInkMode) Color.White else MaterialTheme.colorScheme.surfaceVariant
}

@Composable
fun pageCardElevatedContainerColor(): Color {
    if (AppConfig.isEInkMode) return Color.White
    val background = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surface, onSurface, 0.06f).copy(alpha = 0.98f)
    } else {
        surface.copy(alpha = 0.95f)
    }
}

@Composable
fun pageHeaderContainerColor(): Color {
    if (AppConfig.isEInkMode) return Color.White
    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surfaceVariant, onSurface, 0.08f).copy(alpha = 0.92f)
    } else {
        surfaceVariant.copy(alpha = 0.7f)
    }
}

@Composable
fun pageSecondaryTextColor(): Color {
    if (AppConfig.isEInkMode) return Color(0xFF4A4A4A)
    val background = MaterialTheme.colorScheme.background
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(onSurfaceVariant, onSurface, 0.32f)
    } else {
        onSurfaceVariant
    }
}

@Composable
fun pageAccentColor(): Color {
    if (AppConfig.isEInkMode) return Color.Black
    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    return if (background.luminance() < 0.18f) {
        lerp(primary, Color.White, 0.2f)
    } else {
        primary
    }
}

@Composable
fun pageSurfaceVariantColor(): Color {
    if (AppConfig.isEInkMode) return Color.White
    val background = MaterialTheme.colorScheme.background
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(surfaceVariant, onSurface, 0.08f)
    } else {
        surfaceVariant
    }
}

@Composable
fun pageMutedIconTint(): Color {
    if (AppConfig.isEInkMode) return Color(0xFF4A4A4A)
    val background = MaterialTheme.colorScheme.background
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    return if (background.luminance() < 0.18f) {
        lerp(onSurfaceVariant, onSurface, 0.24f).copy(alpha = 0.78f)
    } else {
        onSurfaceVariant.copy(alpha = 0.5f)
    }
}
