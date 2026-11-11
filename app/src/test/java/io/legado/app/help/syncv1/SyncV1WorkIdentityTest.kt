package io.legado.app.help.syncv1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SyncV1WorkIdentityTest {
    @Test
    fun repeatedFullBookUrlResolvesToSameWorkId() = kotlinx.coroutines.runBlocking {
        val store = MemoryCatalogStore()
        val repository = SyncV1WorkIdentityRepository(store)
        val url = "https://books.example/work/17"

        val first = repository.resolve(url) as SyncV1WorkIdentityResolution.Resolved
        val second = repository.resolve(url) as SyncV1WorkIdentityResolution.Resolved

        assertEquals(first.identity.workId, second.identity.workId)
        assertEquals(0L, first.identity.tocRevision)
        assertEquals(0L, first.identity.progressRevision)
        assertEquals(1, store.aliases.size)
    }

    @Test
    fun explicitSourceChangeKeepsOldAndNewUrlsOnOneIdentity() = kotlinx.coroutines.runBlocking {
        val store = MemoryCatalogStore()
        val repository = SyncV1WorkIdentityRepository(store)
        val oldUrl = "https://old.example/book/17"
        val newUrl = "https://new.example/book/91"
        val original = repository.resolve(oldUrl) as SyncV1WorkIdentityResolution.Resolved

        val switched = repository.recordExplicitSourceChange(oldUrl, newUrl)

        assertEquals(original.identity.workId, switched.workId)
        assertEquals(original.identity.workId, store.aliases[oldUrl])
        assertEquals(original.identity.workId, store.aliases[newUrl])
    }

    @Test
    fun localRootMigrationAndSwitchBackKeepOneWorkIdentity() = kotlinx.coroutines.runBlocking {
        val store = MemoryCatalogStore()
        val stableWorkId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val repository = SyncV1WorkIdentityRepository(store) { stableWorkId }
        val oldPath = "content://provider/tree/old/document/books%2Fsample.epub"
        val newPath = "content://provider/tree/new/document/books%2Fsample.epub"

        val original = repository.resolve(oldPath) as SyncV1WorkIdentityResolution.Resolved
        val migrated = repository.recordExplicitSourceChange(oldPath, newPath)
        val resolvedAtNewPath = repository.resolve(newPath) as SyncV1WorkIdentityResolution.Resolved
        val switchedBack = repository.recordExplicitSourceChange(newPath, oldPath)

        assertEquals(original.identity.workId, migrated.workId)
        assertEquals(original.identity.workId, resolvedAtNewPath.identity.workId)
        assertEquals(original.identity.workId, switchedBack.workId)
        assertEquals(original.identity.workId, store.aliases[oldPath])
        assertEquals(original.identity.workId, store.aliases[newPath])
        assertNull(SyncV1BookUrlAlias.digestIfSafe(oldPath))
        assertNull(SyncV1BookUrlAlias.digestIfSafe(newPath))
    }

    @Test
    fun failedLocalBookPathUpdateDoesNotWriteIdentityAlias() {
        val oldPath = "content://provider/tree/old/document/books%2Fsample.epub"
        val newPath = "content://provider/tree/new/document/books%2Fsample.epub"
        var bookUrl = oldPath
        val aliases = linkedMapOf<String, String>()
        var failed = false

        try {
            persistLocalBookUrlMigration(
                oldBookUrl = oldPath,
                newBookUrl = newPath,
                persistBookUpdate = {
                    bookUrl = newPath
                    throw IllegalStateException("book update failed")
                },
                restoreBookUrl = { bookUrl = oldPath },
                recordIdentityMigration = { from, to -> aliases[to] = aliases[from] ?: "unexpected" }
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(oldPath, bookUrl)
        assertFalse(aliases.containsKey(newPath))
    }

    @Test
    fun unchangedLocalBookPathSkipsPersistenceAndIdentityWork() {
        val path = "content://provider/tree/current/document/books%2Fsample.epub"
        var updateCalled = false
        var mappingCalled = false

        persistLocalBookUrlMigration(
            oldBookUrl = path,
            newBookUrl = path,
            persistBookUpdate = { updateCalled = true },
            restoreBookUrl = {},
            recordIdentityMigration = { _, _ -> mappingCalled = true }
        )

        assertFalse(updateCalled)
        assertFalse(mappingCalled)
    }

    @Test
    fun differentUrlsAreNotMergedByMatchingBookMetadata() = kotlinx.coroutines.runBlocking {
        val store = MemoryCatalogStore()
        val repository = SyncV1WorkIdentityRepository(store)
        val ids = ArrayDeque(
            listOf(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
            )
        )

        val first = SyncV1WorkIdentityRepository(store) { ids.removeFirst() }
            .resolve("https://one.example/book") as SyncV1WorkIdentityResolution.Resolved
        val second = SyncV1WorkIdentityRepository(store) { ids.removeFirst() }
            .resolve("https://two.example/book") as SyncV1WorkIdentityResolution.Resolved

        assertFalse(first.identity.workId == second.identity.workId)
        assertEquals(2, store.identities.size)
    }

    @Test
    fun unsafeCredentialBearingUrlsDoNotProducePublishedAliasDigest() {
        assertNull(SyncV1BookUrlAlias.digestIfSafe("https://user:pass@books.example/book"))
        assertNull(SyncV1BookUrlAlias.digestIfSafe("https://books.example/book?token=secret"))
        assertNull(SyncV1BookUrlAlias.digestIfSafe("https://books.example/secret/abc"))
        assertNull(SyncV1BookUrlAlias.digestIfSafe("file:///private/book.epub"))
        assertTrue(SyncV1BookUrlAlias.digestIfSafe("https://books.example/book/17")!!.matches(Regex("[a-f0-9]{64}")))
    }

    @Test
    fun recoveryRequiresOneUniqueRemoteAliasOwner() = kotlinx.coroutines.runBlocking {
        val store = MemoryCatalogStore()
        val repository = SyncV1WorkIdentityRepository(store)
        val url = "https://books.example/work/17"
        val digest = requireNotNull(SyncV1BookUrlAlias.digestIfSafe(url))
        val remoteWorkId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"

        val restored = repository.resolve(url, mapOf(digest to setOf(remoteWorkId)))
            as SyncV1WorkIdentityResolution.Resolved
        val conflict = repository.resolve(
            url,
            mapOf(digest to setOf(remoteWorkId, "dddddddd-dddd-4ddd-8ddd-dddddddddddd"))
        )

        assertEquals(remoteWorkId, restored.identity.workId)
        assertEquals(SyncV1WorkIdentityResolution.IdentityConflict, conflict)
    }

    private class MemoryCatalogStore : SyncV1CatalogStore {
        val identities = linkedMapOf<String, SyncV1WorkIdentity>()
        val aliases = linkedMapOf<String, String>()

        override suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID) = uuidProvider().toString()

        override suspend fun workAliases(): List<SyncV1WorkAlias> =
            aliases.map { SyncV1WorkAlias(it.key, it.value) }

        override suspend fun resolveWorkIdentity(
            bookUrl: String,
            matchingRemoteWorkIds: Set<String>,
            uuidProvider: () -> UUID
        ): SyncV1WorkIdentityResolution {
            if (matchingRemoteWorkIds.size > 1) return SyncV1WorkIdentityResolution.IdentityConflict
            aliases[bookUrl]?.let { return SyncV1WorkIdentityResolution.Resolved(identity(it)) }
            val id = matchingRemoteWorkIds.singleOrNull() ?: uuidProvider().toString()
            val identity = identity(id)
            aliases[bookUrl] = id
            return SyncV1WorkIdentityResolution.Resolved(identity)
        }

        override suspend fun updateWorkCatalogFields(
            identity: SyncV1WorkIdentity,
            catalogFieldsSha256: String,
            now: Long,
            initialUpdatedAt: Long?
        ): SyncV1WorkIdentity {
            val updated = identity.copy(
                catalogFieldsSha256 = catalogFieldsSha256,
                updatedAt = if (identity.catalogFieldsSha256 == catalogFieldsSha256) identity.updatedAt
                    else initialUpdatedAt ?: now
            )
            identities[identity.workId] = updated
            return updated
        }

        override suspend fun linkExplicitWorkAlias(
            oldBookUrl: String,
            newBookUrl: String,
            uuidProvider: () -> UUID
        ): SyncV1WorkIdentity {
            val id = aliases[oldBookUrl] ?: uuidProvider().toString().also {
                identity(it)
                aliases[oldBookUrl] = it
            }
            aliases[newBookUrl] = id
            return identity(id)
        }

        override suspend fun hasReadyDevice() = true
        override suspend fun pendingCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent? = null
        override suspend fun latestPublishedCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent? = null
        override suspend fun catalogPublicationState(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationState? = null
        override suspend fun createCatalogIntentIfNoPending(intent: SyncV1CatalogPublicationIntent) = intent
        override suspend fun incrementCatalogPutAttempt(intent: SyncV1CatalogPublicationIntent, now: Long) = 1
        override suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent) = Unit

        private fun identity(id: String) = identities.getOrPut(id) { SyncV1WorkIdentity(id) }
    }
}
