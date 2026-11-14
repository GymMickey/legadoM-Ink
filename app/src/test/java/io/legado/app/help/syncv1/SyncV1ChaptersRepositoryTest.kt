package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID

class SyncV1ChaptersRepositoryTest {
    private val publisherId = "11111111-1111-4111-8111-111111111111"
    private val workUrl = "https://books.example/work/1"
    private val now = 1_800_000_000_000L
    private val snapshot = UUID.fromString("88888888-8888-4888-8888-888888888881")

    @Test
    fun firstPublishWritesFrozenSchemaAndCatalogReference() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val result = repository(store, transport).publish(listOf(input(chapters("甲", 0, "乙", 3))))

        assertEquals(SyncV1ChaptersPhase.PUBLISHED, result.phase)
        assertEquals(1, result.published)
        assertTrue(result.catalogPublished)
        val chapterPath = transport.remote.keys.single { "/chapters/" in it }
        val parsed = requireNotNull(SyncV1Protocol.parseChapters(transport.remote.getValue(chapterPath)))
        assertEquals(1L, parsed.tocRevision)
        assertEquals(publisherId, parsed.deviceId)
        assertEquals(listOf(0L, 3L), parsed.chapters.map { it.localTocIndex })
        assertTrue(parsed.chapters.map { it.chapterKey }.distinct().size == 2)
        val json = com.google.gson.JsonParser.parseString(String(transport.remote.getValue(chapterPath))).asJsonObject
        assertFalse(json.has("normalization_version"))
        assertEquals(setOf("protocol_version", "schema_version", "device_id", "work_id", "toc_revision", "snapshot_id", "generated_at", "chapters"), json.keySet())
        val catalog = transport.remote.entries.first { "/catalog/" in it.key }.value
        assertEquals(1L, requireNotNull(SyncV1Protocol.parseCatalog(catalog)).works.single().tocRevision)
    }

    @Test
    fun createsMissingChaptersParentBeforeWorkDirectoryAndPublishesSnapshot() = runBlocking {
        val store = MemoryStore()
        val transport = StrictHierarchyTransport()
        val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        store.aliases[workUrl] = workId
        store.identities[workId] = SyncV1WorkIdentity(workId, updatedAt = now)
        val chaptersDirectory = "legadom_ink_sync/v1/android/chapters/"
        val workDirectory = "${chaptersDirectory}${workId}/"

        val directGrandchildFailure = runCatching { transport.ensureDirectory(workDirectory) }.exceptionOrNull()
        assertEquals(SyncV1ErrorCode.HTTP_CONFLICT, (directGrandchildFailure as? SyncV1Failure)?.errorCode)
        assertTrue(transport.operations.contains("MKCOL $workDirectory ->409"))

        val result = repository(store, transport).publish(listOf(input(chapters("first chapter", 0))))

        val parentCreate = transport.operations.indexOf("MKCOL $chaptersDirectory")
        val workCreate = transport.operations.indexOf("MKCOL $workDirectory")
        assertTrue("chapters/ parent must be created before work_id/", parentCreate >= 0 && parentCreate < workCreate)
        assertEquals(SyncV1ChaptersPhase.PUBLISHED, result.phase)
        val chapterPath = transport.remote.keys.single { "/chapters/" in it }
        val published = requireNotNull(SyncV1Protocol.parseChapters(transport.remote.getValue(chapterPath)))
        assertEquals("${workDirectory}${published.tocRevision}-${published.snapshotId}.json", chapterPath)
        assertEquals(1L, published.tocRevision)
    }

    @Test
    fun knownEmptyIsPublishedButEmptyDaoEvidenceIsNot() = runBlocking {
        val knownStore = MemoryStore()
        val knownTransport = FakeTransport()
        val known = repository(knownStore, knownTransport).publish(
            listOf(input(SyncV1ChapterSourceResult(SyncV1ChapterSourceState.KNOWN_EMPTY)))
        )
        assertEquals(1, known.published)
        val emptyChapters = knownTransport.remote.entries.single { "/chapters/" in it.key }.value
        assertTrue(requireNotNull(SyncV1Protocol.parseChapters(emptyChapters)).chapters.isEmpty())

        val unavailableStore = MemoryStore()
        val unavailableTransport = FakeTransport()
        val unavailable = repository(unavailableStore, unavailableTransport).publish(
            listOf(input(SyncV1ChapterSourceResult(SyncV1ChapterSourceState.UNAVAILABLE)))
        )
        assertEquals(1, unavailable.unavailable)
        assertTrue(unavailableTransport.putBodies.none { String(it).contains("\"chapters\"") })
        assertEquals(0L, unavailableStore.identities.values.single().tocRevision)
    }

    @Test
    fun failedSourceDoesNotPublishEmptyOrReplacePriorRevision() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val first = repo.publish(listOf(input(chapters("title"))))
        val before = store.chapterStates.values.single().rawJsonBytes.copyOf()
        val failed = repo.publish(listOf(input(SyncV1ChapterSourceResult(SyncV1ChapterSourceState.FAILED))))

        assertEquals(1L, first.workOutcomes.single().tocRevision)
        assertEquals(SyncV1ChaptersPhase.FAILED, failed.phase)
        assertArrayEquals(before, store.chapterStates.values.single().rawJsonBytes)
        assertEquals(1L, store.chapterStates.values.single().tocRevision)
    }

    @Test
    fun duplicateTitlesUseDistinctKeysAndIndicesAreNotRenumbered() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        repository(store, transport).publish(listOf(input(chapters("同名", 7, "同名", 18))))
        val parsed = requireNotNull(SyncV1Protocol.parseChapters(transport.remote.values.first { SyncV1Protocol.parseChapters(it) != null }))
        assertEquals(listOf(7L, 18L), parsed.chapters.map { it.localTocIndex })
        assertNotEquals(parsed.chapters[0].chapterKey, parsed.chapters[1].chapterKey)
    }

    @Test
    fun invalidTitleRejectsEntireBookDirectory() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val bad = SyncV1ChapterSourceResult(
            SyncV1ChapterSourceState.AVAILABLE,
            listOf(SyncV1ChapterInput("valid", 0), SyncV1ChapterInput("\uD800", 1))
        )
        val result = repository(store, transport).publish(listOf(input(bad)))
        assertEquals(SyncV1ErrorCode.CHAPTER_TITLE_INVALID, result.workOutcomes.single().errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun emptyDaoEvidenceIsUnavailableAndChapterKeyMatchesFrozenVector() {
        val empty = SyncV1ChapterSourceClassifier.fromPersistedDirectory(0, emptyList())
        assertEquals(SyncV1ChapterSourceState.UNAVAILABLE, empty.state)
        assertEquals(
            "ck1-4dcd670f8a39bd6837ec8e6add71c439ddea48e7121369ae4e366a25f9bfe15d",
            SyncV1Protocol.chapterKey("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 0, "第一章")
        )
        val loaded = SyncV1ChapterSourceClassifier.fromPersistedDirectory(
            2, listOf(SyncV1ChapterInput("a", 3), SyncV1ChapterInput("b", 9))
        )
        assertEquals(SyncV1ChapterSourceState.AVAILABLE, loaded.state)
        assertEquals(listOf(3L, 9L), loaded.chapters.map { it.localTocIndex })
        assertEquals(SyncV1ChapterSourceState.UNAVAILABLE, SyncV1ChapterSourceClassifier.fromPersistedDirectory(3, loaded.chapters).state)
    }

    @Test
    fun emptyAndOverlongTitlesAndDuplicateOrDescendingIndicesAreRejected() = runBlocking {
        val badTitles = listOf("", "x".repeat(513), "\uD800")
        for (title in badTitles) {
            val source = SyncV1ChapterSourceResult(
                SyncV1ChapterSourceState.AVAILABLE,
                listOf(SyncV1ChapterInput(title, 0))
            )
            val result = repository(MemoryStore(), FakeTransport()).publish(listOf(input(source)))
            assertEquals(SyncV1ErrorCode.CHAPTER_TITLE_INVALID, result.workOutcomes.single().errorCode)
        }
        for (indices in listOf(listOf(2L, 2L), listOf(3L, 1L))) {
            val source = SyncV1ChapterSourceResult(
                SyncV1ChapterSourceState.AVAILABLE,
                indices.mapIndexed { index, tocIndex -> SyncV1ChapterInput("title$index", tocIndex) }
            )
            val result = repository(MemoryStore(), FakeTransport()).publish(listOf(input(source)))
            assertEquals(SyncV1ErrorCode.CHAPTERS_INVALID, result.workOutcomes.single().errorCode)
        }
    }

    @Test
    fun countLimitRejectsWithoutTruncation() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val tooMany = SyncV1ChapterSourceResult(
            SyncV1ChapterSourceState.AVAILABLE,
            (0..SYNC_V1_MAX_CHAPTERS).map { SyncV1ChapterInput("c$it", it.toLong()) }
        )
        val result = repository(store, transport).publish(listOf(input(tooMany)))
        assertEquals(SyncV1ErrorCode.CHAPTERS_LIMIT_EXCEEDED, result.workOutcomes.single().errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun unchangedStructureReusesBytesAndSemanticChangeIncrementsOnce() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val first = repo.publish(listOf(input(chapters("one", 2))))
        val originalPath = transport.putPaths.first { "/chapters/" in it }
        val originalBytes = transport.remote.getValue(originalPath).copyOf()
        val repeated = repo.publish(listOf(input(chapters("one", 2))))
        val changed = repo.publish(listOf(input(chapters("two", 2))))
        val repeatedChanged = repo.publish(listOf(input(chapters("two", 2))))

        assertEquals(1L, first.workOutcomes.single().tocRevision)
        assertEquals(SyncV1ChaptersPhase.UNCHANGED, repeated.phase)
        assertArrayEquals(originalBytes, transport.remote.getValue(originalPath))
        assertEquals(2L, changed.workOutcomes.single().tocRevision)
        assertEquals(2L, repeatedChanged.workOutcomes.single().tocRevision)
        assertEquals(2, transport.putPaths.count { "/chapters/" in it })
    }

    @Test
    fun generatedAtChangeDoesNotAllocateNewChapterRevision() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        var clock = now
        val repo = repository(store, transport, clock = { clock })
        val first = repo.publish(listOf(input(chapters("stable"))))
        val original = transport.remote.entries.first { "/chapters/" in it.key }.value.copyOf()
        clock += 86_400_000L
        val second = repo.publish(listOf(input(chapters("stable"))))
        assertEquals(1L, first.workOutcomes.single().tocRevision)
        assertEquals(SyncV1ChaptersPhase.UNCHANGED, second.phase)
        assertArrayEquals(original, transport.remote.entries.first { "/chapters/" in it.key }.value)
        assertEquals(1, transport.putPaths.count { "/chapters/" in it })
    }

    @Test
    fun unchangedLocalSnapshotRepairsMissingRemoteObjectWithSavedBytes() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val input = input(chapters("stable"))
        val first = repo.publish(listOf(input))
        val path = transport.remote.keys.single { "/chapters/" in it }
        val savedBytes = transport.remote.getValue(path).copyOf()
        transport.remote.remove(path)

        val repaired = repo.publish(listOf(input))

        assertEquals(1L, first.workOutcomes.single().tocRevision)
        assertEquals(SyncV1ChaptersPhase.UNCHANGED, repaired.phase)
        assertEquals(1L, repaired.workOutcomes.single().tocRevision)
        assertArrayEquals(savedBytes, transport.remote.getValue(path))
        assertEquals(2, transport.putPaths.count { it == path })
    }

    @Test
    fun indexChangeCreatesExactlyOneNewRevision() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val first = repo.publish(listOf(input(chapters("one", 4, "two", 9))))
        val changed = repo.publish(listOf(input(chapters("one", 4, "two", 10))))
        val repeated = repo.publish(listOf(input(chapters("one", 4, "two", 10))))
        assertEquals(1L, first.workOutcomes.single().tocRevision)
        assertEquals(2L, changed.workOutcomes.single().tocRevision)
        assertEquals(2L, repeated.workOutcomes.single().tocRevision)
        assertEquals(2, transport.putPaths.count { "/chapters/" in it })
    }

    @Test
    fun invalidSameRevisionRemoteObjectsConflict() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val first = repository(store, transport).publish(listOf(input(chapters("one"))))
        val workId = store.aliases.getValue(workUrl)
        val path = transport.remote.keys.first { "/chapters/" in it }
        val snapshotId = path.substringAfterLast('/').removePrefix("1-").removeSuffix(".json")
        val conflicting = chapterJson(publisherId, workId, 1, snapshotId, listOf("two"))
        transport.remote[path] = conflicting
        val result = repository(store, transport).publish(listOf(input(chapters("changed"))))
        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, result.workOutcomes.single().errorCode)
        assertEquals(1L, first.workOutcomes.single().tocRevision)
    }

    @Test
    fun remoteSnapshotIsAdoptedAfterLocalSyncDatabaseLoss() = runBlocking {
        val transport = FakeTransport()
        val originalStore = MemoryStore()
        repository(originalStore, transport).publish(listOf(input(chapters("same"))))
        val priorPutCount = transport.putBodies.size
        val replacementStore = MemoryStore()
        val recovered = repository(replacementStore, transport).publish(listOf(input(chapters("same"))))

        assertEquals(SyncV1ChaptersPhase.UNCHANGED, recovered.phase)
        assertEquals(1, recovered.adopted)
        assertEquals(0, recovered.published)
        assertEquals(priorPutCount, transport.putBodies.size)
        assertEquals(1L, replacementStore.chapterStates.values.single().tocRevision)
    }

    @Test
    fun multipleSnapshotIdsAtOneRevisionAreConflict() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        repository(store, transport).publish(listOf(input(chapters("one"))))
        val existing = transport.remote.keys.first { "/chapters/" in it }
        val workId = store.aliases.getValue(workUrl)
        val secondSnapshot = "99999999-9999-4999-8999-999999999999"
        transport.remote["legadom_ink_sync/v1/android/chapters/$workId/1-$secondSnapshot.json"] =
            chapterJson(publisherId, workId, 1, secondSnapshot, listOf("different"))
        assertTrue(transport.remote.containsKey(existing))

        val result = repository(store, transport).publish(listOf(input(chapters("changed"))))
        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, result.workOutcomes.single().errorCode)
    }

    @Test
    fun remotePathAndDocumentRevisionMismatchCannotBeAdoptedOrReplaced() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        repository(store, transport).publish(listOf(input(chapters("one"))))
        val originalPath = transport.remote.keys.first { "/chapters/" in it }
        val workId = store.aliases.getValue(workUrl)
        val secondSnapshot = "99999999-9999-4999-8999-999999999999"
        val mismatchedBytes = chapterJson(publisherId, workId, 2, secondSnapshot, listOf("remote mismatch"))
        transport.remote["legadom_ink_sync/v1/android/chapters/$workId/1-$secondSnapshot.json"] = mismatchedBytes

        val result = repository(store, transport).publish(listOf(input(chapters("changed"))))
        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, result.workOutcomes.single().errorCode)
        assertTrue(transport.remote.containsKey(originalPath))
    }

    @Test
    fun mismatchingReadbackIsRejected() = runBlocking {
        val transport = FakeTransport().apply {
            putBehavior = { path, _, _ ->
                remote[path] = "different bytes".toByteArray()
                SyncV1HttpResponse(201)
            }
        }
        val result = repository(MemoryStore(), transport).publish(listOf(input(chapters("title"))))
        assertEquals(SyncV1ErrorCode.READBACK_MISMATCH, result.workOutcomes.single().errorCode)
        assertTrue(result.failed > 0)
    }

    @Test
    fun failedDirectoryEnsurePreventsPut() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply { failEnsure = true }
        val result = repository(store, transport).publish(listOf(input(chapters("title"))))
        assertEquals(SyncV1ErrorCode.CHAPTER_DIRECTORY_UNAVAILABLE, result.workOutcomes.single().errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun timeoutAfterRemoteWriteRecoversByReadback() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply {
            putBehavior = { path, bytes, _ ->
                remote[path] = bytes.copyOf()
                throw SocketTimeoutException("unknown_result")
            }
        }
        val result = repository(store, transport).publish(listOf(input(chapters("title"))))
        assertEquals(SyncV1ChaptersPhase.PUBLISHED, result.phase)
        assertEquals(1, transport.putBodies.count { String(it).contains("\"chapters\"") })
        assertTrue(store.chapterStates.isNotEmpty())
    }

    @Test
    fun unknownPut404RetriesSameIntentAtMostTwice() = runBlocking {
        val store = MemoryStore()
        var attempts = 0
        val transport = FakeTransport().apply {
            putBehavior = { path, bytes, _ ->
                if ("/chapters/" in path) {
                    attempts++
                    if (attempts == 2) remote[path] = bytes.copyOf()
                    if (attempts < 2) throw SocketTimeoutException("unknown_result")
                    SyncV1HttpResponse(201)
                } else {
                    remote[path] = bytes.copyOf()
                    SyncV1HttpResponse(201)
                }
            }
        }
        val result = repository(store, transport).publish(listOf(input(chapters("title"))))
        val chapterBodies = transport.putBodies.filter { String(it).contains("\"chapters\"") }
        assertEquals(SyncV1ChaptersPhase.PUBLISHED, result.phase)
        assertEquals(2, chapterBodies.size)
        assertArrayEquals(chapterBodies[0], chapterBodies[1])
    }

    @Test
    fun secondUnknownPutStopsAtTwoAndKeepsIntent() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply {
            putBehavior = { _, _, _ -> throw SocketTimeoutException("unknown_result") }
        }
        val result = repository(store, transport).publish(listOf(input(chapters("title"))))
        assertEquals(2, transport.putBodies.count { String(it).contains("\"chapters\"") })
        assertEquals(SyncV1ErrorCode.RETRY_LIMIT, result.workOutcomes.single().errorCode)
        assertEquals(1, store.pendingChapterIntents("service", "root").size)
    }

    @Test
    fun partialBatchCatalogOnlyReferencesConfirmedChapters() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val result = repository(store, transport).publish(
            listOf(
                input(chapters("published"), url = "https://books.example/one"),
                input(SyncV1ChapterSourceResult(SyncV1ChapterSourceState.UNAVAILABLE), url = "https://books.example/two")
            )
        )
        assertEquals(SyncV1ChaptersPhase.PARTIAL_SUCCESS, result.phase)
        assertEquals(1, transport.putPaths.count { "/catalog/" in it })
        val catalog = requireNotNull(SyncV1Protocol.parseCatalog(transport.remote.entries.first { "/catalog/" in it.key }.value))
        assertEquals(1, catalog.works.size)
        assertEquals(setOf(1L), catalog.works.map { it.tocRevision }.toSet())
    }

    @Test
    fun confirmedStateIsIsolatedByWebDavRoot() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        repository(store, transport, root = "root-one").publish(listOf(input(chapters("title"))))
        val secondTransport = FakeTransport()
        val secondRoot = repository(store, secondTransport, root = "root-two")
            .publish(listOf(input(chapters("title"))))
        assertEquals(1L, secondRoot.workOutcomes.single().tocRevision)
        assertEquals(1, transport.putPaths.count { "/chapters/" in it })
        assertEquals(1, secondTransport.putPaths.count { "/chapters/" in it })
        assertEquals(1, store.chapterStates.keys.count { it.second == "root-one" })
        assertEquals(1, store.chapterStates.keys.count { it.second == "root-two" })
    }

    @Test
    fun cancellationKeepsPersistedIntentAndSingleFlightSerializesCalls() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply {
            putBehavior = { _, _, _ -> throw CancellationException("cancel") }
        }
        val repo = repository(store, transport)
        var cancelledBytes: ByteArray? = null
        try {
            repo.publish(listOf(input(chapters("cancel"))))
        } catch (_: CancellationException) {
            // Cancellation is expected; durable intent remains for a later explicit retry.
        }
        val pending = store.pendingChapterIntents("service", "root").single()
        cancelledBytes = pending.rawJsonBytes.copyOf()
        transport.putBehavior = { path, bytes, _ ->
            remote[path] = bytes.copyOf()
            SyncV1HttpResponse(201)
        }
        repo.publish(listOf(input(chapters("cancel"))))
        assertArrayEquals(cancelledBytes, transport.putBodies.first { String(it).contains("\"chapters\"") })
        assertTrue(store.pendingChapterIntents("service", "root").isEmpty())

        val store2 = MemoryStore()
        val transport2 = FakeTransport().apply { putDelayMillis = 50 }
        val repo2 = repository(store2, transport2)
        val first = async { repo2.publish(listOf(input(chapters("single")))) }
        delay(5)
        val second = async { repo2.publish(listOf(input(chapters("single")))) }
        first.await()
        second.await()
        assertEquals(1, transport2.putPaths.count { "/chapters/" in it })
    }

    @Test
    fun pendingCatalogIntentIsResumedBeforeChapterWork() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val pendingBytes = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", publisherId)
            addProperty("catalog_revision", 1)
            addProperty("snapshot_id", snapshot.toString())
            addProperty("generated_at", Instant.ofEpochMilli(now).toString())
            add("works", JsonArray())
        }.toString().toByteArray()
        val pending = SyncV1CatalogPublicationIntent(
            1, snapshot, "legadom_ink_sync/v1/android/catalog/1-$snapshot.json", pendingBytes,
            SyncV1Protocol.sha256(pendingBytes), "0".repeat(64), 0, "config", "service", "root",
            "pending", now, now
        )
        store.pendingCatalog += pending

        repository(store, transport).publish(listOf(input(chapters("title"))))

        assertTrue(transport.putPaths.first().contains("/catalog/"))
        assertTrue(transport.putPaths.drop(1).any { "/chapters/" in it })
    }

    @Test
    fun pendingCatalogAndChapterIntentsResumeBeforeCurrentInputIsRead() = runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val pendingCatalogBytes = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", publisherId)
            addProperty("catalog_revision", 1)
            addProperty("snapshot_id", snapshot.toString())
            addProperty("generated_at", Instant.ofEpochMilli(now).toString())
            add("works", JsonArray())
        }.toString().toByteArray()
        store.pendingCatalog += SyncV1CatalogPublicationIntent(
            1, snapshot, "legadom_ink_sync/v1/android/catalog/1-$snapshot.json", pendingCatalogBytes,
            SyncV1Protocol.sha256(pendingCatalogBytes), "0".repeat(64), 0, "config", "service", "root",
            "pending", now, now
        )
        val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val chapterBytes = chapterJson(publisherId, workId, 1, snapshot.toString(), listOf("saved"))
        val chapterPath = "legadom_ink_sync/v1/android/chapters/$workId/1-$snapshot.json"
        store.pendingChapters += SyncV1ChapterPublicationIntent(
            workId, 1, snapshot, chapterPath, chapterBytes, SyncV1Protocol.sha256(chapterBytes),
            "1".repeat(64), 0, "config", "service", "root", "pending", now, now
        )

        var inputRead = false
        val result = repository(store, transport).publish {
            inputRead = true
            assertTrue(transport.putPaths.first().contains("/catalog/"))
            assertTrue(transport.putPaths.any { "/chapters/" in it })
            emptyList()
        }

        assertTrue(inputRead)
        assertEquals(SyncV1ChaptersPhase.PUBLISHED, result.phase)
        assertEquals(1L, store.chapterStates.values.single().tocRevision)
    }

    @Test
    fun exactTwoMiBIsAcceptedAndOneByteOverIsRejected() = runBlocking {
        val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val snapshotId = snapshot.toString()
        val deviceId = publisherId
        val generatedAt = Instant.ofEpochMilli(now).toString()
        val titleSizes = MutableList(SYNC_V1_MAX_CHAPTERS) { 1 }
        var bytes = chapterJsonByLengths(deviceId, workId, snapshotId, generatedAt, titleSizes)
        var remaining = SYNC_V1_MAX_CHAPTERS_BYTES - bytes.size
        for (index in titleSizes.indices) {
            if (remaining <= 0) break
            val addition = minOf(511, remaining)
            titleSizes[index] += addition
            remaining -= addition
        }
        bytes = chapterJsonByLengths(deviceId, workId, snapshotId, generatedAt, titleSizes)
        assertEquals(SYNC_V1_MAX_CHAPTERS_BYTES, bytes.size)

        val exactStore = MemoryStore().apply { identities[workId] = SyncV1WorkIdentity(workId); aliases[workUrl] = workId }
        val exactTransport = FakeTransport()
        val exactSource = SyncV1ChapterSourceResult(
            SyncV1ChapterSourceState.AVAILABLE,
            titleSizes.mapIndexed { index, size -> SyncV1ChapterInput("x".repeat(size), index.toLong()) }
        )
        val fixedId = { snapshot }
        val exact = repository(exactStore, exactTransport, identityId = fixedId, snapshotId = fixedId)
            .publish(listOf(input(exactSource)))
        assertEquals(1, exact.published)

        val overSizes = titleSizes.toMutableList()
        val grow = overSizes.indexOfFirst { it < 512 }
        assertTrue(grow >= 0)
        overSizes[grow]++
        val overSource = SyncV1ChapterSourceResult(
            SyncV1ChapterSourceState.AVAILABLE,
            overSizes.mapIndexed { index, size -> SyncV1ChapterInput("x".repeat(size), index.toLong()) }
        )
        val overStore = MemoryStore().apply { identities[workId] = SyncV1WorkIdentity(workId); aliases[workUrl] = workId }
        val overTransport = FakeTransport()
        val over = repository(overStore, overTransport, identityId = fixedId, snapshotId = fixedId)
            .publish(listOf(input(overSource)))
        assertEquals(SyncV1ErrorCode.CHAPTERS_TOO_LARGE, over.workOutcomes.single().errorCode)
        assertTrue(overTransport.putBodies.isEmpty())
    }

    private fun repository(
        store: MemoryStore,
        transport: SyncV1WebDavTransport,
        root: String = "root",
        identityId: () -> UUID = UUID::randomUUID,
        snapshotId: () -> UUID = UUID::randomUUID,
        clock: () -> Long = { now }
    ): SyncV1ChaptersRepository {
        val sessionProvider = suspend {
            SyncV1Session("config", "service", root, transport) { true }
        }
        val catalog = SyncV1CatalogRepository(store, sessionProvider, clock, identityId)
        return SyncV1ChaptersRepository(store, store, sessionProvider, catalog, clock, snapshotId)
    }

    private fun input(
        source: SyncV1ChapterSourceResult,
        url: String = workUrl
    ) = SyncV1ChapterWorkInput(url, "Work", "Author", source)

    private fun chapters(vararg titleAndIndex: Any): SyncV1ChapterSourceResult {
        val chapters = if (titleAndIndex.size == 1) {
            listOf(SyncV1ChapterInput(titleAndIndex.single() as String, 0))
        } else {
            titleAndIndex.toList().chunked(2).map { pair ->
                SyncV1ChapterInput(pair[0] as String, (pair[1] as Int).toLong())
            }
        }
        return SyncV1ChapterSourceResult(SyncV1ChapterSourceState.AVAILABLE, chapters)
    }

    private fun chapterJson(
        deviceId: String,
        workId: String,
        revision: Long,
        snapshotId: String,
        titles: List<String>
    ): ByteArray {
        val chapters = titles.mapIndexed { index, title ->
            SyncV1ChapterRecord(SyncV1Protocol.chapterKey(workId, index.toLong(), title), title, index.toLong())
        }
        return serialize(deviceId, workId, revision, snapshotId, Instant.ofEpochMilli(now).toString(), chapters)
    }

    private fun chapterJsonByLengths(
        deviceId: String,
        workId: String,
        snapshotId: String,
        generatedAt: String,
        lengths: List<Int>
    ): ByteArray {
        val chapters = lengths.mapIndexed { index, size ->
            val title = "x".repeat(size)
            SyncV1ChapterRecord(SyncV1Protocol.chapterKey(workId, index.toLong(), title), title, index.toLong())
        }
        return serialize(deviceId, workId, 1, snapshotId, generatedAt, chapters)
    }

    private fun serialize(
        deviceId: String,
        workId: String,
        revision: Long,
        snapshotId: String,
        generatedAt: String,
        chapters: List<SyncV1ChapterRecord>
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("device_id", deviceId)
            addProperty("work_id", workId)
            addProperty("toc_revision", revision)
            addProperty("snapshot_id", snapshotId)
            addProperty("generated_at", generatedAt)
            add("chapters", JsonArray().apply {
                chapters.forEach { chapter -> add(JsonObject().apply {
                    addProperty("chapter_key", chapter.chapterKey)
                    addProperty("title", chapter.title)
                    addProperty("local_toc_index", chapter.localTocIndex)
                }) }
            })
        }
        return GsonBuilder().disableHtmlEscaping().create().toJson(root).toByteArray(Charsets.UTF_8)
    }

    private inner class MemoryStore : SyncV1CatalogStore, SyncV1ChaptersStore {
        val identities = linkedMapOf<String, SyncV1WorkIdentity>()
        val aliases = linkedMapOf<String, String>()
        val pendingCatalog = mutableListOf<SyncV1CatalogPublicationIntent>()
        val publishedCatalog = mutableListOf<SyncV1CatalogPublicationIntent>()
        val catalogStates = linkedMapOf<Pair<String, String>, SyncV1CatalogPublicationState>()
        val pendingChapters = mutableListOf<SyncV1ChapterPublicationIntent>()
        val publishedChapters = mutableListOf<SyncV1ChapterPublicationIntent>()
        val chapterStates = linkedMapOf<Triple<String, String, String>, SyncV1ChapterPublicationState>()
        val dirty = mutableSetOf<Pair<String, String>>()
        var ready = true

        override suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID) = publisherId
        override suspend fun workAliases() = aliases.map { SyncV1WorkAlias(it.key, it.value) }
        override suspend fun hasReadyDevice() = ready
        override suspend fun resolveWorkIdentity(bookUrl: String, matchingRemoteWorkIds: Set<String>, uuidProvider: () -> UUID): SyncV1WorkIdentityResolution {
            val workId = aliases[bookUrl] ?: matchingRemoteWorkIds.singleOrNull() ?: uuidProvider().toString()
            aliases[bookUrl] = workId
            val identity = identities.getOrPut(workId) { SyncV1WorkIdentity(workId, updatedAt = now) }
            return SyncV1WorkIdentityResolution.Resolved(identity)
        }
        override suspend fun updateWorkCatalogFields(identity: SyncV1WorkIdentity, catalogFieldsSha256: String, now: Long, initialUpdatedAt: Long?): SyncV1WorkIdentity {
            val previous = identities[identity.workId] ?: identity
            return previous.copy(
                catalogFieldsSha256 = catalogFieldsSha256,
                updatedAt = if (previous.catalogFieldsSha256 == catalogFieldsSha256) previous.updatedAt else initialUpdatedAt ?: now
            ).also { identities[identity.workId] = it }
        }
        override suspend fun linkExplicitWorkAlias(oldBookUrl: String, newBookUrl: String, uuidProvider: () -> UUID): SyncV1WorkIdentity {
            val id = aliases[oldBookUrl] ?: uuidProvider().toString().also { aliases[oldBookUrl] = it }
            aliases[newBookUrl] = id
            return identities.getOrPut(id) { SyncV1WorkIdentity(id, updatedAt = now) }
        }
        override suspend fun pendingCatalogIntent(serviceIdentity: String, rootIdentity: String) = pendingCatalog.firstOrNull { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }
        override suspend fun latestPublishedCatalogIntent(serviceIdentity: String, rootIdentity: String) = publishedCatalog.filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }.maxByOrNull { it.catalogRevision }
        override suspend fun catalogPublicationState(serviceIdentity: String, rootIdentity: String) = catalogStates[serviceIdentity to rootIdentity]
        override suspend fun createCatalogIntentIfNoPending(intent: SyncV1CatalogPublicationIntent): SyncV1CatalogPublicationIntent {
            pendingCatalog.firstOrNull { it.serviceIdentity == intent.serviceIdentity && it.rootIdentity == intent.rootIdentity }?.let { return it }
            pendingCatalog += intent
            return intent
        }
        override suspend fun incrementCatalogPutAttempt(intent: SyncV1CatalogPublicationIntent, now: Long): Int {
            val index = pendingCatalog.indexOfFirst { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            val updated = copyCatalog(pendingCatalog[index], putAttemptCount = pendingCatalog[index].putAttemptCount + 1)
            pendingCatalog[index] = updated
            return updated.putAttemptCount
        }
        override suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent) {
            pendingCatalog.removeAll { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            val done = copyCatalog(intent, publicationStatus = "published")
            publishedCatalog.removeAll { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            publishedCatalog += done
            catalogStates[intent.serviceIdentity to intent.rootIdentity] = SyncV1CatalogPublicationState(
                intent.catalogRevision, intent.snapshotId, intent.remotePath, intent.semanticSha256,
                intent.rawSha256, intent.serviceIdentity, intent.rootIdentity, now
            )
        }
        override suspend fun pendingChapterIntents(serviceIdentity: String, rootIdentity: String) = pendingChapters.filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }
        override suspend fun chapterPublicationState(serviceIdentity: String, rootIdentity: String, workId: String) = chapterStates[Triple(serviceIdentity, rootIdentity, workId)]
        override suspend fun createChapterIntentIfNoPending(intent: SyncV1ChapterPublicationIntent): SyncV1ChapterPublicationIntent {
            pendingChapters.firstOrNull { it.serviceIdentity == intent.serviceIdentity && it.rootIdentity == intent.rootIdentity && it.workId == intent.workId }?.let { return it }
            publishedChapters.firstOrNull { it.remotePath == intent.remotePath }?.let { existing ->
                if (existing.rawSha256 != intent.rawSha256 || !existing.rawJsonBytes.contentEquals(intent.rawJsonBytes)) {
                    throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                }
                val resumed = copyChapter(existing, publicationStatus = "pending")
                publishedChapters.remove(existing)
                pendingChapters += resumed
                return resumed
            }
            pendingChapters += intent
            return intent
        }
        override suspend fun incrementChapterPutAttempt(intent: SyncV1ChapterPublicationIntent, now: Long): Int {
            val index = pendingChapters.indexOfFirst { it.remotePath == intent.remotePath }
            val updated = copyChapter(pendingChapters[index], putAttemptCount = pendingChapters[index].putAttemptCount + 1)
            pendingChapters[index] = updated
            return updated.putAttemptCount
        }
        override suspend fun markChapterIntentPublished(intent: SyncV1ChapterPublicationIntent, now: Long) {
            pendingChapters.removeAll { it.remotePath == intent.remotePath }
            val done = copyChapter(intent, publicationStatus = "published")
            publishedChapters.removeAll { it.remotePath == intent.remotePath }
            publishedChapters += done
            chapterStates[Triple(intent.serviceIdentity, intent.rootIdentity, intent.workId)] = SyncV1ChapterPublicationState(
                intent.workId, intent.tocRevision, intent.snapshotId, intent.remotePath, intent.rawJsonBytes,
                intent.rawSha256, intent.semanticSha256, intent.serviceIdentity, intent.rootIdentity, now
            )
            identities[intent.workId]?.let { identities[intent.workId] = it.copy(tocRevision = intent.tocRevision) }
            dirty += intent.serviceIdentity to intent.rootIdentity
        }
        override suspend fun markChapterCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean) {
            if (dirty) this.dirty += serviceIdentity to rootIdentity else this.dirty -= serviceIdentity to rootIdentity
        }
        override suspend fun isChapterCatalogDirty(serviceIdentity: String, rootIdentity: String) = (serviceIdentity to rootIdentity) in dirty
        override suspend fun confirmedChapterRevisions(serviceIdentity: String, rootIdentity: String) = chapterStates.values
            .filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }
            .associate { it.workId to it.tocRevision }
        override suspend fun clearChapterCatalogDirty(serviceIdentity: String, rootIdentity: String) { dirty -= serviceIdentity to rootIdentity }

        private fun copyCatalog(source: SyncV1CatalogPublicationIntent, putAttemptCount: Int = source.putAttemptCount, publicationStatus: String = source.publicationStatus) =
            SyncV1CatalogPublicationIntent(source.catalogRevision, source.snapshotId, source.remotePath, source.rawJsonBytes, source.rawSha256, source.semanticSha256, putAttemptCount, source.configIdentity, source.serviceIdentity, source.rootIdentity, publicationStatus, source.createdAt, source.updatedAt)
        private fun copyChapter(source: SyncV1ChapterPublicationIntent, putAttemptCount: Int = source.putAttemptCount, publicationStatus: String = source.publicationStatus) =
            SyncV1ChapterPublicationIntent(source.workId, source.tocRevision, source.snapshotId, source.remotePath, source.rawJsonBytes, source.rawSha256, source.semanticSha256, putAttemptCount, source.configIdentity, source.serviceIdentity, source.rootIdentity, publicationStatus, source.createdAt, source.updatedAt)
    }

    private inner class FakeTransport : SyncV1WebDavTransport {
        val remote = linkedMapOf<String, ByteArray>()
        val putPaths = mutableListOf<String>()
        val putBodies = mutableListOf<ByteArray>()
        var failEnsure = false
        var putDelayMillis = 0L
        var putBehavior: suspend FakeTransport.(String, ByteArray, Int) -> SyncV1HttpResponse = { path, bytes, _ ->
            remote[path] = bytes.copyOf()
            SyncV1HttpResponse(201)
        }
        override suspend fun ensureDirectory(relativePath: String) {
            if (failEnsure && "/chapters/" in relativePath
                && relativePath != "legadom_ink_sync/v1/android/chapters/"
            ) throw SyncV1Failure(SyncV1ErrorCode.CHAPTER_DIRECTORY_UNAVAILABLE)
        }
        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> = remote.keys
            .filter { it.startsWith(relativePath) }
            .mapNotNull { path -> path.removePrefix(relativePath).takeIf { it.isNotBlank() && '/' !in it }?.let { SyncV1RemoteEntry(it, false) } }
        override suspend fun get(relativePath: String, maxBytes: Int, computeFullSha256: Boolean): SyncV1HttpResponse {
            val bytes = remote[relativePath] ?: return SyncV1HttpResponse(404)
            if (bytes.size > maxBytes) return SyncV1HttpResponse(200, bodyTooLarge = true)
            return SyncV1HttpResponse(200, bytes.copyOf(), rawSha256 = if (computeFullSha256) SyncV1Protocol.sha256(bytes) else null)
        }
        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            if (putDelayMillis > 0) delay(putDelayMillis)
            putPaths += relativePath
            putBodies += bytes.copyOf()
            return putBehavior(relativePath, bytes.copyOf(), putBodies.size)
        }
    }

    /** Models WebDAV MKCOL semantics: a missing immediate parent produces 409, without recursion. */
    private inner class StrictHierarchyTransport : SyncV1WebDavTransport {
        val operations = mutableListOf<String>()
        val remote = linkedMapOf<String, ByteArray>()
        private val directories = mutableSetOf(
            "legadom_ink_sync/",
            "legadom_ink_sync/v1/",
            "legadom_ink_sync/v1/android/",
            SyncV1CatalogRepository.CATALOG_DIRECTORY
        )

        override suspend fun ensureDirectory(relativePath: String) {
            val directory = relativePath.trimStart('/').trimEnd('/') + "/"
            operations += "PROPFIND $directory"
            if (directory in directories) return
            val parent = directory.removeSuffix("/").substringBeforeLast('/', "")
                .let { if (it.isEmpty()) "" else "$it/" }
            if (parent !in directories) {
                operations += "MKCOL $directory ->409"
                throw SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
            }
            operations += "MKCOL $directory"
            directories += directory
            operations += "PROPFIND $directory (verified collection)"
        }

        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> {
            val directory = relativePath.trimStart('/').trimEnd('/') + "/"
            if (directory !in directories) throw SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
            return remote.keys.asSequence()
                .filter { it.startsWith(directory) }
                .mapNotNull { it.removePrefix(directory).takeIf { name -> name.isNotBlank() && '/' !in name } }
                .map { SyncV1RemoteEntry(it, false) }
                .toList()
        }

        override suspend fun get(
            relativePath: String,
            maxBytes: Int,
            computeFullSha256: Boolean
        ): SyncV1HttpResponse {
            val bytes = remote[relativePath] ?: return SyncV1HttpResponse(404)
            if (bytes.size > maxBytes) return SyncV1HttpResponse(200, bodyTooLarge = true)
            return SyncV1HttpResponse(
                200, bytes.copyOf(), rawSha256 = if (computeFullSha256) SyncV1Protocol.sha256(bytes) else null
            )
        }

        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            val parent = relativePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            if (parent !in directories) return SyncV1HttpResponse(409)
            remote[relativePath] = bytes.copyOf()
            operations += "PUT $relativePath"
            return SyncV1HttpResponse(201)
        }
    }
}
