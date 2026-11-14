package io.legado.app.help.syncv1

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.util.UUID

internal const val SYNC_V1_DATABASE_NAME = "legadoM_ink_sync.db"

/** Independent, lazily opened storage. It is not part of AppDatabase or backup/restore. */
class SyncV1SQLiteStore(
    context: Context,
    databaseName: String = SYNC_V1_DATABASE_NAME
) : SyncV1Store, SyncV1CatalogStore, SyncV1ChaptersStore {
    private val helper = SyncV1DatabaseHelper(context.applicationContext, databaseName)

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
                current.first == null && initialUpdatedAt != null -> initialUpdatedAt
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) createVersionOneSchema(db)
        if (oldVersion < 2) createVersionTwoSchema(db)
        if (oldVersion < 3) createVersionThreeSchema(db)
    }

    private companion object {
        const val DATABASE_VERSION = 3
    }
}
