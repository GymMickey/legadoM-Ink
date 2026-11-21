package io.legado.app.help.syncv1

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncV1SQLiteStoreMigrationTest {
    private val databaseName = "syncv1-a4-migration-test.db"
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val publisherId = "11111111-1111-4111-8111-111111111111"
    private val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val snapshotId = "88888888-8888-4888-8888-888888888881"
    private val timestamp = 1_800_000_000_000L

    @Test
    fun versionTwoUpgradePreservesA1AndA2RowsAndCreatesA3Tables() = runBlocking {
        // Use a dedicated test database under the target app context, never its production sync DB.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        file.parentFile?.mkdirs()
        createVersionTwoDatabase(context)
        try {
            val store = SyncV1SQLiteStore(context, databaseName)
            assertEquals(publisherId, store.androidPublisherDeviceId())
            assertEquals(SyncV1LocalStatus.READY, store.readState(deviceId)?.localStatus)
            assertEquals(workId, store.workAliases().single().workId)
            assertEquals("https://books.example/work/1", store.workAliases().single().bookUrl)
            assertEquals("catalog/1-$snapshotId.json", store.pendingCatalogIntent("service", "root")?.remotePath)
            assertEquals("device-state/1-$snapshotId.json", store.pendingIntent(deviceId)?.remotePath)
            val workIdentity = store.resolveWorkIdentity("https://books.example/work/1", emptySet())
                as SyncV1WorkIdentityResolution.Resolved
            assertEquals(7L, workIdentity.identity.tocRevision)
            assertEquals(2L, workIdentity.identity.progressRevision)
            assertEquals(0L, store.catalogPublicationState("service", "root")?.catalogRevision)
            assertEquals(emptyMap<String, Long>(), store.confirmedChapterRevisions("service", "root"))
            assertFalse(store.isChapterCatalogDirty("service", "root"))

            val chapterBytes = "durable-chapter-intent".toByteArray()
            val chapterIntent = SyncV1ChapterPublicationIntent(
                workId = workId,
                tocRevision = 8,
                snapshotId = UUID.fromString("99999999-9999-4999-8999-999999999999"),
                remotePath = "legadom_ink_sync/v1/android/chapters/$workId/8-99999999-9999-4999-8999-999999999999.json",
                rawJsonBytes = chapterBytes,
                rawSha256 = SyncV1Protocol.sha256(chapterBytes),
                semanticSha256 = "a".repeat(64),
                putAttemptCount = 0,
                configIdentity = "config",
                serviceIdentity = "service",
                rootIdentity = "root",
                publicationStatus = "pending",
                createdAt = timestamp,
                updatedAt = timestamp
            )
            val savedIntent = store.createChapterIntentIfNoPending(chapterIntent)
            assertEquals("prepared", savedIntent.publicationStage)
            assertEquals(1, store.incrementChapterPutAttempt(savedIntent, timestamp + 1))
            val attemptedDb = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals("put_attempted", stringColumn(attemptedDb, "chapter_publication_intent", "publication_stage"))
            } finally {
                attemptedDb.close()
            }
            store.markChapterIntentPublished(savedIntent, timestamp + 2)
            assertEquals(mapOf(workId to 8L), store.confirmedChapterRevisions("service", "root"))
            assertTrue(store.isChapterCatalogDirty("service", "root"))

            val migrated = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(5, migrated.version)
                assertTrue(tableExists(migrated, "device_registration_record"))
                assertTrue(tableExists(migrated, "sync_state"))
                assertTrue(tableExists(migrated, "work_identity"))
                assertTrue(tableExists(migrated, "work_alias"))
                assertTrue(tableExists(migrated, "catalog_publication_intent"))
                assertTrue(tableExists(migrated, "chapter_publication_state"))
                assertTrue(tableExists(migrated, "chapter_publication_intent"))
                assertTrue(tableExists(migrated, "sync_import_receipt"))
                assertTrue(tableExists(migrated, "kindle_content_binding"))
                assertTrue(tableExists(migrated, "ack_publication_intent"))
                assertTrue(tableExists(migrated, "progress_baseline"))
                assertTrue(tableExists(migrated, "progress_state_publication_intent"))
                assertTrue(tableExists(migrated, "progress_event_ledger"))
                assertTrue(tableExists(migrated, "progress_import_receipt"))
                assertEquals(1, rowCount(migrated, "device_registration_record"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionThreeUpgradePreservesA1A2A3RowsAndCreatesA4Tables() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        file.parentFile?.mkdirs()
        createVersionThreeDatabase(context)
        try {
            val store = SyncV1SQLiteStore(context, databaseName)
            assertEquals(SyncV1LocalStatus.READY, store.readState(deviceId)?.localStatus)
            assertEquals(workId, store.workAliases().single().workId)
            assertEquals(mapOf(workId to 7L), store.confirmedChapterRevisions("service", "root"))
            assertTrue(store.isChapterCatalogDirty("service", "root"))

            val migrated = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(5, migrated.version)
                assertEquals(1, rowCount(migrated, "device_registration_record"))
                assertEquals(1, rowCount(migrated, "sync_state"))
                assertEquals(1, rowCount(migrated, "work_identity"))
                assertEquals(1, rowCount(migrated, "work_alias"))
                assertEquals(1, rowCount(migrated, "chapter_publication_intent"))
                assertEquals(1, rowCount(migrated, "chapter_publication_state"))
                assertEquals(1, rowCount(migrated, "chapter_catalog_publication_state"))
                assertTrue(tableExists(migrated, "sync_import_receipt"))
                assertTrue(tableExists(migrated, "kindle_content_binding"))
                assertTrue(tableExists(migrated, "ack_publication_intent"))
                assertTrue(tableExists(migrated, "progress_baseline"))
                assertTrue(tableExists(migrated, "progress_state_publication_intent"))
                assertTrue(tableExists(migrated, "progress_event_ledger"))
                assertTrue(tableExists(migrated, "progress_import_receipt"))
                assertEquals(0, rowCount(migrated, "sync_import_receipt"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionFourUpgradePreservesA1ToA4RowsAndPendingIntents() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        file.parentFile?.mkdirs()
        createVersionFourDatabase(context)
        try {
            SyncV1SQLiteStore(context, databaseName).also { store ->
                assertEquals(SyncV1LocalStatus.READY, store.readState(deviceId)?.localStatus)
                assertEquals(1, store.pendingAckIntents("service", "root").size)
                assertEquals("pending-v3-chapter", store.pendingChapterIntents("service", "root").single().rawJsonBytes.decodeToString())
                assertEquals("pending-v2-catalog", store.pendingCatalogIntent("service", "root")?.rawJsonBytes?.decodeToString())
            }
            val migrated = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(5, migrated.version)
                assertEquals(1, rowCount(migrated, "device_registration_record"))
                assertEquals(1, rowCount(migrated, "device_state_publication_intent"))
                assertEquals(1, rowCount(migrated, "work_identity"))
                assertEquals(1, rowCount(migrated, "catalog_publication_intent"))
                assertEquals(1, rowCount(migrated, "chapter_publication_intent"))
                assertEquals(1, rowCount(migrated, "sync_import_receipt"))
                assertEquals(1, rowCount(migrated, "kindle_content_binding"))
                assertEquals(1, rowCount(migrated, "ack_publication_intent"))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(databaseName) }
    }

    @Test
    fun appliedProgressTransactionRollsBackLedgerStateAndReceiptTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        file.parentFile?.mkdirs()
        createVersionFourDatabase(context)
        try {
            val store = SyncV1SQLiteStore(context, databaseName)
            store.setProgressBaseline(
                serviceIdentity = "service",
                rootIdentity = "root",
                workId = workId,
                status = SyncV1ProgressBaseline.ZERO_CONFIRMED,
                revision = 0,
                tocRevision = 7,
                now = timestamp
            )

            val eventId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
            val eventBytes = """
                {"protocol_version":"sync-v1","schema_version":"1.0.0","normalization_version":"normalization-v1",
                "event_type":"progress","event_id":"$eventId","device_id":"$deviceId",
                "counter_epoch":"33333333-3333-4333-8333-333333333333","session_id":"44444444-4444-4444-8444-444444444444",
                "sequence":2,"created_at":"2026-01-02T03:04:05Z","work_id":"$workId",
                "based_on_toc_revision":7,"based_on_progress_revision":0,"chapter_title":"第一章","local_toc_index":0,
                "visible_char_offset":null,"visible_char_count":null,"chapter_fraction_ppm":null,
                "chapter_fraction_is_estimated":true,"chapter_text_fingerprint":null,"reported_locator_confidence":"chapter"}
            """.trimIndent().toByteArray(Charsets.UTF_8)
            val snapshotId = "77777777-7777-4777-8777-777777777777"
            val stateBytes = SyncV1ProgressProtocol.stateBytes(
                workId = workId,
                tocRevision = 7,
                progressRevision = 1,
                snapshotId = snapshotId,
                chapterTitle = "第一章",
                localTocIndex = 0,
                sourceEventId = eventId,
                updatedByDeviceId = deviceId,
                updatedAt = "2026-01-02T03:04:05Z"
            )
            val intent = SyncV1ProgressPublicationIntent(
                workId = workId,
                tocRevision = 7,
                progressRevision = 1,
                snapshotId = UUID.fromString(snapshotId),
                remotePath = "legadom_ink_sync/v1/android/progress/$workId/1-$snapshotId.json",
                rawJsonBytes = stateBytes,
                rawSha256 = SyncV1Protocol.sha256(stateBytes),
                putAttemptCount = 0,
                configIdentity = "test-config",
                serviceIdentity = "service",
                rootIdentity = "root",
                sourceEventId = eventId,
                createMode = SYNC_V1_CREATE_MODE,
                publicationStatus = "pending",
                createdAt = timestamp,
                updatedAt = timestamp
            )
            val ackBytes = SyncV1ProgressProtocol.ackBytes(
                deviceId = deviceId,
                eventId = eventId,
                workId = workId,
                payloadSha256 = SyncV1Protocol.sha256(eventBytes),
                status = "applied",
                acknowledgedAt = "2026-01-02T03:04:05Z"
            )
            val receipt = SyncV1ProgressReceipt(
                eventId = eventId,
                deviceId = deviceId,
                workId = workId,
                payloadSha256 = SyncV1Protocol.sha256(eventBytes),
                eventRawJsonBytes = eventBytes,
                terminalStatus = "applied",
                acknowledgedAt = "2026-01-02T03:04:05Z",
                ackRawJsonBytes = ackBytes,
                ackSha256 = SyncV1Protocol.sha256(ackBytes),
                projectionStage = "projection_pending",
                serviceIdentity = "service",
                rootIdentity = "root"
            )

            val writable = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
            try {
                writable.execSQL(
                    "CREATE TRIGGER fail_progress_receipt_insert BEFORE INSERT ON progress_import_receipt " +
                        "BEGIN SELECT RAISE(ABORT, 'transaction rollback test'); END"
                )
            } finally {
                writable.close()
            }

            try {
                store.persistAppliedProgress(eventBytes, intent, receipt)
                throw AssertionError("Expected injected receipt insert failure")
            } catch (_: SQLiteConstraintException) {
                // The production transaction must roll back the earlier intent and ledger inserts too.
            }

            val migrated = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                assertEquals(0, rowCountWhere(migrated, "progress_state_publication_intent", "source_event_id", eventId))
                assertEquals(0, rowCountWhere(migrated, "progress_event_ledger", "event_id", eventId))
                assertEquals(0, rowCountWhere(migrated, "progress_import_receipt", "event_id", eventId))
                assertEquals(0, rowCount(migrated, "progress_catalog_publication_state"))
                assertEquals("zero_confirmed", stringColumnWhere(migrated, "progress_baseline", "baseline_status", "work_id", workId))
                assertEquals(1, rowCountWhere(migrated, "sync_import_receipt", "event_id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
                assertEquals(1, rowCount(migrated, "ack_publication_intent"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun createVersionFourDatabase(context: Context) {
        createVersionThreeDatabase(context)
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TABLE sync_import_receipt (event_id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, event_type TEXT, payload_sha256 TEXT NOT NULL, counter_epoch TEXT, sequence INTEGER, content_id TEXT, title TEXT, author TEXT, local_toc_count INTEGER, toc_candidates_json TEXT NOT NULL, terminal_status TEXT NOT NULL, reason_code TEXT, recovery_action TEXT NOT NULL, work_id TEXT, candidates_json TEXT NOT NULL, acknowledged_at TEXT NOT NULL, ack_raw_json_bytes BLOB NOT NULL, ack_sha256 TEXT NOT NULL, service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX sync_v1_receipt_content ON sync_import_receipt(device_id, counter_epoch, content_id)")
            db.execSQL("CREATE TABLE kindle_content_binding (device_id TEXT NOT NULL, counter_epoch TEXT NOT NULL, content_id TEXT NOT NULL, work_id TEXT NOT NULL REFERENCES work_identity(work_id) ON DELETE RESTRICT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(device_id,counter_epoch,content_id))")
            db.execSQL("CREATE INDEX sync_v1_binding_work ON kindle_content_binding(work_id)")
            db.execSQL("CREATE TABLE ack_publication_intent (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, remote_path TEXT NOT NULL, device_id TEXT NOT NULL, event_id TEXT NOT NULL REFERENCES sync_import_receipt(event_id) ON DELETE RESTRICT, raw_json_bytes BLOB NOT NULL, raw_sha256 TEXT NOT NULL, put_attempt_count INTEGER NOT NULL CHECK (put_attempt_count BETWEEN 0 AND 2), publication_status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(service_identity,root_identity,remote_path), UNIQUE(service_identity,root_identity,event_id))")
            db.execSQL("CREATE INDEX sync_v1_pending_ack ON ack_publication_intent(service_identity,root_identity,publication_status,created_at)")
            val ackBytes = "a4-ack".toByteArray()
            db.insertOrThrow("sync_import_receipt", null, ContentValues().apply {
                put("event_id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"); put("device_id", deviceId); put("event_type", "binding_request")
                put("payload_sha256", "a".repeat(64)); put("toc_candidates_json", "[]"); put("terminal_status", "bound")
                put("recovery_action", "none"); put("candidates_json", "[]"); put("acknowledged_at", "2026-01-01T00:00:00Z")
                put("ack_raw_json_bytes", ackBytes); put("ack_sha256", SyncV1Protocol.sha256(ackBytes)); put("service_identity", "service")
                put("root_identity", "root"); put("created_at", timestamp)
            })
            db.insertOrThrow("kindle_content_binding", null, ContentValues().apply {
                put("device_id", deviceId); put("counter_epoch", "33333333-3333-4333-8333-333333333333"); put("content_id", "content-1")
                put("work_id", workId); put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.insertOrThrow("ack_publication_intent", null, ContentValues().apply {
                put("service_identity", "service"); put("root_identity", "root"); put("remote_path", "acks/$deviceId/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb.json")
                put("device_id", deviceId); put("event_id", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"); put("raw_json_bytes", ackBytes)
                put("raw_sha256", SyncV1Protocol.sha256(ackBytes)); put("put_attempt_count", 1); put("publication_status", "pending")
                put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.execSQL("UPDATE chapter_publication_intent SET raw_json_bytes = ?, raw_sha256 = ?", arrayOf("pending-v3-chapter".toByteArray(), SyncV1Protocol.sha256("pending-v3-chapter".toByteArray())))
            db.execSQL("UPDATE catalog_publication_intent SET raw_json_bytes = ?, raw_sha256 = ?", arrayOf("pending-v2-catalog".toByteArray(), SyncV1Protocol.sha256("pending-v2-catalog".toByteArray())))
            db.version = 4
        } finally { db.close() }
    }

    private fun createVersionThreeDatabase(context: Context) {
        createVersionTwoDatabase(context)
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TABLE chapter_publication_intent (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, work_id TEXT NOT NULL, toc_revision INTEGER NOT NULL CHECK (toc_revision > 0), snapshot_id TEXT NOT NULL, remote_path TEXT NOT NULL, raw_json_bytes BLOB NOT NULL, raw_sha256 TEXT NOT NULL, semantic_sha256 TEXT NOT NULL, put_attempt_count INTEGER NOT NULL CHECK (put_attempt_count BETWEEN 0 AND 2), config_identity TEXT NOT NULL, publication_status TEXT NOT NULL, publication_stage TEXT NOT NULL DEFAULT 'prepared', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity, remote_path))")
            db.execSQL("CREATE INDEX sync_v1_pending_chapters ON chapter_publication_intent(service_identity, root_identity, publication_status, created_at)")
            db.execSQL("CREATE TABLE chapter_publication_state (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, work_id TEXT NOT NULL, toc_revision INTEGER NOT NULL CHECK (toc_revision > 0), snapshot_id TEXT NOT NULL, remote_path TEXT NOT NULL, raw_json_bytes BLOB NOT NULL, raw_sha256 TEXT NOT NULL, semantic_sha256 TEXT NOT NULL, confirmed_at INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity, work_id))")
            db.execSQL("CREATE TABLE chapter_catalog_publication_state (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, catalog_dirty INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity))")
            val chapterIntentId = "99999999-9999-4999-8999-999999999999"
            val bytes = "a3-pending".toByteArray()
            db.insertOrThrow("chapter_publication_intent", null, ContentValues().apply {
                put("service_identity", "service"); put("root_identity", "root"); put("work_id", workId)
                put("toc_revision", 8); put("snapshot_id", chapterIntentId); put("remote_path", "chapters/$workId/8-$chapterIntentId.json")
                put("raw_json_bytes", bytes); put("raw_sha256", SyncV1Protocol.sha256(bytes)); put("semantic_sha256", "a".repeat(64))
                put("put_attempt_count", 1); put("config_identity", "config"); put("publication_status", "pending")
                put("publication_stage", "put_attempted"); put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.insertOrThrow("chapter_publication_state", null, ContentValues().apply {
                put("service_identity", "service"); put("root_identity", "root"); put("work_id", workId)
                put("toc_revision", 7); put("snapshot_id", snapshotId); put("remote_path", "chapters/$workId/7-$snapshotId.json")
                put("raw_json_bytes", byteArrayOf(5)); put("raw_sha256", "a".repeat(64)); put("semantic_sha256", "b".repeat(64))
                put("confirmed_at", timestamp)
            })
            db.insertOrThrow("chapter_catalog_publication_state", null, ContentValues().apply {
                put("service_identity", "service"); put("root_identity", "root"); put("catalog_dirty", 1)
            })
            db.version = 3
        } finally {
            db.close()
        }
    }

    private fun createVersionTwoDatabase(context: Context) {
        val db = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        try {
            db.execSQL("CREATE TABLE device_registration_record (device_id TEXT PRIMARY KEY NOT NULL, registration_payload_sha256 TEXT NOT NULL, remote_path TEXT NOT NULL, parsed_status TEXT NOT NULL, supported_create_modes TEXT NOT NULL, locator_capability TEXT, validation_error_code TEXT, first_seen_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE device_state_publication_intent (remote_path TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, state_revision INTEGER NOT NULL, snapshot_id TEXT NOT NULL, raw_json_bytes BLOB NOT NULL, raw_sha256 TEXT NOT NULL, put_attempt_count INTEGER NOT NULL, config_identity TEXT NOT NULL, service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, publication_status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sync_state (device_id TEXT PRIMARY KEY NOT NULL, highest_confirmed_state_revision INTEGER NOT NULL, processing_status TEXT NOT NULL, remote_status TEXT, create_mode TEXT, last_error_code TEXT, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sync_v1_metadata (metadata_key TEXT PRIMARY KEY NOT NULL, metadata_value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE work_identity (work_id TEXT PRIMARY KEY NOT NULL, toc_revision INTEGER NOT NULL, progress_revision INTEGER NOT NULL, catalog_fields_sha256 TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE work_alias (book_url TEXT PRIMARY KEY NOT NULL, work_id TEXT NOT NULL REFERENCES work_identity(work_id) ON DELETE RESTRICT)")
            db.execSQL("CREATE TABLE catalog_publication_intent (remote_path TEXT NOT NULL, catalog_revision INTEGER NOT NULL, snapshot_id TEXT NOT NULL, raw_json_bytes BLOB NOT NULL, raw_sha256 TEXT NOT NULL, semantic_sha256 TEXT NOT NULL, put_attempt_count INTEGER NOT NULL, config_identity TEXT NOT NULL, service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, publication_status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity, remote_path))")
            db.execSQL("CREATE TABLE catalog_publication_state (service_identity TEXT NOT NULL, root_identity TEXT NOT NULL, catalog_revision INTEGER NOT NULL, snapshot_id TEXT NOT NULL, remote_path TEXT NOT NULL, semantic_sha256 TEXT NOT NULL, raw_sha256 TEXT NOT NULL, published_at INTEGER NOT NULL, PRIMARY KEY(service_identity, root_identity))")
            db.execSQL("CREATE INDEX sync_v1_pending_intent ON device_state_publication_intent(device_id, publication_status, created_at)")
            db.execSQL("CREATE INDEX sync_v1_pending_catalog ON catalog_publication_intent(publication_status, created_at)")
            db.version = 2

            db.insertOrThrow("device_registration_record", null, ContentValues().apply {
                put("device_id", deviceId); put("registration_payload_sha256", "a".repeat(64)); put("remote_path", "devices/$deviceId.json")
                put("parsed_status", "valid"); put("supported_create_modes", "[uuid_verified]"); put("locator_capability", "chapter_only")
                put("first_seen_at", timestamp); put("last_seen_at", timestamp)
            })
            db.insertOrThrow("device_state_publication_intent", null, ContentValues().apply {
                put("remote_path", "device-state/1-$snapshotId.json"); put("device_id", deviceId); put("state_revision", 1)
                put("snapshot_id", snapshotId); put("raw_json_bytes", byteArrayOf(1, 2)); put("raw_sha256", "b".repeat(64))
                put("put_attempt_count", 1); put("config_identity", "config"); put("service_identity", "service")
                put("root_identity", "root"); put("publication_status", "pending"); put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.insertOrThrow("sync_state", null, ContentValues().apply {
                put("device_id", deviceId); put("highest_confirmed_state_revision", 1); put("processing_status", "ready")
                put("remote_status", "ready"); put("create_mode", "uuid_verified"); put("updated_at", timestamp)
            })
            db.insertOrThrow("sync_v1_metadata", null, ContentValues().apply {
                put("metadata_key", "android_publisher_device_id"); put("metadata_value", publisherId)
            })
            db.insertOrThrow("work_identity", null, ContentValues().apply {
                put("work_id", workId); put("toc_revision", 7); put("progress_revision", 2)
                put("catalog_fields_sha256", "c".repeat(64)); put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.insertOrThrow("work_alias", null, ContentValues().apply {
                put("book_url", "https://books.example/work/1"); put("work_id", workId)
            })
            db.insertOrThrow("catalog_publication_intent", null, ContentValues().apply {
                put("remote_path", "catalog/1-$snapshotId.json"); put("catalog_revision", 1); put("snapshot_id", snapshotId)
                put("raw_json_bytes", byteArrayOf(3, 4)); put("raw_sha256", "d".repeat(64)); put("semantic_sha256", "e".repeat(64))
                put("put_attempt_count", 1); put("config_identity", "config"); put("service_identity", "service")
                put("root_identity", "root"); put("publication_status", "pending"); put("created_at", timestamp); put("updated_at", timestamp)
            })
            db.insertOrThrow("catalog_publication_state", null, ContentValues().apply {
                put("service_identity", "service"); put("root_identity", "root"); put("catalog_revision", 0)
                put("snapshot_id", snapshotId); put("remote_path", "catalog/0-$snapshotId.json")
                put("semantic_sha256", "f".repeat(64)); put("raw_sha256", "0".repeat(64)); put("published_at", timestamp)
            })
        } finally {
            db.close()
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)
    ).use { it.moveToFirst() }

    private fun rowCount(db: SQLiteDatabase, table: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $table", null
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun stringColumn(db: SQLiteDatabase, table: String, column: String): String = db.rawQuery(
        "SELECT $column FROM $table LIMIT 1", null
    ).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }

    private fun rowCountWhere(db: SQLiteDatabase, table: String, column: String, value: String): Int = db.rawQuery(
        "SELECT COUNT(*) FROM $table WHERE $column = ?", arrayOf(value)
    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    private fun stringColumnWhere(db: SQLiteDatabase, table: String, resultColumn: String, whereColumn: String, value: String): String = db.rawQuery(
        "SELECT $resultColumn FROM $table WHERE $whereColumn = ? LIMIT 1", arrayOf(value)
    ).use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
}
