package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreDataPlanTest {

    @Test
    fun missingAndInvalidFilesNeverCreateReplacementPlan() {
        assertFalse(RestoreDataPlan.shouldReplace(BackupJsonResult.Missing))
        assertFalse(RestoreDataPlan.shouldReplace(BackupJsonResult.Invalid))
    }

    @Test
    fun validEmptyArrayStillCreatesReplacementPlan() {
        assertTrue(RestoreDataPlan.shouldReplace(BackupJsonResult.Valid(emptyList<Any>())))
    }

    @Test
    fun validArrayCreatesReplacementPlan() {
        assertTrue(RestoreDataPlan.shouldReplace(BackupJsonResult.Valid(listOf("item"))))
    }

    @Test
    fun bookGroupMissingFileKeepsCurrentGroups() {
        assertFalse(RestoreDataPlan.shouldReplace(BackupJsonResult.Missing))
    }

    @Test
    fun bookGroupInvalidFileKeepsCurrentGroups() {
        assertFalse(RestoreDataPlan.shouldReplace(BackupJsonResult.Invalid))
    }

    @Test
    fun bookGroupEmptyArrayIsAnExplicitClearPlan() {
        assertTrue(RestoreDataPlan.shouldReplace(BackupJsonResult.Valid(emptyList<Any>())))
    }

    @Test
    fun bookGroupValidArrayIsAReplacementPlan() {
        assertTrue(RestoreDataPlan.shouldReplace(BackupJsonResult.Valid(listOf("group"))))
    }

    @Test
    fun homepageNeedsBothCollectionsToBeValid() {
        assertFalse(
            RestoreDataPlan.shouldReplaceHomepage(
                BackupJsonResult.Valid(listOf<Any>()),
                BackupJsonResult.Invalid
            )
        )
        assertTrue(
            RestoreDataPlan.shouldReplaceHomepage(
                BackupJsonResult.Valid(listOf<Any>()),
                BackupJsonResult.Valid(listOf<Any>())
            )
        )
    }

    @Test
    fun serverOrRuntimeParseFailureNeverCreatesReplacementPlan() {
        assertFalse(RestoreDataPlan.shouldReplace(BackupJsonResult.Invalid))
        assertTrue(RestoreDataPlan.shouldReplace(BackupJsonResult.Valid(emptyList<Any>())))
    }

    @Test
    fun invalidReadRecordFileStopsTheWholeBundle() {
        assertFalse(
            RestoreDataPlan.shouldApplyReadRecordBundle(
                listOf(
                    BackupJsonResult.Valid(listOf<Any>()),
                    BackupJsonResult.Invalid,
                    BackupJsonResult.Missing
                )
            )
        )
    }

    @Test
    fun missingLegacySessionFileStillAllowsRecordsAndDetails() {
        assertTrue(
            RestoreDataPlan.shouldApplyReadRecordBundle(
                listOf(
                    BackupJsonResult.Valid(listOf<Any>()),
                    BackupJsonResult.Valid(listOf<Any>()),
                    BackupJsonResult.Missing
                )
            )
        )
    }

    @Test
    fun selectingOneRecordFileDoesNotSelectOtherTables() {
        assertEquals(
            setOf("readRecordDetail.json"),
            RestoreDataPlan.selectedReadRecordFiles(setOf("readRecordDetail.json"))
        )
    }

    @Test
    fun emptyBundleDoesNotReplaceAnything() {
        assertFalse(RestoreDataPlan.shouldApplyReadRecordBundle(emptyList()))
    }
}
