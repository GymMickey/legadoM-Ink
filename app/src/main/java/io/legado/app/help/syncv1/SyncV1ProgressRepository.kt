package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException

/** A5-A baseline/projection coordinator and A5-B event entry point. All remote writes use saved bytes. */
internal class SyncV1ProgressRepository(
    private val store: SyncV1ProgressStore,
    private val catalogStore: SyncV1CatalogStore,
    private val chaptersStore: SyncV1ChaptersStore,
    private val bindingStore: SyncV1BindingStore,
    private val catalogRepository: SyncV1ProgressCatalogGateway,
    private val sessionProvider: suspend () -> SyncV1Session,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> UUID = UUID::randomUUID
) {
    private val operationMutex = Mutex()

    suspend fun initialize(
        inputs: List<SyncV1ProgressWorkInput>,
        sessionOverride: SyncV1Session? = null,
        allowRemoteRecovery: Boolean = false
    ): SyncV1ProgressOutcome = operationMutex.withLock {
        val outcomes = mutableListOf<SyncV1ProgressWorkOutcome>()
        var published = 0
        var unchanged = 0
        var failed = 0
        var catalogPublished = false
        var catalogRevision: Long? = null
        var stateIntentsPending = 0
        var catalogIntentsPending = 0
        val pendingIntentFailures = mutableMapOf<String, SyncV1ErrorCode>()
        try {
            if (!catalogStore.hasReadyDevice()) return@withLock SyncV1ProgressOutcome(
                SyncV1ProgressPhase.FAILED, errorCode = SyncV1ErrorCode.NO_READY_DEVICE
            )
            val session = sessionOverride ?: sessionProvider()
            requireCurrent(session)
            val oldCatalog = catalogRepository.resumePendingIntent(session)
            if (oldCatalog?.phase == SyncV1CatalogPhase.FAILED) {
                catalogIntentsPending = 1
                throw SyncV1Failure(oldCatalog.errorCode ?: SyncV1ErrorCode.NETWORK_UNKNOWN)
            }
            restoreConfirmedCatalogBaselines(session)
            val directoryBatch = ProgressDirectoryBatch(session)

            // A saved intent owns its bytes and revision across process restarts.
            for (intent in store.pendingProgressIntents(session.serviceIdentity, session.rootIdentity)) {
                try {
                    publishStateIntent(session, intent, directoryBatch)
                    store.markProgressIntentPublished(intent, nowMillis())
                    published++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed++
                    pendingIntentFailures[intent.workId] = failure.asProgressError()
                }
            }

            val books = inputs.distinctBy { it.bookUrl }.map { SyncV1CatalogBook(it.bookUrl, it.title, it.author) }
            val workIds = catalogRepository.resolveWorkIdentities(books, session)
            val chapters = inputs.distinctBy { it.bookUrl }.associate { input ->
                input.bookUrl to workIds[input.bookUrl]?.let { chaptersStore.chapterPublicationState(session.serviceIdentity, session.rootIdentity, it) }
            }
            val publisherId = catalogStore.androidPublisherDeviceId(uuidProvider)
            val latestRemoteCatalog = catalogRepository.latestRemoteCatalog(session)
            val catalogOverrides = linkedMapOf<String, SyncV1ProgressCatalogValue>()
            val pendingWorkIds = buildSet {
                store.pendingProgressIntents(session.serviceIdentity, session.rootIdentity).forEach { add(it.workId) }
                store.pendingProgressReceipts(session.serviceIdentity, session.rootIdentity)
                    .filter { it.projectionStage != "ack_published" }
                    .forEach { add(it.workId) }
            }

            for (input in inputs.distinctBy { it.bookUrl }) {
                requireCurrent(session)
                val workId = workIds[input.bookUrl] ?: continue
                pendingIntentFailures[workId]?.let { error ->
                    val old = store.progressCatalogValue(session.serviceIdentity, session.rootIdentity, workId)
                    catalogOverrides[workId] = old
                    outcomes += SyncV1ProgressWorkOutcome(workId, old.status, old.progressRevision, errorCode = error)
                    return@let
                }
                if (pendingIntentFailures.containsKey(workId)) continue
                try {
                    val chapterState = chapters[input.bookUrl]
                    val parsedChapters = chapterState?.takeIf {
                        it.serviceIdentity == session.serviceIdentity && it.rootIdentity == session.rootIdentity
                            && it.rawSha256 == SyncV1Protocol.sha256(it.rawJsonBytes)
                    }?.let { SyncV1Protocol.parseChapters(it.rawJsonBytes) }
                    val validChapters = chapterState?.let { saved -> parsedChapters?.takeIf {
                        it.workId == workId && it.tocRevision == saved.tocRevision
                            && it.snapshotId == saved.snapshotId.toString()
                    } }
                    val localState = store.progressState(session.serviceIdentity, session.rootIdentity, workId)
                    val localBaseline = store.progressCatalogValue(session.serviceIdentity, session.rootIdentity, workId)
                    val catalogWork = latestRemoteCatalog?.works?.firstOrNull { it.workId == workId }
                    val catalogAllowsRecovery = catalogWork?.progressBaselineStatus == SyncV1ProgressBaseline.AVAILABLE.wireValue
                        && catalogWork.progressRevision >= 1
                    val localStateNeedsVerification = localState != null
                        || localBaseline.status == SyncV1ProgressBaseline.AVAILABLE && localBaseline.progressRevision >= 1
                    val shouldScanRemote = allowRemoteRecovery || localStateNeedsVerification || catalogAllowsRecovery || workId in pendingWorkIds
                    val remote = if (shouldScanRemote) scanRemoteStates(session, workId) else RemoteProgressScan(null)
                    val remoteLatest = remote.latest
                    var state = localState

                    if (state != null && (remoteLatest == null || remoteLatest.progressRevision < state.progressRevision)) {
                        throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING)
                    }
                    if (state == null && remoteLatest == null
                        && catalogWork?.progressBaselineStatus == SyncV1ProgressBaseline.AVAILABLE.wireValue
                        && catalogWork.progressRevision > 0
                    ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING)
                    if (remoteLatest != null && state != null && remoteLatest.progressRevision == state.progressRevision
                        && (!remoteLatest.rawJsonBytes.contentEquals(state.rawJsonBytes) || remoteLatest.rawSha256 != state.rawSha256)
                    ) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)

                    if (state == null && remoteLatest != null) {
                        if (catalogWork?.progressBaselineStatus == "available"
                            && catalogWork.progressRevision == remoteLatest.progressRevision
                        ) {
                            store.adoptConfirmedProgressState(remoteLatest, session.configIdentity, nowMillis())
                            state = remoteLatest
                        } else {
                            throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING)
                        }
                    } else if (remoteLatest != null && state != null && remoteLatest.progressRevision > state.progressRevision) {
                        if (catalogWork?.progressBaselineStatus == "available"
                            && catalogWork.progressRevision == remoteLatest.progressRevision
                        ) {
                            store.adoptConfirmedProgressState(remoteLatest, session.configIdentity, nowMillis())
                            state = remoteLatest
                        } else throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_CATALOG_PENDING)
                    }

                    if (state == null && !input.hasLocalProgress
                        && catalogWork?.progressBaselineStatus == SyncV1ProgressBaseline.ZERO_CONFIRMED.wireValue
                        && catalogWork.progressRevision == 0L
                    ) {
                        catalogOverrides[workId] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.ZERO_CONFIRMED, 0)
                        outcomes += SyncV1ProgressWorkOutcome(workId, SyncV1ProgressBaseline.ZERO_CONFIRMED, 0)
                        unchanged++
                        continue
                    }

                    if (state == null && SyncV1ProgressSemantics.baselineForEvidence(
                            hasLocalProgress = input.hasLocalProgress,
                            authoritativeNeverRead = input.authoritativeNeverRead,
                            exactChapterMappingAvailable = false
                        ) == SyncV1ProgressBaseline.ZERO_CONFIRMED
                    ) {
                        catalogOverrides[workId] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.ZERO_CONFIRMED, 0)
                        outcomes += SyncV1ProgressWorkOutcome(workId, SyncV1ProgressBaseline.ZERO_CONFIRMED, 0)
                        unchanged++
                        continue
                    }

                    if (state == null && input.hasLocalProgress && validChapters != null) {
                        val chapter = validChapters.chapters.firstOrNull {
                            it.localTocIndex == input.currentIndex && (input.currentTitle == null || it.title == input.currentTitle)
                        }
                        if (chapter != null) {
                            val intent = newStateIntent(
                                session, workId, validChapters.tocRevision, 1, uuidProvider(), chapter.title,
                                chapter.localTocIndex, null, publisherId
                            )
                            val durable = store.createProgressIntentIfAbsent(intent)
                            publishStateIntent(session, durable, directoryBatch)
                            store.markProgressIntentPublished(durable, nowMillis())
                            state = store.progressState(session.serviceIdentity, session.rootIdentity, workId)
                            published++
                        }
                    } else if (state != null && validChapters != null && state.tocRevision != validChapters.tocRevision) {
                        val chapter = validChapters.chapters.firstOrNull {
                            it.localTocIndex == state.localTocIndex && it.title == state.chapterTitle
                        }
                        if (chapter != null) {
                            val nextRevision = maxOf(state.progressRevision, remoteLatest?.progressRevision ?: 0L) + 1
                            val intent = newStateIntent(
                                session, workId, validChapters.tocRevision, nextRevision, uuidProvider(),
                                chapter.title, chapter.localTocIndex, null, publisherId
                            )
                            val durable = store.createProgressIntentIfAbsent(intent)
                            publishStateIntent(session, durable, directoryBatch)
                            store.markProgressIntentPublished(durable, nowMillis())
                            state = store.progressState(session.serviceIdentity, session.rootIdentity, workId)
                            published++
                        }
                    }

                    when {
                        state == null -> {
                            catalogOverrides[workId] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.UNAVAILABLE, 0)
                            outcomes += SyncV1ProgressWorkOutcome(workId, SyncV1ProgressBaseline.UNAVAILABLE, 0)
                            unchanged++
                        }
                        else -> {
                            val staleToc = validChapters == null || state.tocRevision != validChapters.tocRevision
                            catalogOverrides[workId] = SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, state.progressRevision)
                            outcomes += SyncV1ProgressWorkOutcome(workId, SyncV1ProgressBaseline.AVAILABLE, state.progressRevision, staleToc)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed++
                    val old = store.progressCatalogValue(session.serviceIdentity, session.rootIdentity, workId)
                    catalogOverrides[workId] = old
                    outcomes += SyncV1ProgressWorkOutcome(workId, old.status, old.progressRevision, errorCode = failure.asProgressError())
                }
            }

            stateIntentsPending = store.pendingProgressIntents(session.serviceIdentity, session.rootIdentity).size
            val tocRevisions = chaptersStore.confirmedChapterRevisions(session.serviceIdentity, session.rootIdentity)
            val catalog = catalogRepository.publish(
                books = books,
                confirmedTocRevisions = tocRevisions,
                sessionOverride = session,
                progressValuesOverride = catalogOverrides
            )
            catalogPublished = catalog.phase in setOf(SyncV1CatalogPhase.PUBLISHED, SyncV1CatalogPhase.UNCHANGED)
            catalogRevision = catalog.catalogRevision
            if (!catalogPublished) {
                catalogIntentsPending = 1
                throw SyncV1Failure(catalog.errorCode ?: SyncV1ErrorCode.NETWORK_UNKNOWN)
            }
            for ((workId, value) in catalogOverrides) {
                val toc = tocRevisions[workId] ?: store.progressState(session.serviceIdentity, session.rootIdentity, workId)?.tocRevision ?: 0
                store.setProgressBaseline(session.serviceIdentity, session.rootIdentity, workId, value.status, value.progressRevision, toc, nowMillis())
            }
            store.setProgressCatalogDirty(session.serviceIdentity, session.rootIdentity, false)
            val phase = when {
                failed > 0 -> SyncV1ProgressPhase.PARTIAL_SUCCESS
                published > 0 -> SyncV1ProgressPhase.PUBLISHED
                else -> SyncV1ProgressPhase.UNCHANGED
            }
            SyncV1ProgressOutcome(phase, inputs.distinctBy { it.bookUrl }.size, published, unchanged, failed,
                catalogPublished, catalogRevision, outcomes, stateIntentsPending = stateIntentsPending,
                catalogIntentsPending = catalogIntentsPending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SyncV1ProgressOutcome(SyncV1ProgressPhase.FAILED, workOutcomes = outcomes,
                errorCode = failure.asProgressError(), catalogPublished = catalogPublished, catalogRevision = catalogRevision,
                stateIntentsPending = stateIntentsPending, catalogIntentsPending = catalogIntentsPending)
        }
    }

    suspend fun processEvents(
        books: List<SyncV1CatalogBook>,
        sessionOverride: SyncV1Session? = null
    ): SyncV1ProgressOutcome = operationMutex.withLock {
        var scanned = 0; var applied = 0; var staleToc = 0; var staleProgress = 0
        var rejected = 0; var conflicts = 0
        var eventsPending = 0; var stateIntentsPending = 0; var catalogIntentsPending = 0; var acknowledgementsPending = 0
        var firstError: SyncV1ErrorCode? = null
        try {
            val session = sessionOverride ?: sessionProvider()
            requireCurrent(session)
            val oldCatalog = catalogRepository.resumePendingIntent(session)
            if (oldCatalog?.phase == SyncV1CatalogPhase.FAILED) {
                catalogIntentsPending++
                throw SyncV1Failure(oldCatalog.errorCode ?: SyncV1ErrorCode.NETWORK_UNKNOWN)
            }
            restoreConfirmedCatalogBaselines(session)
            val directoryBatch = ProgressDirectoryBatch(session)
            val devices = store.readyProgressDevices(session.serviceIdentity, session.rootIdentity)
                .filter { SyncV1Protocol.isUuidV4(it.deviceId) }.distinctBy { it.deviceId }
            if (devices.isEmpty()) return@withLock SyncV1ProgressOutcome(
                SyncV1ProgressPhase.FAILED, errorCode = SyncV1ErrorCode.NO_READY_DEVICE
            )

            // Restore every durable A5 transaction before looking at new remote events.
            for (saved in store.pendingProgressReceipts(session.serviceIdentity, session.rootIdentity)) {
                try {
                    var receipt = saved
                    if (receipt.projectionStage == "projection_pending") {
                        val intent = store.progressIntentForEvent(receipt.eventId, session.serviceIdentity, session.rootIdentity)
                            ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_PROJECTION_PENDING)
                        publishStateIntent(session, intent, directoryBatch)
                        store.markProgressIntentPublished(intent, nowMillis())
                        val toc = chaptersStore.confirmedChapterRevisions(session.serviceIdentity, session.rootIdentity)
                        val progressOverride = mapOf(
                            intent.workId to SyncV1ProgressCatalogValue(
                                SyncV1ProgressBaseline.AVAILABLE,
                                intent.progressRevision
                            )
                        )
                        val catalog = catalogRepository.publish(books, toc, session, progressOverride)
                        if (catalog.phase !in setOf(SyncV1CatalogPhase.PUBLISHED, SyncV1CatalogPhase.UNCHANGED))
                            throw SyncV1Failure(catalog.errorCode ?: SyncV1ErrorCode.NETWORK_UNKNOWN)
                        store.setProgressBaseline(
                            session.serviceIdentity, session.rootIdentity, intent.workId,
                            SyncV1ProgressBaseline.AVAILABLE, intent.progressRevision,
                            intent.tocRevision, nowMillis()
                        )
                        store.updateProgressReceipt(receipt, catalog.catalogRevision?.let { "${SyncV1CatalogRepository.CATALOG_DIRECTORY}$it-${catalog.snapshotId}.json" }, "ack_publishable")
                        store.setProgressCatalogDirty(session.serviceIdentity, session.rootIdentity, false)
                        receipt = receipt.copy(projectionStage = "ack_publishable")
                    }
                    publishProgressAck(session, receipt)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    when {
                        saved.projectionStage != "projection_pending" -> acknowledgementsPending++
                        store.progressIntentForEvent(saved.eventId, session.serviceIdentity, session.rootIdentity)
                            ?.publicationStatus == "published" -> catalogIntentsPending++
                        else -> stateIntentsPending++
                    }
                    if (firstError == null) firstError = failure.asProgressError()
                }
            }

            for (device in devices) {
                val registrationResponse = session.transport.get("legadom_ink_sync/v1/devices/${device.deviceId}.json", SYNC_V1_MAX_OBJECT_BYTES, true)
                requireCurrent(session)
                if (registrationResponse.statusCode != 200 || registrationResponse.bodyTooLarge
                    || registrationResponse.body.size > SYNC_V1_MAX_OBJECT_BYTES
                    || registrationResponse.rawSha256?.let { it != SyncV1Protocol.sha256(registrationResponse.body) } == true
                    || SyncV1Protocol.sha256(registrationResponse.body) != device.registrationPayloadSha256
                ) { if (firstError == null) firstError = SyncV1ErrorCode.INVALID_REGISTRATION; continue }
                val registrationResult = SyncV1Protocol.parseRegistration(device.deviceId, registrationResponse.body)
                val registration = registrationResult as? RegistrationValidation.Valid
                if (registration == null) { if (firstError == null) firstError = SyncV1ErrorCode.INVALID_REGISTRATION; continue }
                if (registration.registration.locatorCapability != device.locatorCapability) {
                    if (firstError == null) firstError = SyncV1ErrorCode.INVALID_REGISTRATION
                    continue
                }
                val entries = try { session.transport.listDirectChildren("legadom_ink_sync/v1/events/${device.deviceId}/") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (firstError == null) firstError = failure.asProgressError(); continue }
                for (entry in entries) {
                    if (entry.isDirectory || !entry.name.endsWith(".json")) continue
                    val eventId = entry.name.removeSuffix(".json")
                    if (!SyncV1Protocol.isUuidV4(eventId)) continue
                    try {
                        val response = session.transport.get("legadom_ink_sync/v1/events/${device.deviceId}/${entry.name}",
                            SYNC_V1_MAX_BINDING_EVENT_BYTES, true)
                        requireCurrent(session)
                        if (response.statusCode != 200) throw httpFailure(response.statusCode)
                        if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_BINDING_EVENT_BYTES) {
                            eventsPending++; if (firstError == null) firstError = SyncV1ErrorCode.SIZE_EXCEEDED; continue
                        }
                        val rawSha = SyncV1Protocol.sha256(response.body)
                        if (response.rawSha256 != null && response.rawSha256 != rawSha) {
                            eventsPending++; if (firstError == null) firstError = SyncV1ErrorCode.REMOTE_CONTENT_CONFLICT; continue
                        }
                        val eventType = SyncV1Protocol.eventType(response.body)
                        if (eventType != "progress") continue // A4 owns binding_request; reading_time/unknown stay untouched.
                        scanned++
                        val prior = store.progressReceipt(eventId)
                        if (prior != null) {
                            if (prior.serviceIdentity != session.serviceIdentity || prior.rootIdentity != session.rootIdentity
                                || prior.deviceId != device.deviceId || prior.payloadSha256 != rawSha
                                || !prior.eventRawJsonBytes.contentEquals(response.body)
                            ) {
                                conflicts++; if (firstError == null) firstError = SyncV1ErrorCode.EVENT_ID_CONFLICT; continue
                            }
                            // Every unpublished receipt was attempted above. Do not retry it twice
                            // in the same pass; retain the original durable stage for the next run.
                            continue
                        }
                        if (bindingStore.importReceipt(eventId) != null) {
                            conflicts++; if (firstError == null) firstError = SyncV1ErrorCode.EVENT_ID_CONFLICT; continue
                        }
                        when (val parsed = SyncV1ProgressProtocol.parseEvent(device.deviceId, eventId, response.body)) {
                            SyncV1ProgressEventParseResult.NotProgress -> Unit
                            is SyncV1ProgressEventParseResult.Invalid -> {
                                if (!parsed.declaredProgress) { eventsPending++; continue }
                                val reason = if (parsed.errorCode == SyncV1ErrorCode.UNSUPPORTED_SCHEMA) "unsupported_schema" else "invalid_event"
                                val acknowledgedAt = Instant.ofEpochMilli(nowMillis()).toString()
                                val ack = SyncV1ProgressProtocol.ackBytes(device.deviceId, eventId, null, rawSha,
                                    "rejected", acknowledgedAt, "manual_review", reason)
                                val receipt = SyncV1ProgressReceipt(eventId, device.deviceId, "", rawSha, response.body.copyOf(), "rejected",
                                    acknowledgedAt, ack, SyncV1Protocol.sha256(ack), "ack_publishable",
                                    session.serviceIdentity, session.rootIdentity)
                                store.persistProgressReceipt(receipt)
                                publishProgressAck(session, receipt)
                                rejected++
                            }
                            is SyncV1ProgressEventParseResult.Valid -> {
                                val result = processValidProgressEvent(session, device, registration.registration.counterEpoch,
                                    parsed.event, response.body, rawSha, books, directoryBatch)
                                when (result) {
                                    "applied" -> applied++
                                    "stale_toc" -> staleToc++
                                    "stale_progress" -> staleProgress++
                                    "rejected" -> rejected++
                                    "event_id_conflict" -> conflicts++
                                    "pending" -> eventsPending++
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        val receipt = store.progressReceipt(eventId)
                        when {
                            receipt == null -> eventsPending++
                            receipt.projectionStage == "projection_pending" -> {
                                val intent = store.progressIntentForEvent(eventId, session.serviceIdentity, session.rootIdentity)
                                if (intent?.publicationStatus == "published") catalogIntentsPending++ else stateIntentsPending++
                                if (receipt.terminalStatus == "applied") applied++
                            }
                            receipt.projectionStage != "ack_published" -> {
                                acknowledgementsPending++
                                when (receipt.terminalStatus) {
                                    "applied" -> applied++
                                    "stale_toc" -> staleToc++
                                    "stale_progress" -> staleProgress++
                                    "rejected" -> rejected++
                                }
                            }
                            else -> Unit
                        }
                        if (firstError == null) firstError = failure.asProgressError()
                    }
                }
            }
            val terminal = applied + staleToc + staleProgress + rejected
            SyncV1ProgressOutcome(
                phase = if (eventsPending + stateIntentsPending + catalogIntentsPending + acknowledgementsPending > 0 || firstError != null)
                    SyncV1ProgressPhase.PARTIAL_SUCCESS else if (terminal > 0) SyncV1ProgressPhase.PUBLISHED else SyncV1ProgressPhase.UNCHANGED,
                scannedEvents = scanned, applied = applied, staleToc = staleToc, staleProgress = staleProgress,
                rejected = rejected, eventIdConflicts = conflicts, eventsPending = eventsPending,
                stateIntentsPending = stateIntentsPending, catalogIntentsPending = catalogIntentsPending,
                acknowledgementsPending = acknowledgementsPending, errorCode = firstError
            )
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            SyncV1ProgressOutcome(SyncV1ProgressPhase.FAILED, scannedEvents = scanned, applied = applied,
                staleToc = staleToc, staleProgress = staleProgress, rejected = rejected, eventIdConflicts = conflicts,
                eventsPending = eventsPending, stateIntentsPending = stateIntentsPending,
                catalogIntentsPending = catalogIntentsPending, acknowledgementsPending = acknowledgementsPending,
                errorCode = failure.asProgressError())
        }
    }

    private suspend fun processValidProgressEvent(
        session: SyncV1Session,
        device: SyncV1ProgressDevice,
        registeredEpoch: String,
        event: ParsedSyncV1ProgressEvent,
        eventBytes: ByteArray,
        payloadSha: String,
        books: List<SyncV1CatalogBook>,
        directoryBatch: ProgressDirectoryBatch
    ): String {
        if (event.counterEpoch != registeredEpoch || !store.hasProgressBinding(event.deviceId, event.counterEpoch, event.workId)) {
            persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                "rejected", "invalid_event", "manual_review")
            return "rejected"
        }
        if (store.hasPendingProgressProjection(session.serviceIdentity, session.rootIdentity, event.workId)) {
            return "pending"
        }
        val baseline = store.progressCatalogValue(session.serviceIdentity, session.rootIdentity, event.workId)
        if (!SyncV1ProgressSemantics.isValidCatalogBaseline(baseline.status, baseline.progressRevision)
            || !SyncV1ProgressSemantics.acceptsProgressUpload(baseline.status)
        ) return "pending"
        val catalog = catalogRepository.latestRemoteCatalog(session) ?: return "pending"
        val catalogWork = catalog.works.firstOrNull { it.workId == event.workId } ?: return "pending"
        if (catalogWork.progressBaselineStatus != baseline.status.wireValue || catalogWork.progressRevision != baseline.progressRevision) return "pending"
        val chapterState = chaptersStore.chapterPublicationState(session.serviceIdentity, session.rootIdentity, event.workId)
            ?: return "pending"
        if (chapterState.serviceIdentity != session.serviceIdentity || chapterState.rootIdentity != session.rootIdentity
            || chapterState.rawSha256 != SyncV1Protocol.sha256(chapterState.rawJsonBytes)
        ) return "pending"
        val chapters = SyncV1Protocol.parseChapters(chapterState.rawJsonBytes)
            ?.takeIf { it.workId == event.workId && it.tocRevision == chapterState.tocRevision && it.snapshotId == chapterState.snapshotId.toString() }
            ?: return "pending"
        val currentProgress = if (baseline.status == SyncV1ProgressBaseline.AVAILABLE) {
            store.progressState(session.serviceIdentity, session.rootIdentity, event.workId) ?: return "pending"
        } else null
        if (baseline.status == SyncV1ProgressBaseline.AVAILABLE && currentProgress?.progressRevision != baseline.progressRevision) return "pending"
        if (catalogWork.tocRevision != chapters.tocRevision) return "pending"

        // Protocol precedence: TOC staleness wins when both independent revisions are stale.
        val staleStatus = SyncV1ProgressSemantics.staleStatus(
            event.basedOnTocRevision, chapters.tocRevision, event.basedOnProgressRevision, baseline.progressRevision
        )
        if (staleStatus == "stale_toc") {
            persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                "stale_toc", "stale_toc", "download_chapters", currentTocRevision = chapters.tocRevision)
            return "stale_toc"
        }
        if (staleStatus == "stale_progress") {
            if (baseline.progressRevision < 1) return "pending"
            persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                "stale_progress", "stale_progress", "download_progress_state", currentProgressRevision = baseline.progressRevision)
            return "stale_progress"
        }
        if (!SyncV1ProgressSemantics.locatorAllowed(device.locatorCapability, event.locatorConfidence)) {
            persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                "rejected", "unsupported_schema", "manual_review")
            return "rejected"
        }
        val currentChapter = chapters.chapters.getOrNull(event.localTocIndex.toInt())
            ?.takeIf { it.localTocIndex == event.localTocIndex && it.title == event.chapterTitle }
            ?: chapters.chapters.firstOrNull { it.localTocIndex == event.localTocIndex && it.title == event.chapterTitle }
            ?: run {
                persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                    "rejected", "invalid_event", "manual_review")
                return "rejected"
            }
        val chapterIndex = chapters.chapters.indexOf(currentChapter)
        if (event.previousChapterTitle != null && chapters.chapters.getOrNull(chapterIndex - 1)?.title != event.previousChapterTitle
            || event.nextChapterTitle != null && chapters.chapters.getOrNull(chapterIndex + 1)?.title != event.nextChapterTitle
        ) {
            persistTerminalAck(session, event.deviceId, event.eventId, event.workId, payloadSha, eventBytes,
                "rejected", "invalid_event", "manual_review")
            return "rejected"
        }
        val nextRevision = baseline.progressRevision + 1
        if (nextRevision !in 1..SYNC_V1_MAX_SAFE_INTEGER) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val snapshotId = uuidProvider()
        if (!SyncV1Protocol.isUuidV4(snapshotId.toString())) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val acknowledgedAt = Instant.ofEpochMilli(nowMillis()).toString()
        val stateBytes = SyncV1ProgressProtocol.stateBytes(
            workId = event.workId, tocRevision = chapters.tocRevision, progressRevision = nextRevision,
            snapshotId = snapshotId.toString(), chapterTitle = currentChapter.title,
            localTocIndex = currentChapter.localTocIndex, sourceEventId = event.eventId,
            updatedByDeviceId = event.deviceId, updatedAt = acknowledgedAt,
            fractionPpm = event.chapterFractionPpm, visibleCharOffset = event.visibleCharOffset,
            visibleCharCount = event.visibleCharCount, chapterTextFingerprint = event.chapterTextFingerprint,
            locatorConfidence = event.locatorConfidence
        )
        if (stateBytes.size > SYNC_V1_MAX_OBJECT_BYTES) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_TOO_LARGE)
        val path = "$PROGRESS_DIRECTORY${event.workId}/$nextRevision-$snapshotId.json"
        val intent = SyncV1ProgressPublicationIntent(event.workId, chapters.tocRevision, nextRevision, snapshotId,
            path, stateBytes, SyncV1Protocol.sha256(stateBytes), 0, session.configIdentity, session.serviceIdentity,
            session.rootIdentity, event.eventId, SYNC_V1_CREATE_MODE, "pending", nowMillis(), nowMillis())
        val ackBytes = SyncV1ProgressProtocol.ackBytes(event.deviceId, event.eventId, event.workId, payloadSha,
            "applied", acknowledgedAt)
        val receipt = SyncV1ProgressReceipt(event.eventId, event.deviceId, event.workId, payloadSha, eventBytes.copyOf(), "applied",
            acknowledgedAt, ackBytes, SyncV1Protocol.sha256(ackBytes), "projection_pending",
            session.serviceIdentity, session.rootIdentity)
        store.persistAppliedProgress(eventBytes, intent, receipt)

        publishStateIntent(session, intent, directoryBatch)
        store.markProgressIntentPublished(intent, nowMillis())
        val revisions = chaptersStore.confirmedChapterRevisions(session.serviceIdentity, session.rootIdentity)
        val catalogResult = catalogRepository.publish(
            books,
            revisions,
            session,
            mapOf(event.workId to SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.AVAILABLE, intent.progressRevision))
        )
        if (catalogResult.phase !in setOf(SyncV1CatalogPhase.PUBLISHED, SyncV1CatalogPhase.UNCHANGED))
            throw SyncV1Failure(catalogResult.errorCode ?: SyncV1ErrorCode.NETWORK_UNKNOWN)
        val catalogPath = catalogResult.catalogRevision?.let { "${SyncV1CatalogRepository.CATALOG_DIRECTORY}$it-${catalogResult.snapshotId}.json" }
        store.setProgressBaseline(
            session.serviceIdentity, session.rootIdentity, event.workId,
            SyncV1ProgressBaseline.AVAILABLE, intent.progressRevision,
            intent.tocRevision, nowMillis()
        )
        store.updateProgressReceipt(receipt, catalogPath, "projection_confirmed")
        store.setProgressCatalogDirty(session.serviceIdentity, session.rootIdentity, false)
        store.updateProgressReceipt(receipt, catalogPath, "ack_publishable")
        publishProgressAck(session, receipt.copy(projectionStage = "ack_publishable"))
        return "applied"
    }

    /** Rehydrates v5 baseline rows only from the exact locally confirmed catalog bytes. */
    private suspend fun restoreConfirmedCatalogBaselines(session: SyncV1Session) {
        val intent = catalogStore.latestPublishedCatalogIntent(session.serviceIdentity, session.rootIdentity) ?: return
        if (intent.rawSha256 != SyncV1Protocol.sha256(intent.rawJsonBytes)) return
        val catalog = SyncV1Protocol.parseCatalog(intent.rawJsonBytes) ?: return
        if (catalog.catalogRevision != intent.catalogRevision || catalog.snapshotId != intent.snapshotId.toString()) return
        for (work in catalog.works) {
            val status = SyncV1ProgressBaseline.entries.firstOrNull { it.wireValue == work.progressBaselineStatus }
                ?: continue
            if (!SyncV1ProgressSemantics.isValidCatalogBaseline(status, work.progressRevision)) continue
            if (status == SyncV1ProgressBaseline.AVAILABLE) {
                val saved = store.progressState(session.serviceIdentity, session.rootIdentity, work.workId) ?: continue
                if (saved.progressRevision != work.progressRevision || saved.rawSha256 != SyncV1Protocol.sha256(saved.rawJsonBytes)) continue
            }
            store.setProgressBaseline(
                session.serviceIdentity, session.rootIdentity, work.workId,
                status, work.progressRevision, work.tocRevision, nowMillis()
            )
        }
    }

    private suspend fun persistTerminalAck(
        session: SyncV1Session, deviceId: String, eventId: String, workId: String, payloadSha: String,
        eventRawBytes: ByteArray,
        status: String, reason: String, recovery: String,
        currentProgressRevision: Long? = null, currentTocRevision: Long? = null
    ) {
        val now = Instant.ofEpochMilli(nowMillis()).toString()
        val bytes = SyncV1ProgressProtocol.ackBytes(deviceId, eventId, workId, payloadSha, status, now,
            recovery, reason, currentProgressRevision, currentTocRevision)
        val receipt = SyncV1ProgressReceipt(eventId, deviceId, workId, payloadSha, eventRawBytes.copyOf(), status, now, bytes,
            SyncV1Protocol.sha256(bytes), "ack_publishable", session.serviceIdentity, session.rootIdentity)
        store.persistProgressReceipt(receipt)
        publishProgressAck(session, receipt)
    }

    private suspend fun publishProgressAck(session: SyncV1Session, receipt: SyncV1ProgressReceipt) {
        if (receipt.projectionStage == "projection_pending") throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_PROJECTION_PENDING)
        if (receipt.ackSha256 != SyncV1Protocol.sha256(receipt.ackRawJsonBytes)) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
        val path = "legadom_ink_sync/v1/acks/${receipt.deviceId}/${receipt.eventId}.json"
        var attempts = receipt.ackPutAttemptCount
        while (true) {
            requireCurrent(session)
            val existing = session.transport.get(path, SYNC_V1_MAX_BINDING_EVENT_BYTES, true)
            requireCurrent(session)
            when (existing.statusCode) {
                200 -> {
                    val digest = SyncV1Protocol.sha256(existing.body)
                    if (!existing.body.contentEquals(receipt.ackRawJsonBytes) || digest != receipt.ackSha256
                        || existing.rawSha256?.let { it != digest } == true
                    ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
                    store.updateProgressReceipt(receipt, stage = "ack_published")
                    return
                }
                404 -> Unit
                else -> throw httpFailure(existing.statusCode)
            }
            if (attempts >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            store.incrementProgressAckAttempt(receipt.eventId); attempts++
            var putResponse: SyncV1HttpResponse? = null
            var putFailure: Exception? = null
            try {
                val response = session.transport.put(path, receipt.ackRawJsonBytes.copyOf())
                putResponse = response
                requireCurrent(session)
                if (response.statusCode !in 200..299 && response.statusCode != 409
                    && response.statusCode !in 500..599
                ) throw httpFailure(response.statusCode)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { putFailure = failure }
            val readback = try {
                session.transport.get(path, SYNC_V1_MAX_BINDING_EVENT_BYTES, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw putFailure ?: failure }
            requireCurrent(session)
            if (readback.statusCode == 200) {
                val digest = SyncV1Protocol.sha256(readback.body)
                if (!readback.body.contentEquals(receipt.ackRawJsonBytes) || digest != receipt.ackSha256
                    || readback.rawSha256?.let { it != digest } == true
                ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
                store.updateProgressReceipt(receipt, stage = "ack_published")
                return
            }
            if (readback.statusCode != 404) throw httpFailure(readback.statusCode)
            val putStatus = putResponse?.statusCode
            if (putStatus == 409) throw httpFailure(409)
            if (putStatus != null && putStatus !in 200..299 && putStatus !in 500..599) {
                throw putFailure ?: httpFailure(putStatus)
            }
            val failureCode = (putFailure as? SyncV1Failure)?.errorCode
            if (failureCode != null && failureCode !in setOf(SyncV1ErrorCode.HTTP_SERVER_ERROR)) {
                throw putFailure
            }
            if (attempts >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
        }
    }

    private fun newStateIntent(
        session: SyncV1Session, workId: String, tocRevision: Long, progressRevision: Long, snapshotId: UUID,
        title: String, index: Long, sourceEventId: String?, publisherId: String
    ): SyncV1ProgressPublicationIntent {
        if (!SyncV1Protocol.isUuidV4(snapshotId.toString())) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val at = Instant.ofEpochMilli(nowMillis()).toString()
        val bytes = SyncV1ProgressProtocol.stateBytes(workId, tocRevision, progressRevision, snapshotId.toString(), title,
            index, sourceEventId, publisherId, at)
        if (bytes.size > SYNC_V1_MAX_OBJECT_BYTES) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_TOO_LARGE)
        val path = "$PROGRESS_DIRECTORY$workId/$progressRevision-$snapshotId.json"
        return SyncV1ProgressPublicationIntent(workId, tocRevision, progressRevision, snapshotId, path, bytes,
            SyncV1Protocol.sha256(bytes), 0, session.configIdentity, session.serviceIdentity, session.rootIdentity,
            sourceEventId, SYNC_V1_CREATE_MODE, "pending", nowMillis(), nowMillis())
    }

    private suspend fun publishStateIntent(
        session: SyncV1Session,
        initial: SyncV1ProgressPublicationIntent,
        directoryBatch: ProgressDirectoryBatch
    ) {
        var attempts = initial.putAttemptCount
        validateIntent(session, initial)
        directoryBatch.ensureWorkDirectory(initial.workId)
        while (true) {
            requireCurrent(session)
            val existing = session.transport.get(initial.remotePath, SYNC_V1_MAX_OBJECT_BYTES, true)
            requireCurrent(session)
            when (existing.statusCode) {
                200 -> {
                    if (!sameObject(initial, existing)) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                    return
                }
                404 -> Unit
                else -> throw httpFailure(existing.statusCode)
            }
            if (attempts >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            store.incrementProgressPutAttempt(initial, nowMillis())
            attempts++
            var putFailure: Exception? = null
            var putResponse: SyncV1HttpResponse? = null
            try {
                val response = session.transport.put(initial.remotePath, initial.rawJsonBytes.copyOf())
                putResponse = response
                requireCurrent(session)
                if (response.statusCode !in 200..299 && response.statusCode != 409
                    && response.statusCode !in 500..599
                ) throw httpFailure(response.statusCode)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { putFailure = failure }

            val readback = try { session.transport.get(initial.remotePath, SYNC_V1_MAX_OBJECT_BYTES, true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { throw putFailure ?: failure }
            requireCurrent(session)
            when (readback.statusCode) {
                200 -> {
                    if (!sameObject(initial, readback)) throw SyncV1Failure(SyncV1ErrorCode.READBACK_MISMATCH)
                    return
                }
                404 -> {
                    val putStatus = putResponse?.statusCode
                    if (putStatus == 409) throw httpFailure(409)
                    if (putStatus != null && putStatus !in 200..299 && putStatus !in 500..599) {
                        throw putFailure ?: httpFailure(putStatus)
                    }
                    val failureCode = (putFailure as? SyncV1Failure)?.errorCode
                    if (failureCode != null && failureCode !in setOf(SyncV1ErrorCode.HTTP_SERVER_ERROR)) {
                        throw putFailure
                    }
                    if (attempts >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                }
                else -> throw httpFailure(readback.statusCode)
            }
        }
    }

    private fun sameObject(intent: SyncV1ProgressPublicationIntent, response: SyncV1HttpResponse): Boolean {
        if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_OBJECT_BYTES) return false
        val sha = SyncV1Protocol.sha256(response.body)
        return response.body.contentEquals(intent.rawJsonBytes) && sha == intent.rawSha256
            && (response.rawSha256 == null || response.rawSha256 == sha)
    }

    private suspend fun scanRemoteStates(session: SyncV1Session, workId: String): RemoteProgressScan {
        val directory = "$PROGRESS_DIRECTORY$workId/"
        requireCurrent(session)
        val entries = try {
            session.transport.listDirectChildren(directory)
        } catch (failure: SyncV1Failure) {
            if (failure.errorCode == SyncV1ErrorCode.HTTP_NOT_FOUND) return RemoteProgressScan(null)
            throw failure
        }
        requireCurrent(session)
        val paths = entries.asSequence().filterNot { it.isDirectory }
            .mapNotNull { entry -> parseProgressPath(directory, entry.name) }.toList()
        requireCurrent(session)
        if (paths.groupBy { it.revision }.values.any { it.map(RemoteProgressPath::snapshotId).distinct().size > 1 })
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        val states = mutableListOf<SyncV1ProgressState>()
        for (path in paths.sortedByDescending { it.revision }) {
            val response = session.transport.get(path.path, SYNC_V1_MAX_OBJECT_BYTES, true)
            requireCurrent(session)
            if (response.statusCode == 404) continue
            if (response.statusCode != 200) throw httpFailure(response.statusCode)
            if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_OBJECT_BYTES)
                throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_TOO_LARGE)
            val digest = SyncV1Protocol.sha256(response.body)
            val parsed = SyncV1ProgressProtocol.parseState(response.body)
                ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
            if (response.rawSha256 != null && response.rawSha256 != digest || parsed.workId != workId
                || parsed.progressRevision != path.revision || parsed.snapshotId != path.snapshotId
            ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
            states += SyncV1ProgressState(parsed.workId, parsed.tocRevision, parsed.progressRevision, parsed.snapshotId,
                parsed.chapterTitle, parsed.localTocIndex, parsed.chapterFractionPpm, parsed.visibleCharOffset,
                parsed.visibleCharCount, parsed.chapterTextFingerprint, parsed.locatorConfidence, parsed.sourceEventId,
                parsed.updatedByDeviceId, parsed.updatedAt, response.body.copyOf(), digest, path.path,
                session.serviceIdentity, session.rootIdentity)
        }
        val sameRevision = states.groupBy { it.progressRevision }
        if (sameRevision.values.any { values -> values.map { it.rawSha256 }.distinct().size > 1 || values.map { it.rawJsonBytes.toList() }.distinct().size > 1 })
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        return RemoteProgressScan(states.maxByOrNull { it.progressRevision })
    }

    private fun parseProgressPath(directory: String, fileName: String): RemoteProgressPath? {
        val match = PROGRESS_PATH_PATTERN.matchEntire(fileName)
        if (match == null) {
            if (REVISION_CANDIDATE_PATTERN.matches(fileName)) {
                throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
            }
            return null
        }
        val revisionText = match.groupValues[1]
        val revision = revisionText.toLongOrNull()?.takeIf {
            it in 1..SYNC_V1_MAX_SAFE_INTEGER && it.toString() == revisionText
        } ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val snapshot = match.groupValues[2]
        if (!SyncV1Protocol.isUuidV4(snapshot)) return null
        return RemoteProgressPath(revision, snapshot, "$directory$fileName")
    }

    private fun validateIntent(session: SyncV1Session, intent: SyncV1ProgressPublicationIntent) {
        if (intent.serviceIdentity != session.serviceIdentity || intent.rootIdentity != session.rootIdentity
            || !session.acceptsConfigIdentity(intent.configIdentity) || !session.isCurrent()
            || intent.remotePath != "$PROGRESS_DIRECTORY${intent.workId}/${intent.progressRevision}-${intent.snapshotId}.json"
            || intent.rawSha256 != SyncV1Protocol.sha256(intent.rawJsonBytes)
        ) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun requireCurrent(session: SyncV1Session) { if (!session.isCurrent()) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED) }

    private inner class ProgressDirectoryBatch(private val session: SyncV1Session) {
        private var androidDirectoryReady = false
        private var progressDirectoryReady = false
        private val workDirectoriesReady = mutableSetOf<String>()

        suspend fun ensureWorkDirectory(workId: String) {
            if (!androidDirectoryReady) {
                session.transport.ensureDirectory(ANDROID_DIRECTORY)
                requireCurrent(session)
                androidDirectoryReady = true
            }
            if (!progressDirectoryReady) {
                session.transport.ensureDirectory(PROGRESS_DIRECTORY)
                requireCurrent(session)
                progressDirectoryReady = true
            }
            if (workDirectoriesReady.add(workId)) {
                try {
                    session.transport.ensureDirectory("$PROGRESS_DIRECTORY$workId/")
                    requireCurrent(session)
                } catch (failure: Exception) {
                    workDirectoriesReady.remove(workId)
                    throw failure
                }
            }
        }
    }
    private fun httpFailure(code: Int) = SyncV1Failure(when (code) {
        401, 403 -> SyncV1ErrorCode.AUTHENTICATION_FAILED
        404 -> SyncV1ErrorCode.HTTP_NOT_FOUND
        409 -> SyncV1ErrorCode.HTTP_CONFLICT
        in 500..599 -> SyncV1ErrorCode.HTTP_SERVER_ERROR
        in 400..499 -> SyncV1ErrorCode.HTTP_CLIENT_ERROR
        else -> SyncV1ErrorCode.NETWORK_UNKNOWN
    })
    private fun Throwable.asProgressError(): SyncV1ErrorCode = when (this) {
        is SyncV1Failure -> errorCode
        is SocketTimeoutException -> SyncV1ErrorCode.TIMEOUT
        is UnknownHostException -> SyncV1ErrorCode.DNS_ERROR
        is SSLException -> SyncV1ErrorCode.TLS_ERROR
        is IOException -> SyncV1ErrorCode.NETWORK_UNKNOWN
        else -> SyncV1ErrorCode.NETWORK_UNKNOWN
    }

    private data class RemoteProgressPath(val revision: Long, val snapshotId: String, val path: String)
    private data class RemoteProgressScan(val latest: SyncV1ProgressState?)

    private companion object {
        const val ANDROID_DIRECTORY = "legadom_ink_sync/v1/android/"
        const val PROGRESS_DIRECTORY = "legadom_ink_sync/v1/android/progress/"
        val PROGRESS_PATH_PATTERN = Regex("^([1-9][0-9]*)-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\.json$")
        val REVISION_CANDIDATE_PATTERN = Regex("^[0-9]+-.+\\.json$")
    }
}
