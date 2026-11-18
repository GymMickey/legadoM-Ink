package io.legado.app.help.syncv1

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class SyncV1BindingRepositoryTest {
    private val deviceA = "11111111-1111-4111-8111-111111111111"
    private val deviceB = "22222222-2222-4222-8222-222222222222"
    private val epoch = "33333333-3333-4333-8333-333333333333"
    private val workA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val workB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val outsiderWork = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
    private val publisher = "99999999-9999-4999-8999-999999999999"
    private val service = "test-service"
    private val root = "test-root"

    @Test
    fun exactTitleAndAuthorBindEvenWhenCatalogHasNoChapters() = runBlocking {
        val env = environment(catalog(work(workA, tocRevision = 0)))
        env.transport.addEvent(deviceA, event(eventId(1), title = "Novel", author = "Author"))
        env.transport.addEvent(
            deviceA,
            event(eventId(2), title = "Novel", author = "", contentId = "kindle-content-2")
        )

        val result = env.repository().processRequests()

        assertEquals(2, result.bound)
        assertEquals(2, env.store.bindings.size)
        assertTrue(env.store.bindings.values.all { it.workId == workA })
        assertEquals("bound", ack(env.transport, deviceA, eventId(1)).get("status").asString)
    }

    @Test
    fun sameTitleWithMultipleCandidatesIsAmbiguousAndSelectionBindsOnlyCandidate() = runBlocking {
        val env = environment(catalog(work(workA), work(workB)))
        val firstEvent = event(eventId(1), title = "Novel", author = "Author", sequence = 10)
        env.transport.addEvent(deviceA, firstEvent)

        val first = env.repository().processRequests()

        assertEquals(1, first.ambiguous)
        assertEquals("ambiguous", ack(env.transport, deviceA, eventId(1)).get("status").asString)
        env.transport.addEvent(
            deviceA,
            event(eventId(2), title = "Novel", author = "Author", sequence = 11)
                .also { it.addProperty("selected_work_id", workB); it.addProperty("selection_from_event_id", eventId(1)) }
        )

        val selected = env.repository().processRequests()

        assertEquals(1, selected.bound)
        assertEquals(workB, env.store.bindings.values.single().workId)
    }

    @Test
    fun selectionOutsideCandidatesAndCrossEventIdentityMismatchAreRejected() = runBlocking {
        data class InvalidSelection(
            val device: String = deviceA,
            val epochValue: String = epoch,
            val content: String = "kindle-content-1",
            val sequence: Long = 11,
            val selectedWork: String = workB
        )
        val cases = listOf(
            InvalidSelection(selectedWork = outsiderWork),
            InvalidSelection(device = deviceB),
            InvalidSelection(epochValue = "44444444-4444-4444-8444-444444444444"),
            InvalidSelection(content = "different-content"),
            InvalidSelection(sequence = 10)
        )

        cases.forEachIndexed { index, invalid ->
            val env = environment(catalog(work(workA), work(workB)))
            if (invalid.device == deviceB) env.store.ready += "$service|$root|$deviceB"
            env.transport.addEvent(deviceA, event(eventId(10 + index * 2), sequence = 10))
            env.repository().processRequests()
            val sourceId = eventId(10 + index * 2)
            val selected = event(
                eventId(11 + index * 2),
                deviceId = invalid.device,
                counterEpoch = invalid.epochValue,
                sequence = invalid.sequence,
                contentId = invalid.content
            ).apply {
                addProperty("selected_work_id", invalid.selectedWork)
                addProperty("selection_from_event_id", sourceId)
            }
            env.transport.addEvent(invalid.device, selected)

            env.repository().processRequests()

            assertEquals("rejected", ack(env.transport, invalid.device, eventId(11 + index * 2)).get("status").asString)
            assertEquals("invalid_event", ack(env.transport, invalid.device, eventId(11 + index * 2)).get("reason_code").asString)
            assertTrue(env.store.bindings.isEmpty())
        }
    }

    @Test
    fun missingOrMoreThanTwentyCandidatesReturnNotFound() = runBlocking {
        val missing = environment(catalog(work(workA, title = "Other")))
        missing.transport.addEvent(deviceA, event(eventId(1)))
        assertEquals(1, missing.repository().processRequests().notFound)

        val tooMany = environment(catalog(*(0..SYNC_V1_MAX_BINDING_CANDIDATES).map { index ->
            work(if (index == 0) workA else uuidFor(index), title = "Novel")
        }.toTypedArray()))
        tooMany.transport.addEvent(deviceA, event(eventId(2)))
        assertEquals(1, tooMany.repository().processRequests().notFound)
        assertEquals("not_found", ack(tooMany.transport, deviceA, eventId(2)).get("status").asString)
    }

    @Test
    fun nonBindingEventIsLeftUntouchedAndOversizedPayloadIsNotParsed() = runBlocking {
        val env = environment(catalog(work(workA)))
        val nonBinding = JsonObject().apply { addProperty("event_type", "progress") }
        env.transport.addRawEvent(deviceA, eventId(1), nonBinding.toString().toByteArray(Charsets.UTF_8))
        env.transport.addRawEvent(deviceA, eventId(2), ByteArray(SYNC_V1_MAX_BINDING_EVENT_BYTES + 1) { 0x20 })

        val result = env.repository().processRequests()

        assertEquals(2, result.unprocessed)
        assertEquals(0, result.discoveredEvents)
        assertFalse(env.transport.remote.keys.any { it.startsWith("legadom_ink_sync/v1/acks/$deviceA/") })
        assertNull(env.store.importReceipt(eventId(1)))
    }

    @Test
    fun invalidSchemaAndPayloadPathMismatchReceiveRejectedAck() = runBlocking {
        val env = environment(catalog(work(workA)))
        val unknownField = event(eventId(1)).apply { addProperty("book_url", "https://private.example/book") }
        val mismatchedPathId = event(eventId(3), eventIdValue = eventId(4))
        val mismatchedDevice = event(eventId(5), deviceId = deviceB)
        val invalidUuid = event(eventId(6), counterEpoch = "33333333-3333-1333-8333-333333333333")
        env.transport.addEvent(deviceA, unknownField)
        env.transport.addEvent(deviceA, mismatchedPathId, pathEventId = eventId(3))
        env.transport.addEvent(deviceA, mismatchedDevice)
        env.transport.addEvent(deviceA, invalidUuid)

        val result = env.repository().processRequests()

        assertEquals(4, result.rejected)
        assertEquals("rejected", ack(env.transport, deviceA, eventId(1)).get("status").asString)
        assertEquals("unsupported_schema", ack(env.transport, deviceA, eventId(1)).get("reason_code").asString)
        assertEquals("invalid_event", ack(env.transport, deviceA, eventId(3)).get("reason_code").asString)
        assertEquals("invalid_event", ack(env.transport, deviceA, eventId(5)).get("reason_code").asString)
        assertEquals("invalid_event", ack(env.transport, deviceA, eventId(6)).get("reason_code").asString)
        assertFalse(env.store.receipts.values.any { it.toString().contains("private.example") })
        assertFalse(env.transport.remote.values.any { it.toString(Charsets.UTF_8).contains("private.example") })
    }

    @Test
    fun exactReplayPreservesAckBytesAndAcknowledgedAtAndDifferentDigestConflicts() = runBlocking {
        val env = environment(catalog(work(workA)))
        val raw = event(eventId(1))
        env.transport.addEvent(deviceA, raw)
        env.repository().processRequests()
        val originalReceipt = env.store.importReceipt(eventId(1))!!
        val originalAck = env.transport.remote[ackPath(deviceA, eventId(1))]!!.copyOf()

        env.repository().processRequests()
        val replayedReceipt = env.store.importReceipt(eventId(1))!!
        assertEquals(originalReceipt.acknowledgedAt, replayedReceipt.acknowledgedAt)
        assertArrayEquals(originalReceipt.ackRawJsonBytes, replayedReceipt.ackRawJsonBytes)
        assertArrayEquals(originalAck, env.transport.remote[ackPath(deviceA, eventId(1))])

        val beforeBinding = env.store.bindings.values.single()
        env.transport.addEvent(deviceA, event(eventId(1), title = "Modified payload"))
        val conflict = env.repository().processRequests()

        assertEquals(1, conflict.eventIdConflicts)
        assertEquals(SyncV1ErrorCode.EVENT_ID_CONFLICT, conflict.errorCode)
        assertEquals(beforeBinding, env.store.bindings.values.single())
        assertArrayEquals(originalAck, env.transport.remote[ackPath(deviceA, eventId(1))])
        assertEquals(originalReceipt.payloadSha256, env.store.importReceipt(eventId(1))!!.payloadSha256)
    }

    @Test
    fun putUnknownAfterWriteRecoversAndUnknown404RetriesIdenticalBytes() = runBlocking {
        val committed = environment(catalog(work(workA)))
        committed.transport.addEvent(deviceA, event(eventId(1)))
        committed.transport.putHandler = { path, bytes, _ ->
            committed.transport.remote[path] = bytes.copyOf()
            throw SocketTimeoutException("commit response lost")
        }
        assertEquals(1, committed.repository().processRequests().ackPublished)
        assertEquals(1, committed.transport.putBodies.size)

        val retried = environment(catalog(work(workA)))
        retried.transport.addEvent(deviceA, event(eventId(2)))
        retried.transport.putHandler = { path, bytes, attempt ->
            if (attempt == 2) retried.transport.remote[path] = bytes.copyOf()
            if (attempt == 1) throw SocketTimeoutException("unknown result")
            SyncV1HttpResponse(201)
        }
        assertEquals(1, retried.repository().processRequests().ackPublished)
        assertEquals(2, retried.transport.putBodies.size)
        assertArrayEquals(retried.transport.putBodies[0], retried.transport.putBodies[1])
    }

    @Test
    fun unknownPutResultStopsAfterTwoAttemptsAndLeavesDurableIntent() = runBlocking {
        val env = environment(catalog(work(workA)))
        env.transport.addEvent(deviceA, event(eventId(1)))
        env.transport.putHandler = { _, _, _ -> throw SocketTimeoutException("unknown result") }

        val result = env.repository().processRequests()

        assertEquals(2, env.transport.putBodies.size)
        assertArrayEquals(env.transport.putBodies[0], env.transport.putBodies[1])
        assertEquals(1, result.ackPending)
        assertEquals(2, env.store.pendingAckIntents(service, root).single().putAttemptCount)
    }

    @Test
    fun existingIdenticalAckIsAdoptedAndDifferentAckIsNeverOverwritten() = runBlocking {
        val env = environment(catalog(work(workA)))
        env.transport.addEvent(deviceA, event(eventId(1)))
        env.repository().processRequests()
        val path = ackPath(deviceA, eventId(1))
        val expected = env.transport.remote[path]!!.copyOf()
        val putsBeforeReplay = env.transport.putBodies.size

        env.repository().processRequests()

        assertEquals(putsBeforeReplay, env.transport.putBodies.size)
        val collision = "different immutable ack".toByteArray()
        env.transport.remote[path] = collision
        val result = env.repository().processRequests()

        assertEquals(SyncV1ErrorCode.ACK_CONFLICT, result.errorCode)
        assertArrayEquals(collision, env.transport.remote[path])
        assertArrayEquals(expected, env.store.importReceipt(eventId(1))!!.ackRawJsonBytes)
    }

    @Test
    fun pendingAckRecoversBeforeEventScanAfterRepositoryRecreation() = runBlocking {
        val first = environment(catalog(work(workA)))
        first.transport.addEvent(deviceA, event(eventId(1)))
        first.transport.putHandler = { _, _, _ -> throw SocketTimeoutException("offline") }
        first.transport.getHandler = { path, _, _ ->
            if (path.startsWith("legadom_ink_sync/v1/acks/") && first.transport.putBodies.isNotEmpty()) {
                throw SocketTimeoutException("readback unavailable")
            }
            null
        }
        assertEquals(1, first.repository().processRequests().ackPending)
        assertEquals(1, first.transport.putBodies.size)

        val recoveryTransport = FakeTransport()
        recoveryTransport.putHandler = { path, bytes, _ ->
            recoveryTransport.remote[path] = bytes.copyOf()
            SyncV1HttpResponse(201)
        }
        val recoveryRepository = SyncV1BindingRepository(
            first.store,
            { null },
            { session(recoveryTransport) },
            nowMillis = { 1_800_000_000_000L }
        )

        val recovered = recoveryRepository.processRequests()

        assertEquals(1, recovered.ackPublished)
        assertTrue(recoveryTransport.getPaths.none { it.startsWith("legadom_ink_sync/v1/events/") })
        assertEquals(0, first.store.pendingAckCount(service, root))
    }

    @Test
    fun readyDevicesAndPendingIntentsAreIsolatedByServiceAndRoot() = runBlocking {
        val env = environment(catalog(work(workA)))
        env.transport.addEvent(deviceA, event(eventId(1)))
        env.transport.putHandler = { _, _, _ -> throw SocketTimeoutException("unknown") }
        env.transport.getHandler = { path, _, _ ->
            if (path.startsWith("legadom_ink_sync/v1/acks/") && env.transport.putBodies.isNotEmpty()) {
                throw SocketTimeoutException("readback unavailable")
            }
            null
        }
        assertEquals(1, env.repository().processRequests().ackPending)
        assertEquals(1, env.store.pendingAckCount(service, root))

        env.store.ready += "other-service|other-root|$deviceA"
        val otherRootTransport = FakeTransport()
        val otherRootRepo = SyncV1BindingRepository(
            env.store,
            { catalog(work(workA)) },
            { session(otherRootTransport, "other-root", "other-service") }
        )
        val otherRootResult = otherRootRepo.processRequests()

        assertEquals(1, otherRootResult.scannedDevices)
        assertEquals(0, otherRootResult.ackPublished)
        assertEquals(0, otherRootResult.ackPending)
        assertEquals(1, env.store.pendingAckCount(service, root))
        assertTrue(otherRootTransport.getPaths.none { it.startsWith("legadom_ink_sync/v1/acks/") })
    }

    @Test
    fun acknowledgementsAndReceiptsDoNotPersistOrExposeBookUrlOrCredentials() = runBlocking {
        val privateUrl = "https://private.example/book/secret"
        val authorizationMarker = "test-only-authorization-marker"
        val env = environment(catalog(work(workA)))
        // An attempted URL field is rejected by the strict schema and is never copied into receipt/ack.
        env.transport.addEvent(deviceA, event(eventId(1)).apply {
            addProperty("book_url", privateUrl)
            addProperty("authorization", authorizationMarker)
        })

        env.repository().processRequests()

        val receipt = env.store.importReceipt(eventId(1))!!
        val ackBytes = env.transport.remote[ackPath(deviceA, eventId(1))]!!
        assertFalse(receipt.toString().contains("password"))
        assertFalse(receipt.toString().contains("private.example"))
        assertFalse(ackBytes.toString(Charsets.UTF_8).contains("private.example"))
        assertFalse(env.store.receipts.values.toString().contains(privateUrl))
        assertFalse(env.store.receipts.values.toString().contains(authorizationMarker))
    }

    private fun environment(catalog: ParsedCatalog?): Environment {
        val store = FakeBindingStore().apply { ready += "$service|$root|$deviceA" }
        val transport = FakeTransport()
        return Environment(store, transport, catalog)
    }

    private fun Environment.repository() = SyncV1BindingRepository(
        store = store,
        catalogProvider = { catalog },
        sessionProvider = { session(transport) },
        nowMillis = { 1_800_000_000_000L }
    )

    private fun session(
        transport: FakeTransport,
        rootIdentity: String = root,
        serviceIdentity: String = service
    ) = SyncV1Session(
        configIdentity = "config",
        serviceIdentity = serviceIdentity,
        rootIdentity = rootIdentity,
        transport = transport,
        isCurrent = { true }
    )

    private fun catalog(vararg works: ParsedCatalogWork) = ParsedCatalog(
        deviceId = publisher,
        catalogRevision = 1,
        snapshotId = "88888888-8888-4888-8888-888888888888",
        generatedAt = "2026-11-15T00:00:00Z",
        works = works.toList()
    )

    private fun work(
        id: String,
        title: String = "Novel",
        authors: List<String> = listOf("Author"),
        tocRevision: Long = 1
    ) = ParsedCatalogWork(id, title, authors, emptyList(), tocRevision, 0, "2026-11-15T00:00:00Z")

    private fun event(
        id: String,
        title: String = "Novel",
        author: String = "Author",
        sequence: Long = 1,
        deviceId: String = deviceA,
        counterEpoch: String = epoch,
        contentId: String = "kindle-content-1",
        eventIdValue: String = id
    ) = JsonObject().apply {
        addProperty("protocol_version", "sync-v1")
        addProperty("schema_version", "1.0.0")
        addProperty("normalization_version", "normalization-v1")
        addProperty("event_type", "binding_request")
        addProperty("event_id", eventIdValue)
        addProperty("device_id", deviceId)
        addProperty("counter_epoch", counterEpoch)
        addProperty("sequence", sequence)
        addProperty("created_at", "2026-11-15T00:00:00Z")
        addProperty("content_id", contentId)
        addProperty("title", title)
        addProperty("author", author)
        addProperty("local_toc_count", 0)
    }

    private fun eventId(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"

    private fun uuidFor(n: Int) = "00000000-0000-4000-8000-${(100 + n).toString().padStart(12, '0')}"

    private fun ack(transport: FakeTransport, deviceId: String, eventId: String) =
        com.google.gson.JsonParser.parseString(
            transport.remote.getValue(ackPath(deviceId, eventId)).toString(Charsets.UTF_8)
        ).asJsonObject

    private fun ackPath(deviceId: String, eventId: String) =
        "legadom_ink_sync/v1/acks/$deviceId/$eventId.json"

    private data class Environment(
        val store: FakeBindingStore,
        val transport: FakeTransport,
        val catalog: ParsedCatalog?
    )

    private class FakeBindingStore : SyncV1BindingStore {
        val ready = mutableSetOf<String>()
        val receipts = linkedMapOf<String, SyncV1ImportReceipt>()
        val bindings = linkedMapOf<Triple<String, String, String>, SyncV1KindleContentBinding>()
        val intents = linkedMapOf<Triple<String, String, String>, SyncV1AckPublicationIntent>()

        override suspend fun readyDeviceIds(serviceIdentity: String, rootIdentity: String) =
            ready.filter { it.startsWith("$serviceIdentity|$rootIdentity|") }
                .map { it.substringAfterLast('|') }

        override suspend fun importReceipt(eventId: String) = receipts[eventId]

        override suspend fun contentBinding(deviceId: String, counterEpoch: String, contentId: String) =
            bindings[Triple(deviceId, counterEpoch, contentId)]

        override suspend fun persistBindingOutcome(
            receipt: SyncV1ImportReceipt,
            intent: SyncV1AckPublicationIntent,
            binding: SyncV1KindleContentBinding?
        ): SyncV1BindingPersistResult {
            receipts[receipt.eventId]?.let { existing ->
                return if (existing.deviceId == receipt.deviceId && existing.payloadSha256 == receipt.payloadSha256) {
                    SyncV1BindingPersistResult.Existing(existing)
                } else SyncV1BindingPersistResult.EventIdConflict
            }
            val key = binding?.let { Triple(it.deviceId, it.counterEpoch, it.contentId) }
            val existingBinding = key?.let(bindings::get)
            if (existingBinding != null && existingBinding.workId != binding.workId) {
                return SyncV1BindingPersistResult.BindingConflict
            }
            if (binding != null && key != null) bindings.putIfAbsent(key, binding)
            receipts[receipt.eventId] = receipt
            intents[Triple(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)] = intent
            return SyncV1BindingPersistResult.Stored
        }

        override suspend fun ensureAckIntent(
            receipt: SyncV1ImportReceipt,
            serviceIdentity: String,
            rootIdentity: String
        ): SyncV1AckPublicationIntent {
            val remotePath = "legadom_ink_sync/v1/acks/${receipt.deviceId}/${receipt.eventId}.json"
            val key = Triple(serviceIdentity, rootIdentity, remotePath)
            return intents[key] ?: SyncV1AckPublicationIntent(
                receipt.deviceId, receipt.eventId, key.third, receipt.ackRawJsonBytes,
                receipt.ackSha256, 0, serviceIdentity, rootIdentity, "pending", 1, 1
            ).also { intents[key] = it }
        }

        override suspend fun pendingAckIntents(serviceIdentity: String, rootIdentity: String) =
            intents.values.filter {
                it.serviceIdentity == serviceIdentity && it.rootIdentity == rootIdentity && it.publicationStatus == "pending"
            }

        override suspend fun incrementAckPutAttempt(intent: SyncV1AckPublicationIntent, now: Long): Int {
            val key = Triple(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
            val current = intents.getValue(key)
            val updated = SyncV1AckPublicationIntent(
                current.deviceId, current.eventId, current.remotePath, current.rawJsonBytes,
                current.rawSha256, current.putAttemptCount + 1, current.serviceIdentity, current.rootIdentity,
                current.publicationStatus, current.createdAt, now
            )
            intents[key] = updated
            return updated.putAttemptCount
        }

        override suspend fun markAckPublished(intent: SyncV1AckPublicationIntent, now: Long) {
            val key = Triple(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
            val current = intents.getValue(key)
            intents[key] = SyncV1AckPublicationIntent(
                current.deviceId, current.eventId, current.remotePath, current.rawJsonBytes,
                current.rawSha256, current.putAttemptCount, current.serviceIdentity, current.rootIdentity,
                "published", current.createdAt, now
            )
        }

        override suspend fun pendingAckCount(serviceIdentity: String, rootIdentity: String) =
            pendingAckIntents(serviceIdentity, rootIdentity).size
    }

    private class FakeTransport : SyncV1WebDavTransport {
        val events = linkedMapOf<String, ByteArray>()
        val remote = linkedMapOf<String, ByteArray>()
        val getPaths = mutableListOf<String>()
        val putPaths = mutableListOf<String>()
        val putBodies = mutableListOf<ByteArray>()
        var putHandler: (suspend (String, ByteArray, Int) -> SyncV1HttpResponse)? = null
        var getHandler: (suspend (String, Int, Boolean) -> SyncV1HttpResponse?)? = null

        fun addEvent(deviceId: String, value: JsonObject, pathEventId: String? = null) {
            val payload = value.toString().toByteArray(Charsets.UTF_8)
            val eventId = pathEventId ?: value.get("event_id").asString
            addRawEvent(deviceId, eventId, payload)
        }

        fun addRawEvent(deviceId: String, eventId: String, payload: ByteArray) {
            events["legadom_ink_sync/v1/events/$deviceId/$eventId.json"] = payload.copyOf()
        }

        override suspend fun ensureDirectory(relativePath: String) = Unit

        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> {
            val prefix = "legadom_ink_sync/v1/events/"
            val matching = events.keys.filter { it.startsWith(prefix) }
            if (relativePath == prefix) {
                return matching.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
                    .map { SyncV1RemoteEntry(it, true) }
            }
            val devicePrefix = relativePath
            return matching.filter { it.startsWith(devicePrefix) }
                .map { it.removePrefix(devicePrefix).substringBefore('/') }
                .filter(String::isNotEmpty)
                .distinct()
                .map { SyncV1RemoteEntry(it, false) }
        }

        override suspend fun get(relativePath: String, maxBytes: Int, computeFullSha256: Boolean): SyncV1HttpResponse {
            getPaths += relativePath
            getHandler?.invoke(relativePath, maxBytes, computeFullSha256)?.let { return it }
            val body = events[relativePath] ?: remote[relativePath]
                ?: return SyncV1HttpResponse(404)
            return if (body.size > maxBytes) {
                SyncV1HttpResponse(200, bodyTooLarge = true)
            } else {
                SyncV1HttpResponse(200, body.copyOf(), rawSha256 = SyncV1Protocol.sha256(body))
            }
        }

        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            putPaths += relativePath
            putBodies += bytes.copyOf()
            val attempt = putBodies.size
            return putHandler?.invoke(relativePath, bytes.copyOf(), attempt) ?: run {
                remote[relativePath] = bytes.copyOf()
                SyncV1HttpResponse(201)
            }
        }
    }
}
