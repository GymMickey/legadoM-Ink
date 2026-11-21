package io.legado.app.help.syncv1

import java.util.UUID

enum class SyncV1ProgressBaseline(val wireValue: String) {
    ZERO_CONFIRMED("zero_confirmed"), AVAILABLE("available"), UNAVAILABLE("unavailable"), LEGACY_UNKNOWN("legacy_unknown")
}

data class SyncV1ProgressCatalogValue(
    val status: SyncV1ProgressBaseline,
    val progressRevision: Long
)

data class SyncV1ProgressPosition(
    val localTocIndex: Long,
    val chapterTitle: String
)

data class SyncV1ProgressState(
    val workId: String,
    val tocRevision: Long,
    val progressRevision: Long,
    val snapshotId: String,
    val chapterTitle: String,
    val localTocIndex: Long,
    val chapterFractionPpm: Int?,
    val visibleCharOffset: Long?,
    val visibleCharCount: Long?,
    val chapterTextFingerprint: String?,
    val locatorConfidence: String,
    val sourceEventId: String?,
    val updatedByDeviceId: String,
    val updatedAt: String,
    val rawJsonBytes: ByteArray,
    val rawSha256: String,
    val remotePath: String,
    val serviceIdentity: String,
    val rootIdentity: String
)

class SyncV1ProgressPublicationIntent(
    val workId: String,
    val tocRevision: Long,
    val progressRevision: Long,
    val snapshotId: UUID,
    val remotePath: String,
    rawJsonBytes: ByteArray,
    val rawSha256: String,
    val putAttemptCount: Int,
    val configIdentity: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val sourceEventId: String?,
    val createMode: String,
    val publicationStatus: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    val rawJsonBytes: ByteArray = rawJsonBytes.copyOf()
    override fun toString(): String = "SyncV1ProgressPublicationIntent(workId=$workId, progressRevision=$progressRevision, snapshotId=$snapshotId, rawBytes=${rawJsonBytes.size}, putAttemptCount=$putAttemptCount, publicationStatus=$publicationStatus)"
}

data class SyncV1ProgressWorkInput(
    val bookUrl: String,
    val title: String,
    val author: String = "",
    val currentIndex: Long,
    val currentTitle: String?,
    /** A non-default persisted chapter position/title is evidence of local progress. */
    val hasLocalProgress: Boolean,
    /** Reserved for a future explicit authoritative source. Default Book fields are never proof. */
    val authoritativeNeverRead: Boolean = false
) {
    override fun toString(): String = "SyncV1ProgressWorkInput(title=$title, hasLocalProgress=$hasLocalProgress, bookUrl=[redacted])"
}

enum class SyncV1ProgressPhase { PUBLISHED, UNCHANGED, PARTIAL_SUCCESS, FAILED }

data class SyncV1ProgressWorkOutcome(
    val workId: String,
    val baseline: SyncV1ProgressBaseline,
    val progressRevision: Long,
    val progressTocStale: Boolean = false,
    val errorCode: SyncV1ErrorCode? = null
)

data class SyncV1ProgressOutcome(
    val phase: SyncV1ProgressPhase,
    val works: Int = 0,
    val initialStatesPublished: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    val catalogPublished: Boolean = false,
    val catalogRevision: Long? = null,
    val workOutcomes: List<SyncV1ProgressWorkOutcome> = emptyList(),
    val scannedEvents: Int = 0,
    val applied: Int = 0,
    val staleToc: Int = 0,
    val staleProgress: Int = 0,
    val rejected: Int = 0,
    val eventIdConflicts: Int = 0,
    val eventsPending: Int = 0,
    val stateIntentsPending: Int = 0,
    val catalogIntentsPending: Int = 0,
    val acknowledgementsPending: Int = 0,
    val errorCode: SyncV1ErrorCode? = null
)

data class SyncV1ProgressUiState(
    val isRunning: Boolean = false,
    val initialization: SyncV1ProgressOutcome? = null,
    val events: SyncV1ProgressOutcome? = null
)

data class ParsedSyncV1ProgressEvent(
    val eventId: String,
    val deviceId: String,
    val counterEpoch: String,
    val sequence: Long,
    val createdAt: String,
    val workId: String,
    val basedOnTocRevision: Long,
    val basedOnProgressRevision: Long,
    val chapterTitle: String,
    val localTocIndex: Long,
    val previousChapterTitle: String?,
    val nextChapterTitle: String?,
    val visibleCharOffset: Long?,
    val visibleCharCount: Long?,
    val chapterFractionPpm: Int?,
    val chapterTextFingerprint: String?,
    val locatorConfidence: String
)

sealed interface SyncV1ProgressEventParseResult {
    data object NotProgress : SyncV1ProgressEventParseResult
    data class Valid(val event: ParsedSyncV1ProgressEvent) : SyncV1ProgressEventParseResult
    data class Invalid(val errorCode: SyncV1ErrorCode, val declaredProgress: Boolean) : SyncV1ProgressEventParseResult
}

data class SyncV1ProgressReceipt(
    val eventId: String,
    val deviceId: String,
    val workId: String,
    val payloadSha256: String,
    val eventRawJsonBytes: ByteArray,
    val terminalStatus: String,
    val acknowledgedAt: String,
    val ackRawJsonBytes: ByteArray,
    val ackSha256: String,
    val projectionStage: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val ackPutAttemptCount: Int = 0
)

