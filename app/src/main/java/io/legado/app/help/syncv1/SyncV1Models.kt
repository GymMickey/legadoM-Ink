package io.legado.app.help.syncv1

import java.util.UUID

enum class SyncV1ErrorCode {
    WEBDAV_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    HTTP_NOT_FOUND,
    HTTP_CONFLICT,
    HTTP_CLIENT_ERROR,
    HTTP_SERVER_ERROR,
    TLS_ERROR,
    DNS_ERROR,
    TIMEOUT,
    NETWORK_UNKNOWN,
    DIRECTORY_ERROR,
    INVALID_DEVICE_ID,
    SIZE_EXCEEDED,
    INVALID_JSON,
    INVALID_REGISTRATION,
    DEVICE_ID_CONFLICT,
    UNSUPPORTED_MODE,
    CONFIG_CHANGED,
    REVISION_CONFLICT,
    REMOTE_CONTENT_CONFLICT,
    READBACK_MISMATCH,
    RETRY_LIMIT,
    NO_REGISTRATIONS,
    IDENTITY_CONFLICT,
    CATALOG_TOO_LARGE,
    CATALOG_WORK_LIMIT,
    CATALOG_INVALID,
    NO_READY_DEVICE
}

class SyncV1Failure(
    val errorCode: SyncV1ErrorCode
) : Exception(errorCode.name)

enum class SyncV1UiPhase {
    IDLE,
    SCANNING,
    VALIDATING,
    PUBLISHING,
    READY,
    REJECTED,
    FAILED
}

data class SyncV1DeviceOutcome(
    val deviceId: String?,
    val phase: SyncV1UiPhase,
    val remoteStatus: String? = null,
    val createMode: String? = null,
    val stateRevision: Long? = null,
    val errorCode: SyncV1ErrorCode? = null
)

enum class SyncV1LocalStatus(val persistedValue: String) {
    UNREGISTERED("unregistered"),
    UPLOADED("uploaded"),
    READY("ready"),
    REJECTED("rejected");

    companion object {
        fun fromPersistedValue(value: String): SyncV1LocalStatus =
            entries.firstOrNull { it.persistedValue == value }
                ?: throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
    }
}

data class SyncV1UiState(
    val isRunning: Boolean = false,
    val currentPhase: SyncV1UiPhase = SyncV1UiPhase.IDLE,
    val outcomes: List<SyncV1DeviceOutcome> = emptyList(),
    val globalErrorCode: SyncV1ErrorCode? = null
)

data class SyncV1CatalogUiState(
    val isRunning: Boolean = false,
    val outcome: SyncV1CatalogOutcome? = null
)

data class SyncV1HttpResponse(
    val statusCode: Int,
    val body: ByteArray = byteArrayOf(),
    val bodyTooLarge: Boolean = false,
    val rawSha256: String? = null
) {
    override fun toString(): String = "SyncV1HttpResponse(statusCode=$statusCode, bodyBytes=${body.size}, bodyTooLarge=$bodyTooLarge)"
}

data class SyncV1RemoteEntry(
    val name: String,
    val isDirectory: Boolean
)

interface SyncV1WebDavTransport {
    suspend fun ensureDirectory(relativePath: String)
    suspend fun listDirectChildren(relativePath: String): List<SyncV1RemoteEntry>
    suspend fun get(
        relativePath: String,
        maxBytes: Int,
        computeFullSha256: Boolean = false
    ): SyncV1HttpResponse
    suspend fun put(relativePath: String, bytes: ByteArray): SyncV1HttpResponse
}

data class SyncV1Session(
    val configIdentity: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val transport: SyncV1WebDavTransport,
    val isCurrent: () -> Boolean
)

data class DeviceRegistrationRecord(
    val deviceId: String,
    val registrationPayloadSha256: String,
    val remotePath: String,
    val parsedStatus: String,
    val supportedCreateModes: List<String>,
    val locatorCapability: String?,
    val validationErrorCode: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long
)

class DeviceStatePublicationIntent(
    val deviceId: String,
    val stateRevision: Long,
    val snapshotId: UUID,
    val remotePath: String,
    val rawJsonBytes: ByteArray,
    val rawSha256: String,
    val putAttemptCount: Int,
    val configIdentity: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val publicationStatus: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun toString(): String =
        "DeviceStatePublicationIntent(deviceId=$deviceId, stateRevision=$stateRevision, snapshotId=$snapshotId, " +
            "remotePath=$remotePath, rawBytes=${rawJsonBytes.size}, rawSha256=$rawSha256, " +
            "putAttemptCount=$putAttemptCount, publicationStatus=$publicationStatus)"
}

