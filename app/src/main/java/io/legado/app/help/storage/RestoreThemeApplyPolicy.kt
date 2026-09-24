package io.legado.app.help.storage

/** Whether restore should immediately apply the saved theme to the current activity. */
internal fun shouldApplyRestoredThemeImmediately(
    isEInkMode: Boolean,
    ignoreThemeConfig: Boolean
): Boolean = !isEInkMode && !ignoreThemeConfig
