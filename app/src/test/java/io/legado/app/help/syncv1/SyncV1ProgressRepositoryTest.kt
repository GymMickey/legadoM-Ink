package io.legado.app.help.syncv1

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

class SyncV1ProgressRepositoryTest {
    private val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val epoch = "33333333-3333-4333-8333-333333333333"
    private val sessionId = "44444444-4444-4444-8444-444444444444"
    private val eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val secondEventId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val publisherId = "11111111-1111-4111-8111-111111111111"
    private val snapshot = "88888888-8888-4888-8888-888888888881"
    private val bookUrl = "https://books.example/book/1"
    private val service = "service-A"
    private val root = "root-A"
    private val now = 1_800_000_000_000L

    @Test
    fun firstStateUsesOnlyConfirmedChapterSnapshotAndPublishesCatalogBaseline() = runBlocking {
        val h = harness().apply { store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章", 2L to "第二章")) }
        val result = h.repository.initialize(listOf(input(index = 2, title = "第二章", hasProgress = true)))

        assertEquals(SyncV1ProgressPhase.PUBLISHED, result.phase)
        assertEquals(1, result.initialStatesPublished)
        val state = requireNotNull(h.store.progressState(service, root, workId))
        assertEquals(1L, state.progressRevision)
        assertEquals(7L, state.tocRevision)
        assertEquals(2L, state.localTocIndex)
        assertEquals("第二章", state.chapterTitle)
        assertNull(state.sourceEventId)
        assertEquals(publisherId, state.updatedByDeviceId)
        assertEquals("chapter", state.locatorConfidence)
        assertNull(state.visibleCharOffset)
        assertNull(state.visibleCharCount)
        assertNull(state.chapterTextFingerprint)
        assertEquals(h.session.configIdentity, h.store.pendingIntents.single().configIdentity)
        assertEquals(SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 1), h.store.progressCatalogValue(service, root, workId))
        assertEquals("available", h.gateway.latest!!.works.single().progressBaselineStatus)
        assertEquals(1, h.gateway.publishCount)
        assertEquals(
            listOf(
                "legadom_ink_sync/v1/android/",
                "legadom_ink_sync/v1/android/progress/",
                "legadom_ink_sync/v1/android/progress/$workId/"
            ),
            h.transport.ensurePaths
        )
        assertEquals(2, h.transport.getPaths.count { it == state.remotePath })
    }

    @Test
    fun bothLegacyUnsafeFlagIdentitiesResumeTheSamePendingProgressIntent() = runBlocking {
        val rootUrl = "https://dav.example/sync/"
        val newIdentity = SyncV1ConfigIdentity.current(rootUrl, "account", "password")
        val acceptedIdentities = SyncV1ConfigIdentity.acceptedForRecovery(rootUrl, "account", "password")
        val legacyIdentities = acceptedIdentities - newIdentity
        assertEquals(2, legacyIdentities.size)

        for (legacyIdentity in legacyIdentities) {
            val h = harness()
            val compatibleSession = h.session.copy(
                configIdentity = newIdentity,
                compatibleConfigIdentities = acceptedIdentities
            )
            val saved = state(toc = 7, revision = 4, title = "第二章", index = 2)
            val intent = SyncV1ProgressPublicationIntent(
                workId = workId,
                tocRevision = saved.tocRevision,
                progressRevision = saved.progressRevision,
                snapshotId = UUID.fromString(saved.snapshotId),
                remotePath = saved.remotePath,
                rawJsonBytes = saved.rawJsonBytes,
                rawSha256 = saved.rawSha256,
                putAttemptCount = 1,
                configIdentity = legacyIdentity,
                serviceIdentity = service,
                rootIdentity = root,
                sourceEventId = null,
                createMode = SYNC_V1_CREATE_MODE,
                publicationStatus = "pending",
                createdAt = now - 100,
                updatedAt = now - 50
            )
            h.store.pendingIntents += intent
            h.transport.remote[intent.remotePath] = intent.rawJsonBytes.copyOf()

            val outcome = h.repository.initialize(emptyList(), sessionOverride = compatibleSession)

            assertTrue("legacy identity should be resumed", outcome.phase != SyncV1ProgressPhase.FAILED)
            val persisted = h.store.pendingIntents.single()
            assertEquals(legacyIdentity, persisted.configIdentity)
            assertEquals(intent.putAttemptCount, persisted.putAttemptCount)
            assertEquals(intent.progressRevision, persisted.progressRevision)
            assertEquals(intent.snapshotId, persisted.snapshotId)
            assertEquals(intent.remotePath, persisted.remotePath)
            assertArrayEquals(intent.rawJsonBytes, persisted.rawJsonBytes)
            assertEquals(intent.rawSha256, persisted.rawSha256)
            assertTrue(h.transport.putPaths.isEmpty())
            assertEquals(intent.rawJsonBytes.toList(), h.transport.remote[intent.remotePath]!!.toList())
        }
    }

    @Test
    fun compatibleIdentityCandidatesAreBoundToCurrentEndpointAndCredentials() {
        val rootUrl = "https://dav.example/sync/"
        val accepted = SyncV1ConfigIdentity.acceptedForRecovery(rootUrl, "account", "password")
        val current = SyncV1ConfigIdentity.current(rootUrl, "account", "password")
        val changedConfigurations = listOf(
            SyncV1ConfigIdentity.acceptedForRecovery("https://other.example/sync/", "account", "password"),
            SyncV1ConfigIdentity.acceptedForRecovery("https://dav.example/other-root/", "account", "password"),
            SyncV1ConfigIdentity.acceptedForRecovery(rootUrl, "other-account", "password"),
            SyncV1ConfigIdentity.acceptedForRecovery(rootUrl, "account", "other-password")
        )

        assertEquals(3, accepted.size)
        assertTrue(current in accepted)
        changedConfigurations.forEach { changed -> assertTrue(accepted.intersect(changed).isEmpty()) }
    }

    @Test
    fun unavailableRevisionZeroWorksDoNotCausePerWorkRemoteRequestsOrDirectories() = runBlocking {
        val h = harness()
        val inputs = (0 until 185).map { index ->
            val url = "https://books.example/work/$index"
            val id = UUID.randomUUID().toString()
            h.store.workIdByUrl[url] = id
            SyncV1ProgressWorkInput(url, "Book $index", "Author", 0, null, false)
        }
        h.gateway.latest = ParsedCatalog(
            publisherId, 4, snapshot, Instant.ofEpochMilli(now).toString(),
            inputs.map { input ->
                val id = requireNotNull(h.store.workIdByUrl[input.bookUrl])
                ParsedCatalogWork(id, input.title, listOf(input.author), emptyList(), 0, 0,
                    Instant.ofEpochMilli(now).toString(), "unavailable")
            }
        )

        val result = h.repository.initialize(inputs)

        assertEquals(185, result.works)
        assertEquals(185, result.workOutcomes.size)
        assertTrue(result.workOutcomes.all { it.baseline == SyncV1ProgressBaseline.UNAVAILABLE })
        assertTrue(h.transport.listPaths.isEmpty())
        assertTrue(h.transport.ensurePaths.isEmpty())
        assertTrue(h.transport.remote.isEmpty())
    }

