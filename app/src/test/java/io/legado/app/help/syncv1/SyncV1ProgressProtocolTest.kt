package io.legado.app.help.syncv1

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncV1ProgressProtocolTest {
    private val deviceId = "22222222-2222-4222-8222-222222222222"
    private val eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val workId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val epoch = "33333333-3333-4333-8333-333333333333"
    private val sessionId = "44444444-4444-4444-8444-444444444444"

    @Test
    fun strictProgressEventParsingAndCapabilitySemantics() {
        val parsed = SyncV1ProgressProtocol.parseEvent(deviceId, eventId, event())
        assertTrue(parsed is SyncV1ProgressEventParseResult.Valid)
        val value = (parsed as SyncV1ProgressEventParseResult.Valid).event
        assertEquals(0L, value.basedOnTocRevision)
        assertEquals("chapter", value.locatorConfidence)
        assertTrue(SyncV1ProgressSemantics.locatorAllowed("chapter_only", value.locatorConfidence))
        assertFalse(SyncV1ProgressSemantics.locatorAllowed("chapter_only", "high"))
        assertTrue(SyncV1ProgressSemantics.locatorAllowed("character_exact", "estimated"))
    }

    @Test
    fun chapterOnlyRejectableOffsetsAndUnknownFieldsAreStrictlyParsed() {
        val characterEvent = JsonObjectEvent(event()).apply {
            addProperty("reported_locator_confidence", "high")
            addProperty("visible_char_offset", 8)
            addProperty("visible_char_count", 7)
            addProperty("chapter_text_fingerprint", "a".repeat(64))
        }.bytes()
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, characterEvent) is SyncV1ProgressEventParseResult.Invalid)

        val extraField = JsonObjectEvent(event()).apply { addProperty("unexpected", true) }.bytes()
        val result = SyncV1ProgressProtocol.parseEvent(deviceId, eventId, extraField)
        assertEquals(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, (result as SyncV1ProgressEventParseResult.Invalid).errorCode)

        val wrongPath = SyncV1ProgressProtocol.parseEvent(deviceId, "cccccccc-cccc-4ccc-8ccc-cccccccccccc", event())
        assertTrue(wrongPath is SyncV1ProgressEventParseResult.Invalid)
    }

    @Test
    fun eventByteLimitIsCheckedBeforeParsing() {
        val valid = event()
        val atLimit = valid + ByteArray(256 * 1024 - valid.size) { 0x20 }
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, atLimit) is SyncV1ProgressEventParseResult.Valid)
        val over = atLimit + byteArrayOf(0x20)
        val result = SyncV1ProgressProtocol.parseEvent(deviceId, eventId, over) as SyncV1ProgressEventParseResult.Invalid
        assertEquals(SyncV1ErrorCode.SIZE_EXCEEDED, result.errorCode)
        assertFalse(result.declaredProgress)
    }

    @Test
    fun progressStateUsesExactSchemaAndChecksTheSixtyFourKiBLimit() {
        val bytes = SyncV1ProgressProtocol.stateBytes(
            workId, 7, 1, "88888888-8888-4888-8888-888888888881", "第一章", 0,
            null, deviceId, "2026-01-02T03:04:05Z"
        )
        val parsed = SyncV1ProgressProtocol.parseState(bytes)
        assertEquals("第一章", parsed?.chapterTitle)
        assertNull(parsed?.visibleCharOffset)
        val atLimit = bytes + ByteArray(65_536 - bytes.size) { 0x20 }
        assertTrue(SyncV1ProgressProtocol.parseState(atLimit) != null)
        assertNull(SyncV1ProgressProtocol.parseState(atLimit + byteArrayOf(0x20)))
    }

    @Test
    fun stateRevisionsAndLocatorFieldsAreValidated() {
        val base = JsonObjectEvent(SyncV1ProgressProtocol.stateBytes(
            workId, 7, 1, "88888888-8888-4888-8888-888888888881", "第一章", 0,
            eventId, deviceId, "2026-01-02T03:04:05Z"
        ))
        assertTrue(SyncV1ProgressProtocol.parseState(base.bytes()) != null)
        base.addProperty("progress_revision", 0)
        assertNull(SyncV1ProgressProtocol.parseState(base.bytes()))
    }

    @Test
    fun baselineEvidenceAndStalePrecedenceAreConservative() {
        assertEquals(SyncV1ProgressBaseline.UNAVAILABLE,
            SyncV1ProgressSemantics.baselineForEvidence(false, false, false))
        assertEquals(SyncV1ProgressBaseline.UNAVAILABLE,
            SyncV1ProgressSemantics.baselineForEvidence(true, false, false))
        assertEquals(SyncV1ProgressBaseline.AVAILABLE,
            SyncV1ProgressSemantics.baselineForEvidence(true, false, true))
        assertEquals(SyncV1ProgressBaseline.ZERO_CONFIRMED,
            SyncV1ProgressSemantics.baselineForEvidence(false, true, false))
        assertFalse(SyncV1ProgressSemantics.isValidCatalogBaseline(SyncV1ProgressBaseline.AVAILABLE, 0))
        assertFalse(SyncV1ProgressSemantics.isValidCatalogBaseline(SyncV1ProgressBaseline.ZERO_CONFIRMED, 1))
        assertEquals("stale_toc", SyncV1ProgressSemantics.staleStatus(2, 3, 4, 5))
        assertEquals("stale_progress", SyncV1ProgressSemantics.staleStatus(3, 3, 4, 5))
        assertNull(SyncV1ProgressSemantics.staleStatus(3, 3, 5, 5))
        assertTrue(SyncV1ProgressSemantics.acceptsProgressUpload(SyncV1ProgressBaseline.ZERO_CONFIRMED))
        assertTrue(SyncV1ProgressSemantics.acceptsProgressUpload(SyncV1ProgressBaseline.AVAILABLE))
        assertFalse(SyncV1ProgressSemantics.acceptsProgressUpload(SyncV1ProgressBaseline.UNAVAILABLE))
        assertFalse(SyncV1ProgressSemantics.acceptsProgressUpload(SyncV1ProgressBaseline.LEGACY_UNKNOWN))
    }

    @Test
    fun titleUsesUnicodeScalarsAndRejectsEmptyOversizedOrIsolatedSurrogates() {
        fun bytes(title: String): ByteArray = JsonParser.parseString(String(event())).asJsonObject.apply {
            addProperty("chapter_title", title)
        }.toString().toByteArray(Charsets.UTF_8)

        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes("🧪")) is SyncV1ProgressEventParseResult.Valid)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes("界".repeat(512))) is SyncV1ProgressEventParseResult.Valid)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes("界".repeat(513))) is SyncV1ProgressEventParseResult.Invalid)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes("")) is SyncV1ProgressEventParseResult.Invalid)
        val isolatedSurrogate = String(event(), Charsets.UTF_8)
            .replace("\"chapter_title\":\"第一章\"", "\"chapter_title\":\"\\uD800\"")
            .toByteArray(Charsets.UTF_8)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, isolatedSurrogate) is SyncV1ProgressEventParseResult.Invalid)
    }

    @Test
    fun progressFractionUsesNullableSafeIntegerPpm() {
        fun bytes(ppm: Long?): ByteArray = JsonParser.parseString(String(event())).asJsonObject.apply {
            if (ppm == null) add("chapter_fraction_ppm", com.google.gson.JsonNull.INSTANCE)
            else addProperty("chapter_fraction_ppm", ppm)
        }.toString().toByteArray(Charsets.UTF_8)

        for (ppm in listOf(0L, 1_000_000L)) {
            assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes(ppm)) is SyncV1ProgressEventParseResult.Valid)
        }
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes(null)) is SyncV1ProgressEventParseResult.Valid)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes(-1)) is SyncV1ProgressEventParseResult.Invalid)
        assertTrue(SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes(1_000_001)) is SyncV1ProgressEventParseResult.Invalid)
    }

    @Test
    fun sequenceAndRevisionsRejectNegativeAndUnsafeIntegers() {
        val invalid = listOf(
            "sequence" to -1L,
            "based_on_toc_revision" to -1L,
            "based_on_progress_revision" to -1L,
            "sequence" to (SYNC_V1_MAX_SAFE_INTEGER + 1)
        )
        for ((field, value) in invalid) {
            val bytes = JsonParser.parseString(String(event())).asJsonObject.apply { addProperty(field, value) }
                .toString().toByteArray(Charsets.UTF_8)
            assertTrue("$field=$value", SyncV1ProgressProtocol.parseEvent(deviceId, eventId, bytes) is SyncV1ProgressEventParseResult.Invalid)
        }
    }

    private fun event(): ByteArray = """
        {"protocol_version":"sync-v1","schema_version":"1.0.0","normalization_version":"normalization-v1",
         "event_type":"progress","event_id":"$eventId","device_id":"$deviceId","counter_epoch":"$epoch",
         "session_id":"$sessionId","sequence":3,"created_at":"2026-01-02T03:04:05Z","work_id":"$workId",
         "based_on_toc_revision":0,"based_on_progress_revision":0,"chapter_title":"第一章","local_toc_index":0,
         "visible_char_offset":null,"visible_char_count":null,"chapter_fraction_ppm":null,
         "chapter_fraction_is_estimated":true,"chapter_text_fingerprint":null,"reported_locator_confidence":"chapter"}
    """.trimIndent().toByteArray(Charsets.UTF_8)

    private class JsonObjectEvent(bytes: ByteArray) {
        private val delegate = com.google.gson.JsonParser.parseString(String(bytes)).asJsonObject
        fun addProperty(name: String, value: String) = delegate.addProperty(name, value)
        fun addProperty(name: String, value: Number) = delegate.addProperty(name, value)
        fun addProperty(name: String, value: Boolean) = delegate.addProperty(name, value)
        fun bytes() = delegate.toString().toByteArray(Charsets.UTF_8)
    }
}
