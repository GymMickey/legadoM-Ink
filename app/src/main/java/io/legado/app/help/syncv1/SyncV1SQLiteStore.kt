package io.legado.app.help.syncv1

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import java.util.UUID

/** Independent, lazily opened storage. It is not part of AppDatabase or backup/restore. */
class SyncV1SQLiteStore(context: Context) : SyncV1Store {
    private val helper = SyncV1DatabaseHelper(context.applicationContext)

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

private class SyncV1DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private companion object {
        const val DATABASE_NAME = "legadoM_ink_sync.db"
        const val DATABASE_VERSION = 1
    }
}