data class SyncV1ProgressDevice(
    val deviceId: String,
    val locatorCapability: String,
    val counterEpoch: String,
    val registrationPayloadSha256: String
)

interface SyncV1ProgressStore {
    suspend fun progressCatalogValue(serviceIdentity: String, rootIdentity: String, workId: String): SyncV1ProgressCatalogValue
    suspend fun progressState(serviceIdentity: String, rootIdentity: String, workId: String): SyncV1ProgressState?
    suspend fun hasPendingProgressProjection(serviceIdentity: String, rootIdentity: String, workId: String): Boolean
    suspend fun pendingProgressIntents(serviceIdentity: String, rootIdentity: String): List<SyncV1ProgressPublicationIntent>
    suspend fun createProgressIntentIfAbsent(intent: SyncV1ProgressPublicationIntent): SyncV1ProgressPublicationIntent
    suspend fun incrementProgressPutAttempt(intent: SyncV1ProgressPublicationIntent, now: Long): Int
    suspend fun markProgressIntentPublished(intent: SyncV1ProgressPublicationIntent, now: Long)
    suspend fun adoptConfirmedProgressState(state: SyncV1ProgressState, configIdentity: String, now: Long)
    suspend fun progressIntentForEvent(eventId: String, serviceIdentity: String, rootIdentity: String): SyncV1ProgressPublicationIntent?
    suspend fun persistAppliedProgress(eventRawBytes: ByteArray, intent: SyncV1ProgressPublicationIntent, receipt: SyncV1ProgressReceipt)
    suspend fun updateProgressReceipt(receipt: SyncV1ProgressReceipt, catalogPath: String? = null, stage: String = receipt.projectionStage)
    suspend fun incrementProgressAckAttempt(eventId: String): Int
    suspend fun setProgressBaseline(serviceIdentity: String, rootIdentity: String, workId: String, status: SyncV1ProgressBaseline, revision: Long, tocRevision: Long, now: Long)
    suspend fun setProgressCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean)
    suspend fun isProgressCatalogDirty(serviceIdentity: String, rootIdentity: String): Boolean
    suspend fun readyProgressDevices(serviceIdentity: String, rootIdentity: String): List<SyncV1ProgressDevice>
    suspend fun hasProgressBinding(deviceId: String, counterEpoch: String, workId: String): Boolean
    suspend fun progressReceipt(eventId: String): SyncV1ProgressReceipt?
    suspend fun persistProgressReceipt(receipt: SyncV1ProgressReceipt)
    suspend fun pendingProgressReceipts(serviceIdentity: String, rootIdentity: String): List<SyncV1ProgressReceipt>
    suspend fun markProgressReceiptStage(eventId: String, stage: String)
}

internal interface SyncV1ProgressCatalogGateway {
    suspend fun publish(
        books: List<SyncV1CatalogBook>,
        confirmedTocRevisions: Map<String, Long>? = null,
        sessionOverride: SyncV1Session? = null,
        progressValuesOverride: Map<String, SyncV1ProgressCatalogValue>? = null
    ): SyncV1CatalogOutcome
    suspend fun resumePendingIntent(sessionOverride: SyncV1Session? = null): SyncV1CatalogOutcome?
    suspend fun resolveWorkIdentities(books: List<SyncV1CatalogBook>, session: SyncV1Session): Map<String, String>
    suspend fun latestRemoteCatalog(session: SyncV1Session): ParsedCatalog?
}

internal object SyncV1ProgressSemantics {
    fun baselineForEvidence(
        hasLocalProgress: Boolean,
        authoritativeNeverRead: Boolean,
        exactChapterMappingAvailable: Boolean
    ): SyncV1ProgressBaseline = when {
        hasLocalProgress && exactChapterMappingAvailable -> SyncV1ProgressBaseline.AVAILABLE
        hasLocalProgress -> SyncV1ProgressBaseline.UNAVAILABLE
        authoritativeNeverRead -> SyncV1ProgressBaseline.ZERO_CONFIRMED
        else -> SyncV1ProgressBaseline.UNAVAILABLE
    }

    fun isValidCatalogBaseline(status: SyncV1ProgressBaseline, revision: Long): Boolean = when (status) {
        SyncV1ProgressBaseline.ZERO_CONFIRMED, SyncV1ProgressBaseline.UNAVAILABLE -> revision == 0L
        SyncV1ProgressBaseline.AVAILABLE -> revision >= 1L
        SyncV1ProgressBaseline.LEGACY_UNKNOWN -> false
    }

    /** unavailable and legacy_unknown are valid catalog declarations, but never authorize uploads. */
    fun acceptsProgressUpload(status: SyncV1ProgressBaseline): Boolean =
        status == SyncV1ProgressBaseline.ZERO_CONFIRMED || status == SyncV1ProgressBaseline.AVAILABLE

    /** TOC staleness takes precedence over progress staleness. */
    fun staleStatus(basedOnToc: Long, currentToc: Long, basedOnProgress: Long, currentProgress: Long): String? = when {
        basedOnToc != currentToc -> "stale_toc"
        basedOnProgress != currentProgress -> "stale_progress"
        else -> null
    }

    fun locatorAllowed(capability: String, confidence: String): Boolean = when (capability) {
        "chapter_only" -> confidence == "chapter"
        "character_exact" -> confidence in setOf("chapter", "estimated", "high")
        else -> false
    }
}
