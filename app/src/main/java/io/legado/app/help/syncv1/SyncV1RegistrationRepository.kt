package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.legado.app.lib.webdav.WebDavHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException

class SyncV1RegistrationRepository(
    private val store: SyncV1Store,
    private val sessionProvider: suspend () -> SyncV1Session,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> UUID = UUID::randomUUID
) {
    private val operationMutex = Mutex()
    // Rejected states require explicit JSON null values for create_mode and, when invalid,
    // locator_capability. Keep those required fields in the immutable serialized payload.
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    suspend fun processRegistrations(
        onProgress: (SyncV1DeviceOutcome) -> Unit = {}
    ): List<SyncV1DeviceOutcome> = operationMutex.withLock {
        val outcomes = ArrayList<SyncV1DeviceOutcome>()
        onProgress(SyncV1DeviceOutcome(null, SyncV1UiPhase.SCANNING))
        try {
            val session = sessionProvider()
            requireCurrent(session)
            val pendingIntents = store.pendingIntents()
            pendingIntents.forEach { requireIntentConfig(session, it) }
            SYNC_V1_BASE_DIRECTORIES.forEach { path ->
                requireCurrent(session)
                try {
                    session.transport.ensureDirectory(path)
                    requireCurrent(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    throw mappedFailure(error, directoryOperation = true)
                }
            }

            // A persisted publication intent always wins over creating a new revision.
            for (intent in pendingIntents) {
                try {
                    onProgress(SyncV1DeviceOutcome(intent.deviceId, SyncV1UiPhase.PUBLISHING))
                    val state = SyncV1Protocol.parseDeviceState(intent.rawJsonBytes)
                        ?: throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
                    if (state.deviceId != intent.deviceId
                        || state.stateRevision != intent.stateRevision
                        || state.snapshotId != intent.snapshotId.toString()
                        || intent.remotePath != statePath(state.deviceId, state.stateRevision, state.snapshotId)
                        || SyncV1Protocol.sha256(intent.rawJsonBytes) != intent.rawSha256
                    ) throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
                    publishIntent(session, intent, state)
                    outcomes += outcomeFromState(state)
                    onProgress(outcomes.last())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val code = error.asSyncV1Error()
                    store.updateProcessing(intent.deviceId, SyncV1LocalStatus.UPLOADED, code.name)
                    outcomes += SyncV1DeviceOutcome(
                        intent.deviceId,
                        SyncV1UiPhase.FAILED,
                        errorCode = code
                    )
                    onProgress(outcomes.last())
                    return@withLock outcomes
                }
            }

            onProgress(SyncV1DeviceOutcome(null, SyncV1UiPhase.SCANNING))
            val registrations = try {
                session.transport.listDirectChildren("legadom_ink_sync/v1/devices/")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw mappedFailure(error)
            }
            val files = registrations.filter { !it.isDirectory && it.name.endsWith(".json") }
            if (files.isEmpty() && outcomes.isEmpty()) {
                return@withLock listOf(
                    SyncV1DeviceOutcome(
                        deviceId = null,
                        phase = SyncV1UiPhase.IDLE,
                        errorCode = SyncV1ErrorCode.NO_REGISTRATIONS
                    )
                )
            }

            for (file in files) {
                val deviceId = file.name.removeSuffix(".json")
                if (!SyncV1Protocol.isUuidV4(deviceId)) {
                    val outcome = SyncV1DeviceOutcome(
                        deviceId = null,
                        phase = SyncV1UiPhase.FAILED,
                        errorCode = SyncV1ErrorCode.INVALID_DEVICE_ID
                    )
                    outcomes += outcome
                    onProgress(outcome)
                    continue
                }
                val outcome = try {
                    processOne(session, deviceId, file.name, onProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val code = failure.asSyncV1Error()
                    val previousStatus = store.readState(deviceId)?.localStatus
                        ?: SyncV1LocalStatus.UNREGISTERED
                    store.updateProcessing(deviceId, previousStatus, code.name)
                    SyncV1DeviceOutcome(deviceId, SyncV1UiPhase.FAILED, errorCode = code)
                }
                outcomes += outcome
                onProgress(outcome)
            }
            outcomes
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val code = failure.asSyncV1Error()
            outcomes += SyncV1DeviceOutcome(null, SyncV1UiPhase.FAILED, errorCode = code)
            onProgress(outcomes.last())
            outcomes
        }
    }

    private suspend fun processOne(
        session: SyncV1Session,
        deviceId: String,
        fileName: String,
        onProgress: (SyncV1DeviceOutcome) -> Unit
    ): SyncV1DeviceOutcome {
        requireCurrent(session)
        onProgress(SyncV1DeviceOutcome(deviceId, SyncV1UiPhase.VALIDATING))
        val registrationResponse = session.transport.get(
            "legadom_ink_sync/v1/devices/$fileName",
            SYNC_V1_MAX_OBJECT_BYTES,
            computeFullSha256 = true
        )
        requireCurrent(session)
        checkReadResponse(registrationResponse)
        val registrationTooLarge = registrationResponse.bodyTooLarge
            || registrationResponse.body.size > SYNC_V1_MAX_OBJECT_BYTES
        val parsedValidation = if (registrationTooLarge) {
            val rawSha256 = registrationResponse.rawSha256
                ?: throw SyncV1Failure(SyncV1ErrorCode.SIZE_EXCEEDED)
            RegistrationValidation.Rejected(
                deviceId = deviceId,
                payloadSha256 = rawSha256,
                result = "invalid_registration",
                errorCode = SyncV1ErrorCode.SIZE_EXCEEDED
            )
        } else {
            SyncV1Protocol.parseRegistration(deviceId, registrationResponse.body)
        }
        val now = nowMillis()
        val registrationUnchanged = store.saveRegistration(
            DeviceRegistrationRecord(
                deviceId = deviceId,
                registrationPayloadSha256 = parsedValidation.payloadSha256,
                remotePath = "legadom_ink_sync/v1/devices/$fileName",
                parsedStatus = when {
                    parsedValidation is RegistrationValidation.Valid -> "valid"
                    (parsedValidation as? RegistrationValidation.Rejected)?.result == "unsupported_mode" -> "unsupported_mode"
                    else -> "invalid"
                },
                supportedCreateModes = (parsedValidation as? RegistrationValidation.Valid)?.registration?.supportedCreateModes
                    ?: (parsedValidation as? RegistrationValidation.Rejected)?.supportedCreateModes.orEmpty(),
                locatorCapability = (parsedValidation as? RegistrationValidation.Valid)?.registration?.locatorCapability
                    ?: (parsedValidation as? RegistrationValidation.Rejected)?.locatorCapability,
                validationErrorCode = (parsedValidation as? RegistrationValidation.Rejected)?.errorCode?.name,
                firstSeenAt = now,
                lastSeenAt = now
            )
        )
        val validation = if (registrationUnchanged) {
            parsedValidation
        } else {
            RegistrationValidation.Rejected(
                deviceId = deviceId,
                payloadSha256 = parsedValidation.payloadSha256,
                result = "device_id_conflict",
                errorCode = SyncV1ErrorCode.DEVICE_ID_CONFLICT
            )
        }
        val valid = validation as? RegistrationValidation.Valid
        val rejected = validation as? RegistrationValidation.Rejected

        store.updateProcessing(
            deviceId = deviceId,
            localStatus = SyncV1LocalStatus.UPLOADED,
            errorCode = rejected?.errorCode?.name
        )

        val desired = if (valid != null) {
            ensureDeviceDirectories(session, deviceId, includeEventFolders = true)
            DesiredDeviceState(
                deviceId = deviceId,
                registrationPayloadSha256 = validation.payloadSha256,
                status = "ready",
                registrationResult = "registration_verified",
                createMode = SYNC_V1_CREATE_MODE,
                locatorCapability = valid.registration.locatorCapability
            )
        } else {
            val rejectedRegistration = rejected!!
            ensureDeviceDirectories(session, deviceId, includeEventFolders = false)
            DesiredDeviceState(
                deviceId = deviceId,
                registrationPayloadSha256 = validation.payloadSha256,
                status = "rejected",
                registrationResult = rejectedRegistration.result,
                createMode = null,
                locatorCapability = if (rejectedRegistration.result == "unsupported_mode") {
                    rejectedRegistration.locatorCapability
                } else {
                    null
                }
            )
        }

        requireCurrent(session)
        val remoteStates = loadRemoteStates(session, deviceId)
        val highest = selectHighestRevision(remoteStates)
        if (highest != null && highest.matches(desired)) {
            store.updateProcessing(
                deviceId = deviceId,
                localStatus = SyncV1LocalStatus.fromPersistedValue(highest.state.status),
                highestRevision = highest.state.stateRevision,
                remoteStatus = highest.state.status,
                createMode = highest.state.createMode
            )
            return outcomeFromState(highest.state, rejected?.errorCode)
        }

        val previousRevision = highest?.state?.stateRevision ?: 0L
        if (previousRevision >= SYNC_V1_MAX_SAFE_INTEGER) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
        val intent = createIntent(session, desired, previousRevision + 1)
        val persistedIntent = store.createIntentIfNoPending(intent)
        requireIntentConfig(session, persistedIntent)
        if (persistedIntent.remotePath != intent.remotePath
            || !persistedIntent.rawJsonBytes.contentEquals(intent.rawJsonBytes)
        ) {
            // Another process may have persisted an intent after our initial pending scan.
            val persistedState = SyncV1Protocol.parseDeviceState(persistedIntent.rawJsonBytes)
                ?: throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
            publishIntent(session, persistedIntent, persistedState)
            return outcomeFromState(persistedState)
        }
        val state = SyncV1Protocol.parseDeviceState(persistedIntent.rawJsonBytes)
            ?: throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
        onProgress(SyncV1DeviceOutcome(deviceId, SyncV1UiPhase.PUBLISHING))
        publishIntent(session, persistedIntent, state)
        return outcomeFromState(state, rejected?.errorCode)
    }

    private suspend fun ensureDeviceDirectories(
        session: SyncV1Session,
        deviceId: String,
        includeEventFolders: Boolean
    ) {
        val directories = buildList {
            add("legadom_ink_sync/v1/android/device-state/$deviceId/")
            if (includeEventFolders) {
                add("legadom_ink_sync/v1/events/$deviceId/")
                add("legadom_ink_sync/v1/acks/$deviceId/")
            }
        }
        directories.forEach { path ->
            requireCurrent(session)
            try {
                session.transport.ensureDirectory(path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw mappedFailure(error, directoryOperation = true)
            }
            requireCurrent(session)
        }
    }

    private suspend fun loadRemoteStates(
        session: SyncV1Session,
        deviceId: String
    ): List<RemoteStateCandidate> {
        val entries = try {
            session.transport.listDirectChildren("legadom_ink_sync/v1/android/device-state/$deviceId/")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw mappedFailure(error)
        }
        val revisionSnapshots = entries.asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { entry ->
                STATE_FILE_PATTERN.matchEntire(entry.name)?.let { match ->
                    val revision = match.groupValues[1].toLongOrNull()
                        ?.takeIf { it in 0..SYNC_V1_MAX_SAFE_INTEGER }
                        ?: return@mapNotNull null
                    revision to match.groupValues[2]
                }
            }
            .groupBy({ it.first }, { it.second })
        if (revisionSnapshots.values.any { snapshots -> snapshots.distinct().size > 1 }) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
        val candidates = ArrayList<RemoteStateCandidate>()
        for (entry in entries) {
            if (entry.isDirectory) continue
            val match = STATE_FILE_PATTERN.matchEntire(entry.name) ?: continue
            val revision = match.groupValues[1].toLongOrNull()
                ?.takeIf { it in 0..SYNC_V1_MAX_SAFE_INTEGER } ?: continue
            val snapshotId = match.groupValues[2]
            requireCurrent(session)
            val relativePath = "legadom_ink_sync/v1/android/device-state/$deviceId/${entry.name}"
            val response = try {
                session.transport.get(relativePath, SYNC_V1_MAX_OBJECT_BYTES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw mappedFailure(error)
            }
            requireCurrent(session)
            if (response.statusCode != 200) throw httpFailure(response.statusCode)
            if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_OBJECT_BYTES) {
                throw SyncV1Failure(SyncV1ErrorCode.SIZE_EXCEEDED)
            }
            val state = SyncV1Protocol.parseDeviceState(response.body) ?: continue
            if (state.deviceId != deviceId || state.stateRevision != revision || state.snapshotId != snapshotId) {
                continue
            }
            candidates += RemoteStateCandidate(
                revision = revision,
                snapshotId = snapshotId,
                bytes = response.body,
                sha256 = SyncV1Protocol.sha256(response.body),
                state = state,
                remotePath = relativePath
            )
        }
        return candidates
    }

    private fun selectHighestRevision(candidates: List<RemoteStateCandidate>): RemoteStateCandidate? {
        if (candidates.isEmpty()) return null
        candidates.groupBy { it.revision }.forEach { (_, sameRevision) ->
            val first = sameRevision.first()
            val conflict = sameRevision.any { candidate ->
                candidate.snapshotId != first.snapshotId
                    || candidate.sha256 != first.sha256
                    || !candidate.bytes.contentEquals(first.bytes)
            }
            if (conflict) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
        return candidates.maxByOrNull { it.revision }
    }

    private suspend fun createIntent(
        session: SyncV1Session,
        desired: DesiredDeviceState,
        revision: Long
    ): DeviceStatePublicationIntent {
        val snapshotId = uuidProvider()
        if (!SyncV1Protocol.isUuidV4(snapshotId.toString())) {
            throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        val remotePath = statePath(desired.deviceId, revision, snapshotId.toString())
        val json = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", desired.deviceId)
            addProperty("state_revision", revision)
            addProperty("snapshot_id", snapshotId.toString())
            addProperty("status", desired.status)
            addProperty("registration_result", desired.registrationResult)
            if (desired.createMode == null) add("create_mode", com.google.gson.JsonNull.INSTANCE)
            else addProperty("create_mode", desired.createMode)
            if (desired.locatorCapability == null) add("locator_capability", com.google.gson.JsonNull.INSTANCE)
            else addProperty("locator_capability", desired.locatorCapability)
            addProperty("registration_payload_sha256", desired.registrationPayloadSha256)
            addProperty("generated_at", Instant.ofEpochMilli(nowMillis()).toString())
        }
        // This is the only serialization of the immutable state payload.
        val rawBytes = gson.toJson(json).toByteArray(Charsets.UTF_8)
        if (rawBytes.size > SYNC_V1_MAX_OBJECT_BYTES) {
            throw SyncV1Failure(SyncV1ErrorCode.SIZE_EXCEEDED)
        }
        val rawSha = SyncV1Protocol.sha256(rawBytes)
        return DeviceStatePublicationIntent(
            deviceId = desired.deviceId,
            stateRevision = revision,
            snapshotId = snapshotId,
            remotePath = remotePath,
            rawJsonBytes = rawBytes,
            rawSha256 = rawSha,
            putAttemptCount = 0,
            configIdentity = session.configIdentity,
            serviceIdentity = session.serviceIdentity,
            rootIdentity = session.rootIdentity,
            publicationStatus = "pending",
            createdAt = nowMillis(),
            updatedAt = nowMillis()
        )
    }

    private suspend fun publishIntent(
        session: SyncV1Session,
        intent: DeviceStatePublicationIntent,
        state: ParsedDeviceState
    ) {
        requireIntentConfig(session, intent)
        val expectedSha = SyncV1Protocol.sha256(intent.rawJsonBytes)
        if (expectedSha != intent.rawSha256) throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
        var putAttempts = intent.putAttemptCount
        while (true) {
            requireIntentConfig(session, intent)
            val beforePut = try {
                session.transport.get(intent.remotePath, SYNC_V1_MAX_OBJECT_BYTES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw mappedFailure(error)
            }
            requireIntentConfig(session, intent)
            when (beforePut.statusCode) {
                200 -> {
                    if (responseMatches(beforePut, intent)) {
                        store.markIntentPublished(intent, state.status, state.createMode)
                        return
                    }
                    throw SyncV1Failure(SyncV1ErrorCode.REMOTE_CONTENT_CONFLICT)
                }

                404 -> Unit
                else -> throw httpFailure(beforePut.statusCode)
            }
            if (beforePut.bodyTooLarge) throw SyncV1Failure(SyncV1ErrorCode.REMOTE_CONTENT_CONFLICT)
            if (putAttempts >= MAX_PUT_ATTEMPTS) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)

            putAttempts = store.incrementPutAttempt(intent.remotePath, nowMillis())
            val putResponse = try {
                session.transport.put(intent.remotePath, intent.rawJsonBytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SyncV1Failure) {
                if (failure.errorCode == SyncV1ErrorCode.CONFIG_CHANGED) throw failure
                null
            } catch (_: IOException) {
                null
            } catch (error: Exception) {
                throw mappedFailure(error)
            }
            requireIntentConfig(session, intent)

            if (putResponse == null) {
                // The PUT result is ambiguous. The next iteration GETs the same path first.
                continue
            }
            if (putResponse.statusCode !in 200..299) throw httpFailure(putResponse.statusCode)

            val readback = try {
                session.transport.get(intent.remotePath, SYNC_V1_MAX_OBJECT_BYTES)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw mappedFailure(error)
            }
            requireIntentConfig(session, intent)
            if (readback.statusCode != 200 || !responseMatches(readback, intent)) {
                throw SyncV1Failure(SyncV1ErrorCode.READBACK_MISMATCH)
            }
            store.markIntentPublished(intent, state.status, state.createMode)
            return
        }
    }

    private fun responseMatches(
        response: SyncV1HttpResponse,
        intent: DeviceStatePublicationIntent
    ): Boolean = !response.bodyTooLarge
        && response.body.contentEquals(intent.rawJsonBytes)
        && SyncV1Protocol.sha256(response.body) == intent.rawSha256

    private fun checkReadResponse(response: SyncV1HttpResponse) {
        if (response.statusCode != 200) throw httpFailure(response.statusCode)
    }

    private fun httpFailure(statusCode: Int): SyncV1Failure = when (statusCode) {
        401, 403 -> SyncV1Failure(SyncV1ErrorCode.AUTHENTICATION_FAILED)
        404 -> SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
        409 -> SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
        in 500..599 -> SyncV1Failure(SyncV1ErrorCode.HTTP_SERVER_ERROR)
        else -> SyncV1Failure(SyncV1ErrorCode.HTTP_CLIENT_ERROR)
    }

    private fun mappedFailure(error: Throwable, directoryOperation: Boolean = false): SyncV1Failure {
        if (error is CancellationException) throw error
        if (error is SyncV1Failure) return error
        if (directoryOperation && error is WebDavHttpException && error.code !in setOf(401, 403, 404, 409)
            && error.code !in 500..599
        ) return SyncV1Failure(SyncV1ErrorCode.DIRECTORY_ERROR)
        return SyncV1Failure(error.asSyncV1Error())
    }

    private fun Throwable.asSyncV1Error(): SyncV1ErrorCode = when (this) {
        is SyncV1Failure -> errorCode
        is WebDavHttpException -> when (code) {
            401, 403 -> SyncV1ErrorCode.AUTHENTICATION_FAILED
            404 -> SyncV1ErrorCode.HTTP_NOT_FOUND
            409 -> SyncV1ErrorCode.HTTP_CONFLICT
            in 500..599 -> SyncV1ErrorCode.HTTP_SERVER_ERROR
            else -> SyncV1ErrorCode.HTTP_CLIENT_ERROR
        }

        is SocketTimeoutException -> SyncV1ErrorCode.TIMEOUT
        is UnknownHostException -> SyncV1ErrorCode.DNS_ERROR
        is SSLException -> SyncV1ErrorCode.TLS_ERROR
        is IOException -> SyncV1ErrorCode.NETWORK_UNKNOWN
        else -> SyncV1ErrorCode.NETWORK_UNKNOWN
    }

    private fun requireCurrent(session: SyncV1Session) {
        if (!session.isCurrent()) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun requireIntentConfig(session: SyncV1Session, intent: DeviceStatePublicationIntent) {
        requireCurrent(session)
        if (intent.configIdentity != session.configIdentity
            || intent.serviceIdentity != session.serviceIdentity
            || intent.rootIdentity != session.rootIdentity
        ) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun outcomeFromState(
        state: ParsedDeviceState,
        errorCodeOverride: SyncV1ErrorCode? = null
    ) = SyncV1DeviceOutcome(
        deviceId = state.deviceId,
        phase = if (state.status == "ready") SyncV1UiPhase.READY else SyncV1UiPhase.REJECTED,
        remoteStatus = state.status,
        createMode = state.createMode,
        stateRevision = state.stateRevision,
        errorCode = errorCodeOverride ?: when (state.registrationResult) {
            "device_id_conflict" -> SyncV1ErrorCode.DEVICE_ID_CONFLICT
            "registration_parent_missing" -> SyncV1ErrorCode.DIRECTORY_ERROR
            "unsupported_mode" -> SyncV1ErrorCode.UNSUPPORTED_MODE
            "invalid_registration" -> SyncV1ErrorCode.INVALID_REGISTRATION
            else -> null
        }
    )

    private fun RemoteStateCandidate.matches(desired: DesiredDeviceState): Boolean =
        state.deviceId == desired.deviceId
            && state.registrationPayloadSha256 == desired.registrationPayloadSha256
            && state.status == desired.status
            && state.registrationResult == desired.registrationResult
            && state.createMode == desired.createMode
            && state.locatorCapability == desired.locatorCapability

    private fun statePath(deviceId: String, revision: Long, snapshotId: String) =
        "legadom_ink_sync/v1/android/device-state/$deviceId/$revision-$snapshotId.json"

    private data class DesiredDeviceState(
        val deviceId: String,
        val registrationPayloadSha256: String,
        val status: String,
        val registrationResult: String,
        val createMode: String?,
        val locatorCapability: String?
    )

    private data class RemoteStateCandidate(
        val revision: Long,
        val snapshotId: String,
        val bytes: ByteArray,
        val sha256: String,
        val state: ParsedDeviceState,
        val remotePath: String
    )

    private companion object {
        val STATE_FILE_PATTERN = Regex(
            "^(0|[1-9][0-9]*)-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\.json$"
        )
        const val MAX_PUT_ATTEMPTS = 2
        val SYNC_V1_BASE_DIRECTORIES = listOf(
            "legadom_ink_sync/",
            "legadom_ink_sync/v1/",
            "legadom_ink_sync/v1/devices/",
            "legadom_ink_sync/v1/android/",
            "legadom_ink_sync/v1/android/device-state/",
            "legadom_ink_sync/v1/events/",
            "legadom_ink_sync/v1/acks/",
            "legadom_ink_sync/v1/snapshots/"
        )
    }
}
