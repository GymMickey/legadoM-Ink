package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFileMappingPolicyTest {

    @Test
    fun selectorDoesNotExposeOrdinaryChapterBackup() {
        assertFalse(BackupFileMappingPolicy.readRecordFileNames.contains("bookChapter.json"))
        assertFalse(BackupFileMappingPolicy.bookCacheFileNames.contains("bookChapter.json"))
    }

    @Test
    fun shareConfigUsesCanonicalNameAndAcceptsLegacyAlias() {
        assertEquals(
            "shareReadConfig.json",
            BackupFileMappingPolicy.canonicalFileName("readShareConfig.json")
        )
        assertEquals(
            setOf("shareReadConfig.json"),
            BackupFileMappingPolicy.expandLogicalSelection(listOf("readShareConfig.json"))
        )
    }

    @Test
    fun readRecordLogicalSelectionExpandsToThreeFiles() {
        assertEquals(
            BackupFileMappingPolicy.readRecordFileNames,
            BackupFileMappingPolicy.expandLogicalSelection(listOf("readRecord"))
        )
    }

    @Test
    fun legacyRecordBackupMayOmitSessionFile() {
        val files = BackupFileMappingPolicy.expandLogicalSelection(
            listOf("readRecord.json", "readRecordDetail.json")
        )
        assertTrue(files.contains("readRecord.json"))
        assertTrue(files.contains("readRecordDetail.json"))
        assertFalse(files.contains("readRecordSession.json"))
    }

    @Test
    fun bookCacheAuxiliaryFilesAreOneLogicalSelection() {
        assertEquals(
            BackupFileMappingPolicy.bookCacheFileNames,
            BackupFileMappingPolicy.expandLogicalSelection(listOf("bookCache"))
        )
    }
}
