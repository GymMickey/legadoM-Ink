package io.legado.app.help.syncv1

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.UUID

class SyncV1CatalogRepositoryTest {
    private val publisherId = "11111111-1111-4111-8111-111111111111"
    private val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val snapshotOne = UUID.fromString("88888888-8888-4888-8888-888888888881")
    private val url = "https://books.example/book/1"
    private val baseTime = 1_800_000_000_000L

    @Test
    fun firstPublishCreatesImmutableCatalogRevisionOne() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val result = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1CatalogPhase.PUBLISHED, result.phase)
        assertEquals(1L, result.catalogRevision)
        assertEquals(1, transport.putBodies.size)
        val path = transport.putPaths.single()
        assertTrue(path.matches(Regex("legadom_ink_sync/v1/android/catalog/1-[0-9a-f-]{36}\\.json")))
        val bytes = transport.remote[path]!!
        val parsed = requireNotNull(SyncV1Protocol.parseCatalog(bytes))
        assertEquals(publisherId, parsed.deviceId)
        assertTrue(SyncV1Protocol.isUuidV4(parsed.works.single().workId))
        assertEquals(0L, parsed.works.single().tocRevision)
        assertEquals(0L, parsed.works.single().progressRevision)
    }

    @Test
    fun identicalBookshelfReusesRevisionSnapshotBytesAndGeneratedTime() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val first = repo.publish(listOf(book()))
        val firstBytes = transport.remote.values.single().copyOf()
        val second = repo.publish(listOf(book()))

        assertEquals(SyncV1CatalogPhase.UNCHANGED, second.phase)
        assertEquals(first.catalogRevision, second.catalogRevision)
        assertEquals(first.snapshotId, second.snapshotId)
        assertEquals(1, transport.putBodies.size)
        assertArrayEquals(firstBytes, transport.remote.values.single())
    }

    @Test
    fun semanticChangeCreatesOneNextRevision() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val first = repo.publish(listOf(book()))
        val changed = repo.publish(listOf(book(title = "Changed title")))
        val repeated = repo.publish(listOf(book(title = "Changed title")))

        assertEquals(1L, first.catalogRevision)
        assertEquals(2L, changed.catalogRevision)
        assertEquals(SyncV1CatalogPhase.UNCHANGED, repeated.phase)
        assertEquals(2L, repeated.catalogRevision)
        assertEquals(2, transport.putBodies.size)
    }

    @Test
    fun localRootPathMigrationKeepsIdentityAndDoesNotPublishNewRevision() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val repo = repository(store, transport)
        val oldPath = "content://provider/tree/old/document/books%2Fsample.epub"
        val newPath = "content://provider/tree/new/document/books%2Fsample.epub"

        val first = repo.publish(listOf(book(bookUrl = oldPath)))
        val originalCatalog = requireNotNull(SyncV1Protocol.parseCatalog(transport.remote.values.single()))
        val originalWork = originalCatalog.works.single()
        assertTrue(originalWork.bookUrlAliases.isEmpty())

        store.linkExplicitWorkAlias(oldPath, newPath)
        val migrated = repo.publish(listOf(book(bookUrl = newPath)))
        val switchedBack = store.linkExplicitWorkAlias(newPath, oldPath)
        val afterSwitchBack = repo.publish(listOf(book(bookUrl = oldPath)))

        assertEquals(SyncV1CatalogPhase.PUBLISHED, first.phase)
        assertEquals(SyncV1CatalogPhase.UNCHANGED, migrated.phase)
        assertEquals(SyncV1CatalogPhase.UNCHANGED, afterSwitchBack.phase)
        assertEquals(1L, migrated.catalogRevision)
        assertEquals(1L, afterSwitchBack.catalogRevision)
        assertEquals(originalWork.workId, switchedBack.workId)
        assertEquals(originalWork.workId, store.aliases[oldPath])
        assertEquals(originalWork.workId, store.aliases[newPath])
        assertEquals(originalWork.updatedAt, requireNotNull(store.latestPublishedCatalogIntent("service", "root"))
            .let { SyncV1Protocol.parseCatalog(it.rawJsonBytes)!!.works.single().updatedAt })
        assertEquals(1, transport.putBodies.size)
        assertEquals(1, transport.remote.size)
        assertEquals(1L, store.catalogPublicationState("service", "root")!!.catalogRevision)
    }

    @Test
    fun remoteIdenticalCatalogIsAdoptedWithoutPut() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val remoteBytes = catalogBytes()
        val path = "legadom_ink_sync/v1/android/catalog/4-$snapshotOne.json"
        transport.remote[path] = remoteBytes

        val outcome = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1CatalogPhase.UNCHANGED, outcome.phase)
        assertEquals(4L, outcome.catalogRevision)
        assertEquals(0, transport.putBodies.size)
        assertArrayEquals(remoteBytes, store.latestPublishedCatalogIntent("service", "root")!!.rawJsonBytes)
    }

    @Test
    fun existingDifferentBytesAtIntentPathIsRevisionConflict() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val raw = catalogBytes(revision = 1, snapshotId = snapshotOne.toString())
        val path = "legadom_ink_sync/v1/android/catalog/1-$snapshotOne.json"
        store.seedPending(intent(raw, 1, snapshotOne, path))
        val collision = "{\"different\":true}".toByteArray()
        transport.remote[path] = collision

        val result = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, result.errorCode)
        assertArrayEquals(collision, transport.remote[path])
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun sameRevisionWithMultipleSnapshotsIsRevisionConflict() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val one = catalogBytes(revision = 3, snapshotId = snapshotOne.toString())
        val otherId = "99999999-9999-4999-8999-999999999999"
        val other = catalogBytes(revision = 3, snapshotId = otherId)
        transport.remote["legadom_ink_sync/v1/android/catalog/3-$snapshotOne.json"] = one
        transport.remote["legadom_ink_sync/v1/android/catalog/3-$otherId.json"] = other

        val outcome = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun putTimeoutAfterStoredBytesRecoversByReadback() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply {
            putHandler = { path, bytes, _ ->
                remote[path] = bytes.copyOf()
                throw SocketTimeoutException("simulated")
            }
        }

        val result = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1CatalogPhase.PUBLISHED, result.phase)
        assertEquals(1, transport.putBodies.size)
        assertTrue(store.publishedIntents.isNotEmpty())
    }

    @Test
    fun unknownPutResultRetriesSameBytesAtMostTwice() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport().apply {
            putHandler = { _, _, _ -> throw SocketTimeoutException("unknown") }
        }

        val result = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1ErrorCode.RETRY_LIMIT, result.errorCode)
        assertEquals(2, transport.putBodies.size)
        assertArrayEquals(transport.putBodies[0], transport.putBodies[1])
        assertEquals(2, store.pending.single().putAttemptCount)
    }

    @Test
    fun ambiguousRemoteAliasOwnershipReturnsIdentityConflict() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val alias = requireNotNull(SyncV1BookUrlAlias.digestIfSafe(url))
        transport.remote["legadom_ink_sync/v1/android/catalog/1-$snapshotOne.json"] = catalogBytes(
            revision = 1,
            snapshotId = snapshotOne.toString(),
            works = listOf(
                catalogWork("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", alias),
                catalogWork("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", alias)
            )
        )

        val outcome = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1ErrorCode.IDENTITY_CONFLICT, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun workLimitIsEnforcedWithoutTruncatingOrUploading() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val books = (0..SYNC_V1_MAX_CATALOG_WORKS).map { index ->
            SyncV1CatalogBook("https://books.example/$index", "Title $index", "")
        }

        val outcome = repository(store, transport).publish(books)

        assertEquals(SyncV1ErrorCode.CATALOG_WORK_LIMIT, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun oneMiBCatalogLimitRejectsWholeObject() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val transport = FakeTransport()
        val title = "长".repeat(512)
        val books = (0 until SYNC_V1_MAX_CATALOG_WORKS).map { index ->
            SyncV1CatalogBook("https://books.example/$index", title, "")
        }

        val outcome = repository(store, transport).publish(books)

        assertEquals(SyncV1ErrorCode.CATALOG_TOO_LARGE, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun unavailableWebDavOrMissingReadyDeviceDoesNotTouchRegistrationState() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore().apply { ready = false }
        val transport = FakeTransport()
        val result = repository(store, transport).publish(listOf(book()))

        assertEquals(SyncV1ErrorCode.NO_READY_DEVICE, result.errorCode)
        assertEquals(0, transport.requestCount)
        assertTrue(store.states.isEmpty())
    }

    private fun repository(store: MemoryStore, transport: FakeTransport) = SyncV1CatalogRepository(
        store = store,
        sessionProvider = { SyncV1Session("config", "service", "root", transport) { true } },
        nowMillis = { baseTime },
        uuidProvider = { UUID.randomUUID() }
    )

    private fun book(
        title: String = "Example title",
        bookUrl: String = url
    ) = SyncV1CatalogBook(bookUrl, title, "Author")

    private fun intent(raw: ByteArray, revision: Long, snapshotId: UUID, path: String) =
        SyncV1CatalogPublicationIntent(
            revision, snapshotId, path, raw, SyncV1Protocol.sha256(raw), "a".repeat(64), 0,
            "config", "service", "root", "pending", baseTime, baseTime
        )

    private fun catalogBytes(
        revision: Long = 4,
        snapshotId: String = snapshotOne.toString(),
        works: List<JsonObject> = listOf(catalogWork(workId, requireNotNull(SyncV1BookUrlAlias.digestIfSafe(url))))
    ): ByteArray = JsonObject().apply {
        addProperty("protocol_version", "sync-v1")
        addProperty("schema_version", "1.0.0")
        addProperty("normalization_version", "normalization-v1")
        addProperty("device_id", publisherId)
        addProperty("catalog_revision", revision)
        addProperty("snapshot_id", snapshotId)
        addProperty("generated_at", "2026-11-11T00:00:00Z")
        add("works", JsonArray().apply { works.forEach(::add) })
    }.toString().toByteArray(Charsets.UTF_8)

    private fun catalogWork(id: String, alias: String) = JsonObject().apply {
        addProperty("work_id", id)
        addProperty("title", "Example title")
        add("authors", JsonArray().apply { add("Author") })
        add("book_url_aliases", JsonArray().apply { add(alias) })
        addProperty("toc_revision", 0)
        addProperty("progress_revision", 0)
        addProperty("updated_at", Instant.ofEpochMilli(baseTime).toString())
    }

    private class MemoryStore : SyncV1CatalogStore {
        private val baseTime = 1_800_000_000_000L
        var ready = true
        val identities = linkedMapOf<String, SyncV1WorkIdentity>()
        val aliases = linkedMapOf<String, String>()
        val pending = mutableListOf<SyncV1CatalogPublicationIntent>()
        val publishedIntents = mutableListOf<SyncV1CatalogPublicationIntent>()
        val states = linkedMapOf<Pair<String, String>, SyncV1CatalogPublicationState>()

        override suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID) = "11111111-1111-4111-8111-111111111111"
        override suspend fun hasReadyDevice() = ready
        override suspend fun workAliases() = aliases.map { SyncV1WorkAlias(it.key, it.value) }

        override suspend fun resolveWorkIdentity(
            bookUrl: String,
            matchingRemoteWorkIds: Set<String>,
            uuidProvider: () -> UUID
        ): SyncV1WorkIdentityResolution {
            aliases[bookUrl]?.let { return SyncV1WorkIdentityResolution.Resolved(identity(it)) }
            if (matchingRemoteWorkIds.size > 1) return SyncV1WorkIdentityResolution.IdentityConflict
            val id = matchingRemoteWorkIds.singleOrNull() ?: uuidProvider().toString()
            aliases[bookUrl] = id
            return SyncV1WorkIdentityResolution.Resolved(identity(id))
        }

        override suspend fun updateWorkCatalogFields(
            identity: SyncV1WorkIdentity,
            catalogFieldsSha256: String,
            now: Long,
            initialUpdatedAt: Long?
        ): SyncV1WorkIdentity {
            val previous = this.identities[identity.workId]
            val updated = identity.copy(
                catalogFieldsSha256 = catalogFieldsSha256,
                updatedAt = if (previous?.catalogFieldsSha256 == catalogFieldsSha256) previous.updatedAt
                    else initialUpdatedAt ?: now
            )
            identities[identity.workId] = updated
            return updated
        }

        override suspend fun linkExplicitWorkAlias(oldBookUrl: String, newBookUrl: String, uuidProvider: () -> UUID): SyncV1WorkIdentity {
            val id = aliases[oldBookUrl] ?: uuidProvider().toString().also { aliases[oldBookUrl] = it }
            aliases[newBookUrl] = id
            return identity(id)
        }

        override suspend fun pendingCatalogIntent(serviceIdentity: String, rootIdentity: String) =
            pending.firstOrNull { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }

        override suspend fun latestPublishedCatalogIntent(serviceIdentity: String, rootIdentity: String) =
            publishedIntents.filter { it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity }
                .maxByOrNull { it.catalogRevision }

        override suspend fun catalogPublicationState(serviceIdentity: String, rootIdentity: String) =
            states[serviceIdentity to rootIdentity]

        override suspend fun createCatalogIntentIfNoPending(intent: SyncV1CatalogPublicationIntent): SyncV1CatalogPublicationIntent {
            val previous = pending.firstOrNull {
                it.serviceIdentity == intent.serviceIdentity && it.rootIdentity == intent.rootIdentity
            }
            if (previous != null) return previous
            pending += intent
            return intent
        }

        override suspend fun incrementCatalogPutAttempt(intent: SyncV1CatalogPublicationIntent, now: Long): Int {
            val index = pending.indexOfFirst { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            val updated = copyIntent(pending[index], count = pending[index].putAttemptCount + 1)
            pending[index] = updated
            return updated.putAttemptCount
        }

        override suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent) {
            val index = pending.indexOfFirst { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            if (index >= 0) pending.removeAt(index)
            val published = copyIntent(intent, status = "published")
            publishedIntents.removeAll { it.remotePath == intent.remotePath && it.rootIdentity == intent.rootIdentity }
            publishedIntents += published
            states[intent.serviceIdentity to intent.rootIdentity] = SyncV1CatalogPublicationState(
                intent.catalogRevision, intent.snapshotId, intent.remotePath, intent.semanticSha256,
                intent.rawSha256, intent.serviceIdentity, intent.rootIdentity, baseTime
            )
        }

        fun seedPending(intent: SyncV1CatalogPublicationIntent) { pending += intent }

        private fun identity(id: String) = identities.getOrPut(id) { SyncV1WorkIdentity(id, updatedAt = baseTime) }

        private fun copyIntent(
            source: SyncV1CatalogPublicationIntent,
            count: Int = source.putAttemptCount,
            status: String = source.publicationStatus
        ) = SyncV1CatalogPublicationIntent(
            source.catalogRevision, source.snapshotId, source.remotePath, source.rawJsonBytes,
            source.rawSha256, source.semanticSha256, count, source.configIdentity, source.serviceIdentity,
            source.rootIdentity, status, source.createdAt, source.updatedAt
        )
    }

    private class FakeTransport : SyncV1WebDavTransport {
        val remote = linkedMapOf<String, ByteArray>()
        val putPaths = mutableListOf<String>()
        val putBodies = mutableListOf<ByteArray>()
        var requestCount = 0
        var putHandler: suspend FakeTransport.(String, ByteArray, Int) -> SyncV1HttpResponse = { path, bytes, _ ->
            remote[path] = bytes.copyOf()
            SyncV1HttpResponse(201)
        }

        override suspend fun ensureDirectory(relativePath: String) { requestCount++ }

        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> {
            requestCount++
            return remote.keys.filter { it.startsWith(relativePath) }.mapNotNull { path ->
                val name = path.removePrefix(relativePath)
                if (name.isBlank() || '/' in name) null else SyncV1RemoteEntry(name, false)
            }
        }

        override suspend fun get(relativePath: String, maxBytes: Int, computeFullSha256: Boolean): SyncV1HttpResponse {
            requestCount++
            val bytes = remote[relativePath] ?: return SyncV1HttpResponse(404)
            return SyncV1HttpResponse(200, bytes.copyOf(), rawSha256 = if (computeFullSha256) SyncV1Protocol.sha256(bytes) else null)
        }

        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            requestCount++
            putPaths += relativePath
            putBodies += bytes.copyOf()
            return putHandler(relativePath, bytes.copyOf(), putBodies.size)
        }
    }
}
