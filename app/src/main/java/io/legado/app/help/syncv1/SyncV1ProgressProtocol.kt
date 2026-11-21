package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

internal data class ParsedSyncV1ProgressState(
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
    val updatedAt: String
)

internal object SyncV1ProgressProtocol {
    private const val STATE_LIMIT = 65_536
    private const val EVENT_LIMIT = 256 * 1024
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val sha = Regex("^[a-f0-9]{64}$")
    private val eventRequired = setOf(
        "protocol_version", "schema_version", "normalization_version", "event_type", "event_id",
        "device_id", "counter_epoch", "session_id", "sequence", "created_at", "work_id",
        "based_on_toc_revision", "based_on_progress_revision", "chapter_title", "local_toc_index",
        "visible_char_offset", "visible_char_count", "chapter_fraction_ppm", "chapter_fraction_is_estimated",
        "chapter_text_fingerprint", "reported_locator_confidence"
    )
    private val stateKeys = setOf(
        "protocol_version", "schema_version", "normalization_version", "work_id", "toc_revision",
        "progress_revision", "snapshot_id", "chapter_title", "local_toc_index", "visible_char_offset",
        "visible_char_count", "chapter_fraction_ppm", "chapter_fraction_is_estimated",
        "chapter_text_fingerprint", "locator_confidence", "source_event_id", "updated_by_device_id", "updated_at"
    )

