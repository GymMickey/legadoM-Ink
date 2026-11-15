package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupConfigPreferencePolicyTest {

    @Test
    fun fixedDevicePreferencesAreExcludedAndRestoreIgnoreDoesNotAffectBackup() {
        val fixedDeviceKeys = setOf(
            PreferKey.backupPath,
            PreferKey.defaultBookTreeUri,
            PreferKey.webDavDeviceName
        )
        val restoreIgnore = mutableMapOf(PreferKey.readStyleSelect to false)

        assertFalse(shouldBackupPreferenceKey(PreferKey.backupPath, fixedDeviceKeys))
        assertFalse(shouldBackupPreferenceKey(PreferKey.defaultBookTreeUri, fixedDeviceKeys))
        assertFalse(shouldBackupPreferenceKey(PreferKey.webDavDeviceName, fixedDeviceKeys))
        assertTrue(shouldBackupPreferenceKey(PreferKey.readStyleSelect, fixedDeviceKeys))

        val beforeRestoreChoice = shouldBackupPreferenceKey(PreferKey.readStyleSelect, fixedDeviceKeys)
        restoreIgnore[PreferKey.readStyleSelect] = true
        val afterRestoreChoice = shouldBackupPreferenceKey(PreferKey.readStyleSelect, fixedDeviceKeys)

        assertTrue(beforeRestoreChoice)
        assertTrue(afterRestoreChoice)
    }
}
