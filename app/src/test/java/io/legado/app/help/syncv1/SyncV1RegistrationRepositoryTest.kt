package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

class SyncV1RegistrationRepositoryTest {
    private val deviceId = "55555555-5555-4555-8555-555555555555"
    private val currentSnapshotId = "99999999-9999-4999-8999-999999999991"
    private val timestamp = "2026-11-11T00:00:00Z"

    @Test
    fun validRegistrationPublishesUuidVerifiedReadyState() = runBlocking {
        val rawRegistration = registration(deviceId)
        val transport = FakeTransport().apply { addRegistration(deviceId, rawRegistration) }
        val store = MemoryStore()

        val outcome = repository(store, transport).processRegistrations().last()

        assertEquals(SyncV1UiPhase.READY, outcome.phase)
        assertEquals(SyncV1LocalStatus.READY, store.readState(deviceId)?.localStatus)
        assertEquals("ready", outcome.remoteStatus)
        assertEquals("uuid_verified", outcome.createMode)
        assertEquals(1L, outcome.stateRevision)
        val published = transport.remote.entries.single { it.key.contains("android/device-state/$deviceId/") }
        val state = SyncV1Protocol.parseDeviceState(published.value)
        assertNotNull(state)
        assertEquals(SyncV1Protocol.sha256(rawRegistration), state?.registrationPayloadSha256)
        assertEquals("registration_verified", state?.registrationResult)
        assertEquals("chapter_only", state?.locatorCapability)
        assertTrue(transport.ensuredDirectories.contains("legadom_ink_sync/v1/events/$deviceId/"))
        assertTrue(transport.ensuredDirectories.contains("legadom_ink_sync/v1/acks/$deviceId/"))
    }

    @Test
    fun invalidSchemaPublishesRejectedAndClearsModeAndLocator() = runBlocking {
        val raw = "{\"protocol_version\":\"sync-v1\",\"device_id\":\"$deviceId\"}".toByteArray()
        val transport = FakeTransport().apply { addRegistration(deviceId, raw) }
        val store = MemoryStore()
        val outcome = repository(store, transport).processRegistrations().last()

        assertEquals("error=${outcome.errorCode}", SyncV1UiPhase.REJECTED, outcome.phase)
        assertEquals("rejected", outcome.remoteStatus)
        val stateBytes = transport.remote.entries.single { it.key.contains("android/device-state/$deviceId/") }.value
        val state = SyncV1Protocol.parseDeviceState(stateBytes)
        assertEquals("invalid_registration", state?.registrationResult)
        assertNull(state?.createMode)
        assertNull(state?.locatorCapability)
        assertEquals(SyncV1Protocol.sha256(raw), state?.registrationPayloadSha256)
        assertFalse(transport.ensuredDirectories.contains("legadom_ink_sync/v1/events/$deviceId/"))
        assertEquals(SyncV1LocalStatus.REJECTED, store.readState(deviceId)?.localStatus)
    }

