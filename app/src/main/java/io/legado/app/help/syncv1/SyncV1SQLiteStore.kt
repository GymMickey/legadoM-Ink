package io.legado.app.help.syncv1

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.util.UUID

internal const val SYNC_V1_DATABASE_NAME = "legadoM_ink_sync.db"
private const val MAX_SYNC_V1_ACK_PUT_ATTEMPTS = 2
private fun syncV1AckRemotePath(deviceId: String, eventId: String) =
    "legadom_ink_sync/v1/acks/$deviceId/$eventId.json"

/** Independent, lazily opened storage. It is not part of AppDatabase or backup/restore. */
class SyncV1SQLiteStore(
    context: Context,
    databaseName: String = SYNC_V1_DATABASE_NAME
) : SyncV1Store, SyncV1CatalogStore, SyncV1ChaptersStore, SyncV1BindingStore, SyncV1ProgressStore {
    private val helper = SyncV1DatabaseHelper(context.applicationContext, databaseName)
    private val bindingGson = GsonBuilder().disableHtmlEscaping().create()

    override suspend fun androidPublisherDeviceId(uuidProvider: () -> UUID): String = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT metadata_value FROM sync_v1_metadata WHERE metadata_key = 'android_publisher_device_id'",
                null
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val result = existing ?: uuidProvider().toString().also { generated ->
                if (!SyncV1Protocol.isUuidV4(generated)) {
                    throw SyncV1Failure(SyncV1ErrorCode.INVALID_DEVICE_ID)
                }
                db.insertOrThrow(
                    "sync_v1_metadata",
                    null,
                    ContentValues().apply {
                        put("metadata_key", "android_publisher_device_id")
                        put("metadata_value", generated)
                    }
                )
            }
            if (!SyncV1Protocol.isUuidV4(result)) {
                throw SyncV1Failure(SyncV1ErrorCode.INVALID_DEVICE_ID)
            }
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun workAliases(): List<SyncV1WorkAlias> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT book_url, work_id FROM work_alias ORDER BY work_id, book_url",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(SyncV1WorkAlias(cursor.getString(0), cursor.getString(1)))
                }
            }
        }
    }

    override suspend fun resolveWorkIdentity(
        bookUrl: String,
        matchingRemoteWorkIds: Set<String>,
        uuidProvider: () -> UUID
    ): SyncV1WorkIdentityResolution = withContext(IO) {
        require(bookUrl.isNotBlank())
        if (matchingRemoteWorkIds.size > 1 || matchingRemoteWorkIds.any { !SyncV1Protocol.isUuidV4(it) }) {
            return@withContext SyncV1WorkIdentityResolution.IdentityConflict
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT work_id FROM work_alias WHERE book_url = ?",
                arrayOf(bookUrl)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing != null) {
                val identity = db.readOrCreateWorkIdentity(existing)
                db.setTransactionSuccessful()
                return@withContext SyncV1WorkIdentityResolution.Resolved(identity)
            }
            val workId = matchingRemoteWorkIds.singleOrNull() ?: uuidProvider().toString()
            if (!SyncV1Protocol.isUuidV4(workId)) {
                throw SyncV1Failure(SyncV1ErrorCode.INVALID_DEVICE_ID)
            }
            val identity = db.readOrCreateWorkIdentity(workId)
            db.insertOrThrow(
                "work_alias",
                null,
                ContentValues().apply {
                    put("book_url", bookUrl)
                    put("work_id", workId)
                }
            )
            db.setTransactionSuccessful()
            SyncV1WorkIdentityResolution.Resolved(identity)
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun updateWorkCatalogFields(
        identity: SyncV1WorkIdentity,
        catalogFieldsSha256: String,
        now: Long,
        initialUpdatedAt: Long?
    ): SyncV1WorkIdentity = withContext(IO) {
        if (!catalogFieldsSha256.matches(Regex("[a-f0-9]{64}"))) {
            throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
        }
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT catalog_fields_sha256, updated_at FROM work_identity WHERE work_id = ?",
                arrayOf(identity.workId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val hashIndex = cursor.getColumnIndexOrThrow("catalog_fields_sha256")
                    (if (cursor.isNull(hashIndex)) null else cursor.getString(hashIndex)) to
                        cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                } else null
            } ?: throw SyncV1Failure(SyncV1ErrorCode.INVALID_REGISTRATION)
            val timestamp = when {
                current.first == catalogFieldsSha256 -> current.second
                initialUpdatedAt != null -> initialUpdatedAt
                else -> now
            }
            if (current.first != catalogFieldsSha256) {
                db.update(
                    "work_identity",
                    ContentValues().apply {
                        put("catalog_fields_sha256", catalogFieldsSha256)
                        put("updated_at", timestamp)
                    },
                    "work_id = ?",
                    arrayOf(identity.workId)
                )
            }
            val updated = identity.copy(catalogFieldsSha256 = catalogFieldsSha256, updatedAt = timestamp)
            db.setTransactionSuccessful()
            updated
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun linkExplicitWorkAlias(
        oldBookUrl: String,
        newBookUrl: String,
        uuidProvider: () -> UUID
    ): SyncV1WorkIdentity = withContext(IO) {
        linkExplicitWorkAliasNow(oldBookUrl, newBookUrl, uuidProvider)
    }

    /** Synchronous variant for legacy Book URL migration paths that are not suspend functions. */
    internal fun linkExplicitWorkAliasNow(
        oldBookUrl: String,
        newBookUrl: String,
        uuidProvider: () -> UUID = UUID::randomUUID
    ): SyncV1WorkIdentity {
        require(oldBookUrl.isNotBlank() && newBookUrl.isNotBlank())
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val oldWorkId = db.rawQuery(
                "SELECT work_id FROM work_alias WHERE book_url = ?",
                arrayOf(oldBookUrl)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val workId = oldWorkId ?: uuidProvider().toString().also { generated ->
                if (!SyncV1Protocol.isUuidV4(generated)) {
                    throw SyncV1Failure(SyncV1ErrorCode.INVALID_DEVICE_ID)
                }
                db.readOrCreateWorkIdentity(generated)
                db.insertOrThrow(
                    "work_alias",
                    null,
                    ContentValues().apply {
                        put("book_url", oldBookUrl)
                        put("work_id", generated)
                    }
                )
            }
            if (oldWorkId != null) db.readOrCreateWorkIdentity(workId)
            if (newBookUrl != oldBookUrl) {
                val aliasValues = ContentValues().apply { put("work_id", workId) }
                val updated = db.update("work_alias", aliasValues, "book_url = ?", arrayOf(newBookUrl))
                if (updated == 0) {
                    db.insertOrThrow(
                        "work_alias",
                        null,
                        ContentValues().apply {
                            put("book_url", newBookUrl)
                            put("work_id", workId)
                        }
                    )
                }
            }
            val identity = db.readOrCreateWorkIdentity(workId)
            db.setTransactionSuccessful()
            identity
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun hasReadyDevice(): Boolean = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM sync_state WHERE processing_status = 'ready' AND remote_status = 'ready' AND create_mode IS NOT NULL LIMIT 1",
            null
        ).use { it.moveToFirst() }
    }

    override suspend fun readyDeviceIds(
        serviceIdentity: String,
        rootIdentity: String
    ): List<String> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            """
            SELECT s.device_id, s.highest_confirmed_state_revision, i.raw_json_bytes
            FROM sync_state s
            JOIN device_state_publication_intent i ON i.device_id = s.device_id
            WHERE s.processing_status = 'ready' AND s.remote_status = 'ready'
                AND s.create_mode IS NOT NULL
                AND i.service_identity = ? AND i.root_identity = ?
                AND i.publication_status = 'published'
                AND i.state_revision = s.highest_confirmed_state_revision
            ORDER BY i.state_revision DESC
            """.trimIndent(),
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor ->
            val seen = HashSet<String>()
            buildList {
                while (cursor.moveToNext()) {
                    val deviceId = cursor.getString(0)
                    if (!seen.add(deviceId)) continue
                    val state = SyncV1Protocol.parseDeviceState(cursor.getBlob(2)) ?: continue
                    if (state.deviceId == deviceId
                        && state.status == "ready"
                        && state.createMode == SYNC_V1_CREATE_MODE
                    ) add(deviceId)
                }
            }
        }
    }

    override suspend fun importReceipt(eventId: String): SyncV1ImportReceipt? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM sync_import_receipt WHERE event_id = ?",
            arrayOf(eventId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toImportReceipt() else null }
    }

    override suspend fun contentBinding(
        deviceId: String,
        counterEpoch: String,
        contentId: String
    ): SyncV1KindleContentBinding? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT device_id, counter_epoch, content_id, work_id, created_at, updated_at FROM kindle_content_binding WHERE device_id = ? AND counter_epoch = ? AND content_id = ?",
            arrayOf(deviceId, counterEpoch, contentId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toContentBinding() else null }
    }

    override suspend fun persistBindingOutcome(
        receipt: SyncV1ImportReceipt,
        intent: SyncV1AckPublicationIntent,
        binding: SyncV1KindleContentBinding?
    ): SyncV1BindingPersistResult = withContext(IO) {
        validateAckIntent(receipt, intent)
        if (binding != null && (binding.deviceId != receipt.deviceId
                || binding.counterEpoch != receipt.counterEpoch
                || binding.contentId != receipt.contentId
                || binding.workId != receipt.workId)
        ) throw SyncV1Failure(SyncV1ErrorCode.INVALID_EVENT)

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existingReceipt = db.rawQuery(
                "SELECT * FROM sync_import_receipt WHERE event_id = ?",
                arrayOf(receipt.eventId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toImportReceipt() else null }
            if (existingReceipt != null) {
                db.setTransactionSuccessful()
                return@withContext if (existingReceipt.deviceId == receipt.deviceId
                    && existingReceipt.payloadSha256 == receipt.payloadSha256
                ) SyncV1BindingPersistResult.Existing(existingReceipt)
                else SyncV1BindingPersistResult.EventIdConflict
            }

            val existingBinding = if (binding == null) null else db.rawQuery(
                "SELECT work_id FROM kindle_content_binding WHERE device_id = ? AND counter_epoch = ? AND content_id = ?",
                arrayOf(binding.deviceId, binding.counterEpoch, binding.contentId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existingBinding != null && existingBinding != binding?.workId) {
                db.setTransactionSuccessful()
                return@withContext SyncV1BindingPersistResult.BindingConflict
            }

            if (binding != null && existingBinding == null) {
                db.insertOrThrow(
                    "kindle_content_binding",
                    null,
                    ContentValues().apply {
                        put("device_id", binding.deviceId)
                        put("counter_epoch", binding.counterEpoch)
                        put("content_id", binding.contentId)
                        put("work_id", binding.workId)
                        put("created_at", binding.createdAt)
                        put("updated_at", binding.updatedAt)
                    }
                )
            }
            insertImportReceipt(db, receipt)
            insertAckIntent(db, intent)
            db.setTransactionSuccessful()
            SyncV1BindingPersistResult.Stored
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun ensureAckIntent(
        receipt: SyncV1ImportReceipt,
        serviceIdentity: String,
        rootIdentity: String
    ): SyncV1AckPublicationIntent = withContext(IO) {
        if (SyncV1Protocol.sha256(receipt.ackRawJsonBytes) != receipt.ackSha256) {
            throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
        }
        val path = syncV1AckRemotePath(receipt.deviceId, receipt.eventId)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT * FROM ack_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ?",
                arrayOf(serviceIdentity, rootIdentity, path)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toAckIntent() else null }
            if (existing != null) {
                if (existing.eventId != receipt.eventId
                    || existing.rawSha256 != receipt.ackSha256
                    || !existing.rawJsonBytes.contentEquals(receipt.ackRawJsonBytes)
                ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
                db.setTransactionSuccessful()
                return@withContext existing
            }
            val now = System.currentTimeMillis()
            val intent = SyncV1AckPublicationIntent(
                deviceId = receipt.deviceId,
                eventId = receipt.eventId,
                remotePath = path,
                rawJsonBytes = receipt.ackRawJsonBytes,
                rawSha256 = receipt.ackSha256,
                putAttemptCount = 0,
                serviceIdentity = serviceIdentity,
                rootIdentity = rootIdentity,
                publicationStatus = "pending",
                createdAt = now,
                updatedAt = now
            )
            insertAckIntent(db, intent)
            db.setTransactionSuccessful()
            intent
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun pendingAckIntents(
        serviceIdentity: String,
        rootIdentity: String
    ): List<SyncV1AckPublicationIntent> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM ack_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ? ORDER BY created_at, event_id",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toAckIntent()) } }
    }

    override suspend fun incrementAckPutAttempt(
        intent: SyncV1AckPublicationIntent,
        now: Long
    ): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery(
                "SELECT put_attempt_count FROM ack_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ? AND raw_sha256 = ? AND publication_status = 'pending'",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath, intent.rawSha256)
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                cursor.getInt(0)
            }
            if (count >= MAX_SYNC_V1_ACK_PUT_ATTEMPTS) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            db.update(
                "ack_publication_intent",
                ContentValues().apply {
                    put("put_attempt_count", count + 1)
                    put("updated_at", now)
                },
                "service_identity = ? AND root_identity = ? AND remote_path = ? AND raw_sha256 = ? AND publication_status = 'pending'",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath, intent.rawSha256)
            )
            db.setTransactionSuccessful()
            count + 1
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markAckPublished(intent: SyncV1AckPublicationIntent, now: Long) = withContext(IO) {
        val updated = helper.writableDatabase.update(
            "ack_publication_intent",
            ContentValues().apply {
                put("publication_status", "published")
                put("updated_at", now)
            },
            "service_identity = ? AND root_identity = ? AND remote_path = ? AND raw_sha256 = ? AND publication_status = 'pending'",
            arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath, intent.rawSha256)
        )
        if (updated != 1) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
    }

    override suspend fun pendingAckCount(serviceIdentity: String, rootIdentity: String): Int = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ack_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
    }

    override suspend fun progressCatalogValue(
        serviceIdentity: String,
        rootIdentity: String,
        workId: String
    ): SyncV1ProgressCatalogValue = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT baseline_status, progress_revision FROM progress_baseline WHERE service_identity = ? AND root_identity = ? AND work_id = ?",
            arrayOf(serviceIdentity, rootIdentity, workId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) SyncV1ProgressCatalogValue(SyncV1ProgressBaseline.UNAVAILABLE, 0)
            else SyncV1ProgressCatalogValue(
                SyncV1ProgressBaseline.entries.first { it.wireValue == cursor.getString(0) },
                cursor.getLong(1)
            )
        }
    }

    override suspend fun progressState(
        serviceIdentity: String,
        rootIdentity: String,
        workId: String
    ): SyncV1ProgressState? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND work_id = ? AND publication_status = 'published' ORDER BY progress_revision DESC LIMIT 1",
            arrayOf(serviceIdentity, rootIdentity, workId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toProgressState() else null }
    }

    override suspend fun hasPendingProgressProjection(
        serviceIdentity: String,
        rootIdentity: String,
        workId: String
    ): Boolean = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM progress_import_receipt WHERE service_identity = ? AND root_identity = ? AND work_id = ? AND projection_stage = 'projection_pending' LIMIT 1",
            arrayOf(serviceIdentity, rootIdentity, workId)
        ).use { it.moveToFirst() }
    }

    override suspend fun pendingProgressIntents(
        serviceIdentity: String,
        rootIdentity: String
    ): List<SyncV1ProgressPublicationIntent> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND publication_status = 'pending' ORDER BY created_at, work_id",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toProgressIntent()) } }
    }

    override suspend fun createProgressIntentIfAbsent(
        intent: SyncV1ProgressPublicationIntent
    ): SyncV1ProgressPublicationIntent = withContext(IO) {
        validateProgressIntent(intent)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val sameRevision = db.rawQuery(
                "SELECT * FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND work_id = ? AND progress_revision = ? LIMIT 1",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.workId, intent.progressRevision.toString())
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toProgressIntent() else null }
            if (sameRevision != null) {
                if (sameRevision.remotePath != intent.remotePath || sameRevision.rawSha256 != intent.rawSha256
                    || !sameRevision.rawJsonBytes.contentEquals(intent.rawJsonBytes)
                ) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                db.setTransactionSuccessful()
                return@withContext sameRevision
            }
            val pending = db.rawQuery(
                "SELECT * FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND work_id = ? AND publication_status = 'pending' LIMIT 1",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.workId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toProgressIntent() else null }
            if (pending != null) {
                db.setTransactionSuccessful()
                if (pending.remotePath != intent.remotePath || pending.rawSha256 != intent.rawSha256
                    || !pending.rawJsonBytes.contentEquals(intent.rawJsonBytes)
                ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_PROJECTION_PENDING)
                return@withContext pending
            }
            db.insertOrThrow("progress_state_publication_intent", null, ContentValues().apply {
                put("service_identity", intent.serviceIdentity); put("root_identity", intent.rootIdentity)
                put("work_id", intent.workId); put("toc_revision", intent.tocRevision)
                put("progress_revision", intent.progressRevision); put("snapshot_id", intent.snapshotId.toString())
                put("remote_path", intent.remotePath); put("raw_json_bytes", intent.rawJsonBytes)
                put("raw_sha256", intent.rawSha256); put("put_attempt_count", intent.putAttemptCount)
                put("config_identity", intent.configIdentity); put("create_mode", intent.createMode)
                put("source_event_id", intent.sourceEventId); put("publication_status", "pending")
                put("created_at", intent.createdAt); put("updated_at", intent.updatedAt)
            })
            db.setTransactionSuccessful()
            intent
        } finally { db.endTransaction() }
    }

    override suspend fun incrementProgressPutAttempt(
        intent: SyncV1ProgressPublicationIntent,
        now: Long
    ): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery(
                "SELECT put_attempt_count FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ? AND publication_status = 'pending'",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
            ).use { if (it.moveToFirst()) it.getInt(0) else throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT) }
            if (count >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            db.update("progress_state_publication_intent", ContentValues().apply {
                put("put_attempt_count", count + 1); put("updated_at", now)
            }, "service_identity = ? AND root_identity = ? AND remote_path = ?", arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath))
            db.setTransactionSuccessful(); count + 1
        } finally { db.endTransaction() }
    }

    override suspend fun markProgressIntentPublished(
        intent: SyncV1ProgressPublicationIntent,
        now: Long
    ) = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update("progress_state_publication_intent", ContentValues().apply {
                put("publication_status", "published"); put("updated_at", now)
            }, "service_identity = ? AND root_identity = ? AND remote_path = ? AND raw_sha256 = ? AND publication_status = 'pending'",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath, intent.rawSha256))
            if (changed != 1) {
                val same = db.rawQuery("SELECT 1 FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ? AND raw_sha256 = ? AND publication_status = 'published'",
                    arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath, intent.rawSha256)).use { it.moveToFirst() }
                if (!same) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
            }
            db.insertWithOnConflict("progress_catalog_publication_state", null, ContentValues().apply {
                put("service_identity", intent.serviceIdentity); put("root_identity", intent.rootIdentity); put("catalog_dirty", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun adoptConfirmedProgressState(
        state: SyncV1ProgressState,
        configIdentity: String,
        now: Long
    ) = withContext(IO) {
        if (state.rawSha256 != SyncV1Protocol.sha256(state.rawJsonBytes)
            || state.remotePath != "legadom_ink_sync/v1/android/progress/${state.workId}/${state.progressRevision}-${state.snapshotId}.json"
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val parsed = SyncV1ProgressProtocol.parseState(state.rawJsonBytes)
            ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT raw_sha256, raw_json_bytes FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ?", arrayOf(state.serviceIdentity, state.rootIdentity, state.remotePath)).use {
                if (it.moveToFirst()) it.getString(0) to it.getBlob(1) else null
            }
            if (existing != null && (existing.first != state.rawSha256 || !existing.second.contentEquals(state.rawJsonBytes))) {
                throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
            }
            if (existing == null) db.insertOrThrow("progress_state_publication_intent", null, ContentValues().apply {
                put("service_identity", state.serviceIdentity); put("root_identity", state.rootIdentity); put("work_id", state.workId)
                put("toc_revision", state.tocRevision); put("progress_revision", state.progressRevision); put("snapshot_id", state.snapshotId)
                put("remote_path", state.remotePath); put("raw_json_bytes", state.rawJsonBytes); put("raw_sha256", state.rawSha256)
                put("put_attempt_count", 0); put("config_identity", configIdentity); put("create_mode", SYNC_V1_CREATE_MODE)
                put("source_event_id", parsed.sourceEventId); put("publication_status", "published"); put("created_at", now); put("updated_at", now)
            })
            val values = ContentValues().apply {
                put("service_identity", state.serviceIdentity); put("root_identity", state.rootIdentity); put("work_id", state.workId)
                put("baseline_status", "available"); put("progress_revision", state.progressRevision); put("toc_revision", state.tocRevision)
                put("snapshot_id", state.snapshotId); put("remote_path", state.remotePath); put("raw_json_bytes", state.rawJsonBytes)
                put("raw_sha256", state.rawSha256); put("updated_at", now)
            }
            db.insertWithOnConflict("progress_baseline", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun progressIntentForEvent(
        eventId: String,
        serviceIdentity: String,
        rootIdentity: String
    ): SyncV1ProgressPublicationIntent? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM progress_state_publication_intent WHERE source_event_id = ? AND service_identity = ? AND root_identity = ?",
            arrayOf(eventId, serviceIdentity, rootIdentity)
        ).use { if (it.moveToFirst()) it.toProgressIntent() else null }
    }

    override suspend fun persistAppliedProgress(
        eventRawBytes: ByteArray,
        intent: SyncV1ProgressPublicationIntent,
        receipt: SyncV1ProgressReceipt
    ) = withContext(IO) {
        validateProgressIntent(intent)
        if (receipt.eventId != intent.sourceEventId || receipt.workId != intent.workId
            || receipt.payloadSha256 != SyncV1Protocol.sha256(eventRawBytes)
            || !receipt.eventRawJsonBytes.contentEquals(eventRawBytes)
            || receipt.projectionStage != "projection_pending"
            || receipt.serviceIdentity != intent.serviceIdentity || receipt.rootIdentity != intent.rootIdentity
            || receipt.ackSha256 != SyncV1Protocol.sha256(receipt.ackRawJsonBytes)
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_INVALID)
        val parsedEvent = SyncV1ProgressProtocol.parseEvent(receipt.deviceId, receipt.eventId, eventRawBytes)
        val event = (parsedEvent as? SyncV1ProgressEventParseResult.Valid)?.event
            ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_INVALID)
        val proposedState = SyncV1ProgressProtocol.parseState(intent.rawJsonBytes)
            ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        if (proposedState.workId != event.workId || proposedState.tocRevision != event.basedOnTocRevision
            || proposedState.progressRevision != event.basedOnProgressRevision + 1
            || proposedState.chapterTitle != event.chapterTitle || proposedState.localTocIndex != event.localTocIndex
            || proposedState.sourceEventId != event.eventId || proposedState.updatedByDeviceId != event.deviceId
            || proposedState.chapterFractionPpm != event.chapterFractionPpm
            || proposedState.visibleCharOffset != event.visibleCharOffset
            || proposedState.visibleCharCount != event.visibleCharCount
            || proposedState.chapterTextFingerprint != event.chapterTextFingerprint
            || proposedState.locatorConfidence != event.locatorConfidence
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (receipt.terminalStatus != "applied" || event.deviceId != receipt.deviceId
                || event.workId != receipt.workId || event.counterEpoch.isBlank()
                || event.basedOnTocRevision != intent.tocRevision
                || intent.progressRevision != event.basedOnProgressRevision + 1
                || intent.createMode != SYNC_V1_CREATE_MODE
            ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_INVALID)
            val existing = db.rawQuery("SELECT payload_sha256 FROM progress_event_ledger WHERE event_id = ?", arrayOf(receipt.eventId))
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) {
                if (existing != receipt.payloadSha256) throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
                val saved = db.rawQuery("SELECT ack_raw_json_bytes FROM progress_import_receipt WHERE event_id = ?", arrayOf(receipt.eventId))
                    .use { if (it.moveToFirst()) it.getBlob(0) else null }
                val savedEvent = db.rawQuery("SELECT event_raw_json_bytes FROM progress_import_receipt WHERE event_id = ?", arrayOf(receipt.eventId))
                    .use { if (it.moveToFirst()) it.getBlob(0) else null }
                if (saved == null || !saved.contentEquals(receipt.ackRawJsonBytes)
                    || savedEvent == null || !savedEvent.contentEquals(receipt.eventRawJsonBytes)
                ) throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
                db.setTransactionSuccessful()
                return@withContext
            }
            val pending = db.rawQuery("SELECT 1 FROM progress_import_receipt WHERE work_id = ? AND service_identity = ? AND root_identity = ? AND projection_stage = 'projection_pending' LIMIT 1",
                arrayOf(intent.workId, intent.serviceIdentity, intent.rootIdentity)).use { it.moveToFirst() }
            if (pending) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_PROJECTION_PENDING)
            val priorA4Receipt = db.rawQuery("SELECT 1 FROM sync_import_receipt WHERE event_id = ? LIMIT 1", arrayOf(event.eventId)).use { it.moveToFirst() }
            if (priorA4Receipt) throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
            val hasBinding = db.rawQuery(
                "SELECT 1 FROM kindle_content_binding WHERE device_id = ? AND counter_epoch = ? AND work_id = ? LIMIT 1",
                arrayOf(event.deviceId, event.counterEpoch, event.workId)
            ).use { it.moveToFirst() }
            if (!hasBinding) throw SyncV1Failure(SyncV1ErrorCode.INVALID_EVENT)
            val baseline = db.rawQuery(
                "SELECT baseline_status, progress_revision, toc_revision FROM progress_baseline WHERE service_identity = ? AND root_identity = ? AND work_id = ?",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.workId)
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)) else null
            } ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_BASELINE_UNAVAILABLE)
            if (baseline.first !in setOf("zero_confirmed", "available")
                || baseline.second != event.basedOnProgressRevision
                || baseline.third != event.basedOnTocRevision
                || intent.tocRevision != baseline.third
            ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_CONFLICT)
            val intentValues = ContentValues().apply {
                put("service_identity", intent.serviceIdentity); put("root_identity", intent.rootIdentity); put("work_id", intent.workId)
                put("toc_revision", intent.tocRevision); put("progress_revision", intent.progressRevision); put("snapshot_id", intent.snapshotId.toString())
                put("remote_path", intent.remotePath); put("raw_json_bytes", intent.rawJsonBytes); put("raw_sha256", intent.rawSha256)
                put("put_attempt_count", 0); put("config_identity", intent.configIdentity); put("create_mode", intent.createMode)
                put("source_event_id", intent.sourceEventId); put("publication_status", "pending"); put("created_at", intent.createdAt); put("updated_at", intent.updatedAt)
            }
            db.insertOrThrow("progress_state_publication_intent", null, intentValues)
            db.insertOrThrow("progress_event_ledger", null, ContentValues().apply {
                put("event_id", event.eventId); put("service_identity", intent.serviceIdentity); put("root_identity", intent.rootIdentity)
                put("device_id", event.deviceId); put("counter_epoch", event.counterEpoch); put("sequence", event.sequence)
                put("work_id", event.workId); put("payload_sha256", receipt.payloadSha256); put("raw_json_bytes", eventRawBytes)
                put("applied_progress_revision", intent.progressRevision); put("created_at", intent.createdAt)
            })
            db.insertOrThrow("progress_import_receipt", null, progressReceiptValues(receipt, intent.remotePath))
            db.insertWithOnConflict("progress_catalog_publication_state", null, ContentValues().apply {
                put("service_identity", intent.serviceIdentity); put("root_identity", intent.rootIdentity); put("catalog_dirty", 1)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    override suspend fun updateProgressReceipt(
        receipt: SyncV1ProgressReceipt,
        catalogPath: String?,
        stage: String
    ) = withContext(IO) {
        require(stage in setOf("projection_pending", "projection_confirmed", "ack_publishable", "ack_pending", "ack_published"))
        val values = ContentValues().apply {
            put("projection_stage", stage)
            if (catalogPath != null) put("catalog_remote_path", catalogPath)
            if (stage == "ack_published") put("ack_publication_status", "published")
            else if (stage == "ack_pending" || stage == "ack_publishable") put("ack_publication_status", "pending")
        }
        val changed = helper.writableDatabase.update("progress_import_receipt", values,
            "event_id = ? AND payload_sha256 = ? AND ack_sha256 = ?",
            arrayOf(receipt.eventId, receipt.payloadSha256, receipt.ackSha256))
        if (changed != 1) throw SyncV1Failure(SyncV1ErrorCode.EVENT_ID_CONFLICT)
    }

    override suspend fun incrementProgressAckAttempt(eventId: String): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT ack_put_attempt_count FROM progress_import_receipt WHERE event_id = ? AND projection_stage IN ('ack_publishable','ack_pending')",
                arrayOf(eventId)).use { if (it.moveToFirst()) it.getInt(0) else throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT) }
            if (count >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            db.update("progress_import_receipt", ContentValues().apply { put("ack_put_attempt_count", count + 1); put("projection_stage", "ack_pending") },
                "event_id = ?", arrayOf(eventId))
            db.setTransactionSuccessful(); count + 1
        } finally { db.endTransaction() }
    }

    override suspend fun setProgressBaseline(
        serviceIdentity: String,
        rootIdentity: String,
        workId: String,
        status: SyncV1ProgressBaseline,
        revision: Long,
        tocRevision: Long,
        now: Long
    ) = withContext(IO) {
        if (status == SyncV1ProgressBaseline.LEGACY_UNKNOWN
            || !SyncV1ProgressSemantics.isValidCatalogBaseline(status, revision)
            || tocRevision !in 0..SYNC_V1_MAX_SAFE_INTEGER
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val currentState = if (status == SyncV1ProgressBaseline.AVAILABLE) {
            helper.readableDatabase.rawQuery(
                "SELECT * FROM progress_state_publication_intent WHERE service_identity = ? AND root_identity = ? AND work_id = ? AND progress_revision = ? AND publication_status = 'published'",
                arrayOf(serviceIdentity, rootIdentity, workId, revision.toString())
            ).use { if (it.moveToFirst()) it.toProgressState() else null }
                ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        } else null
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("service_identity", serviceIdentity); put("root_identity", rootIdentity); put("work_id", workId)
            put("baseline_status", status.wireValue); put("progress_revision", revision); put("toc_revision", tocRevision)
            put("snapshot_id", currentState?.snapshotId); put("remote_path", currentState?.remotePath)
            put("raw_json_bytes", currentState?.rawJsonBytes); put("raw_sha256", currentState?.rawSha256)
            put("updated_at", now)
        }
        db.insertWithOnConflict("progress_baseline", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun setProgressCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean) = withContext(IO) {
        helper.writableDatabase.insertWithOnConflict("progress_catalog_publication_state", null, ContentValues().apply {
            put("service_identity", serviceIdentity); put("root_identity", rootIdentity); put("catalog_dirty", if (dirty) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override suspend fun isProgressCatalogDirty(serviceIdentity: String, rootIdentity: String): Boolean = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT catalog_dirty FROM progress_catalog_publication_state WHERE service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { it.moveToFirst() && it.getInt(0) != 0 }
    }

    override suspend fun readyProgressDevices(
        serviceIdentity: String,
        rootIdentity: String
    ): List<SyncV1ProgressDevice> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            """SELECT s.device_id, i.raw_json_bytes FROM sync_state s
                JOIN device_state_publication_intent i ON i.device_id = s.device_id
                WHERE s.processing_status = 'ready' AND s.remote_status = 'ready'
                AND i.service_identity = ? AND i.root_identity = ? AND i.publication_status = 'published'
                AND i.state_revision = s.highest_confirmed_state_revision ORDER BY s.device_id""".trimIndent(),
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> buildList {
            val seen = HashSet<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                if (!seen.add(id)) continue
                val state = SyncV1Protocol.parseDeviceState(cursor.getBlob(1)) ?: continue
                if (state.deviceId == id && state.status == "ready" && state.createMode == SYNC_V1_CREATE_MODE
                    && state.locatorCapability in setOf("chapter_only", "character_exact")
                ) add(SyncV1ProgressDevice(id, state.locatorCapability!!, "", state.registrationPayloadSha256))
            }
        } }
    }

    override suspend fun hasProgressBinding(deviceId: String, counterEpoch: String, workId: String): Boolean = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM kindle_content_binding WHERE device_id = ? AND counter_epoch = ? AND work_id = ? LIMIT 1",
            arrayOf(deviceId, counterEpoch, workId)
        ).use { it.moveToFirst() }
    }

    override suspend fun progressReceipt(eventId: String): SyncV1ProgressReceipt? = withContext(IO) {
        helper.readableDatabase.rawQuery("SELECT * FROM progress_import_receipt WHERE event_id = ?", arrayOf(eventId))
            .use { if (it.moveToFirst()) it.toProgressReceipt() else null }
    }

    override suspend fun persistProgressReceipt(receipt: SyncV1ProgressReceipt) = withContext(IO) {
        if (receipt.ackSha256 != SyncV1Protocol.sha256(receipt.ackRawJsonBytes)
            || receipt.payloadSha256 != SyncV1Protocol.sha256(receipt.eventRawJsonBytes)
        ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
        helper.writableDatabase.insertOrThrow("progress_import_receipt", null, progressReceiptValues(receipt, null))
        Unit
    }

    override suspend fun pendingProgressReceipts(serviceIdentity: String, rootIdentity: String): List<SyncV1ProgressReceipt> = withContext(IO) {
        helper.readableDatabase.rawQuery("SELECT * FROM progress_import_receipt WHERE service_identity = ? AND root_identity = ? AND projection_stage != 'ack_published' ORDER BY created_at", arrayOf(serviceIdentity, rootIdentity))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toProgressReceipt()) } }
    }

    override suspend fun markProgressReceiptStage(eventId: String, stage: String) = withContext(IO) {
        require(stage in setOf("projection_pending", "projection_confirmed", "ack_publishable", "ack_pending", "ack_published"))
        val changed = helper.writableDatabase.update("progress_import_receipt", ContentValues().apply { put("projection_stage", stage) }, "event_id = ?", arrayOf(eventId))
        if (changed != 1) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_EVENT_INVALID)
    }

    override suspend fun pendingCatalogIntent(
        serviceIdentity: String,
        rootIdentity: String
    ): SyncV1CatalogPublicationIntent? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM catalog_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ? ORDER BY created_at LIMIT 1",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCatalogIntent() else null }
    }

    override suspend fun latestPublishedCatalogIntent(
        serviceIdentity: String,
        rootIdentity: String
    ): SyncV1CatalogPublicationIntent? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM catalog_publication_intent WHERE publication_status = 'published' AND service_identity = ? AND root_identity = ? ORDER BY catalog_revision DESC LIMIT 1",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCatalogIntent() else null }
    }

    override suspend fun catalogPublicationState(
        serviceIdentity: String,
        rootIdentity: String
    ): SyncV1CatalogPublicationState? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM catalog_publication_state WHERE service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCatalogState() else null }
    }

    override suspend fun createCatalogIntentIfNoPending(
        intent: SyncV1CatalogPublicationIntent
    ): SyncV1CatalogPublicationIntent = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT * FROM catalog_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ? ORDER BY created_at LIMIT 1",
                arrayOf(intent.serviceIdentity, intent.rootIdentity)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toCatalogIntent() else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                return@withContext existing
            }
            db.insertOrThrow(
                "catalog_publication_intent",
                null,
                ContentValues().apply {
                    put("catalog_revision", intent.catalogRevision)
                    put("snapshot_id", intent.snapshotId.toString())
                    put("remote_path", intent.remotePath)
                    put("raw_json_bytes", intent.rawJsonBytes)
                    put("raw_sha256", intent.rawSha256)
                    put("semantic_sha256", intent.semanticSha256)
                    put("put_attempt_count", intent.putAttemptCount)
                    put("config_identity", intent.configIdentity)
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("publication_status", "pending")
                    put("created_at", intent.createdAt)
                    put("updated_at", intent.updatedAt)
                }
            )
            db.setTransactionSuccessful()
            intent
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun incrementCatalogPutAttempt(
        intent: SyncV1CatalogPublicationIntent,
        now: Long
    ): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery(
                "SELECT put_attempt_count FROM catalog_publication_intent WHERE remote_path = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.serviceIdentity, intent.rootIdentity)
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                cursor.getInt(0)
            }
            if (count >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            db.update(
                "catalog_publication_intent",
                ContentValues().apply {
                    put("put_attempt_count", count + 1)
                    put("updated_at", now)
                },
                "remote_path = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.serviceIdentity, intent.rootIdentity)
            )
            db.setTransactionSuccessful()
            count + 1
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markCatalogIntentPublished(intent: SyncV1CatalogPublicationIntent) = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val updatedRows = db.update(
                "catalog_publication_intent",
                ContentValues().apply {
                    put("publication_status", "published")
                    put("updated_at", System.currentTimeMillis())
                },
                "remote_path = ? AND raw_sha256 = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.rawSha256, intent.serviceIdentity, intent.rootIdentity)
            )
            if (updatedRows != 1) throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
            db.insertWithOnConflict(
                "catalog_publication_state",
                null,
                ContentValues().apply {
                    put("catalog_revision", intent.catalogRevision)
                    put("snapshot_id", intent.snapshotId.toString())
                    put("remote_path", intent.remotePath)
                    put("semantic_sha256", intent.semanticSha256)
                    put("raw_sha256", intent.rawSha256)
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("published_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun pendingChapterIntents(
        serviceIdentity: String,
        rootIdentity: String
    ): List<SyncV1ChapterPublicationIntent> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM chapter_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ? ORDER BY created_at",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toChapterIntent()) } }
    }

    override suspend fun chapterPublicationState(
        serviceIdentity: String,
        rootIdentity: String,
        workId: String
    ): SyncV1ChapterPublicationState? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM chapter_publication_state WHERE service_identity = ? AND root_identity = ? AND work_id = ?",
            arrayOf(serviceIdentity, rootIdentity, workId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toChapterState() else null }
    }

    override suspend fun createChapterIntentIfNoPending(
        intent: SyncV1ChapterPublicationIntent
    ): SyncV1ChapterPublicationIntent = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT * FROM chapter_publication_intent WHERE publication_status = 'pending' AND service_identity = ? AND root_identity = ? AND work_id = ? ORDER BY created_at LIMIT 1",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.workId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toChapterIntent() else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                return@withContext existing
            }
            val samePath = db.rawQuery(
                "SELECT raw_sha256, raw_json_bytes FROM chapter_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ?",
                arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getBlob(1) else null
            }
            if (samePath != null) {
                if (samePath.first != intent.rawSha256 || !samePath.second.contentEquals(intent.rawJsonBytes)) {
                    throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                }
                db.update(
                    "chapter_publication_intent",
                    ContentValues().apply {
                        put("publication_status", "pending")
                        put("publication_stage", "prepared")
                        put("updated_at", intent.updatedAt)
                    },
                    "service_identity = ? AND root_identity = ? AND remote_path = ?",
                    arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
                )
                val resumed = db.rawQuery(
                    "SELECT * FROM chapter_publication_intent WHERE service_identity = ? AND root_identity = ? AND remote_path = ?",
                    arrayOf(intent.serviceIdentity, intent.rootIdentity, intent.remotePath)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.toChapterIntent() else intent }
                db.setTransactionSuccessful()
                return@withContext resumed
            }
            db.insertOrThrow(
                "chapter_publication_intent",
                null,
                ContentValues().apply {
                    put("work_id", intent.workId)
                    put("toc_revision", intent.tocRevision)
                    put("snapshot_id", intent.snapshotId.toString())
                    put("remote_path", intent.remotePath)
                    put("raw_json_bytes", intent.rawJsonBytes)
                    put("raw_sha256", intent.rawSha256)
                    put("semantic_sha256", intent.semanticSha256)
                    put("put_attempt_count", intent.putAttemptCount)
                    put("config_identity", intent.configIdentity)
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("publication_status", "pending")
                    put("publication_stage", intent.publicationStage)
                    put("created_at", intent.createdAt)
                    put("updated_at", intent.updatedAt)
                }
            )
            db.setTransactionSuccessful()
            intent
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun incrementChapterPutAttempt(
        intent: SyncV1ChapterPublicationIntent,
        now: Long
    ): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery(
                "SELECT put_attempt_count FROM chapter_publication_intent WHERE remote_path = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.serviceIdentity, intent.rootIdentity)
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                cursor.getInt(0)
            }
            if (count >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            db.update(
                "chapter_publication_intent",
                ContentValues().apply {
                    put("put_attempt_count", count + 1)
                    put("publication_stage", "put_attempted")
                    put("updated_at", now)
                },
                "remote_path = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.serviceIdentity, intent.rootIdentity)
            )
            db.setTransactionSuccessful()
            count + 1
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markChapterIntentPublished(intent: SyncV1ChapterPublicationIntent, now: Long) = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val updated = db.update(
                "chapter_publication_intent",
                ContentValues().apply {
                    put("publication_status", "published")
                    put("publication_stage", "verified")
                    put("updated_at", now)
                },
                "remote_path = ? AND raw_sha256 = ? AND service_identity = ? AND root_identity = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath, intent.rawSha256, intent.serviceIdentity, intent.rootIdentity)
            )
            if (updated != 1) throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
            db.insertWithOnConflict(
                "chapter_publication_state",
                null,
                ContentValues().apply {
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("work_id", intent.workId)
                    put("toc_revision", intent.tocRevision)
                    put("snapshot_id", intent.snapshotId.toString())
                    put("remote_path", intent.remotePath)
                    put("raw_json_bytes", intent.rawJsonBytes)
                    put("raw_sha256", intent.rawSha256)
                    put("semantic_sha256", intent.semanticSha256)
                    put("confirmed_at", now)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            val identityUpdated = db.update(
                "work_identity",
                ContentValues().apply { put("toc_revision", intent.tocRevision) },
                "work_id = ?",
                arrayOf(intent.workId)
            )
            if (identityUpdated != 1) throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
            db.insertWithOnConflict(
                "chapter_catalog_publication_state",
                null,
                ContentValues().apply {
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("catalog_dirty", 1)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markChapterCatalogDirty(serviceIdentity: String, rootIdentity: String, dirty: Boolean) = withContext(IO) {
        val db = helper.writableDatabase
        db.insertWithOnConflict(
            "chapter_catalog_publication_state",
            null,
            ContentValues().apply {
                put("service_identity", serviceIdentity)
                put("root_identity", rootIdentity)
                put("catalog_dirty", if (dirty) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
        Unit
    }

    override suspend fun isChapterCatalogDirty(serviceIdentity: String, rootIdentity: String): Boolean = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT catalog_dirty FROM chapter_catalog_publication_state WHERE service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) != 0 }
    }

    override suspend fun confirmedChapterRevisions(serviceIdentity: String, rootIdentity: String): Map<String, Long> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT work_id, toc_revision FROM chapter_publication_state WHERE service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }
    }

    override suspend fun clearChapterCatalogDirty(serviceIdentity: String, rootIdentity: String) = withContext(IO) {
        helper.writableDatabase.update(
            "chapter_catalog_publication_state",
            ContentValues().apply { put("catalog_dirty", 0) },
            "service_identity = ? AND root_identity = ?",
            arrayOf(serviceIdentity, rootIdentity)
        )
        Unit
    }

    override suspend fun saveRegistration(record: DeviceRegistrationRecord): Boolean = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT first_seen_at, registration_payload_sha256 FROM device_registration_record WHERE device_id = ?",
                arrayOf(record.deviceId)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getString(1) else null
            }
            if (previous != null && previous.second != record.registrationPayloadSha256) {
                db.update(
                    "device_registration_record",
                    ContentValues().apply {
                        put("parsed_status", "conflict")
                        put("validation_error_code", SyncV1ErrorCode.DEVICE_ID_CONFLICT.name)
                        put("last_seen_at", record.lastSeenAt)
                    },
                    "device_id = ?",
                    arrayOf(record.deviceId)
                )
                db.setTransactionSuccessful()
                return@withContext false
            }
            val values = ContentValues().apply {
                put("device_id", record.deviceId)
                put("registration_payload_sha256", record.registrationPayloadSha256)
                put("remote_path", record.remotePath)
                put("parsed_status", record.parsedStatus)
                put("supported_create_modes", record.supportedCreateModes.joinToString(","))
                put("locator_capability", record.locatorCapability)
                put("validation_error_code", record.validationErrorCode)
                put("first_seen_at", previous?.first ?: record.firstSeenAt)
                put("last_seen_at", record.lastSeenAt)
            }
            db.insertWithOnConflict(
                "device_registration_record",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun pendingIntent(deviceId: String): DeviceStatePublicationIntent? =
        withContext(IO) {
            helper.readableDatabase.rawQuery(
                "SELECT * FROM device_state_publication_intent WHERE device_id = ? AND publication_status = 'pending' ORDER BY created_at LIMIT 1",
                arrayOf(deviceId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toIntent() else null }
        }

    override suspend fun pendingIntents(): List<DeviceStatePublicationIntent> = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM device_state_publication_intent WHERE publication_status = 'pending' ORDER BY created_at",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toIntent())
            }
        }
    }

    override suspend fun createIntentIfNoPending(
        intent: DeviceStatePublicationIntent
    ): DeviceStatePublicationIntent = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT * FROM device_state_publication_intent WHERE device_id = ? AND publication_status = 'pending' ORDER BY created_at LIMIT 1",
                arrayOf(intent.deviceId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.toIntent() else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                existing
            } else {
                val values = ContentValues().apply {
                    put("device_id", intent.deviceId)
                    put("state_revision", intent.stateRevision)
                    put("snapshot_id", intent.snapshotId.toString())
                    put("remote_path", intent.remotePath)
                    put("raw_json_bytes", intent.rawJsonBytes)
                    put("raw_sha256", intent.rawSha256)
                    put("put_attempt_count", intent.putAttemptCount)
                    put("config_identity", intent.configIdentity)
                    put("service_identity", intent.serviceIdentity)
                    put("root_identity", intent.rootIdentity)
                    put("publication_status", "pending")
                    put("created_at", intent.createdAt)
                    put("updated_at", intent.updatedAt)
                }
                db.insertOrThrow("device_state_publication_intent", null, values)
                db.setTransactionSuccessful()
                intent
            }
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun incrementPutAttempt(remotePath: String, now: Long): Int = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val count = db.rawQuery(
                "SELECT put_attempt_count FROM device_state_publication_intent WHERE remote_path = ? AND publication_status = 'pending'",
                arrayOf(remotePath)
            ).use { cursor ->
                if (!cursor.moveToFirst()) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                cursor.getInt(0)
            }
            if (count >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            val values = ContentValues().apply {
                put("put_attempt_count", count + 1)
                put("updated_at", now)
            }
            db.update(
                "device_state_publication_intent",
                values,
                "remote_path = ? AND publication_status = 'pending'",
                arrayOf(remotePath)
            )
            db.setTransactionSuccessful()
            count + 1
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markIntentPublished(
        intent: DeviceStatePublicationIntent,
        remoteStatus: String,
        createMode: String?
    ) = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.update(
                "device_state_publication_intent",
                ContentValues().apply {
                    put("publication_status", "published")
                    put("updated_at", System.currentTimeMillis())
                },
                "remote_path = ? AND publication_status = 'pending'",
                arrayOf(intent.remotePath)
            )
            upsertSyncState(
                db,
                SyncV1StoredState(
                    deviceId = intent.deviceId,
                    highestConfirmedStateRevision = intent.stateRevision,
                    localStatus = SyncV1LocalStatus.fromPersistedValue(remoteStatus),
                    remoteStatus = remoteStatus,
                    createMode = createMode,
                    lastErrorCode = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun updateProcessing(
        deviceId: String,
        localStatus: SyncV1LocalStatus,
        errorCode: String?,
        highestRevision: Long?,
        remoteStatus: String?,
        createMode: String?
    ) = withContext(IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val previous = db.rawQuery(
                "SELECT highest_confirmed_state_revision, remote_status, create_mode FROM sync_state WHERE device_id = ?",
                arrayOf(deviceId)
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getLong(0), cursor.nullableString(1), cursor.nullableString(2))
                else Triple(0L, null, null)
            }
            upsertSyncState(
                db,
                SyncV1StoredState(
                    deviceId = deviceId,
                    highestConfirmedStateRevision = maxOf(previous.first, highestRevision ?: previous.first),
                    localStatus = localStatus,
                    remoteStatus = remoteStatus ?: previous.second,
                    createMode = createMode ?: previous.third,
                    lastErrorCode = errorCode,
                    updatedAt = System.currentTimeMillis()
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun readState(deviceId: String): SyncV1StoredState? = withContext(IO) {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM sync_state WHERE device_id = ?",
            arrayOf(deviceId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toStoredState() else null }
    }

    private fun upsertSyncState(db: SQLiteDatabase, state: SyncV1StoredState) {
        val values = ContentValues().apply {
            put("device_id", state.deviceId)
            put("highest_confirmed_state_revision", state.highestConfirmedStateRevision)
            put("processing_status", state.localStatus.persistedValue)
            put("remote_status", state.remoteStatus)
            put("create_mode", state.createMode)
            put("last_error_code", state.lastErrorCode)
            put("updated_at", state.updatedAt)
        }
        db.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun validateAckIntent(receipt: SyncV1ImportReceipt, intent: SyncV1AckPublicationIntent) {
        if (intent.deviceId != receipt.deviceId
            || intent.eventId != receipt.eventId
            || intent.remotePath != syncV1AckRemotePath(receipt.deviceId, receipt.eventId)
            || intent.rawSha256 != receipt.ackSha256
            || !intent.rawJsonBytes.contentEquals(receipt.ackRawJsonBytes)
            || intent.serviceIdentity != receipt.serviceIdentity
            || intent.rootIdentity != receipt.rootIdentity
            || SyncV1Protocol.sha256(intent.rawJsonBytes) != intent.rawSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.ACK_CONFLICT)
    }

    private fun validateProgressIntent(intent: SyncV1ProgressPublicationIntent) {
        val expected = "legadom_ink_sync/v1/android/progress/${intent.workId}/${intent.progressRevision}-${intent.snapshotId}.json"
        val parsed = SyncV1ProgressProtocol.parseState(intent.rawJsonBytes)
        if (!SyncV1Protocol.isUuidV4(intent.workId) || intent.tocRevision < 1 || intent.progressRevision < 1
            || intent.createMode != SYNC_V1_CREATE_MODE || intent.remotePath != expected
            || intent.rawSha256 != SyncV1Protocol.sha256(intent.rawJsonBytes)
            || parsed == null || parsed.workId != intent.workId || parsed.tocRevision != intent.tocRevision
            || parsed.progressRevision != intent.progressRevision || parsed.snapshotId != intent.snapshotId.toString()
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
    }

    private fun progressReceiptValues(receipt: SyncV1ProgressReceipt, statePath: String?): ContentValues = ContentValues().apply {
        put("event_id", receipt.eventId); put("processor_owner", "progress"); put("device_id", receipt.deviceId)
        put("work_id", receipt.workId); put("payload_sha256", receipt.payloadSha256); put("terminal_status", receipt.terminalStatus)
        put("event_raw_json_bytes", receipt.eventRawJsonBytes)
        put("acknowledged_at", receipt.acknowledgedAt); put("ack_raw_json_bytes", receipt.ackRawJsonBytes)
        put("ack_sha256", receipt.ackSha256); put("ack_remote_path", "legadom_ink_sync/v1/acks/${receipt.deviceId}/${receipt.eventId}.json")
        put("projection_stage", receipt.projectionStage); put("progress_state_path", statePath)
        put("ack_put_attempt_count", 0); put("ack_publication_status", "pending")
        put("service_identity", receipt.serviceIdentity); put("root_identity", receipt.rootIdentity)
        put("created_at", System.currentTimeMillis())
    }

    private fun Cursor.toProgressIntent() = SyncV1ProgressPublicationIntent(
        workId = getString(getColumnIndexOrThrow("work_id")),
        tocRevision = getLong(getColumnIndexOrThrow("toc_revision")),
        progressRevision = getLong(getColumnIndexOrThrow("progress_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        putAttemptCount = getInt(getColumnIndexOrThrow("put_attempt_count")),
        configIdentity = getString(getColumnIndexOrThrow("config_identity")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        sourceEventId = nullableString("source_event_id"),
        createMode = getString(getColumnIndexOrThrow("create_mode")),
        publicationStatus = getString(getColumnIndexOrThrow("publication_status")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toProgressState(): SyncV1ProgressState {
        val bytes = getBlob(getColumnIndexOrThrow("raw_json_bytes"))
        val parsed = SyncV1ProgressProtocol.parseState(bytes) ?: throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        val workId = getString(getColumnIndexOrThrow("work_id"))
        val revision = getLong(getColumnIndexOrThrow("progress_revision"))
        val snapshot = getString(getColumnIndexOrThrow("snapshot_id"))
        val path = getString(getColumnIndexOrThrow("remote_path"))
        val digest = getString(getColumnIndexOrThrow("raw_sha256"))
        if (parsed.workId != workId || parsed.progressRevision != revision || parsed.snapshotId != snapshot
            || digest != SyncV1Protocol.sha256(bytes)
            || path != "legadom_ink_sync/v1/android/progress/$workId/$revision-$snapshot.json"
        ) throw SyncV1Failure(SyncV1ErrorCode.PROGRESS_STATE_INVALID)
        return SyncV1ProgressState(
            workId = parsed.workId,
            tocRevision = parsed.tocRevision,
            progressRevision = parsed.progressRevision,
            snapshotId = parsed.snapshotId,
            chapterTitle = parsed.chapterTitle,
            localTocIndex = parsed.localTocIndex,
            chapterFractionPpm = parsed.chapterFractionPpm,
            visibleCharOffset = parsed.visibleCharOffset,
            visibleCharCount = parsed.visibleCharCount,
            chapterTextFingerprint = parsed.chapterTextFingerprint,
            locatorConfidence = parsed.locatorConfidence,
            sourceEventId = parsed.sourceEventId,
            updatedByDeviceId = parsed.updatedByDeviceId,
            updatedAt = parsed.updatedAt,
            rawJsonBytes = bytes,
            rawSha256 = digest,
            remotePath = path,
            serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
            rootIdentity = getString(getColumnIndexOrThrow("root_identity"))
        )
    }

    private fun Cursor.toProgressReceipt() = SyncV1ProgressReceipt(
        eventId = getString(getColumnIndexOrThrow("event_id")),
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        workId = getString(getColumnIndexOrThrow("work_id")),
        payloadSha256 = getString(getColumnIndexOrThrow("payload_sha256")),
        eventRawJsonBytes = getBlob(getColumnIndexOrThrow("event_raw_json_bytes")),
        terminalStatus = getString(getColumnIndexOrThrow("terminal_status")),
        acknowledgedAt = getString(getColumnIndexOrThrow("acknowledged_at")),
        ackRawJsonBytes = getBlob(getColumnIndexOrThrow("ack_raw_json_bytes")),
        ackSha256 = getString(getColumnIndexOrThrow("ack_sha256")),
        projectionStage = getString(getColumnIndexOrThrow("projection_stage")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        ackPutAttemptCount = getInt(getColumnIndexOrThrow("ack_put_attempt_count"))
    )

    private fun insertImportReceipt(db: SQLiteDatabase, receipt: SyncV1ImportReceipt) {
        db.insertOrThrow(
            "sync_import_receipt",
            null,
            ContentValues().apply {
                put("event_id", receipt.eventId)
                put("device_id", receipt.deviceId)
                put("event_type", receipt.eventType)
                put("payload_sha256", receipt.payloadSha256)
                put("counter_epoch", receipt.counterEpoch)
                put("sequence", receipt.sequence)
                put("content_id", receipt.contentId)
                put("title", receipt.title)
                put("author", receipt.author)
                put("local_toc_count", receipt.localTocCount)
                put("toc_candidates_json", bindingGson.toJson(receipt.tocCandidates))
                put("terminal_status", receipt.terminalStatus)
                put("reason_code", receipt.reasonCode)
                put("recovery_action", receipt.recoveryAction)
                put("work_id", receipt.workId)
                put("candidates_json", bindingGson.toJson(receipt.candidates))
                put("acknowledged_at", receipt.acknowledgedAt)
                put("ack_raw_json_bytes", receipt.ackRawJsonBytes)
                put("ack_sha256", receipt.ackSha256)
            put("ack_remote_path", "legadom_ink_sync/v1/acks/${receipt.deviceId}/${receipt.eventId}.json")
                put("service_identity", receipt.serviceIdentity)
                put("root_identity", receipt.rootIdentity)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    private fun insertAckIntent(db: SQLiteDatabase, intent: SyncV1AckPublicationIntent) {
        db.insertOrThrow(
            "ack_publication_intent",
            null,
            ContentValues().apply {
                put("service_identity", intent.serviceIdentity)
                put("root_identity", intent.rootIdentity)
                put("remote_path", intent.remotePath)
                put("device_id", intent.deviceId)
                put("event_id", intent.eventId)
                put("raw_json_bytes", intent.rawJsonBytes)
                put("raw_sha256", intent.rawSha256)
                put("put_attempt_count", intent.putAttemptCount)
                put("publication_status", intent.publicationStatus)
                put("created_at", intent.createdAt)
                put("updated_at", intent.updatedAt)
            }
        )
    }

    private fun Cursor.toImportReceipt(): SyncV1ImportReceipt {
        val tocType = object : TypeToken<List<SyncV1BindingTocCandidate>>() {}.type
        val candidateType = object : TypeToken<List<SyncV1BindingCandidate>>() {}.type
        val tocCandidates: List<SyncV1BindingTocCandidate> =
            bindingGson.fromJson(getString(getColumnIndexOrThrow("toc_candidates_json")), tocType) ?: emptyList()
        val candidates: List<SyncV1BindingCandidate> =
            bindingGson.fromJson(getString(getColumnIndexOrThrow("candidates_json")), candidateType) ?: emptyList()
        return SyncV1ImportReceipt(
            eventId = getString(getColumnIndexOrThrow("event_id")),
            deviceId = getString(getColumnIndexOrThrow("device_id")),
            eventType = nullableString("event_type"),
            payloadSha256 = getString(getColumnIndexOrThrow("payload_sha256")),
            counterEpoch = nullableString("counter_epoch"),
            sequence = nullableLong("sequence"),
            contentId = nullableString("content_id"),
            title = nullableString("title"),
            author = nullableString("author"),
            localTocCount = nullableLong("local_toc_count"),
            tocCandidates = tocCandidates,
            terminalStatus = getString(getColumnIndexOrThrow("terminal_status")),
            reasonCode = nullableString("reason_code"),
            recoveryAction = getString(getColumnIndexOrThrow("recovery_action")),
            workId = nullableString("work_id"),
            candidates = candidates,
            acknowledgedAt = getString(getColumnIndexOrThrow("acknowledged_at")),
            ackRawJsonBytes = getBlob(getColumnIndexOrThrow("ack_raw_json_bytes")),
            ackSha256 = getString(getColumnIndexOrThrow("ack_sha256")),
            serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
            rootIdentity = getString(getColumnIndexOrThrow("root_identity"))
        )
    }

    private fun Cursor.toContentBinding() = SyncV1KindleContentBinding(
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        counterEpoch = getString(getColumnIndexOrThrow("counter_epoch")),
        contentId = getString(getColumnIndexOrThrow("content_id")),
        workId = getString(getColumnIndexOrThrow("work_id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toAckIntent() = SyncV1AckPublicationIntent(
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        eventId = getString(getColumnIndexOrThrow("event_id")),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        putAttemptCount = getInt(getColumnIndexOrThrow("put_attempt_count")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        publicationStatus = getString(getColumnIndexOrThrow("publication_status")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toIntent(): DeviceStatePublicationIntent = DeviceStatePublicationIntent(
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        stateRevision = getLong(getColumnIndexOrThrow("state_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        putAttemptCount = getInt(getColumnIndexOrThrow("put_attempt_count")),
        configIdentity = getString(getColumnIndexOrThrow("config_identity")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        publicationStatus = getString(getColumnIndexOrThrow("publication_status")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toCatalogIntent(): SyncV1CatalogPublicationIntent = SyncV1CatalogPublicationIntent(
        catalogRevision = getLong(getColumnIndexOrThrow("catalog_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        semanticSha256 = getString(getColumnIndexOrThrow("semantic_sha256")),
        putAttemptCount = getInt(getColumnIndexOrThrow("put_attempt_count")),
        configIdentity = getString(getColumnIndexOrThrow("config_identity")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        publicationStatus = getString(getColumnIndexOrThrow("publication_status")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toCatalogState(): SyncV1CatalogPublicationState = SyncV1CatalogPublicationState(
        catalogRevision = getLong(getColumnIndexOrThrow("catalog_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        semanticSha256 = getString(getColumnIndexOrThrow("semantic_sha256")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        publishedAt = getLong(getColumnIndexOrThrow("published_at"))
    )

    private fun Cursor.toChapterIntent() = SyncV1ChapterPublicationIntent(
        workId = getString(getColumnIndexOrThrow("work_id")),
        tocRevision = getLong(getColumnIndexOrThrow("toc_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        semanticSha256 = getString(getColumnIndexOrThrow("semantic_sha256")),
        putAttemptCount = getInt(getColumnIndexOrThrow("put_attempt_count")),
        configIdentity = getString(getColumnIndexOrThrow("config_identity")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        publicationStatus = getString(getColumnIndexOrThrow("publication_status")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.toChapterState() = SyncV1ChapterPublicationState(
        workId = getString(getColumnIndexOrThrow("work_id")),
        tocRevision = getLong(getColumnIndexOrThrow("toc_revision")),
        snapshotId = UUID.fromString(getString(getColumnIndexOrThrow("snapshot_id"))),
        remotePath = getString(getColumnIndexOrThrow("remote_path")),
        rawJsonBytes = getBlob(getColumnIndexOrThrow("raw_json_bytes")),
        rawSha256 = getString(getColumnIndexOrThrow("raw_sha256")),
        semanticSha256 = getString(getColumnIndexOrThrow("semantic_sha256")),
        serviceIdentity = getString(getColumnIndexOrThrow("service_identity")),
        rootIdentity = getString(getColumnIndexOrThrow("root_identity")),
        confirmedAt = getLong(getColumnIndexOrThrow("confirmed_at"))
    )

    private fun Cursor.toWorkIdentity() = SyncV1WorkIdentity(
        workId = getString(getColumnIndexOrThrow("work_id")),
        tocRevision = getLong(getColumnIndexOrThrow("toc_revision")),
        progressRevision = getLong(getColumnIndexOrThrow("progress_revision")),
        catalogFieldsSha256 = nullableString("catalog_fields_sha256"),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun SQLiteDatabase.readOrCreateWorkIdentity(workId: String): SyncV1WorkIdentity {
        val existing = rawQuery(
            "SELECT work_id, toc_revision, progress_revision, catalog_fields_sha256, updated_at FROM work_identity WHERE work_id = ?",
            arrayOf(workId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toWorkIdentity() else null }
        if (existing != null) return existing
        insertOrThrow(
            "work_identity",
            null,
            ContentValues().apply {
                put("work_id", workId)
                put("toc_revision", 0L)
                put("progress_revision", 0L)
                put("created_at", System.currentTimeMillis())
                put("updated_at", System.currentTimeMillis())
            }
        )
        return SyncV1WorkIdentity(workId)
    }

    private fun SyncV1WorkIdentityResolution.resolvedOrThrow(): SyncV1WorkIdentity =
        (this as? SyncV1WorkIdentityResolution.Resolved)?.identity
            ?: throw SyncV1Failure(SyncV1ErrorCode.IDENTITY_CONFLICT)

    private fun Cursor.toStoredState(): SyncV1StoredState = SyncV1StoredState(
        deviceId = getString(getColumnIndexOrThrow("device_id")),
        highestConfirmedStateRevision = getLong(getColumnIndexOrThrow("highest_confirmed_state_revision")),
        localStatus = SyncV1LocalStatus.fromPersistedValue(getString(getColumnIndexOrThrow("processing_status"))),
        remoteStatus = nullableString("remote_status"),
        createMode = nullableString("create_mode"),
        lastErrorCode = nullableString("last_error_code"),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at"))
    )

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return nullableString(index)
    }

    private fun Cursor.nullableString(index: Int): String? {
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableLong(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }
}

private class SyncV1DatabaseHelper(context: Context, databaseName: String) :
    SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createVersionOneSchema(db)
        createVersionTwoSchema(db)
        createVersionThreeSchema(db)
        createVersionFourSchema(db)
        createVersionFiveSchema(db)
    }

    private fun createVersionOneSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE device_registration_record (
                device_id TEXT PRIMARY KEY NOT NULL,
                registration_payload_sha256 TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                parsed_status TEXT NOT NULL,
                supported_create_modes TEXT NOT NULL,
                locator_capability TEXT,
                validation_error_code TEXT,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE device_state_publication_intent (
                remote_path TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                state_revision INTEGER NOT NULL,
                snapshot_id TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                put_attempt_count INTEGER NOT NULL,
                config_identity TEXT NOT NULL,
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                publication_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_pending_intent ON device_state_publication_intent(device_id, publication_status, created_at)"
        )
        db.execSQL(
            """CREATE TABLE sync_state (
                device_id TEXT PRIMARY KEY NOT NULL,
                highest_confirmed_state_revision INTEGER NOT NULL,
                processing_status TEXT NOT NULL,
                remote_status TEXT,
                create_mode TEXT,
                last_error_code TEXT,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
    }

    private fun createVersionTwoSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sync_v1_metadata (
                metadata_key TEXT PRIMARY KEY NOT NULL,
                metadata_value TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE work_identity (
                work_id TEXT PRIMARY KEY NOT NULL,
                toc_revision INTEGER NOT NULL CHECK (toc_revision >= 0),
                progress_revision INTEGER NOT NULL CHECK (progress_revision >= 0),
                catalog_fields_sha256 TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE work_alias (
                book_url TEXT PRIMARY KEY NOT NULL,
                work_id TEXT NOT NULL REFERENCES work_identity(work_id) ON DELETE RESTRICT
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX sync_v1_work_alias_work_id ON work_alias(work_id)")
        db.execSQL(
            """CREATE TABLE catalog_publication_intent (
                remote_path TEXT NOT NULL,
                catalog_revision INTEGER NOT NULL,
                snapshot_id TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                semantic_sha256 TEXT NOT NULL,
                put_attempt_count INTEGER NOT NULL,
                config_identity TEXT NOT NULL,
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                publication_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, remote_path)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_pending_catalog ON catalog_publication_intent(publication_status, created_at)"
        )
        db.execSQL(
            """CREATE TABLE catalog_publication_state (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                catalog_revision INTEGER NOT NULL,
                snapshot_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                semantic_sha256 TEXT NOT NULL,
                raw_sha256 TEXT NOT NULL,
                published_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity)
            )""".trimIndent()
        )
    }

    private fun createVersionThreeSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE chapter_publication_intent (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                work_id TEXT NOT NULL,
                toc_revision INTEGER NOT NULL CHECK (toc_revision > 0),
                snapshot_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                semantic_sha256 TEXT NOT NULL,
                put_attempt_count INTEGER NOT NULL CHECK (put_attempt_count BETWEEN 0 AND 2),
                config_identity TEXT NOT NULL,
                publication_status TEXT NOT NULL,
                publication_stage TEXT NOT NULL DEFAULT 'prepared',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, remote_path)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_pending_chapters ON chapter_publication_intent(service_identity, root_identity, publication_status, created_at)"
        )
        db.execSQL(
            """CREATE TABLE chapter_publication_state (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                work_id TEXT NOT NULL,
                toc_revision INTEGER NOT NULL CHECK (toc_revision > 0),
                snapshot_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                semantic_sha256 TEXT NOT NULL,
                confirmed_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, work_id)
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE chapter_catalog_publication_state (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                catalog_dirty INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity)
            )""".trimIndent()
        )
    }

    private fun createVersionFourSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sync_import_receipt (
                event_id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                event_type TEXT,
                payload_sha256 TEXT NOT NULL,
                counter_epoch TEXT,
                sequence INTEGER,
                content_id TEXT,
                title TEXT,
                author TEXT,
                local_toc_count INTEGER,
                toc_candidates_json TEXT NOT NULL,
                terminal_status TEXT NOT NULL,
                reason_code TEXT,
                recovery_action TEXT NOT NULL,
                work_id TEXT,
                candidates_json TEXT NOT NULL,
                acknowledged_at TEXT NOT NULL,
                ack_raw_json_bytes BLOB NOT NULL,
                ack_sha256 TEXT NOT NULL,
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_receipt_content ON sync_import_receipt(device_id, counter_epoch, content_id)"
        )
        db.execSQL(
            """CREATE TABLE kindle_content_binding (
                device_id TEXT NOT NULL,
                counter_epoch TEXT NOT NULL,
                content_id TEXT NOT NULL,
                work_id TEXT NOT NULL REFERENCES work_identity(work_id) ON DELETE RESTRICT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(device_id, counter_epoch, content_id)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_binding_work ON kindle_content_binding(work_id)"
        )
        db.execSQL(
            """CREATE TABLE ack_publication_intent (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                device_id TEXT NOT NULL,
                event_id TEXT NOT NULL REFERENCES sync_import_receipt(event_id) ON DELETE RESTRICT,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                put_attempt_count INTEGER NOT NULL CHECK (put_attempt_count BETWEEN 0 AND 2),
                publication_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, remote_path),
                UNIQUE(service_identity, root_identity, event_id)
            )""".trimIndent()
        )
        db.execSQL(
            "CREATE INDEX sync_v1_pending_ack ON ack_publication_intent(service_identity, root_identity, publication_status, created_at)"
        )
    }

    private fun createVersionFiveSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE progress_baseline (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                work_id TEXT NOT NULL REFERENCES work_identity(work_id) ON DELETE RESTRICT,
                baseline_status TEXT NOT NULL CHECK (baseline_status IN ('zero_confirmed','available','unavailable')),
                progress_revision INTEGER NOT NULL CHECK (progress_revision >= 0),
                toc_revision INTEGER NOT NULL CHECK (toc_revision >= 0),
                snapshot_id TEXT,
                remote_path TEXT,
                raw_json_bytes BLOB,
                raw_sha256 TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, work_id),
                CHECK ((baseline_status = 'available' AND progress_revision > 0 AND snapshot_id IS NOT NULL
                    AND remote_path IS NOT NULL AND raw_json_bytes IS NOT NULL AND raw_sha256 IS NOT NULL)
                    OR (baseline_status != 'available' AND progress_revision = 0))
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE progress_state_publication_intent (
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                work_id TEXT NOT NULL,
                toc_revision INTEGER NOT NULL CHECK (toc_revision > 0),
                progress_revision INTEGER NOT NULL CHECK (progress_revision > 0),
                snapshot_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                raw_sha256 TEXT NOT NULL,
                put_attempt_count INTEGER NOT NULL CHECK (put_attempt_count BETWEEN 0 AND 2),
                config_identity TEXT NOT NULL,
                create_mode TEXT NOT NULL CHECK (create_mode = 'uuid_verified'),
                source_event_id TEXT,
                publication_status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(service_identity, root_identity, remote_path)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX sync_v1_pending_progress_state ON progress_state_publication_intent(service_identity, root_identity, publication_status, created_at)")
        db.execSQL("CREATE UNIQUE INDEX sync_v1_progress_state_revision ON progress_state_publication_intent(service_identity, root_identity, work_id, progress_revision)")
        db.execSQL("CREATE TABLE progress_catalog_publication_state (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, catalog_dirty INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity))")
        db.execSQL(
            """CREATE TABLE progress_event_ledger (
                event_id TEXT PRIMARY KEY NOT NULL,
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                device_id TEXT NOT NULL,
                counter_epoch TEXT NOT NULL,
                sequence INTEGER NOT NULL CHECK (sequence >= 0),
                work_id TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                raw_json_bytes BLOB NOT NULL,
                applied_progress_revision INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX sync_v1_progress_ledger_work ON progress_event_ledger(service_identity, root_identity, work_id, applied_progress_revision)")
        db.execSQL(
            """CREATE TABLE progress_import_receipt (
                event_id TEXT PRIMARY KEY NOT NULL,
                processor_owner TEXT NOT NULL CHECK (processor_owner = 'progress'),
                device_id TEXT NOT NULL,
                work_id TEXT NOT NULL,
                payload_sha256 TEXT NOT NULL,
                event_raw_json_bytes BLOB NOT NULL,
                terminal_status TEXT NOT NULL,
                acknowledged_at TEXT NOT NULL,
                ack_raw_json_bytes BLOB NOT NULL,
                ack_sha256 TEXT NOT NULL,
                ack_remote_path TEXT NOT NULL,
                projection_stage TEXT NOT NULL CHECK (projection_stage IN ('projection_pending','projection_confirmed','ack_publishable','ack_pending','ack_published')),
                progress_state_path TEXT,
                catalog_remote_path TEXT,
                ack_put_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (ack_put_attempt_count BETWEEN 0 AND 2),
                ack_publication_status TEXT NOT NULL DEFAULT 'pending',
                service_identity TEXT NOT NULL,
                root_identity TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX sync_v1_pending_progress_receipt ON progress_import_receipt(service_identity, root_identity, projection_stage, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) createVersionOneSchema(db)
        if (oldVersion < 2) createVersionTwoSchema(db)
        if (oldVersion < 3) createVersionThreeSchema(db)
        if (oldVersion < 4) createVersionFourSchema(db)
        if (oldVersion < 5) createVersionFiveSchema(db)
    }

    private companion object {
        const val DATABASE_VERSION = 5
    }
}
