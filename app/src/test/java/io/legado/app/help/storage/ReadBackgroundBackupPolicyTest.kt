package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReadBackgroundBackupPolicyTest {

    @Test
    fun selectingReadConfigAlsoSelectsItsBackgroundPlan() {
        assertEquals(
            setOf("readConfig.json"),
            ReadBackgroundBackupPolicy.selectedReadConfigNames(listOf("readConfig.json"))
        )
        assertEquals(
            setOf("shareReadConfig.json"),
            ReadBackgroundBackupPolicy.selectedReadConfigNames(listOf("readShareConfig.json"))
        )
    }

    @Test
    fun duplicateSourceIsStagedOnlyOnce() {
        val names = ReadBackgroundBackupPolicy.assignBackupNames(
            listOf("/device/bg/page.png", "/device/bg/page.png")
        )
        assertEquals(1, names.size)
        assertEquals("page.png", names.values.single())
    }

    @Test
    fun sameNamesFromDifferentSourcesGetStableUniqueNames() {
        val sources = listOf("/one/page.png", "/two/page.png")
        val first = ReadBackgroundBackupPolicy.assignBackupNames(sources)
        val second = ReadBackgroundBackupPolicy.assignBackupNames(sources.reversed())

        assertEquals(first, second)
        assertEquals(2, first.values.toSet().size)
        assertTrue(first.values.all { it.endsWith(".png") })
        assertNotEquals(
            first[File(sources[0]).absolutePath],
            first[File(sources[1]).absolutePath]
        )
    }

    @Test
    fun newAndLegacyBackgroundNamesResolveFromAvailableFiles() {
        assertEquals(
            "page-abc.png",
            ReadBackgroundBackupPolicy.resolveRestoredName(
                "page-abc.png",
                restoredNames = setOf("page-abc.png"),
                existingNames = emptySet()
            )
        )
        assertEquals(
            "page.png",
            ReadBackgroundBackupPolicy.resolveRestoredName(
                "page.png",
                restoredNames = emptySet(),
                existingNames = setOf("page.png")
            )
        )
    }

    @Test
    fun missingBackgroundDoesNotResolveToInvalidPath() {
        assertNull(
            ReadBackgroundBackupPolicy.resolveRestoredName(
                "missing.png",
                restoredNames = emptySet(),
                existingNames = emptySet()
            )
        )
    }

    @Test
    fun existingBackgroundCanBeKeptWhenBackupAttachmentIsMissing() {
        assertEquals(
            "current.png",
            ReadBackgroundBackupPolicy.resolveRestoredName(
                "current.png",
                restoredNames = emptySet(),
                existingNames = setOf("current.png")
            )
        )
    }

    @Test
    fun fallbackColorsAreNonImageValues() {
        assertEquals("#EEEEEE", ReadBackgroundBackupPolicy.fallbackColor(0))
        assertEquals("#000000", ReadBackgroundBackupPolicy.fallbackColor(1))
        assertEquals("#FFFFFF", ReadBackgroundBackupPolicy.fallbackColor(2))
    }

    @Test
    fun backgroundDirectoryNameIsStableForNewBackups() {
        assertEquals("bg", ReadBackgroundBackupPolicy.backupDirectoryName)
    }
}
