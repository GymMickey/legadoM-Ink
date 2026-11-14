package io.legado.app.help.syncv1

import android.content.ContentValues
import android.content.Context
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
    private val databaseName = "syncv1-a3-migration-test.db"
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
                assertEquals(3, migrated.version)
                assertTrue(tableExists(migrated, "device_registration_record"))
                assertTrue(tableExists(migrated, "sync_state"))
                assertTrue(tableExists(migrated, "work_identity"))
                assertTrue(tableExists(migrated, "work_alias"))
                assertTrue(tableExists(migrated, "catalog_publication_intent"))
                assertTrue(tableExists(migrated, "chapter_publication_state"))
                assertTrue(tableExists(migrated, "chapter_publication_intent"))
                assertEquals(1, rowCount(migrated, "device_registration_record"))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
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
}
