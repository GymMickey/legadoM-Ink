package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import javax.net.ssl.SSLException

internal class SyncV1BindingRepository(
    private val store: SyncV1BindingStore,
    private val catalogProvider: suspend (SyncV1Session) -> ParsedCatalog?,
    private val sessionProvider: suspend () -> SyncV1Session,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val operationMutex = Mutex()
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    suspend fun processRequests(
        onProgress: (SyncV1BindingOutcome) -> Unit = {}
    ): SyncV1BindingOutcome = operationMutex.withLock {
        val tally = Tally()
        try {
            val session = sessionProvider()
            requireCurrent(session)

            // Restore acknowledgements from durable intents before looking at new event bytes.
            val pending = store.pendingAckIntents(session.serviceIdentity, session.rootIdentity)
            var pendingFailure = false
            for (intent in pending) {
                try {
                    publishIntent(session, intent)
                    tally.ackPublished++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    tally.error(error.asSyncV1Error())
                    pendingFailure = true
                }
            }
            if (pendingFailure) {
                tally.ackPending = store.pendingAckCount(session.serviceIdentity, session.rootIdentity)
                return@withLock tally.result().also(onProgress)
            }

            val readyDevices = store.readyDeviceIds(session.serviceIdentity, session.rootIdentity)
                .filter(SyncV1Protocol::isUuidV4)
                .distinct()
            tally.scannedDevices = readyDevices.size
            if (readyDevices.isEmpty()) {
                tally.error(SyncV1ErrorCode.NO_READY_DEVICE)
                return@withLock tally.result().also(onProgress)
            }

            // A listing/read failure is not interpreted as an empty catalog or a missing match.
            val catalog = catalogProvider(session)
            requireCurrent(session)
            val deviceDirectories = session.transport.listDirectChildren(EVENTS_DIRECTORY)
            requireCurrent(session)
            for (directory in deviceDirectories) {
                if (!directory.isDirectory || directory.name !in readyDevices) continue
                val deviceId = directory.name
                val eventEntries = try {
                    session.transport.listDirectChildren("$EVENTS_DIRECTORY$deviceId/")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    tally.error(error.asSyncV1Error())
                    continue
                }
                for (entry in eventEntries) {
                    if (entry.isDirectory || !entry.name.endsWith(".json")) continue
                    processEvent(session, catalog, deviceId, entry.name, tally)
                    onProgress(tally.result())
                }
            }
            tally.ackPending = store.pendingAckCount(session.serviceIdentity, session.rootIdentity)
            tally.result().also(onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            tally.error(error.asSyncV1Error())
            runCatching {
                val session = sessionProvider()
                tally.ackPending = store.pendingAckCount(session.serviceIdentity, session.rootIdentity)
            }
            tally.result().also(onProgress)
        }
    }

    private suspend fun processEvent(
        session: SyncV1Session,
        catalog: ParsedCatalog?,
        deviceId: String,
        fileName: String,
        tally: Tally
    ) {
        val eventId = fileName.removeSuffix(".json")
        if (!SyncV1Protocol.isUuidV4(deviceId) || !SyncV1Protocol.isUuidV4(eventId)) {
            tally.rejected++
            tally.error(SyncV1ErrorCode.INVALID_DEVICE_ID)
            return
        }

        try {
            requireCurrent(session)
            val response = session.transport.get(
                "$EVENTS_DIRECTORY$deviceId/$fileName",
                SYNC_V1_MAX_BINDING_EVENT_BYTES,
                computeFullSha256 = true
            )
            requireCurrent(session)
            if (response.statusCode != 200) throw httpFailure(response.statusCode)
            if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_BINDING_EVENT_BYTES) {
                // The event type cannot be safely inspected before enforcing the size cap.
                // Leave it untouched instead of risking a rejected ack for another event type.
                tally.unprocessed++
                tally.error(SyncV1ErrorCode.SIZE_EXCEEDED)
                return
            }
            val payloadSha256 = SyncV1Protocol.sha256(response.body)
            if (response.rawSha256 != null && response.rawSha256 != payloadSha256) {
                tally.rejected++
                tally.error(SyncV1ErrorCode.REMOTE_CONTENT_CONFLICT)
                return
            }

            val existing = store.importReceipt(eventId)
            if (existing != null) {
                if (existing.deviceId != deviceId || existing.payloadSha256 != payloadSha256) {
                    tally.eventIdConflicts++
                    tally.error(SyncV1ErrorCode.EVENT_ID_CONFLICT)
                    return
                }
                tally.recordTerminal(existing.terminalStatus)
                val intent = store.ensureAckIntent(existing, session.serviceIdentity, session.rootIdentity)
                publishIntent(session, intent)
                tally.ackPublished++
                return
            }

            if (SyncV1Protocol.eventType(response.body) != "binding_request") {
                tally.unprocessed++
                return
            }
            tally.discoveredEvents++

            when (val parsed = SyncV1Protocol.parseBindingRequest(deviceId, eventId, response.body)) {
                SyncV1BindingRequestParseResult.NotBindingRequest -> {
                    tally.unprocessed++
                    return
                }
                is SyncV1BindingRequestParseResult.Invalid -> {
                    val proposal = AckProposal(
                        status = "rejected",
                        reasonCode = parsed.reasonCode,
                        workId = null,
                        candidates = emptyList(),
                        recoveryAction = "manual_review"
                    )
                    persistAndPublish(
                        session = session,
                        pathDeviceId = deviceId,
                        pathEventId = eventId,
                        payloadSha256 = payloadSha256,
                        eventType = parsed.eventType,
                        counterEpoch = parsed.counterEpoch,
                        sequence = parsed.sequence,
                        contentId = parsed.contentId,
                        title = parsed.title,
                        author = parsed.author,
                        localTocCount = null,
                        tocCandidates = emptyList(),
                        proposal = proposal,
                        binding = null,
                        tally = tally
                    )
                }
                is SyncV1BindingRequestParseResult.Valid -> processValidRequest(
                    session, catalog, parsed.request, payloadSha256, tally
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            tally.error(error.asSyncV1Error())
        }
    }

    private suspend fun processValidRequest(
        session: SyncV1Session,
        catalog: ParsedCatalog?,
        request: ParsedSyncV1BindingRequest,
        payloadSha256: String,
        tally: Tally
    ) {
        val proposal = if (request.selectedWorkId != null || request.selectionFromEventId != null) {
            resolveSelection(session, request)
        } else {
            resolveInitialBinding(session, catalog, request)
        }
        val binding = if (proposal.status == "bound" && proposal.workId != null) {
            val now = nowMillis()
            SyncV1KindleContentBinding(
                deviceId = request.deviceId,
                counterEpoch = request.counterEpoch,
                contentId = request.contentId,
                workId = proposal.workId,
                createdAt = now,
                updatedAt = now
            )
        } else null
        persistAndPublish(
            session = session,
            pathDeviceId = request.deviceId,
            pathEventId = request.eventId,
            payloadSha256 = payloadSha256,
            eventType = "binding_request",
            counterEpoch = request.counterEpoch,
            sequence = request.sequence,
            contentId = request.contentId,
            title = request.title,
            author = request.author,
            localTocCount = request.localTocCount,
            tocCandidates = request.tocCandidates,
            proposal = proposal,
            binding = binding,
            tally = tally
        )
    }

    private suspend fun resolveInitialBinding(
        session: SyncV1Session,
        catalog: ParsedCatalog?,
        request: ParsedSyncV1BindingRequest
    ): AckProposal {
        val matching = catalog?.works.orEmpty().filter { work ->
            work.title == request.title
                && (request.author.isEmpty() || request.author in work.authors)
        }.map { work ->
            SyncV1BindingCandidate(work.workId, work.title, work.authors)
        }.sortedBy { it.workId }
        if (matching.size > SYNC_V1_MAX_BINDING_CANDIDATES) {
            return AckProposal("not_found", "not_found", null, emptyList(), "manual_review")
        }
        if (matching.isEmpty()) {
            return AckProposal(
                "not_found", "not_found", null, emptyList(),
                if (catalog == null) "download_catalog" else "manual_review"
            )
        }

        val existing = store.contentBinding(request.deviceId, request.counterEpoch, request.contentId)
        if (existing != null) {
            val chosen = matching.singleOrNull { it.workId == existing.workId }
                ?: return invalidBindingProposal()
            return AckProposal("bound", null, chosen.workId, emptyList(), "none")
        }
        return when (matching.size) {
            1 -> AckProposal("bound", null, matching.single().workId, emptyList(), "none")
            else -> AckProposal("ambiguous", "ambiguous", null, matching, "select_binding_candidate")
        }
    }

    private suspend fun resolveSelection(
        session: SyncV1Session,
        request: ParsedSyncV1BindingRequest
    ): AckProposal {
        val selectedWorkId = request.selectedWorkId ?: return invalidBindingProposal()
        val sourceEventId = request.selectionFromEventId ?: return invalidBindingProposal()
        if (sourceEventId == request.eventId) return invalidBindingProposal()
        val original = store.importReceipt(sourceEventId) ?: return invalidBindingProposal()
        if (original.terminalStatus != "ambiguous"
            || original.deviceId != request.deviceId
            || original.counterEpoch != request.counterEpoch
            || original.contentId != request.contentId
            || original.sequence == request.sequence
            || original.serviceIdentity != session.serviceIdentity
            || original.rootIdentity != session.rootIdentity
            || original.candidates.none { it.workId == selectedWorkId }
        ) return invalidBindingProposal()

        val existing = store.contentBinding(request.deviceId, request.counterEpoch, request.contentId)
        if (existing != null && existing.workId != selectedWorkId) return invalidBindingProposal()
        return AckProposal("bound", null, selectedWorkId, emptyList(), "none")
    }

    private suspend fun persistAndPublish(
        session: SyncV1Session,
        pathDeviceId: String,
        pathEventId: String,
        payloadSha256: String,
        eventType: String?,
        counterEpoch: String?,
        sequence: Long?,
        contentId: String?,
        title: String?,
        author: String?,
        localTocCount: Long?,
        tocCandidates: List<SyncV1BindingTocCandidate>,
        proposal: AckProposal,
        binding: SyncV1KindleContentBinding?,
        tally: Tally
    ) {
        var actualProposal = proposal
        var actualBinding = binding
        var receiptAndIntent = createReceiptAndIntent(
            session, pathDeviceId, pathEventId, payloadSha256, eventType, counterEpoch,
            sequence, contentId, title, author, localTocCount, tocCandidates, actualProposal
        )
        var result = store.persistBindingOutcome(receiptAndIntent.first, receiptAndIntent.second, actualBinding)
        if (result == SyncV1BindingPersistResult.BindingConflict) {
            actualProposal = invalidBindingProposal()
            actualBinding = null
            receiptAndIntent = createReceiptAndIntent(
                session, pathDeviceId, pathEventId, payloadSha256, eventType, counterEpoch,
                sequence, contentId, title, author, localTocCount, tocCandidates, actualProposal
            )
            result = store.persistBindingOutcome(receiptAndIntent.first, receiptAndIntent.second, null)
        }
        when (result) {
            SyncV1BindingPersistResult.Stored -> {
                tally.recordTerminal(actualProposal.status)
                publishIntent(session, receiptAndIntent.second)
                tally.ackPublished++
            }
            is SyncV1BindingPersistResult.Existing -> {
                if (result.receipt.deviceId != pathDeviceId || result.receipt.payloadSha256 != payloadSha256) {
                    tally.eventIdConflicts++
                    tally.error(SyncV1ErrorCode.EVENT_ID_CONFLICT)
                    return
                }
                tally.recordTerminal(result.receipt.terminalStatus)
                val intent = store.ensureAckIntent(result.receipt, session.serviceIdentity, session.rootIdentity)
                publishIntent(session, intent)
                tally.ackPublished++
            }
            SyncV1BindingPersistResult.EventIdConflict -> {
                tally.eventIdConflicts++
                tally.error(SyncV1ErrorCode.EVENT_ID_CONFLICT)
            }
            SyncV1BindingPersistResult.BindingConflict -> {
                // A competing transaction changed the mapping again. Do not write an ack
                // whose result disagrees with the durable binding.
                tally.rejected++
                tally.error(SyncV1ErrorCode.BINDING_CONFLICT)
            }
        }
    }

    private fun createReceiptAndIntent(
        session: SyncV1Session,
        deviceId: String,
        eventId: String,
        payloadSha256: String,
        eventType: String?,
        counterEpoch: String?,
        sequence: Long?,
        contentId: String?,
        title: String?,
        author: String?,
        localTocCount: Long?,
        tocCandidates: List<SyncV1BindingTocCandidate>,
        proposal: AckProposal
    ): Pair<SyncV1ImportReceipt, SyncV1AckPublicationIntent> {
        val acknowledgedAt = Instant.ofEpochMilli(nowMillis()).toString()
        val ackBytes = encodeAck(deviceId, eventId, payloadSha256, acknowledgedAt, proposal)
        val ackSha256 = SyncV1Protocol.sha256(ackBytes)
        val receipt = SyncV1ImportReceipt(
            eventId = eventId,
            deviceId = deviceId,
            eventType = eventType,
            payloadSha256 = payloadSha256,
            counterEpoch = counterEpoch,
            sequence = sequence,
            contentId = contentId,
            title = title,
            author = author,
            localTocCount = localTocCount,
            tocCandidates = tocCandidates,
            terminalStatus = proposal.status,
            reasonCode = proposal.reasonCode,
            recoveryAction = proposal.recoveryAction,
            workId = proposal.workId,
            candidates = proposal.candidates,
            acknowledgedAt = acknowledgedAt,
            ackRawJsonBytes = ackBytes,
            ackSha256 = ackSha256,
            serviceIdentity = session.serviceIdentity,
            rootIdentity = session.rootIdentity
        )
        val intent = SyncV1AckPublicationIntent(
            deviceId = deviceId,
            eventId = eventId,
            remotePath = ackPath(deviceId, eventId),
            rawJsonBytes = ackBytes,
            rawSha256 = ackSha256,
            putAttemptCount = 0,
            serviceIdentity = session.serviceIdentity,
            rootIdentity = session.rootIdentity,
            publicationStatus = "pending",
            createdAt = nowMillis(),
            updatedAt = nowMillis()
        )
        return receipt to intent
    }

    private suspend fun publishIntent(session: SyncV1Session, intent: SyncV1AckPublicationIntent) {
        requireCurrent(session)
        if (intent.serviceIdentity != session.serviceIdentity
            || intent.rootIdentity != session.rootIdentity
            || !SyncV1Protocol.isUuidV4(intent.deviceId)
            || !SyncV1Protocol.isUuidV4(intent.eventId)
            || intent.remotePath != ackPath(intent.deviceId, intent.eventId)
            || intent.rawJsonBytes.size > SYNC_V1_MAX_BINDING_EVENT_BYTES
            || SyncV1Protocol.sha256(intent.rawJsonBytes) != intent.rawSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
        val receipt = store.importReceipt(intent.eventId)
            ?: throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
        if (receipt.deviceId != intent.deviceId
            || receipt.ackSha256 != intent.rawSha256
            || !receipt.ackRawJsonBytes.contentEquals(intent.rawJsonBytes)
            || !encodeAck(
                receipt.deviceId,
                receipt.eventId,
                receipt.payloadSha256,
                receipt.acknowledgedAt,
                AckProposal(
                    receipt.terminalStatus,
                    receipt.reasonCode,
                    receipt.workId,
                    receipt.candidates,
                    receipt.recoveryAction
                )
            ).contentEquals(receipt.ackRawJsonBytes)
        ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)

        var putAttempts = intent.putAttemptCount
        while (true) {
            requireCurrent(session)
            val existing = try {
                session.transport.get(intent.remotePath, SYNC_V1_MAX_BINDING_EVENT_BYTES, computeFullSha256 = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw error.asSyncV1Failure()
            }
            requireCurrent(session)
            when (existing.statusCode) {
                200 -> {
                    if (responseMatches(existing, intent)) {
                        if (intent.publicationStatus != "published") {
                            store.markAckPublished(intent, nowMillis())
                        }
                        return
                    }
                    throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
                }
                404 -> Unit
                else -> throw httpFailure(existing.statusCode)
            }
            if (existing.bodyTooLarge) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
            if (putAttempts >= MAX_PUT_ATTEMPTS) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)

            putAttempts = store.incrementAckPutAttempt(intent, nowMillis())
            try {
                val put = session.transport.put(intent.remotePath, intent.rawJsonBytes)
                requireCurrent(session)
                when (put.statusCode) {
                    401, 403 -> throw httpFailure(put.statusCode)
                    in 200..299, 409 -> Unit // Resolve the result through GET on the next loop.
                    in 500..599 -> Unit // Server may have committed before returning an error.
                    else -> throw httpFailure(put.statusCode)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SyncV1Failure) {
                if (failure.errorCode !in AMBIGUOUS_PUT_ERRORS) throw failure
            } catch (error: Exception) {
                val failure = error.asSyncV1Failure()
                if (failure.errorCode !in AMBIGUOUS_PUT_ERRORS) throw failure
            }
            requireCurrent(session)
        }
    }

    private fun encodeAck(
        deviceId: String,
        eventId: String,
        payloadSha256: String,
        acknowledgedAt: String,
        proposal: AckProposal
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", deviceId)
            addProperty("event_id", eventId)
            addProperty("payload_sha256", payloadSha256)
            addProperty("status", proposal.status)
            addProperty("acknowledged_at", acknowledgedAt)
            addProperty("recovery_action", proposal.recoveryAction)
            if (proposal.reasonCode == null) add("reason_code", JsonNull.INSTANCE)
            else addProperty("reason_code", proposal.reasonCode)
            proposal.workId?.let { addProperty("work_id", it) }
            if (proposal.status == "ambiguous") {
                add("candidates", JsonArray().also { array ->
                    proposal.candidates.forEach { candidate ->
                        array.add(JsonObject().apply {
                            addProperty("work_id", candidate.workId)
                            addProperty("title", candidate.title)
                            add("authors", JsonArray().also { authors -> candidate.authors.forEach(authors::add) })
                        })
                    }
                })
            }
        }
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private fun responseMatches(
        response: SyncV1HttpResponse,
        intent: SyncV1AckPublicationIntent
    ): Boolean = !response.bodyTooLarge
        && response.body.contentEquals(intent.rawJsonBytes)
        && SyncV1Protocol.sha256(response.body) == intent.rawSha256
        && (response.rawSha256 == null || response.rawSha256 == intent.rawSha256)

    private fun invalidBindingProposal() = AckProposal(
        "rejected", "invalid_event", null, emptyList(), "manual_review"
    )

    private fun requireCurrent(session: SyncV1Session) {
        if (!session.isCurrent()) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun ackPath(deviceId: String, eventId: String) =
        "legadom_ink_sync/v1/acks/$deviceId/$eventId.json"

    private fun httpFailure(statusCode: Int): SyncV1Failure = when (statusCode) {
        401, 403 -> SyncV1Failure(SyncV1ErrorCode.AUTHENTICATION_FAILED)
        404 -> SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
        409 -> SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
        in 500..599 -> SyncV1Failure(SyncV1ErrorCode.HTTP_SERVER_ERROR)
        else -> SyncV1Failure(SyncV1ErrorCode.HTTP_CLIENT_ERROR)
    }

    private fun Throwable.asSyncV1Error(): SyncV1ErrorCode = when (this) {
        is SyncV1Failure -> errorCode
        is io.legado.app.lib.webdav.WebDavHttpException -> when (code) {
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

    private fun Throwable.asSyncV1Failure(): SyncV1Failure =
        if (this is SyncV1Failure) this else SyncV1Failure(asSyncV1Error())

    private data class AckProposal(
        val status: String,
        val reasonCode: String?,
        val workId: String?,
        val candidates: List<SyncV1BindingCandidate>,
        val recoveryAction: String
    )

    private class Tally {
        var scannedDevices = 0
        var discoveredEvents = 0
        var bound = 0
        var ambiguous = 0
        var notFound = 0
        var rejected = 0
        var unprocessed = 0
        var eventIdConflicts = 0
        var ackPublished = 0
        var ackPending = 0
        var errorCode: SyncV1ErrorCode? = null

        fun recordTerminal(status: String) {
            when (status) {
                "bound" -> bound++
                "ambiguous" -> ambiguous++
                "not_found" -> notFound++
                "rejected" -> rejected++
            }
        }

        fun error(code: SyncV1ErrorCode) {
            if (errorCode == null) errorCode = code
        }

        fun result() = SyncV1BindingOutcome(
            scannedDevices, discoveredEvents, bound, ambiguous, notFound, rejected,
            unprocessed, eventIdConflicts, ackPublished, ackPending, errorCode
        )
    }

    private companion object {
        const val EVENTS_DIRECTORY = "legadom_ink_sync/v1/events/"
        const val MAX_PUT_ATTEMPTS = 2
        val AMBIGUOUS_PUT_ERRORS = setOf(
            SyncV1ErrorCode.HTTP_CONFLICT,
            SyncV1ErrorCode.HTTP_SERVER_ERROR,
            SyncV1ErrorCode.TIMEOUT,
            SyncV1ErrorCode.DNS_ERROR,
            SyncV1ErrorCode.TLS_ERROR,
            SyncV1ErrorCode.NETWORK_UNKNOWN
        )
    }
}
