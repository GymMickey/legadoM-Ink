package io.legado.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.utils.ColorUtils

@Composable
fun LegadoTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val isNightTheme = AppConfig.isNightTheme
    val primaryColorValue = ThemeStore.primaryColor(context)
    val accentColor = ThemeStore.accentColor(context)
    val bgColor = ThemeStore.backgroundColor(context)
    val textPrimaryColor = ThemeStore.textColorPrimary(context)
    val textSecondaryColor = ThemeStore.textColorSecondary(context)

    val isEInk = AppConfig.isEInkMode
    val isLight = isEInk || (!isNightTheme && ColorUtils.isColorLight(bgColor))
    val background = if (isEInk) Color.White else Color(bgColor)
    val primary = if (isEInk) Color.Black else Color(accentColor)
    val secondary = if (isEInk) Color.White else Color(primaryColorValue)
    val onBackground = if (isEInk) Color.Black else Color(textPrimaryColor)
    val onBackgroundVariant = if (isEInk) Color(0xFF4A4A4A) else Color(textSecondaryColor)

    val surface = if (isEInk) {
        Color.White
    } else {
        lerp(background, if (isLight) Color.White else Color.Black, if (isLight) 0.04f else 0.10f)
    }
    val surfaceVariant = if (isEInk) Color.White else lerp(background, onBackground, if (isLight) 0.05f else 0.14f)
    val outline = if (isEInk) Color(0xFF666666) else lerp(background, onBackground, if (isLight) 0.12f else 0.24f)
    val onSurfaceVariant = onBackgroundVariant
    val outlineVariant = if (isEInk) Color(0xFF666666) else outline.copy(alpha = if (isLight) 0.75f else 0.8f)

    val colorScheme = if (isLight) {
        lightColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = secondary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            onPrimary = if (ColorUtils.isColorLight(accentColor)) Color.Black else Color.White,
            onSecondary = if (isEInk) Color.Black
            else if (ColorUtils.isColorLight(primaryColorValue)) Color.Black else Color.White,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFE53935),
            onError = Color.White
        )
    } else {
        darkColorScheme(
            primary = primary,
            secondary = secondary,
            tertiary = secondary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = surfaceVariant,
            tertiaryContainer = surfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            onPrimary = if (ColorUtils.isColorLight(accentColor)) Color.Black else Color.White,
            onSecondary = if (isEInk) Color.Black
            else if (ColorUtils.isColorLight(primaryColorValue)) Color.Black else Color.White,
            onBackground = onBackground,
            onSurface = onBackground,
            onSurfaceVariant = onSurfaceVariant,
            error = Color(0xFFFF5252),
            onError = Color.Black
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        content()
    }
}