    @Test
    fun catalogAvailableRevisionCanRecoverRemoteStateWithoutCreatingDirectories() = runBlocking {
        val h = harness()
        val remoteState = state(toc = 7, revision = 1, title = "第一章", index = 0)
        h.gateway.latest = catalog(revision = 4, progressRevision = 1, baseline = "available")
        h.transport.remote[remoteState.remotePath] = remoteState.rawJsonBytes.copyOf()

        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false)))

        assertEquals(SyncV1ProgressBaseline.AVAILABLE, result.workOutcomes.single().baseline)
        assertEquals(1L, h.store.progressState(service, root, workId)?.progressRevision)
        assertEquals(listOf("legadom_ink_sync/v1/android/progress/$workId/"), h.transport.listPaths)
        assertTrue(h.transport.ensurePaths.isEmpty())
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
    }

    @Test
    fun missingRemoteProgressDirectoryIsNotCreatedAndDoesNotBecomeServerFailure() = runBlocking {
        val h = harness().apply {
            gateway.latest = catalog(revision = 4, progressRevision = 1, baseline = "available")
        }

        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false)))

        assertEquals(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING, result.workOutcomes.single().errorCode)
        assertFalse(result.workOutcomes.single().errorCode == SyncV1ErrorCode.HTTP_SERVER_ERROR)
        assertEquals(listOf("legadom_ink_sync/v1/android/progress/$workId/"), h.transport.listPaths)
        assertTrue(h.transport.ensurePaths.isEmpty())
    }

    @Test
    fun batchEnsuresSharedParentsOnceBeforeCreatingEachWorkDirectory() = runBlocking {
        val h = harness()
        val secondUrl = "https://books.example/book/second"
        val secondWorkId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        h.store.workIdByUrl[secondUrl] = secondWorkId
        h.store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章"))
        h.store.chapters[scope(service, root, secondWorkId)] = chapterState(7, listOf(0L to "第一章"), forWorkId = secondWorkId)
        val second = SyncV1ProgressWorkInput(secondUrl, "Second", "Author", 0, "第一章", true)

        val result = h.repository.initialize(listOf(input(index = 0, title = "第一章", hasProgress = true), second))

        assertEquals(2, result.initialStatesPublished)
        assertEquals(
            listOf(
                "legadom_ink_sync/v1/android/",
                "legadom_ink_sync/v1/android/progress/",
                "legadom_ink_sync/v1/android/progress/$workId/",
                "legadom_ink_sync/v1/android/progress/$secondWorkId/"
            ),
            h.transport.ensurePaths
        )
    }

    @Test
    fun defaultFieldsAndUnmappablePositionRemainUnavailableWithoutRevisionZeroFile() = runBlocking {
        val h = harness().apply { store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章")) }
        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false)))

        assertEquals(SyncV1ProgressBaseline.UNAVAILABLE, result.workOutcomes.single().baseline)
        assertEquals(0, result.initialStatesPublished)
        assertNull(h.store.progressState(service, root, workId))
        assertTrue(h.transport.remote.keys.none { it.contains("/0-") })
        assertEquals("unavailable", h.gateway.latest!!.works.single().progressBaselineStatus)
    }

    @Test
    fun authoritativeNeverReadIsTheOnlyZeroBaselineEvidence() = runBlocking {
        val h = harness()
        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false, neverRead = true)))

        assertEquals(SyncV1ProgressBaseline.ZERO_CONFIRMED, result.workOutcomes.single().baseline)
        assertEquals(0L, result.workOutcomes.single().progressRevision)
        assertNull(h.store.progressState(service, root, workId))
        assertTrue(h.transport.remote.keys.none { it.contains("/0-") })
        assertEquals("zero_confirmed", h.gateway.latest!!.works.single().progressBaselineStatus)
    }

    @Test
    fun remotelyConfirmedZeroBaselineSurvivesLocalDatabaseReconstruction() = runBlocking {
        val h = harness().apply { gateway.latest = catalog(3, 0, "zero_confirmed") }
        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false)))

        assertEquals(SyncV1ProgressBaseline.ZERO_CONFIRMED, result.workOutcomes.single().baseline)
        assertEquals(0, h.transport.putPaths.count { it.contains("/android/progress/") })
        assertEquals(SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.ZERO_CONFIRMED, 0), h.store.progressCatalogValue(service, root, workId))
    }

    @Test
    fun catalogAvailableWithoutItsStateCannotFallBackToNewRevisionOne() = runBlocking {
        val h = harness().apply { gateway.latest = catalog(3, 4, "available") }
        val result = h.repository.initialize(listOf(input(index = 0, title = "第一章", hasProgress = true)))

        assertEquals(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING, result.workOutcomes.single().errorCode)
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
    }

    @Test
    fun titleAtFirstChapterPositionZeroIsNotMistakenForNeverRead() = runBlocking {
        val h = harness().apply { store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章")) }
        val result = h.repository.initialize(listOf(input(index = 0, title = "第一章", hasProgress = true)))

        assertEquals(SyncV1ProgressBaseline.AVAILABLE, result.workOutcomes.single().baseline)
        assertEquals(1, result.initialStatesPublished)
        assertEquals("第一章", h.store.progressState(service, root, workId)?.chapterTitle)
    }

    @Test
    fun missingOrMismatchedConfirmedChapterDoesNotInventAnIndex() = runBlocking {
        val h = harness().apply { store.chapters[scope(service, root, workId)] = chapterState(7, listOf(1L to "第一章")) }
        val result = h.repository.initialize(listOf(input(index = 0, title = "不同章节", hasProgress = true)))

        assertEquals(SyncV1ProgressBaseline.UNAVAILABLE, result.workOutcomes.single().baseline)
        assertNull(h.store.progressState(service, root, workId))
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
    }

    @Test
    fun negativeLocalIndexIsNotClampedToFirstChapter() = runBlocking {
        val h = harness().apply { store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章")) }

        val result = h.repository.initialize(listOf(input(index = -1, title = "第一章", hasProgress = true)))

        assertEquals(SyncV1ProgressBaseline.UNAVAILABLE, result.workOutcomes.single().baseline)
        assertNull(h.store.progressState(service, root, workId))
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
    }

    @Test
    fun sameIndexAndTitleAllowsSafeTocReprojectionOnly() = runBlocking {
        val h = harness().apply {
            store.chapters[scope(service, root, workId)] = chapterState(8, listOf(0L to "第一章"))
            val previous = state(toc = 7, revision = 4, title = "第一章", index = 0)
            store.seedState(service, root, previous)
            transport.remote[previous.remotePath] = previous.rawJsonBytes
            store.baselines[scope(service, root, workId)] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 4)
        }
        val result = h.repository.initialize(listOf(input(index = 0, title = "第一章", hasProgress = false)))

        val saved = requireNotNull(h.store.progressState(service, root, workId))
        assertEquals(5L, saved.progressRevision)
        assertEquals(8L, saved.tocRevision)
        assertNull(saved.sourceEventId)
        assertEquals(publisherId, saved.updatedByDeviceId)
        assertFalse(result.workOutcomes.single().progressTocStale)
    }

    @Test
    fun unsafeTocChangeRetainsOldStateAndReportsStale() = runBlocking {
        val h = harness().apply {
            store.chapters[scope(service, root, workId)] = chapterState(8, listOf(0L to "改名後"))
            val previous = state(toc = 7, revision = 4, title = "第一章", index = 0)
            store.seedState(service, root, previous)
            transport.remote[previous.remotePath] = previous.rawJsonBytes
            store.baselines[scope(service, root, workId)] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 4)
        }
        val result = h.repository.initialize(listOf(input(index = 0, title = null, hasProgress = false)))

        assertEquals(4L, h.store.progressState(service, root, workId)!!.progressRevision)
        assertTrue(result.workOutcomes.single().progressTocStale)
        assertEquals(0, h.transport.putPaths.count { it.contains("/android/progress/") })
    }

    @Test
    fun orphanHigherRemoteStateBlocksRevisionAllocation() = runBlocking {
        val h = harness().apply {
            store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章"))
            val orphan = state(toc = 7, revision = 4, title = "第一章", index = 0)
            transport.remote[orphan.remotePath] = orphan.rawJsonBytes
            gateway.latest = catalog(progressRevision = 0, baseline = "unavailable")
        }
        val result = h.repository.initialize(
            listOf(input(index = 0, title = "第一章", hasProgress = true)),
            allowRemoteRecovery = true
        )

        assertEquals(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING, result.workOutcomes.single().errorCode)
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
        assertEquals(4L, h.transport.remote.keys.mapNotNull(::revisionFromPath).maxOrNull())
    }

    @Test
    fun appliedProgressPublishesStateThenCatalogThenAckAndReplayIsIdempotent() = runBlocking {
        val h = eventHarness()
        val first = h.repository.processEvents(listOf(catalogBook()), h.session)
        val statePath = h.transport.putPaths.single { it.contains("/android/progress/") }
        val ackPath = h.transport.putPaths.single { it.contains("/acks/") }
        val state = requireNotNull(SyncV1ProgressProtocol.parseState(h.transport.remote.getValue(statePath)))

        assertEquals(1, first.applied)
        assertEquals(2L, state.progressRevision)
        assertEquals(eventId, state.sourceEventId)
        assertEquals(deviceId, state.updatedByDeviceId)
        assertTrue(h.gateway.lastProgressOverride?.get(workId) == SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 2))
        assertEquals(SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 2), h.store.progressCatalogValue(service, root, workId))
        assertTrue(h.transport.remote.containsKey(ackPath))
        assertEquals(listOf("put_state", "catalog", "put_ack"), h.order.filter { it in setOf("put_state", "catalog", "put_ack") })

        val putCount = h.transport.putPaths.size
        val catalogCount = h.gateway.publishCount
        val replay = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(0, replay.applied)
        assertEquals(putCount, h.transport.putPaths.size)
        assertEquals(catalogCount, h.gateway.publishCount)
        assertEquals(1, h.store.ledger.size)
    }

    @Test
    fun staleTocTakesPrecedenceAndAckReferencesOnlyConfirmedCurrentToc() = runBlocking {
        val h = eventHarness()
        h.transport.remote[eventPath(eventId)] = progressEvent(basedOnToc = 6, basedOnProgress = 0)

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.staleToc)
        assertEquals(0, result.staleProgress)
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
        val ack = com.google.gson.JsonParser.parseString(
            String(h.transport.remote.getValue("legadom_ink_sync/v1/acks/$deviceId/$eventId.json"))
        ).asJsonObject
        assertEquals(7L, ack.get("current_toc_revision").asLong)
        assertFalse(ack.has("current_progress_revision"))
    }

    @Test
    fun staleProgressAckReferencesConfirmedCurrentProgressRevision() = runBlocking {
        val h = eventHarness()
        h.transport.remote[eventPath(eventId)] = progressEvent(basedOnProgress = 0)

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.staleProgress)
        val ack = com.google.gson.JsonParser.parseString(
            String(h.transport.remote.getValue("legadom_ink_sync/v1/acks/$deviceId/$eventId.json"))
        ).asJsonObject
        assertEquals(1L, ack.get("current_progress_revision").asLong)
        assertFalse(ack.has("current_toc_revision"))
    }

    @Test
    fun zeroConfirmedBaselineAcceptsRevisionZeroEventAndCreatesRevisionOne() = runBlocking {
        val h = eventHarness(SyncV1ProgressBaseline.ZERO_CONFIRMED, seedState = false)
        h.transport.remote[eventPath(eventId)] = progressEvent(basedOnProgress = 0)

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.applied)
        assertEquals(1L, h.store.progressState(service, root, workId)?.progressRevision)
        assertEquals(SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 1),
            h.store.progressCatalogValue(service, root, workId))
    }

    @Test
    fun sequenceGapDoesNotBlockValidProgressEvent() = runBlocking {
        val h = eventHarness()
        h.transport.remote[eventPath(eventId)] = progressEvent(sequence = 987_654)

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.applied)
        assertEquals(987_654L, (SyncV1ProgressProtocol.parseEvent(deviceId, eventId, h.store.ledger.getValue(eventId))
            as SyncV1ProgressEventParseResult.Valid).event.sequence)
    }

    @Test
    fun sameEventIdWithDifferentBytesConflictsWithoutChangingOriginalReceiptOrState() = runBlocking {
        val h = eventHarness()
        h.repository.processEvents(listOf(catalogBook()), h.session)
        val originalReceipt = requireNotNull(h.store.progressReceipt(eventId))
        val statePuts = h.transport.putPaths.count { it.contains("/android/progress/") }
        h.transport.remote[eventPath(eventId)] = progressEvent(sequence = 44)

        val replay = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, replay.eventIdConflicts)
        assertSame(originalReceipt, h.store.progressReceipt(eventId))
        assertEquals(statePuts, h.transport.putPaths.count { it.contains("/android/progress/") })
    }

    @Test
    fun chapterContextMismatchIsRejectedWithoutApplyingProgress() = runBlocking {
        val h = eventHarness()
        h.transport.remote[eventPath(eventId)] = progressEvent(nextTitle = "不是下一章")

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.rejected)
        assertTrue(h.transport.putPaths.none { it.contains("/android/progress/") })
        assertEquals("rejected", h.store.progressReceipt(eventId)?.terminalStatus)
    }

    @Test
    fun verifiedCharacterCapabilityAllowsEstimatedLocatorFields() = runBlocking {
        val h = eventHarness().apply { store.locatorCapability = "character_exact" }
        val registration = registrationBytes("character_exact")
        h.transport.remote[registrationPath()] = registration
        h.store.registrationSha = SyncV1Protocol.sha256(registration)
        h.transport.remote[eventPath(eventId)] = progressEvent(
            confidence = "estimated", offset = 4, count = 20, fingerprint = "d".repeat(64)
        )

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.applied)
        val saved = requireNotNull(h.store.progressState(service, root, workId))
        assertEquals("estimated", saved.locatorConfidence)
        assertEquals(4L, saved.visibleCharOffset)
        assertEquals("d".repeat(64), saved.chapterTextFingerprint)
    }

    @Test
    fun failedStatePublicationIsPendingAndDoesNotPublishAck() = runBlocking {
        val h = eventHarness()
        h.transport.failPutPaths += progressPath(2, snapshot)

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.applied)
        assertEquals(1, result.stateIntentsPending)
        assertEquals(0, result.catalogIntentsPending)
        assertEquals(0, result.acknowledgementsPending)
        assertTrue(h.transport.putPaths.none { it.contains("/acks/") })
        assertEquals("projection_pending", h.store.progressReceipt(eventId)?.projectionStage)
    }

    @Test
    fun projectionPendingBlocksAnotherEventForSameWorkUntilRecovery() = runBlocking {
        val h = eventHarness()
        h.gateway.failNextPublish = true
        h.transport.remote[eventPath(secondEventId)] = progressEvent(
            eventId = secondEventId, sequence = 44, basedOnProgress = 2,
            chapterTitle = "第二章", localIndex = 1
        )

        val firstPass = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, firstPass.applied)
        assertEquals(1, firstPass.catalogIntentsPending)
        assertEquals(1, firstPass.eventsPending)
        assertEquals(1, h.store.ledger.size)
        assertNull(h.store.progressReceipt(secondEventId))

        val recovered = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(1, recovered.applied)
        assertEquals(2, h.store.ledger.size)
        assertEquals("ack_published", h.store.progressReceipt(eventId)?.projectionStage)
        assertEquals("ack_published", h.store.progressReceipt(secondEventId)?.projectionStage)
    }

    @Test
    fun A5LeavesExistingA4ReceiptAndAckUntouched() = runBlocking {
        val h = eventHarness()
        val ack = SyncV1ProgressProtocol.ackBytes(deviceId, eventId, workId, "e".repeat(64),
            "bound", "2026-01-02T03:04:05Z")
        val a4Receipt = SyncV1ImportReceipt(
            eventId, deviceId, "binding_request", "e".repeat(64), epoch, 42, "content-id",
            "Example", "Author", 1, emptyList(), "bound", null, "none", workId, emptyList(),
            "2026-01-02T03:04:05Z", ack, SyncV1Protocol.sha256(ack), service, root
        )
        h.store.importReceipts[eventId] = a4Receipt

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.eventIdConflicts)
        assertSame(a4Receipt, h.store.importReceipt(eventId))
        assertNull(h.store.progressReceipt(eventId))
        assertTrue(h.transport.putPaths.none { it.contains("/acks/") || it.contains("/android/progress/") })
        assertTrue(h.store.ledger.isEmpty())
    }

    @Test
    fun unavailableBaselineAndNonProgressEventsRemainUnprocessed() = runBlocking {
        val h = eventHarness(baseline = SyncV1ProgressBaseline.UNAVAILABLE)
        val result = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(0, result.applied)
        assertEquals(1, result.eventsPending)
        assertEquals(0, result.acknowledgementsPending)
        assertNull(h.store.progressReceipt(eventId))
        assertTrue(h.transport.putPaths.none { it.contains("/acks/") })

        h.transport.remote[eventPath(eventId)] = """{"event_type":"reading_time","event_id":"$eventId"}""".toByteArray()
        val reading = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(0, reading.scannedEvents)
        assertNull(h.store.progressReceipt(eventId))
    }

    @Test
    fun catalogFailureLeavesProjectionPendingAndRestartResumesWithoutReapplying() = runBlocking {
        val h = eventHarness()
        h.gateway.failNextPublish = true
        val failed = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(1, failed.applied)
        assertEquals(1, h.store.ledger.size)
        assertTrue(h.store.hasPendingProgressProjection(service, root, workId))
        assertEquals(1, failed.catalogIntentsPending)
        assertEquals(0, failed.acknowledgementsPending)
        assertTrue(h.transport.putPaths.none { it.contains("/acks/") })
        val savedBytes = h.store.progressIntentForEvent(eventId, service, root)!!.rawJsonBytes.copyOf()

        val resumed = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(1, resumed.scannedEvents)
        assertEquals(1, h.store.ledger.size)
        assertFalse(h.store.hasPendingProgressProjection(service, root, workId))
        assertArrayEquals(savedBytes, h.transport.remote["legadom_ink_sync/v1/android/progress/$workId/2-${h.store.progressState(service, root, workId)!!.snapshotId}.json"])
        assertTrue(h.transport.putPaths.any { it.contains("/acks/") })
    }

    @Test
    fun ambiguousAckPutRecoversByGetWithoutSecondPut() = runBlocking {
        val h = eventHarness()
        h.transport.storeThenThrowPaths += "legadom_ink_sync/v1/acks/$deviceId/$eventId.json"
        val result = h.repository.processEvents(listOf(catalogBook()), h.session)
        assertEquals(1, result.applied)
        assertEquals(1, h.transport.putPaths.count { it.contains("/acks/") })
        assertEquals("ack_published", h.store.progressReceipt(eventId)?.projectionStage)
    }

    @Test
    fun failedAckPublicationIsReportedSeparatelyFromAppliedAndProjection() = runBlocking {
        val h = eventHarness()
        h.transport.failPutPaths += "legadom_ink_sync/v1/acks/$deviceId/$eventId.json"

        val result = h.repository.processEvents(listOf(catalogBook()), h.session)

        assertEquals(1, result.applied)
        assertEquals(0, result.eventsPending)
        assertEquals(0, result.stateIntentsPending)
        assertEquals(0, result.catalogIntentsPending)
        assertEquals(1, result.acknowledgementsPending)
        assertEquals("ack_pending", h.store.progressReceipt(eventId)?.projectionStage)
        assertEquals(2, h.transport.putPaths.count { it.contains("/acks/") })
    }

    @Test
    fun rootChangeDoesNotReusePreviousBaselineOrState() = runBlocking {
        val h = harness()
        val chapter = chapterState(7, listOf(0L to "第一章"))
        h.store.chapters[scope(service, root, workId)] = chapter
        h.store.chapters[scope(service, "root-B", workId)] = chapterState(7, listOf(0L to "第一章"), rootIdentity = "root-B")
        h.store.seedState(service, root, state(toc = 7, revision = 3, title = "第一章", index = 0))
        h.store.baselines[scope(service, root, workId)] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, 3)
        val newSession = SyncV1Session("config-B", service, "root-B", h.transport) { true }
        val result = h.repository.initialize(listOf(input(index = 0, title = "第一章", hasProgress = true)), newSession)

        assertEquals(1L, h.store.progressState(service, "root-B", workId)!!.progressRevision)
        assertEquals(3L, h.store.progressState(service, root, workId)!!.progressRevision)
        assertEquals(1L, h.store.progressCatalogValue(service, "root-B", workId).progressRevision)
        assertEquals(SyncV1ProgressPhase.PUBLISHED, result.phase)
    }

    private fun harness(): Harness {
        val order = mutableListOf<String>()
        val store = MemoryStore(now, order)
        val transport = MemoryTransport(order)
        val gateway = MemoryCatalogGateway(store, order)
        val session = SyncV1Session("config-A", service, root, transport) { true }
        return Harness(store, transport, gateway, session, order,
            SyncV1ProgressRepository(store, store, store, store, gateway, { session }, { now }, { UUID.fromString(snapshot) }))
    }

    private fun eventHarness(
        baseline: SyncV1ProgressBaseline = SyncV1ProgressBaseline.AVAILABLE,
        seedState: Boolean = true
    ): Harness {
        val h = harness()
        h.store.bindings += Triple(deviceId, epoch, workId)
        h.store.baselines[scope(service, root, workId)] = SyncV1ProgressCatalogValue(baseline, if (baseline == SyncV1ProgressBaseline.AVAILABLE) 1 else 0)
        if (seedState) h.store.seedState(service, root, state(toc = 7, revision = 1, title = "第一章", index = 0))
        h.store.chapters[scope(service, root, workId)] = chapterState(7, listOf(0L to "第一章", 1L to "第二章"))
        h.gateway.latest = catalog(1, if (baseline == SyncV1ProgressBaseline.AVAILABLE) 1 else 0, baseline.wireValue)
        h.transport.remote[registrationPath()] = registrationBytes()
        h.store.registrationSha = SyncV1Protocol.sha256(registrationBytes())
        h.transport.remote[eventPath(eventId)] = progressEvent()
        return h
    }

    private fun input(index: Long, title: String?, hasProgress: Boolean, neverRead: Boolean = false, url: String = bookUrl) =
        SyncV1ProgressWorkInput(url, "Example", "Author", index, title, hasProgress, neverRead)

    private fun catalogBook() = SyncV1CatalogBook(bookUrl, "Example", "Author")

    private fun state(toc: Long, revision: Long, title: String, index: Long, source: String? = null): SyncV1ProgressState {
        val bytes = SyncV1ProgressProtocol.stateBytes(workId, toc, revision, snapshot, title, index, source,
            if (source == null) publisherId else deviceId, Instant.ofEpochMilli(now).toString())
        return SyncV1ProgressState(workId, toc, revision, snapshot, title, index, null, null, null, null,
            "chapter", source, if (source == null) publisherId else deviceId, Instant.ofEpochMilli(now).toString(),
            bytes, SyncV1Protocol.sha256(bytes), progressPath(revision, snapshot), service, root)
    }

    private fun chapterState(
        revision: Long,
        chapters: List<Pair<Long, String>>,
        rootIdentity: String = root,
        forWorkId: String = workId
    ): SyncV1ChapterPublicationState {
        val id = "99999999-9999-4999-8999-999999999999"
        val json = JsonObject().apply {
            addProperty("protocol_version", "sync-v1"); addProperty("schema_version", "1.0.0")
            addProperty("device_id", publisherId); addProperty("work_id", forWorkId); addProperty("toc_revision", revision)
            addProperty("snapshot_id", id); addProperty("generated_at", "2026-01-02T03:04:05Z")
            add("chapters", JsonArray().apply {
                chapters.forEach { (index, title) -> add(JsonObject().apply {
                    addProperty("chapter_key", SyncV1Protocol.chapterKey(forWorkId, index, title))
                    addProperty("title", title); addProperty("local_toc_index", index)
                }) }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        return SyncV1ChapterPublicationState(forWorkId, revision, UUID.fromString(id),
            "legadom_ink_sync/v1/android/chapters/$forWorkId/$revision-$id.json", json,
            SyncV1Protocol.sha256(json), "a".repeat(64), service, rootIdentity, now)
    }

    private fun catalog(revision: Long = 1, progressRevision: Long = 1, baseline: String = "available") = ParsedCatalog(
        publisherId, revision, snapshot, "2026-01-02T03:04:05Z",
        listOf(ParsedCatalogWork(workId, "Example", listOf("Author"), emptyList(), 7, progressRevision,
            Instant.ofEpochMilli(now).toString(), baseline))
    )

    private fun progressEvent(
        eventId: String = this.eventId,
        sequence: Long = 43,
        basedOnToc: Long = 7,
        basedOnProgress: Long = 1,
        chapterTitle: String = "第一章",
        localIndex: Long = 0,
        previousTitle: String? = null,
        nextTitle: String? = null,
        confidence: String = "chapter",
        offset: Long? = null,
        count: Long? = null,
        fingerprint: String? = null
    ): ByteArray = JsonObject().apply {
        addProperty("protocol_version", "sync-v1"); addProperty("schema_version", "1.0.0")
        addProperty("normalization_version", "normalization-v1"); addProperty("event_type", "progress")
        addProperty("event_id", eventId); addProperty("device_id", deviceId); addProperty("counter_epoch", epoch)
        addProperty("session_id", sessionId); addProperty("sequence", sequence); addProperty("created_at", "2026-01-02T03:04:05Z")
        addProperty("work_id", workId); addProperty("based_on_toc_revision", basedOnToc); addProperty("based_on_progress_revision", basedOnProgress)
        addProperty("chapter_title", chapterTitle); addProperty("local_toc_index", localIndex)
        if (offset == null) add("visible_char_offset", com.google.gson.JsonNull.INSTANCE) else addProperty("visible_char_offset", offset)
        if (count == null) add("visible_char_count", com.google.gson.JsonNull.INSTANCE) else addProperty("visible_char_count", count)
        add("chapter_fraction_ppm", com.google.gson.JsonNull.INSTANCE); addProperty("chapter_fraction_is_estimated", true)
        if (fingerprint == null) add("chapter_text_fingerprint", com.google.gson.JsonNull.INSTANCE) else addProperty("chapter_text_fingerprint", fingerprint)
        addProperty("reported_locator_confidence", confidence)
        if (previousTitle != null) addProperty("previous_chapter_title", previousTitle)
        if (nextTitle != null) addProperty("next_chapter_title", nextTitle)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun registrationBytes(locatorCapability: String = "chapter_only") = """{"protocol_version":"sync-v1","schema_version":"1.0.0","normalization_version":"normalization-v1","device_id":"$deviceId","created_at":"2026-01-02T03:04:05Z","client":{"kind":"koreader","name":"KOReader","version":"2026.11"},"counter_epoch":"$epoch","supported_create_modes":["uuid_verified","conditional_create"],"locator_capability":"$locatorCapability"}""".toByteArray()
    private fun registrationPath() = "legadom_ink_sync/v1/devices/$deviceId.json"
    private fun eventPath(id: String) = "legadom_ink_sync/v1/events/$deviceId/$id.json"
    private fun progressPath(revision: Long, id: String) = "legadom_ink_sync/v1/android/progress/$workId/$revision-$id.json"
    private fun revisionFromPath(path: String): Long? = Regex("/(\\d+)-[0-9a-f-]+\\.json$").find(path)?.groupValues?.get(1)?.toLongOrNull()
    private fun scope(service: String, root: String, work: String) = "$service|$root|$work"

    private data class Harness(
        val store: MemoryStore,
        val transport: MemoryTransport,
        val gateway: MemoryCatalogGateway,
        val session: SyncV1Session,
        val order: MutableList<String>,
        val repository: SyncV1ProgressRepository
    )

    private class MemoryTransport(private val order: MutableList<String>) : SyncV1WebDavTransport {
        val remote = linkedMapOf<String, ByteArray>()
        val putPaths = mutableListOf<String>()
        val getPaths = mutableListOf<String>()
        val listPaths = mutableListOf<String>()
        val ensurePaths = mutableListOf<String>()
        val storeThenThrowPaths = mutableSetOf<String>()
        val failPutPaths = mutableSetOf<String>()
        private val directories = mutableSetOf("legadom_ink_sync/v1/")
        override suspend fun ensureDirectory(relativePath: String) {
            ensurePaths += relativePath
            val parent = relativePath.trimEnd('/').substringBeforeLast('/', "")
                .let { if (it.isEmpty()) "" else "$it/" }
            if (parent !in directories) throw SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
            directories += relativePath
        }
        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> {
            listPaths += relativePath
            val exists = relativePath in directories || remote.keys.any { it.startsWith(relativePath) }
            if (!exists && relativePath.startsWith("legadom_ink_sync/v1/android/progress/")) {
                throw SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
            }
            return remote.keys
                .filter { it.startsWith(relativePath) }
                .mapNotNull { path -> path.removePrefix(relativePath).takeIf { it.isNotEmpty() && '/' !in it } }
                .map { SyncV1RemoteEntry(it, false) }
        }
        override suspend fun get(relativePath: String, maxBytes: Int, computeFullSha256: Boolean): SyncV1HttpResponse {
            getPaths += relativePath
            val bytes = remote[relativePath] ?: return SyncV1HttpResponse(404)
            if (bytes.size > maxBytes) return SyncV1HttpResponse(200, bodyTooLarge = true)
            return SyncV1HttpResponse(200, bytes.copyOf(), rawSha256 = if (computeFullSha256) SyncV1Protocol.sha256(bytes) else null)
        }
        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            putPaths += relativePath
            when {
                relativePath in failPutPaths -> throw IOException("simulated unavailable")
                relativePath in storeThenThrowPaths -> {
                    remote[relativePath] = bytes.copyOf()
                    order += if (relativePath.contains("/android/progress/")) "put_state" else if (relativePath.contains("/acks/")) "put_ack" else "put_other"
                    throw IOException("ambiguous PUT")
                }
            }
            remote[relativePath] = bytes.copyOf()
            order += if (relativePath.contains("/android/progress/")) "put_state" else if (relativePath.contains("/acks/")) "put_ack" else "put_other"
            return SyncV1HttpResponse(201)
        }
    }

    private inner class MemoryCatalogGateway(
        private val store: MemoryStore,
        private val order: MutableList<String>
    ) : SyncV1ProgressCatalogGateway {
        var latest: ParsedCatalog? = null
        var publishCount = 0
        var failNextPublish = false
        var lastProgressOverride: Map<String, SyncV1ProgressCatalogValue>? = null
        override suspend fun publish(
            books: List<SyncV1CatalogBook>,
            confirmedTocRevisions: Map<String, Long>?,
            sessionOverride: SyncV1Session?,
            progressValuesOverride: Map<String, SyncV1ProgressCatalogValue>?
        ): SyncV1CatalogOutcome {
            publishCount++
            order += "catalog"
            lastProgressOverride = progressValuesOverride
            if (failNextPublish) {
                failNextPublish = false
                return SyncV1CatalogOutcome(SyncV1CatalogPhase.FAILED, errorCode = SyncV1ErrorCode.NETWORK_UNKNOWN)
            }
            val works = books.map { book ->
                val id = store.workIdByUrl[book.bookUrl] ?: workId
                val value = progressValuesOverride?.get(id) ?: store.baselines[scope(service, root, id)]
                    ?: SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.UNAVAILABLE, 0)
                ParsedCatalogWork(id, book.title, book.author.takeIf(String::isNotBlank)?.let(::listOf).orEmpty(), emptyList(),
                    confirmedTocRevisions?.get(id) ?: store.chapters[scope(service, root, id)]?.tocRevision ?: 0,
                    value.progressRevision, Instant.ofEpochMilli(now).toString(), value.status.wireValue)
            }
            val rev = maxOf(latest?.catalogRevision ?: 0, 0) + 1
            val id = "88888888-8888-4888-8888-888888888882"
            latest = ParsedCatalog(publisherId, rev, id, Instant.ofEpochMilli(now).toString(), works)
            return SyncV1CatalogOutcome(SyncV1CatalogPhase.PUBLISHED, works.size, rev, id)
        }
        override suspend fun resumePendingIntent(sessionOverride: SyncV1Session?) = null
        override suspend fun resolveWorkIdentities(books: List<SyncV1CatalogBook>, session: SyncV1Session) =
            books.associate { it.bookUrl to (store.workIdByUrl[it.bookUrl] ?: workId) }
        override suspend fun latestRemoteCatalog(session: SyncV1Session) = latest
    }

    private inner class MemoryStore(private val clock: Long, private val order: MutableList<String>) :
        SyncV1ProgressStore, SyncV1CatalogStore, SyncV1ChaptersStore, SyncV1BindingStore {
        val workIdByUrl = mutableMapOf(bookUrl to workId)
        val aliases = mutableMapOf<String, String>()
        val baselines = mutableMapOf<String, SyncV1ProgressCatalogValue>()
        val states = mutableMapOf<String, SyncV1ProgressState>()
        val chapters = mutableMapOf<String, SyncV1ChapterPublicationState>()
        val pendingIntents = mutableListOf<SyncV1ProgressPublicationIntent>()
        val publishedIntentPaths = mutableSetOf<String>()
        val receipts = mutableMapOf<String, SyncV1ProgressReceipt>()
        val importReceipts = mutableMapOf<String, SyncV1ImportReceipt>()
        val bindings = mutableSetOf<Triple<String, String, String>>()
        val ledger = mutableMapOf<String, ByteArray>()
        var locatorCapability = "chapter_only"
        var registrationSha = SyncV1Protocol.sha256("registration".toByteArray())
        private val dirtyRoots = mutableSetOf<String>()

        fun seedState(service: String, root: String, state: SyncV1ProgressState) {
            states[scope(service, root, state.workId)] = state
        }
        override suspend fun progressCatalogValue(serviceIdentity: String, rootIdentity: String, workId: String) =
            baselines[scope(serviceIdentity, rootIdentity, workId)] ?: SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.UNAVAILABLE, 0)
        override suspend fun progressState(serviceIdentity: String, rootIdentity: String, workId: String) = states[scope(serviceIdentity, rootIdentity, workId)]
        override suspend fun hasPendingProgressProjection(serviceIdentity: String, rootIdentity: String, workId: String) =
            receipts.values.any { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity && it.workId == workId && it.projectionStage == "projection_pending" }
        override suspend fun pendingProgressIntents(serviceIdentity: String, rootIdentity: String) = pendingIntents.filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity && it.remotePath !in publishedIntentPaths }
        override suspend fun createProgressIntentIfAbsent(intent: SyncV1ProgressPublicationIntent): SyncV1ProgressPublicationIntent {
            val same = (pendingIntents + states.values.mapNotNull { state ->
                if (state.serviceIdentity == intent.serviceIdentity && state.rootIdentity == intent.rootIdentity && state.workId == intent.workId && state.progressRevision == intent.progressRevision) {
                    SyncV1ProgressPublicationIntent(state.workId, state.tocRevision, state.progressRevision, UUID.fromString(state.snapshotId), state.remotePath, state.rawJsonBytes, state.rawSha256, 0, intent.configIdentity, state.serviceIdentity, state.rootIdentity, null, SYNC_V1_CREATE_MODE, "published", clock, clock)
                } else null
            }).firstOrNull { it.serviceIdentity == intent.serviceIdentity && it.rootIdentity == intent.rootIdentity && it.workId == intent.workId && it.progressRevision == intent.progressRevision }
            if (same != null) {
                if (same.rawSha256 != intent.rawSha256 || !same.rawJsonBytes.contentEquals(intent.rawJsonBytes)) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                return same
            }
            pendingIntents.firstOrNull {
                it.serviceIdentity == intent.serviceIdentity && it.rootIdentity == intent.rootIdentity
                    && it.workId == intent.workId && it.remotePath !in publishedIntentPaths
            }?.let { return it }
            pendingIntents += intent
            return intent
        }
        override suspend fun incrementProgressPutAttempt(intent: SyncV1ProgressPublicationIntent, now: Long): Int {
            val i = pendingIntents.indexOfFirst { it.remotePath == intent.remotePath }
            val n = pendingIntents[i].putAttemptCount + 1
            pendingIntents[i] = copyIntent(pendingIntents[i], n)
            return n
        }
        override suspend fun markProgressIntentPublished(intent: SyncV1ProgressPublicationIntent, now: Long) {
            publishedIntentPaths += intent.remotePath
            val intentIndex = pendingIntents.indexOfFirst { it.remotePath == intent.remotePath }
            if (intentIndex >= 0) {
                pendingIntents[intentIndex] = copyIntent(
                    pendingIntents[intentIndex],
                    pendingIntents[intentIndex].putAttemptCount,
                    "published"
                )
            }
            val parsed = requireNotNull(SyncV1ProgressProtocol.parseState(intent.rawJsonBytes))
            states[scope(intent.serviceIdentity, intent.rootIdentity, intent.workId)] = SyncV1ProgressState(
                parsed.workId, parsed.tocRevision, parsed.progressRevision, parsed.snapshotId, parsed.chapterTitle,
                parsed.localTocIndex, parsed.chapterFractionPpm, parsed.visibleCharOffset, parsed.visibleCharCount,
                parsed.chapterTextFingerprint, parsed.locatorConfidence, parsed.sourceEventId, parsed.updatedByDeviceId,
                parsed.updatedAt, intent.rawJsonBytes.copyOf(), intent.rawSha256, intent.remotePath, intent.serviceIdentity, intent.rootIdentity
            )
            dirtyRoots += "${intent.serviceIdentity}|${intent.rootIdentity}"
        }
        override suspend fun adoptConfirmedProgressState(state: SyncV1ProgressState, configIdentity: String, now: Long) {
            states[scope(state.serviceIdentity, state.rootIdentity, state.workId)] = state
            baselines[scope(state.serviceIdentity, state.rootIdentity, state.workId)] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, state.progressRevision)
        }
        override suspend fun progressIntentForEvent(eventId: String, serviceIdentity: String, rootIdentity: String) =
            pendingIntents.firstOrNull { it.sourceEventId == eventId && it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }
        override suspend fun persistAppliedProgress(eventRawBytes: ByteArray, intent: SyncV1ProgressPublicationIntent, receipt: SyncV1ProgressReceipt) {
            if (hasPendingProgressProjection(intent.serviceIdentity, intent.rootIdentity, intent.workId)) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_PROJECTION_PENDING)
            val parsedEvent = SyncV1ProgressProtocol.parseEvent(receipt.deviceId, receipt.eventId, receipt.eventRawJsonBytes)
                as? SyncV1ProgressEventParseResult.Valid
                ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_CONFLICT)
            if (SyncV1Protocol.sha256(eventRawBytes) != receipt.payloadSha256
                || intent.progressRevision != parsedEvent.event.basedOnProgressRevision + 1
            ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_CONFLICT)
            pendingIntents += intent
            ledger[receipt.eventId] = eventRawBytes.copyOf()
            receipts[receipt.eventId] = receipt
        }
        override suspend fun updateProgressReceipt(receipt: SyncV1ProgressReceipt, catalogPath: String?, stage: String) {
            val existing = receipts[receipt.eventId] ?: throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
            receipts[receipt.eventId] = existing.copy(projectionStage = stage)
        }
        override suspend fun incrementProgressAckAttempt(eventId: String): Int {
            val old = receipts.getValue(eventId)
            val next = old.ackPutAttemptCount + 1
            receipts[eventId] = old.copy(ackPutAttemptCount = next, projectionStage = "ack_pending")
            return next
        }
        override suspend fun setProgressBaseline(serviceIdentity: String, rootIdentity: String, workId: String, status: SyncV1ProgressBaseline, revision: Long, tocRevision: Long, now: Long) {
            if (status == SyncV1ProgressBaseline.AVAILABLE && states[scope(serviceIdentity, rootIdentity, workId)]?.progressRevision != revision) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
            baselines[scope(serviceIdentity, rootIdentity, workId)] = SyncV1ProgressCatalogValue(status, revision)
        }
        override suspend fun setProgressCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean) { if (dirty) dirtyRoots += "$serviceIdentity|$rootIdentity" else dirtyRoots -= "$serviceIdentity|$rootIdentity" }
        override suspend fun isProgressCatalogDirty(serviceIdentity: String, rootIdentity: String) = "$serviceIdentity|$rootIdentity" in dirtyRoots
        override suspend fun readyProgressDevices(serviceIdentity: String, rootIdentity: String) = listOf(SyncV1ProgressDevice(deviceId, locatorCapability, "", registrationSha))
        override suspend fun hasProgressBinding(deviceId: String, counterEpoch: String, workId: String) = Triple(deviceId, counterEpoch, workId) in bindings
        override suspend fun progressReceipt(eventId: String) = receipts[eventId]
        override suspend fun persistProgressReceipt(receipt: SyncV1ProgressReceipt) {
            val existing = receipts[receipt.eventId]
            if (existing != null && (!existing.eventRawJsonBytes.contentEquals(receipt.eventRawJsonBytes) || existing.ackSha256 != receipt.ackSha256)) throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
            receipts[receipt.eventId] = receipt
        }
        override suspend fun pendingProgressReceipts(serviceIdentity: String, rootIdentity: String) = receipts.values.filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity && it.projectionStage != "ack_published" }
        override suspend fun markProgressReceiptStage(eventId: String, stage: String) { receipts[eventId] = receipts.getValue(eventId).copy(projectionStage = stage) }

        override suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID) = publisherId
        override suspend fun workAliases() = aliases.map { SyncV1WorkAlias(it.key, it.value) }
        override suspend fun resolveWorkIdentity(bookUrl: String, matchingRemoteWorkIds: Set<String>, uuidProvider: () -> UUID): SyncV1WorkIdentityResolution {
            val id = workIdByUrl.getOrPut(bookUrl) { uuidProvider().toString() }
            aliases[bookUrl] = id
            return SyncV1WorkIdentityResolution.Resolved(SyncV1WorkIdentity(id))
        }
        override suspend fun updateWorkCatalogFields(identity: SyncV1WorkIdentity, catalogFieldsSha256: String, now: Long, initialUpdatedAt: Long?) = identity
        override suspend fun linkExplicitWorkAlias(oldBookUrl: String, newBookUrl: String, uuidProvider: () -> UUID) = SyncV1WorkIdentity(workIdByUrl.getValue(oldBookUrl))
        override suspend fun hasReadyDevice() = true
        override suspend fun pendingCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent? = null
        override suspend fun latestPublishedCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent? = null
        override suspend fun catalogPublicationState(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationState? = null
        override suspend fun createCatalogIntentIfNoPending(intent: SyncV1CatalogPublicationIntent) = intent
        override suspend fun incrementCatalogPutAttempt(intent: SyncV1CatalogPublicationIntent, now: Long) = 1
        override suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent) = Unit
        override suspend fun pendingChapterIntents(serviceIdentity: String, rootIdentity: String) = emptyList<SyncV1ChapterPublicationIntent>()
        override suspend fun chapterPublicationState(serviceIdentity: String, rootIdentity: String, workId: String) = chapters[scope(serviceIdentity, rootIdentity, workId)]
        override suspend fun createChapterIntentIfNoPending(intent: SyncV1ChapterPublicationIntent) = intent
        override suspend fun incrementChapterPutAttempt(intent: SyncV1ChapterPublicationIntent, now: Long) = 1
        override suspend fun markChapterIntentPublished(intent: SyncV1ChapterPublicationIntent, now: Long) = Unit
        override suspend fun markChapterCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean) = Unit
        override suspend fun isChapterCatalogDirty(serviceIdentity: String, rootIdentity: String) = false
        override suspend fun confirmedChapterRevisions(serviceIdentity: String, rootIdentity: String) = chapters.filterKeys { it.startsWith("$serviceIdentity|$rootIdentity|") }.mapValues { it.value.tocRevision }.mapKeys { it.key.substringAfterLast('|') }
        override suspend fun clearChapterCatalogDirty(serviceIdentity: String, rootIdentity: String) = Unit
        override suspend fun readyDeviceIds(serviceIdentity: String, rootIdentity: String) = listOf(deviceId)
        override suspend fun importReceipt(eventId: String): SyncV1ImportReceipt? = importReceipts[eventId]
        override suspend fun contentBinding(deviceId: String, counterEpoch: String, contentId: String): SyncV1KindleContentBinding? = null
        override suspend fun persistBindingOutcome(receipt: SyncV1ImportReceipt, intent: SyncV1AckPublicationIntent, binding: SyncV1KindleContentBinding?) = SyncV1BindingPersistResult.Stored
        override suspend fun ensureAckIntent(receipt: SyncV1ImportReceipt, serviceIdentity: String, rootIdentity: String) = error("unused")
        override suspend fun pendingAckIntents(serviceIdentity: String, rootIdentity: String) = emptyList<SyncV1AckPublicationIntent>()
        override suspend fun incrementAckPutAttempt(intent: SyncV1AckPublicationIntent, now: Long) = 1
        override suspend fun markAckPublished(intent: SyncV1AckPublicationIntent, now: Long) = Unit
        override suspend fun pendingAckCount(serviceIdentity: String, rootIdentity: String) = 0

        private fun copyIntent(
            intent: SyncV1ProgressPublicationIntent,
            attempts: Int,
            publicationStatus: String = intent.publicationStatus
        ) = SyncV1ProgressPublicationIntent(
            intent.workId, intent.tocRevision, intent.progressRevision, intent.snapshotId, intent.remotePath,
            intent.rawJsonBytes, intent.rawSha256, attempts, intent.configIdentity, intent.serviceIdentity,
            intent.rootIdentity, intent.sourceEventId, intent.createMode, publicationStatus, intent.createdAt, intent.updatedAt
        )
    }
}