    fun parseEvent(pathDeviceId: String, pathEventId: String, bytes: ByteArray): SyncV1ProgressEventParseResult {
        if (bytes.size > EVENT_LIMIT) return SyncV1ProgressEventParseResult.Invalid(SyncV1ErrorCode.SIZE_EXCEEDED, false)
        val root = try { strict(bytes) as? JsonObject } catch (_: Exception) { null }
            ?: return SyncV1ProgressEventParseResult.Invalid(SyncV1ErrorCode.INVALID_JSON, false)
        val type = root.string("event_type")
        if (type != "progress") return SyncV1ProgressEventParseResult.NotProgress
        fun invalid(code: SyncV1ErrorCode = SyncV1ErrorCode.PROGRESS_EVENT_INVALID) =
            SyncV1ProgressEventParseResult.Invalid(code, true)
        if (!root.keySet().containsAll(eventRequired) || root.keySet().any {
                it !in eventRequired && it !in setOf("previous_chapter_title", "next_chapter_title")
            }) return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA)
        if (root.string("protocol_version") != "sync-v1" || root.string("schema_version") != "1.0.0"
            || root.string("normalization_version") != "normalization-v1"
        ) return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA)
        val eventId = root.string("event_id")?.takeIf(uuid::matches) ?: return invalid()
        val deviceId = root.string("device_id")?.takeIf(uuid::matches) ?: return invalid()
        val epoch = root.string("counter_epoch")?.takeIf(uuid::matches) ?: return invalid()
        val sessionId = root.string("session_id")?.takeIf(uuid::matches) ?: return invalid()
        if (eventId != pathEventId || deviceId != pathDeviceId || !uuid.matches(pathDeviceId) || !uuid.matches(pathEventId)) return invalid()
        if (sessionId.isEmpty()) return invalid()
        val sequence = root.long("sequence")?.takeIf { it >= 0 } ?: return invalid()
        val created = root.string("created_at")?.takeIf(::isUtc) ?: return invalid()
        val work = root.string("work_id")?.takeIf(uuid::matches) ?: return invalid()
        val tocRevision = root.long("based_on_toc_revision")?.takeIf { it >= 0 } ?: return invalid()
        val progressRevision = root.long("based_on_progress_revision")?.takeIf { it >= 0 } ?: return invalid()
        val title = root.string("chapter_title")?.takeIf { scalarCount(it) in 1..512 } ?: return invalid()
        val index = root.long("local_toc_index")?.takeIf { it >= 0 } ?: return invalid()
        val offset = root.nullableLong("visible_char_offset") ?: if (root.get("visible_char_offset") === JsonNull.INSTANCE) null else return invalid()
        val count = root.nullableLong("visible_char_count") ?: if (root.get("visible_char_count") === JsonNull.INSTANCE) null else return invalid()
        val fraction = root.nullableLong("chapter_fraction_ppm") ?: if (root.get("chapter_fraction_ppm") === JsonNull.INSTANCE) null else return invalid()
        if (fraction != null && fraction !in 0..1_000_000) return invalid()
        if (root.bool("chapter_fraction_is_estimated") != true) return invalid()
        val fingerprint = root.nullableString("chapter_text_fingerprint")
            ?: if (root.get("chapter_text_fingerprint") === JsonNull.INSTANCE) null else return invalid()
        if (fingerprint != null && !sha.matches(fingerprint)) return invalid()
        val confidence = root.string("reported_locator_confidence") ?: return invalid()
        val validLocator = when (confidence) {
            "chapter" -> offset == null && count == null && fingerprint == null
            "high", "estimated" -> offset != null && count != null && count > 0 && offset >= 0 && offset <= count && fingerprint != null
            else -> false
        }
        if (!validLocator) return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA)
        val previous = optionalTitle(root, "previous_chapter_title") ?: if (root.has("previous_chapter_title")) return invalid() else null
        val next = optionalTitle(root, "next_chapter_title") ?: if (root.has("next_chapter_title")) return invalid() else null
        return SyncV1ProgressEventParseResult.Valid(
            ParsedSyncV1ProgressEvent(eventId, deviceId, epoch, sequence, created, work, tocRevision,
                progressRevision, title, index, previous, next, offset, count, fraction?.toInt(), fingerprint, confidence)
        )
    }

    fun parseState(bytes: ByteArray): ParsedSyncV1ProgressState? {
        if (bytes.size > STATE_LIMIT) return null
        val root = try { strict(bytes) as? JsonObject ?: return null } catch (_: Exception) { return null }
        if (root.keySet() != stateKeys || root.string("protocol_version") != "sync-v1"
            || root.string("schema_version") != "1.0.0" || root.string("normalization_version") != "normalization-v1") return null
        val work = root.string("work_id")?.takeIf(uuid::matches) ?: return null
        val toc = root.long("toc_revision")?.takeIf { it > 0 } ?: return null
        val progress = root.long("progress_revision")?.takeIf { it > 0 } ?: return null
        val snapshot = root.string("snapshot_id")?.takeIf(uuid::matches) ?: return null
        val title = root.string("chapter_title")?.takeIf { scalarCount(it) in 1..512 } ?: return null
        val index = root.long("local_toc_index")?.takeIf { it >= 0 } ?: return null
        val offset = root.nullableLong("visible_char_offset") ?: if (root.get("visible_char_offset") === JsonNull.INSTANCE) null else return null
        val count = root.nullableLong("visible_char_count") ?: if (root.get("visible_char_count") === JsonNull.INSTANCE) null else return null
        val fraction = root.nullableLong("chapter_fraction_ppm") ?: if (root.get("chapter_fraction_ppm") === JsonNull.INSTANCE) null else return null
        if (fraction != null && fraction !in 0..1_000_000 || root.bool("chapter_fraction_is_estimated") != true) return null
        val fingerprint = root.nullableString("chapter_text_fingerprint") ?: if (root.get("chapter_text_fingerprint") === JsonNull.INSTANCE) null else return null
        if (fingerprint != null && !sha.matches(fingerprint)) return null
        val confidence = root.string("locator_confidence") ?: return null
        val locatorOk = when (confidence) {
            "chapter" -> offset == null && count == null && fingerprint == null
            "high", "estimated" -> offset != null && count != null && count > 0 && fingerprint != null && offset >= 0 && offset <= count
            else -> false
        }
        if (!locatorOk) return null
        val source = root.nullableString("source_event_id") ?: if (root.get("source_event_id") === JsonNull.INSTANCE) null else return null
        if (source != null && !uuid.matches(source)) return null
        val updatedBy = root.string("updated_by_device_id")?.takeIf(uuid::matches) ?: return null
        val at = root.string("updated_at")?.takeIf(::isUtc) ?: return null
        return ParsedSyncV1ProgressState(work, toc, progress, snapshot, title, index,
            fraction?.toInt(), offset, count, fingerprint, confidence, source, updatedBy, at)
    }

    fun stateBytes(
        workId: String, tocRevision: Long, progressRevision: Long, snapshotId: String,
        chapterTitle: String, localTocIndex: Long, sourceEventId: String?, updatedByDeviceId: String,
        updatedAt: String, fractionPpm: Int? = null, visibleCharOffset: Long? = null,
        visibleCharCount: Long? = null, chapterTextFingerprint: String? = null,
        locatorConfidence: String = "chapter"
    ): ByteArray = JsonObject().apply {
        addProperty("protocol_version", "sync-v1"); addProperty("schema_version", "1.0.0")
        addProperty("normalization_version", "normalization-v1"); addProperty("work_id", workId)
        addProperty("toc_revision", tocRevision); addProperty("progress_revision", progressRevision)
        addProperty("snapshot_id", snapshotId); addProperty("chapter_title", chapterTitle)
        addProperty("local_toc_index", localTocIndex)
        if (visibleCharOffset == null) add("visible_char_offset", JsonNull.INSTANCE) else addProperty("visible_char_offset", visibleCharOffset)
        if (visibleCharCount == null) add("visible_char_count", JsonNull.INSTANCE) else addProperty("visible_char_count", visibleCharCount)
        if (fractionPpm == null) add("chapter_fraction_ppm", JsonNull.INSTANCE) else addProperty("chapter_fraction_ppm", fractionPpm)
        addProperty("chapter_fraction_is_estimated", true)
        if (chapterTextFingerprint == null) add("chapter_text_fingerprint", JsonNull.INSTANCE) else addProperty("chapter_text_fingerprint", chapterTextFingerprint)
        addProperty("locator_confidence", locatorConfidence); if (sourceEventId == null) add("source_event_id", JsonNull.INSTANCE) else addProperty("source_event_id", sourceEventId)
        addProperty("updated_by_device_id", updatedByDeviceId); addProperty("updated_at", updatedAt)
    }.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }

    fun ackBytes(deviceId: String, eventId: String, workId: String?, payloadSha256: String,
                 status: String, acknowledgedAt: String, recoveryAction: String = "none",
                 reasonCode: String? = null, currentProgressRevision: Long? = null,
                 currentTocRevision: Long? = null): ByteArray = JsonObject().apply {
        addProperty("protocol_version", "sync-v1"); addProperty("schema_version", "1.0.0")
        addProperty("normalization_version", "normalization-v1"); addProperty("device_id", deviceId)
        addProperty("event_id", eventId); if (workId != null) addProperty("work_id", workId); addProperty("payload_sha256", payloadSha256)
        addProperty("status", status); addProperty("acknowledged_at", acknowledgedAt)
        if (currentProgressRevision != null) addProperty("current_progress_revision", currentProgressRevision)
        if (currentTocRevision != null) addProperty("current_toc_revision", currentTocRevision)
        addProperty("recovery_action", recoveryAction); if (reasonCode == null) add("reason_code", JsonNull.INSTANCE) else addProperty("reason_code", reasonCode)
    }.let { gson.toJson(it).toByteArray(Charsets.UTF_8) }

    private fun optionalTitle(root: JsonObject, key: String): String? = root.string(key)?.takeIf { scalarCount(it) in 1..512 }
    private fun scalarCount(value: String): Int {
        var i = 0; var count = 0
        while (i < value.length) {
            val c = value[i]
            when {
                Character.isHighSurrogate(c) -> { if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return -1; i += 2 }
                Character.isLowSurrogate(c) -> return -1
                else -> i++
            }
            count++
        }
        return count
    }
    private fun isUtc(value: String): Boolean = value.endsWith("Z") && runCatching { Instant.parse(value) }.isSuccess
    private fun strict(bytes: ByteArray): com.google.gson.JsonElement {
        val text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val reader = JsonReader(StringReader(text)).apply { setStrictness(Strictness.STRICT) }
        fun read(): com.google.gson.JsonElement = when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().also { o -> reader.beginObject(); while (reader.hasNext()) { val k=reader.nextName(); require(!o.has(k)); o.add(k, read()) }; reader.endObject() }
            JsonToken.BEGIN_ARRAY -> JsonArray().also { a -> reader.beginArray(); while (reader.hasNext()) a.add(read()); reader.endArray() }
            JsonToken.STRING -> com.google.gson.JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> com.google.gson.JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> com.google.gson.JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
            else -> error("invalid_json")
        }
        val result = read(); require(reader.peek() == JsonToken.END_DOCUMENT); return result
    }
    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.bool(name: String): Boolean? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
    private fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asJsonPrimitive?.asNumber?.toString()?.toBigDecimalOrNull()?.let { runCatching { it.longValueExact() }.getOrNull() }
        ?.takeIf { it in 0..SYNC_V1_MAX_SAFE_INTEGER }
    private fun JsonObject.nullableLong(name: String): Long? = if (get(name) === JsonNull.INSTANCE) null else long(name)
    private fun JsonObject.nullableString(name: String): String? = if (get(name) === JsonNull.INSTANCE) null else string(name)
}