data class SyncV1StoredState(
    val deviceId: String,
    val highestConfirmedStateRevision: Long,
    val localStatus: SyncV1LocalStatus,
    val remoteStatus: String?,
    val createMode: String?,
    val lastErrorCode: String?,
    val updatedAt: Long
)

interface SyncV1Store {
    /** Returns false when a previously recorded immutable registration changed bytes. */
    suspend fun saveRegistration(record: DeviceRegistrationRecord): Boolean
    suspend fun pendingIntents(): List<DeviceStatePublicationIntent>
    suspend fun pendingIntent(deviceId: String): DeviceStatePublicationIntent?
    suspend fun createIntentIfNoPending(intent: DeviceStatePublicationIntent): DeviceStatePublicationIntent
    suspend fun incrementPutAttempt(remotePath: String, now: Long): Int
    suspend fun markIntentPublished(
        intent: DeviceStatePublicationIntent,
        remoteStatus: String,
        createMode: String?
    )
    suspend fun updateProcessing(
        deviceId: String,
        localStatus: SyncV1LocalStatus,
        errorCode: String? = null,
        highestRevision: Long? = null,
        remoteStatus: String? = null,
        createMode: String? = null
    )
    suspend fun readState(deviceId: String): SyncV1StoredState?
}

data class SyncV1WorkIdentity(
    val workId: String,
    val tocRevision: Long = 0,
    val progressRevision: Long = 0,
    val catalogFieldsSha256: String? = null,
    /** Unix milliseconds, owned by the independent sync database. */
    val updatedAt: Long = 0
)

/** Raw book URLs are only persisted in the independent sync database; never expose them in UI/logs. */
class SyncV1WorkAlias(val bookUrl: String, val workId: String) {
    override fun toString(): String = "SyncV1WorkAlias(workId=$workId, bookUrl=[redacted])"
}

sealed interface SyncV1WorkIdentityResolution {
    data class Resolved(val identity: SyncV1WorkIdentity) : SyncV1WorkIdentityResolution
    data object IdentityConflict : SyncV1WorkIdentityResolution
}

class SyncV1CatalogPublicationIntent(
    val catalogRevision: Long,
    val snapshotId: UUID,
    val remotePath: String,
    rawJsonBytes: ByteArray,
    val rawSha256: String,
    val semanticSha256: String,
    val putAttemptCount: Int,
    val configIdentity: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val publicationStatus: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    val rawJsonBytes: ByteArray = rawJsonBytes.copyOf()

    override fun toString(): String =
        "SyncV1CatalogPublicationIntent(catalogRevision=$catalogRevision, snapshotId=$snapshotId, " +
            "remotePath=$remotePath, rawBytes=${rawJsonBytes.size}, rawSha256=$rawSha256, " +
            "putAttemptCount=$putAttemptCount, publicationStatus=$publicationStatus)"
}

data class SyncV1CatalogPublicationState(
    val catalogRevision: Long,
    val snapshotId: UUID,
    val remotePath: String,
    val semanticSha256: String,
    val rawSha256: String,
    val serviceIdentity: String,
    val rootIdentity: String,
    val publishedAt: Long
)

/** A2 storage contract kept separate so the A1 registration API and its test doubles remain stable. */
interface SyncV1CatalogStore {
    suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID = UUID::randomUUID): String
    suspend fun workAliases(): List<SyncV1WorkAlias>
    suspend fun resolveWorkIdentity(
        bookUrl: String,
        matchingRemoteWorkIds: Set<String>,
        uuidProvider: () -> UUID = UUID::randomUUID
    ): SyncV1WorkIdentityResolution
    suspend fun updateWorkCatalogFields(
        identity: SyncV1WorkIdentity,
        catalogFieldsSha256: String,
        now: Long,
        initialUpdatedAt: Long? = null
    ): SyncV1WorkIdentity
    suspend fun linkExplicitWorkAlias(
        oldBookUrl: String,
        newBookUrl: String,
        uuidProvider: () -> UUID = UUID::randomUUID
    ): SyncV1WorkIdentity
    suspend fun hasReadyDevice(): Boolean
    suspend fun pendingCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent?
    suspend fun latestPublishedCatalogIntent(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationIntent?
    suspend fun catalogPublicationState(serviceIdentity: String, rootIdentity: String): SyncV1CatalogPublicationState?
    suspend fun createCatalogIntentIfNoPending(
        intent: SyncV1CatalogPublicationIntent
    ): SyncV1CatalogPublicationIntent
    suspend fun incrementCatalogPutAttempt(intent: SyncV1CatalogPublicationIntent, now: Long): Int
    suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent)
}
