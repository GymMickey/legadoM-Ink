package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPreferencePolicyTest {

    @Test
    fun deviceLocalPreferencesAreExcludedFromBackupAndRestore() {
        assertEquals(12, BackupPreferencePolicy.deviceLocalPrefKeys.size)
        BackupPreferencePolicy.deviceLocalPrefKeys.forEach { key ->
            assertFalse(BackupPreferencePolicy.shouldBackupPreference(key))
            assertFalse(BackupPreferencePolicy.shouldRestorePreference(key, emptyMap()))
        }
    }

    @Test
    fun legacyThemeAndPageHValuesAreFilteredFromRestore() {
        val legacyValues = mapOf(
            PreferKey.themeMode to true,
            PreferKey.iReaderPageHEnabled to true,
            PreferKey.iReaderPageHDirection to true,
            PreferKey.iReaderPageHSpeed to true
        )

        legacyValues.keys.forEach { key ->
            assertFalse(BackupPreferencePolicy.shouldRestorePreference(key, legacyValues))
        }
    }

    @Test
    fun userIgnoreOnlyAffectsRestoreFiltering() {
        val ignoredRead = mapOf("readConfig" to true)
        val ignoredTheme = mapOf("themeConfig" to true)

        assertTrue(BackupPreferencePolicy.shouldBackupPreference(PreferKey.readStyleSelect))
        assertFalse(
            BackupPreferencePolicy.shouldRestorePreference(PreferKey.readStyleSelect, ignoredRead)
        )
        assertTrue(BackupPreferencePolicy.shouldBackupPreference(PreferKey.cBackground))
        assertFalse(
            BackupPreferencePolicy.shouldRestorePreference(PreferKey.cBackground, ignoredTheme)
        )
    }

    @Test
    fun ordinaryPreferencesRemainRestorable() {
        val key = PreferKey.userAgent

        assertTrue(BackupPreferencePolicy.shouldBackupPreference(key))
        assertTrue(BackupPreferencePolicy.shouldRestorePreference(key, emptyMap()))
    }

    @Test
    fun legacyThemeModeIgnoreEntryDoesNotShiftVisibleOptions() {
        assertEquals(8, BackupPreferencePolicy.userIgnoreKeys.size)
        assertFalse(PreferKey.themeMode in BackupPreferencePolicy.userIgnoreKeys)
        assertTrue("themeConfig" in BackupPreferencePolicy.userIgnoreKeys)
    }
}