    @Test
    fun invalidPayloadUuidIsRejectedUsingFilenameIdentityAndRawDigest() = runBlocking {
        val raw = registration("55555555-5555-4555-7555-555555555555")
        val transport = FakeTransport().apply { addRegistration(deviceId, raw) }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1UiPhase.REJECTED, outcome.phase)
        assertEquals(deviceId, outcome.deviceId)
        val state = publishedState(transport)
        assertEquals("invalid_registration", state.registrationResult)
        assertEquals(SyncV1Protocol.sha256(raw), state.registrationPayloadSha256)
    }

    @Test
    fun changedRegistrationBytesForAnExistingDeviceAreRejectedAsConflict() = runBlocking {
        val original = registration(deviceId)
        val changed = original.toString(Charsets.UTF_8)
            .replace("2026.11", "2026.12")
            .toByteArray(Charsets.UTF_8)
        val transport = FakeTransport().apply { addRegistration(deviceId, original) }
        val store = MemoryStore()
        val repo = repository(store, transport)
        assertEquals(SyncV1UiPhase.READY, repo.processRegistrations().last().phase)

        transport.remote["legadom_ink_sync/v1/devices/$deviceId.json"] = changed
        val outcome = repo.processRegistrations().last()

        assertEquals(SyncV1UiPhase.REJECTED, outcome.phase)
        assertEquals(2L, outcome.stateRevision)
        val state = transport.remote[statePath(deviceId, 2, currentSnapshotId)]
            ?.let { SyncV1Protocol.parseDeviceState(it) }
        assertEquals("device_id_conflict", state?.registrationResult)
        assertEquals(SyncV1Protocol.sha256(changed), state?.registrationPayloadSha256)
        assertEquals(SyncV1Protocol.sha256(original), store.registrations[deviceId]?.registrationPayloadSha256)
    }

    @Test
    fun invalidJsonPublishesRejectedWithRawByteDigest() = runBlocking {
        val raw = byteArrayOf(0x7b, 0x7d, 0x20, 0x7b)
        val transport = FakeTransport().apply { addRegistration(deviceId, raw) }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals("error=${outcome.errorCode}", SyncV1UiPhase.REJECTED, outcome.phase)
        val state = publishedState(transport)
        assertEquals("invalid_registration", state.registrationResult)
        assertEquals(SyncV1Protocol.sha256(raw), state.registrationPayloadSha256)
    }

    @Test
    fun invalidFilenameIsLocalOnlyAndNeverDownloadedOrPublished() = runBlocking {
        val transport = FakeTransport().apply {
            remote["legadom_ink_sync/v1/devices/not-a-uuid.json"] = registration(deviceId)
        }
        val result = repository(MemoryStore(), transport).processRegistrations().single()
        assertEquals(SyncV1ErrorCode.INVALID_DEVICE_ID, result.errorCode)
        assertNull(result.deviceId)
        assertEquals(1, transport.listCalls)
        assertTrue(transport.getPaths.isEmpty())
        assertTrue(transport.putBodies.isEmpty())
        assertFalse(transport.ensuredDirectories.any { it.contains("device-state/not-a-uuid") })
    }

    @Test
    fun payloadDeviceIdMismatchUsesFilenameIdForRejectedPathAndState() = runBlocking {
        val payloadId = "66666666-6666-4666-8666-666666666666"
        val transport = FakeTransport().apply { addRegistration(deviceId, registration(payloadId)) }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(deviceId, outcome.deviceId)
        assertEquals("error=${outcome.errorCode}", SyncV1UiPhase.REJECTED, outcome.phase)
        val state = publishedState(transport)
        assertEquals(deviceId, state.deviceId)
        assertEquals("device_id_conflict", state.registrationResult)
        assertEquals("legadom_ink_sync/v1/android/device-state/$deviceId/1-$currentSnapshotId.json", transport.putPaths.last())
    }

    @Test
    fun validUnsupportedCreateModePublishesUnsupportedMode() = runBlocking {
        val raw = registration(deviceId, modes = listOf("conditional_create"))
        val transport = FakeTransport().apply { addRegistration(deviceId, raw) }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals("error=${outcome.errorCode}", SyncV1UiPhase.REJECTED, outcome.phase)
        val state = publishedState(transport)
        assertEquals("unsupported_mode", state.registrationResult)
        assertNull(state.createMode)
        assertEquals("chapter_only", state.locatorCapability)
    }

    @Test
    fun existingIdenticalStateIsIdempotentAndDoesNotPut() = runBlocking {
        val rawRegistration = registration(deviceId)
        val registrationHash = SyncV1Protocol.sha256(rawRegistration)
        val existing = deviceState(
            deviceId = deviceId,
            revision = 1,
            snapshotId = currentSnapshotId,
            registrationHash = registrationHash
        )
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            remote[statePath(deviceId, 1, currentSnapshotId)] = existing
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1UiPhase.READY, outcome.phase)
        assertEquals(1L, outcome.stateRevision)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun existingDifferentObjectAtIntentPathIsConflictAndNeverOverwritten() = runBlocking {
        val rawRegistration = registration(deviceId)
        val collisionPath = statePath(deviceId, 1, currentSnapshotId)
        val differentBytes = "{\"occupied\":true}".toByteArray()
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            remote[collisionPath] = differentBytes
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1ErrorCode.REMOTE_CONTENT_CONFLICT, outcome.errorCode)
        assertArrayEquals(differentBytes, transport.remote[collisionPath])
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun putSuccessMustReadBackExactBytes() = runBlocking {
        val rawRegistration = registration(deviceId)
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            putHandler = { path, bytes, _ ->
                remote[path] = bytes + byteArrayOf(0x20)
                SyncV1HttpResponse(201)
            }
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1ErrorCode.READBACK_MISMATCH, outcome.errorCode)
        assertEquals(1, transport.putBodies.size)
    }

    @Test
    fun putTimeoutAfterServerStoredBytesRecoversByGet() = runBlocking {
        val transport = FakeTransport().apply {
            addRegistration(deviceId, registration(deviceId))
            putHandler = { path, bytes, _ ->
                remote[path] = bytes.copyOf()
                throw SocketTimeoutException("test timeout")
            }
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1UiPhase.READY, outcome.phase)
        assertEquals(1, transport.putBodies.size)
        assertEquals("ready", publishedState(transport).status)
    }

    @Test
    fun putTimeoutThen404RetriesSameIntentAtMostOnce() = runBlocking {
        val transport = FakeTransport().apply {
            addRegistration(deviceId, registration(deviceId))
            putHandler = { path, bytes, attempt ->
                if (attempt == 1) throw SocketTimeoutException("unknown result")
                remote[path] = bytes.copyOf()
                SyncV1HttpResponse(201)
            }
        }
        val store = MemoryStore()
        val outcome = repository(store, transport).processRegistrations().last()
        assertEquals(SyncV1UiPhase.READY, outcome.phase)
        assertEquals(2, transport.putBodies.size)
        assertArrayEquals(transport.putBodies[0], transport.putBodies[1])
        assertEquals(2, store.intents.values.single().putAttemptCount)
    }

    @Test
    fun repeatedUnknownPutResultsStopAtTwoAndKeepPendingIntent() = runBlocking {
        val transport = FakeTransport().apply {
            addRegistration(deviceId, registration(deviceId))
            putHandler = { _, _, _ -> throw SocketTimeoutException("unknown result") }
        }
        val store = MemoryStore()
        val outcome = repository(store, transport).processRegistrations().last()
        assertEquals(SyncV1ErrorCode.RETRY_LIMIT, outcome.errorCode)
        assertEquals(2, transport.putBodies.size)
        assertEquals("pending", store.intents.values.single().publicationStatus)
        assertEquals(SyncV1LocalStatus.UPLOADED, store.readState(deviceId)?.localStatus)
    }

    @Test
    fun existingPendingIntentIsRecoveredBeforeNewRevisionIsCreated() = runBlocking {
        val rawRegistration = registration(deviceId)
        val registrationHash = SyncV1Protocol.sha256(rawRegistration)
        val pendingSnapshot = "77777777-7777-4777-8777-777777777777"
        val pendingBytes = deviceState(deviceId, 5, pendingSnapshot, registrationHash)
        val pendingPath = statePath(deviceId, 5, pendingSnapshot)
        val store = MemoryStore().apply {
            seedIntent(
                DeviceStatePublicationIntent(
                    deviceId = deviceId,
                    stateRevision = 5,
                    snapshotId = UUID.fromString(pendingSnapshot),
                    remotePath = pendingPath,
                    rawJsonBytes = pendingBytes,
                    rawSha256 = SyncV1Protocol.sha256(pendingBytes),
                    putAttemptCount = 0,
                    configIdentity = "config-A",
                    serviceIdentity = "service-A",
                    rootIdentity = "root-A",
                    publicationStatus = "pending",
                    createdAt = 1,
                    updatedAt = 1
                )
            )
        }
        val transport = FakeTransport().apply { addRegistration(deviceId, rawRegistration) }
        val results = repository(store, transport).processRegistrations()
        assertEquals(1, transport.putBodies.size)
        assertArrayEquals(pendingBytes, transport.remote[pendingPath])
        assertEquals(5L, results.last().stateRevision)
        assertEquals(1, store.intents.size)
    }

    @Test
    fun sameRevisionWithDifferentSnapshotsStopsWithoutPublishing() = runBlocking {
        val rawRegistration = registration(deviceId)
        val registrationHash = SyncV1Protocol.sha256(rawRegistration)
        val one = "88888888-8888-4888-8888-888888888881"
        val two = "88888888-8888-4888-8888-888888888882"
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            remote[statePath(deviceId, 2, one)] = deviceState(deviceId, 2, one, registrationHash)
            remote[statePath(deviceId, 2, two)] = deviceState(deviceId, 2, two, registrationHash)
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun sameRevisionPathConflictIsDetectedEvenWhenOnePayloadIsInvalid() = runBlocking {
        val rawRegistration = registration(deviceId)
        val registrationHash = SyncV1Protocol.sha256(rawRegistration)
        val one = "88888888-8888-4888-8888-888888888881"
        val two = "88888888-8888-4888-8888-888888888882"
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            remote[statePath(deviceId, 2, one)] = deviceState(deviceId, 2, one, registrationHash)
            remote[statePath(deviceId, 2, two)] = "{invalid-json".toByteArray()
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1ErrorCode.REVISION_CONFLICT, outcome.errorCode)
        assertTrue(transport.putBodies.isEmpty())
    }

    @Test
    fun revisionGapsSelectHighestAndNextRevision() = runBlocking {
        val rawRegistration = registration(deviceId)
        val registrationHash = SyncV1Protocol.sha256(rawRegistration)
        val oldId = "88888888-8888-4888-8888-888888888881"
        val highId = "88888888-8888-4888-8888-888888888883"
        val transport = FakeTransport().apply {
            addRegistration(deviceId, rawRegistration)
            remote[statePath(deviceId, 1, oldId)] = deviceState(
                deviceId, 1, oldId, registrationHash, status = "rejected",
                result = "unsupported_mode", createMode = null, locator = "chapter_only"
            )
            remote[statePath(deviceId, 3, highId)] = deviceState(
                deviceId, 3, highId, registrationHash, status = "rejected",
                result = "unsupported_mode", createMode = null, locator = "chapter_only"
            )
        }
        assertNotNull("revision 1 state must satisfy the device-state schema: ${transport.remote[statePath(deviceId, 1, oldId)]!!.toString(Charsets.UTF_8)}", SyncV1Protocol.parseDeviceState(transport.remote[statePath(deviceId, 1, oldId)]!!))
        assertNotNull("revision 3 state must satisfy the device-state schema: ${transport.remote[statePath(deviceId, 3, highId)]!!.toString(Charsets.UTF_8)}", SyncV1Protocol.parseDeviceState(transport.remote[statePath(deviceId, 3, highId)]!!))
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(4L, outcome.stateRevision)
        assertEquals(SyncV1UiPhase.READY, outcome.phase)
    }

    @Test
    fun changedConfigDoesNotSendPendingIntentToNewServer() = runBlocking {
        val snapshot = "77777777-7777-4777-8777-777777777777"
        val bytes = deviceState(deviceId, 1, snapshot, "a".repeat(64))
        val store = MemoryStore().apply {
            seedIntent(
                DeviceStatePublicationIntent(
                    deviceId, 1, UUID.fromString(snapshot), statePath(deviceId, 1, snapshot),
                    bytes, SyncV1Protocol.sha256(bytes), 0,
                    "old-config", "service-A", "root-A", "pending", 1, 1
                )
            )
        }
        val transport = FakeTransport()
        val outcome = repository(store, transport, configIdentity = "new-config")
            .processRegistrations().last()
        assertEquals(SyncV1ErrorCode.CONFIG_CHANGED, outcome.errorCode)
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun registrationReadUsesHardLimitAndPublishesRejectedWithoutParsingWhenTooLarge() = runBlocking {
        val oversizedRegistration = ByteArray(65_537)
        val transport = FakeTransport().apply {
            remote["legadom_ink_sync/v1/devices/$deviceId.json"] = oversizedRegistration
            oversizedPaths += "legadom_ink_sync/v1/devices/$deviceId.json"
        }
        val outcome = repository(MemoryStore(), transport).processRegistrations().last()
        assertEquals(SyncV1UiPhase.REJECTED, outcome.phase)
        assertEquals(SyncV1ErrorCode.SIZE_EXCEEDED, outcome.errorCode)
        assertEquals(SYNC_V1_MAX_OBJECT_BYTES, transport.requestedMaximums.first())
        assertFalse(transport.putPaths.any { it.contains("/devices/") })
        assertEquals(SyncV1Protocol.sha256(oversizedRegistration), publishedState(transport).registrationPayloadSha256)
    }

    @Test
    fun intentAndFailureTextDoNotExposeRawBytesOrConfigurationIdentity() = runBlocking {
        val transport = FakeTransport().apply {
            addRegistration(deviceId, registration(deviceId))
            putHandler = { _, _, _ -> throw SocketTimeoutException("sensitive test marker") }
        }
        val store = MemoryStore()
        repository(store, transport).processRegistrations()
        val intentText = store.intents.values.single().toString()
        assertFalse(intentText.contains("sensitive test marker"))
        assertFalse(intentText.contains("config-A"))
        assertFalse(intentText.contains("Authorization"))
        assertFalse(SyncV1Failure(SyncV1ErrorCode.NETWORK_UNKNOWN).message.orEmpty().contains("http"))
    }

    private fun repository(
        store: MemoryStore,
        transport: FakeTransport,
        configIdentity: String = "config-A",
        current: () -> Boolean = { true },
        snapshotId: String = currentSnapshotId
    ) = SyncV1RegistrationRepository(
        store = store,
        sessionProvider = {
            SyncV1Session(configIdentity, "service-A", "root-A", transport, current)
        },
        nowMillis = { 1_794_240_000_000L },
        uuidProvider = { UUID.fromString(snapshotId) }
    )

    private fun registration(
        payloadDeviceId: String,
        modes: List<String> = listOf("uuid_verified", "conditional_create"),
        locator: String = "chapter_only"
    ): ByteArray {
        val json = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", payloadDeviceId)
            addProperty("created_at", timestamp)
            add("client", JsonObject().apply {
                addProperty("kind", "koreader")
                addProperty("name", "KOReader")
                addProperty("version", "2026.11")
            })
            addProperty("counter_epoch", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
            add("supported_create_modes", com.google.gson.JsonArray().apply {
                modes.forEach(::add)
            })
            addProperty("locator_capability", locator)
        }
        return GsonBuilder().disableHtmlEscaping().serializeNulls().create().toJson(json).toByteArray(Charsets.UTF_8)
    }

    private fun deviceState(
        deviceId: String,
        revision: Long,
        snapshotId: String,
        registrationHash: String,
        status: String = "ready",
        result: String = "registration_verified",
        createMode: String? = "uuid_verified",
        locator: String? = "chapter_only"
    ): ByteArray {
        val json = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", deviceId)
            addProperty("state_revision", revision)
            addProperty("snapshot_id", snapshotId)
            addProperty("status", status)
            addProperty("registration_result", result)
            if (createMode == null) add("create_mode", com.google.gson.JsonNull.INSTANCE)
            else addProperty("create_mode", createMode)
            if (locator == null) add("locator_capability", com.google.gson.JsonNull.INSTANCE)
            else addProperty("locator_capability", locator)
            addProperty("registration_payload_sha256", registrationHash)
            addProperty("generated_at", timestamp)
        }
        return GsonBuilder().disableHtmlEscaping().serializeNulls().create().toJson(json).toByteArray(Charsets.UTF_8)
    }

    private fun statePath(id: String, revision: Long, snapshot: String) =
        "legadom_ink_sync/v1/android/device-state/$id/$revision-$snapshot.json"

    private fun publishedState(transport: FakeTransport): ParsedDeviceState {
        val (path, bytes) = transport.remote.entries.first { it.key.contains("android/device-state/$deviceId/") }
        assertTrue(path.endsWith(".json"))
        return requireNotNull(SyncV1Protocol.parseDeviceState(bytes))
    }

    private inner class FakeTransport : SyncV1WebDavTransport {
        val remote = linkedMapOf<String, ByteArray>()
        val ensuredDirectories = linkedSetOf<String>()
        val putPaths = mutableListOf<String>()
        val putBodies = mutableListOf<ByteArray>()
        val getPaths = mutableListOf<String>()
        val requestedMaximums = mutableListOf<Int>()
        val oversizedPaths = mutableSetOf<String>()
        var requestCount = 0
        var listCalls = 0
        var putHandler: suspend FakeTransport.(String, ByteArray, Int) -> SyncV1HttpResponse = { path, bytes, _ ->
            remote[path] = bytes.copyOf()
            SyncV1HttpResponse(201)
        }

        fun addRegistration(id: String, raw: ByteArray) {
            remote["legadom_ink_sync/v1/devices/$id.json"] = raw.copyOf()
        }

        override suspend fun ensureDirectory(relativePath: String) {
            requestCount++
            ensuredDirectories += relativePath
        }

        override suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry> {
            requestCount++
            listCalls++
            val prefix = relativePath
            return remote.keys.filter { it.startsWith(prefix) }
                .mapNotNull { path ->
                    val name = path.removePrefix(prefix)
                    if (name.isBlank() || '/' in name) null else SyncV1RemoteEntry(name, false)
                }
        }

        override suspend fun get(
            relativePath: String,
            maxBytes: Int,
            computeFullSha256: Boolean
        ): SyncV1HttpResponse {
            requestCount++
            getPaths += relativePath
            requestedMaximums += maxBytes
            val bytes = remote[relativePath] ?: return SyncV1HttpResponse(404)
            return SyncV1HttpResponse(
                statusCode = 200,
                body = if (oversizedPaths.contains(relativePath)) bytes.take(65_537).toByteArray() else bytes.copyOf(),
                bodyTooLarge = oversizedPaths.contains(relativePath),
                rawSha256 = if (computeFullSha256) SyncV1Protocol.sha256(bytes) else null
            )
        }

        override suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse {
            requestCount++
            putPaths += relativePath
            putBodies += bytes.copyOf()
            return putHandler(relativePath, bytes.copyOf(), putBodies.size)
        }
    }

    private class MemoryStore : SyncV1Store {
        val registrations = linkedMapOf<String, DeviceRegistrationRecord>()
        val intents = linkedMapOf<String, DeviceStatePublicationIntent>()
        val states = linkedMapOf<String, SyncV1StoredState>()

        override suspend fun saveRegistration(record: DeviceRegistrationRecord): Boolean {
            val previous = registrations[record.deviceId]
            if (previous != null && previous.registrationPayloadSha256 != record.registrationPayloadSha256) {
                registrations[record.deviceId] = previous.copy(
                    parsedStatus = "conflict",
                    validationErrorCode = SyncV1ErrorCode.DEVICE_ID_CONFLICT.name,
                    lastSeenAt = record.lastSeenAt
                )
                return false
            }
            val first = previous?.firstSeenAt ?: record.firstSeenAt
            registrations[record.deviceId] = record.copy(firstSeenAt = first)
            return true
        }

        override suspend fun pendingIntents(): List<DeviceStatePublicationIntent> =
            intents.values.filter { it.publicationStatus == "pending" }.sortedBy { it.createdAt }

        override suspend fun pendingIntent(deviceId: String): DeviceStatePublicationIntent? =
            pendingIntents().firstOrNull { it.deviceId == deviceId }

        override suspend fun createIntentIfNoPending(intent: DeviceStatePublicationIntent): DeviceStatePublicationIntent {
            return pendingIntent(intent.deviceId) ?: intent.also { intents[it.remotePath] = it }
        }

        override suspend fun incrementPutAttempt(remotePath: String, now: Long): Int {
            val intent = requireNotNull(intents[remotePath])
            val count = intent.putAttemptCount + 1
            intents[remotePath] = intent.copyWith(putAttemptCount = count, updatedAt = now)
            return count
        }

        override suspend fun markIntentPublished(
            intent: DeviceStatePublicationIntent,
            remoteStatus: String,
            createMode: String?
        ) {
            intents[intent.remotePath]?.let {
                intents[intent.remotePath] = it.copyWith(publicationStatus = "published")
            }
            states[intent.deviceId] = SyncV1StoredState(
                intent.deviceId,
                intent.stateRevision,
                SyncV1LocalStatus.fromPersistedValue(remoteStatus),
                remoteStatus,
                createMode,
                null,
                intent.updatedAt
            )
        }

        override suspend fun updateProcessing(
            deviceId: String,
            localStatus: SyncV1LocalStatus,
            errorCode: String?,
            highestRevision: Long?,
            remoteStatus: String?,
            createMode: String?
        ) {
            val previous = states[deviceId]
            states[deviceId] = SyncV1StoredState(
                deviceId,
                maxOf(previous?.highestConfirmedStateRevision ?: 0, highestRevision ?: 0),
                localStatus,
                remoteStatus ?: previous?.remoteStatus,
                createMode ?: previous?.createMode,
                errorCode,
                1
            )
        }

        override suspend fun readState(deviceId: String): SyncV1StoredState? = states[deviceId]

        fun seedIntent(intent: DeviceStatePublicationIntent) {
            intents[intent.remotePath] = intent
        }

        private fun DeviceStatePublicationIntent.copyWith(
            putAttemptCount: Int = this.putAttemptCount,
            publicationStatus: String = this.publicationStatus,
            updatedAt: Long = this.updatedAt
        ) = DeviceStatePublicationIntent(
            deviceId,
            stateRevision,
            snapshotId,
            remotePath,
            rawJsonBytes,
            rawSha256,
            putAttemptCount,
            configIdentity,
            serviceIdentity,
            rootIdentity,
            publicationStatus,
            createdAt,
            updatedAt
        )
    }
}
