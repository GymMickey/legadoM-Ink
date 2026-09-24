package io.legado.app.help.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreThemeApplyPolicyTest {
    @Test
    fun `eink theme is not applied immediately`() {
        assertFalse(shouldApplyRestoredThemeImmediately(isEInkMode = true, ignoreThemeConfig = false))
    }

    @Test
    fun `normal and dark themes apply immediately`() {
        assertTrue(shouldApplyRestoredThemeImmediately(isEInkMode = false, ignoreThemeConfig = false))
    }

    @Test
    fun `ignored theme is never applied`() {
        assertFalse(shouldApplyRestoredThemeImmediately(isEInkMode = false, ignoreThemeConfig = true))
        assertFalse(shouldApplyRestoredThemeImmediately(isEInkMode = true, ignoreThemeConfig = true))
    }
}
