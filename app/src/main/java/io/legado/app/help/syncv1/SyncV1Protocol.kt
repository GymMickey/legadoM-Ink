package io.legado.app.help.syncv1

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal const val SYNC_V1_MAX_OBJECT_BYTES = 64 * 1024
internal const val SYNC_V1_MAX_BINDING_EVENT_BYTES = 256 * 1024
internal const val SYNC_V1_MAX_BINDING_CANDIDATES = 20
internal const val SYNC_V1_MAX_CATALOG_BYTES = 1024 * 1024
internal const val SYNC_V1_MAX_CATALOG_WORKS = 2500
internal const val SYNC_V1_MAX_CHAPTERS_BYTES = 2 * 1024 * 1024
internal const val SYNC_V1_MAX_CHAPTERS = 6000
internal const val SYNC_V1_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
internal const val SYNC_V1_CREATE_MODE = "uuid_verified"

internal data class ParsedRegistration(
    val deviceId: String,
    val counterEpoch: String,
    val createdAt: String,
    val supportedCreateModes: List<String>,
    val locatorCapability: String
)

internal data class ParsedDeviceState(
    val deviceId: String,
    val stateRevision: Long,
    val snapshotId: String,
    val status: String,
    val registrationResult: String,
    val createMode: String?,
    val locatorCapability: String?,
    val registrationPayloadSha256: String,
    val generatedAt: String
)

internal data class ParsedCatalogWork(
    val workId: String,
    val title: String,
    val authors: List<String>,
    val bookUrlAliases: List<String>,
    val tocRevision: Long,
    val progressRevision: Long,
    val updatedAt: String
)

internal data class ParsedCatalog(
    val deviceId: String,
    val catalogRevision: Long,
    val snapshotId: String,
    val generatedAt: String,
    val works: List<ParsedCatalogWork>
)

internal sealed interface RegistrationValidation {
    val deviceId: String
    val payloadSha256: String

    data class Valid(
        override val deviceId: String,
        override val payloadSha256: String,
        val registration: ParsedRegistration
    ) : RegistrationValidation

    data class Rejected(
        override val deviceId: String,
        override val payloadSha256: String,
        val result: String,
        val errorCode: SyncV1ErrorCode,
        val locatorCapability: String? = null,
        val supportedCreateModes: List<String> = emptyList()
    ) : RegistrationValidation
}

internal object SyncV1Protocol {
    private val uuidV4Pattern = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
    )
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")
    private val utcDateTimePattern = Regex(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$"
    )
    private val registrationKeys = setOf(
        "protocol_version",
        "schema_version",
        "normalization_version",
        "device_id",
        "created_at",
        "client",
        "counter_epoch",
        "supported_create_modes",
        "locator_capability"
    )
    private val clientKeys = setOf("kind", "name", "version")
    private val deviceStateKeys = setOf(
        "protocol_version",
        "schema_version",
        "normalization_version",
        "device_id",
        "state_revision",
        "snapshot_id",
        "status",
        "registration_result",
        "create_mode",
        "locator_capability",
        "registration_payload_sha256",
        "generated_at"
    )
    private val catalogKeys = setOf(
        "protocol_version",
        "schema_version",
        "normalization_version",
        "device_id",
        "catalog_revision",
        "snapshot_id",
        "generated_at",
        "works"
    )
    private val catalogWorkKeys = setOf(
        "work_id",
        "title",
        "authors",
        "book_url_aliases",
        "toc_revision",
        "progress_revision",
        "updated_at"
    )
    private val chaptersKeys = setOf(
        "protocol_version", "schema_version", "device_id", "work_id", "toc_revision",
        "snapshot_id", "generated_at", "chapters"
    )
    private val chapterKeys = setOf("chapter_key", "title", "local_toc_index")
    private val bindingRequestRequiredKeys = setOf(
        "protocol_version", "schema_version", "normalization_version", "event_type", "event_id",
        "device_id", "counter_epoch", "sequence", "created_at", "content_id", "title", "author",
        "local_toc_count"
    )
    private val bindingRequestAllowedKeys = bindingRequestRequiredKeys + setOf(
        "toc_candidates", "selected_work_id", "selection_from_event_id"
    )
    private val registrationResults = setOf(
        "registration_verified",
        "registration_recovered",
        "device_id_conflict",
        "registration_parent_missing",
        "unsupported_mode",
        "invalid_registration"
    )

    fun isUuidV4(value: String): Boolean = uuidV4Pattern.matches(value)

    fun eventType(rawBytes: ByteArray): String? = try {
        (parseStrictJson(rawBytes) as? JsonObject)?.string("event_type")
    } catch (_: Exception) {
        null
    }

    fun parseBindingRequest(
        pathDeviceId: String,
        pathEventId: String,
        rawBytes: ByteArray
    ): SyncV1BindingRequestParseResult {
        if (rawBytes.size > SYNC_V1_MAX_BINDING_EVENT_BYTES) {
            return SyncV1BindingRequestParseResult.Invalid(
                errorCode = SyncV1ErrorCode.SIZE_EXCEEDED,
                reasonCode = "unsupported_schema",
                eventType = null,
                deviceId = null,
                counterEpoch = null,
                sequence = null,
                contentId = null,
                title = null,
                author = null
            )
        }
        var root: JsonObject? = null
        try {
            root = parseStrictJson(rawBytes) as? JsonObject
        } catch (_: Exception) {
            // Malformed JSON is an invalid binding event; the immutable event path still
            // supplies the identifiers needed to publish a terminal rejected acknowledgement.
        }
        val event = root
        val type = event?.string("event_type")
        if (type != null && type != "binding_request") {
            return SyncV1BindingRequestParseResult.NotBindingRequest
        }
        fun invalid(
            code: SyncV1ErrorCode,
            reason: String,
            value: JsonObject? = event
        ) = SyncV1BindingRequestParseResult.Invalid(
            errorCode = code,
            reasonCode = reason,
            eventType = value?.string("event_type"),
            deviceId = value?.string("device_id"),
            counterEpoch = value?.string("counter_epoch"),
            sequence = value?.safeLong("sequence"),
            contentId = value?.string("content_id"),
            title = value?.string("title"),
            author = value?.string("author")
        )

        if (event == null) return invalid(SyncV1ErrorCode.INVALID_JSON, "unsupported_schema", null)
        if (!event.keySet().containsAll(bindingRequestRequiredKeys)
            || event.keySet().any { it !in bindingRequestAllowedKeys }
        ) return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
        val hasSelectedWork = event.has("selected_work_id")
        val hasSelectionSource = event.has("selection_from_event_id")
        if (hasSelectedWork != hasSelectionSource) {
            return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
        }
        if (event.string("protocol_version") != "sync-v1"
            || event.string("schema_version") != "1.0.0"
            || event.string("normalization_version") != "normalization-v1"
            || type != "binding_request"
        ) return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")

        val eventId = event.string("event_id")?.takeIf(::isUuidV4)
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val deviceId = event.string("device_id")?.takeIf(::isUuidV4)
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val counterEpoch = event.string("counter_epoch")?.takeIf(::isUuidV4)
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        if (eventId != pathEventId || deviceId != pathDeviceId
            || !isUuidV4(pathEventId) || !isUuidV4(pathDeviceId)
        ) return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")

        val sequence = event.safeLong("sequence")
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val createdAt = event.string("created_at")?.takeIf(::isUtcDateTime)
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val contentId = event.string("content_id")?.takeIf { isValidScalarString(it, 1, 512) }
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val title = event.string("title")?.takeIf { isValidScalarString(it, 1, 512) }
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val author = event.string("author")?.takeIf { isValidScalarString(it, 0, 512) }
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        val localTocCount = event.safeLong("local_toc_count")
            ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")

        val tocCandidates = ArrayList<SyncV1BindingTocCandidate>()
        if (event.has("toc_candidates")) {
            val array = event.get("toc_candidates")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?: return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
            if (array.size() > SYNC_V1_MAX_BINDING_CANDIDATES) {
                return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
            }
            for (element in array) {
                val candidate = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
                if (candidate.keySet() != setOf("local_toc_index", "title")) {
                    return invalid(SyncV1ErrorCode.UNSUPPORTED_SCHEMA, "unsupported_schema")
                }
                val index = candidate.safeLong("local_toc_index")
                    ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
                val candidateTitle = candidate.string("title")
                    ?.takeIf { isValidScalarString(it, 1, 256) }
                    ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
                tocCandidates += SyncV1BindingTocCandidate(index, candidateTitle)
            }
        }

        val selectedWorkId = if (hasSelectedWork) {
            event.string("selected_work_id")?.takeIf(::isUuidV4)
                ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        } else null
        val selectionFromEventId = if (hasSelectionSource) {
            event.string("selection_from_event_id")?.takeIf(::isUuidV4)
                ?: return invalid(SyncV1ErrorCode.INVALID_EVENT, "invalid_event")
        } else null

        return SyncV1BindingRequestParseResult.Valid(
            ParsedSyncV1BindingRequest(
                eventId = eventId,
                deviceId = deviceId,
                counterEpoch = counterEpoch,
                sequence = sequence,
                createdAt = createdAt,
                contentId = contentId,
                title = title,
                author = author,
                localTocCount = localTocCount,
                tocCandidates = tocCandidates,
                selectedWorkId = selectedWorkId,
                selectionFromEventId = selectionFromEventId
            )
        )
    }

    private fun isValidScalarString(value: String, min: Int, max: Int): Boolean {
        var index = 0
        var count = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
            count++
            if (count > max) return false
        }
        return count >= min
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    fun parseRegistration(fileDeviceId: String, rawBytes: ByteArray): RegistrationValidation {
        val digest = sha256(rawBytes)
        val root = try {
            parseStrictJson(rawBytes) as? JsonObject
                ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        } catch (_: Exception) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_JSON)
        }
        val payloadDeviceId = root.string("device_id")
        if (payloadDeviceId == null || !isUuidV4(payloadDeviceId)) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        if (payloadDeviceId != fileDeviceId) {
            return RegistrationValidation.Rejected(
                deviceId = fileDeviceId,
                payloadSha256 = digest,
                result = "device_id_conflict",
                errorCode = SyncV1ErrorCode.DEVICE_ID_CONFLICT
            )
        }
        if (!root.keySet().containsAll(registrationKeys) || root.keySet() != registrationKeys) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }

        if (root.string("protocol_version") != "sync-v1"
            || root.string("schema_version") != "1.0.0"
            || root.string("normalization_version") != "normalization-v1"
        ) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        val counterEpoch = root.string("counter_epoch")
            ?.takeIf(::isUuidV4)
            ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        val createdAt = root.string("created_at")
            ?.takeIf(::isUtcDateTime)
            ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        val client = root.objectValue("client")
            ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        if (client.keySet() != clientKeys
            || client.string("kind") != "koreader"
            || !client.string("name").validLength(1, 80)
            || !client.string("version").validLength(1, 64)
        ) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        val modes = root.stringArray("supported_create_modes")
            ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        if (modes.isEmpty() || modes.size > 2 || modes.distinct().size != modes.size
            || modes.any { it !in setOf("uuid_verified", "conditional_create") }
        ) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        val locator = root.string("locator_capability")
            ?.takeIf { it == "chapter_only" || it == "character_exact" }
            ?: return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        if (SYNC_V1_CREATE_MODE !in modes) {
            return RegistrationValidation.Rejected(
                deviceId = fileDeviceId,
                payloadSha256 = digest,
                result = "unsupported_mode",
                errorCode = SyncV1ErrorCode.UNSUPPORTED_MODE,
                locatorCapability = locator,
                supportedCreateModes = modes
            )
        }
        if (modes.toSet() != setOf("uuid_verified", "conditional_create")) {
            return invalid(fileDeviceId, digest, SyncV1ErrorCode.INVALID_REGISTRATION)
        }
        return RegistrationValidation.Valid(
            deviceId = fileDeviceId,
            payloadSha256 = digest,
            registration = ParsedRegistration(
                deviceId = payloadDeviceId,
                counterEpoch = counterEpoch,
                createdAt = createdAt,
                supportedCreateModes = modes,
                locatorCapability = locator
            )
        )
    }

    fun parseDeviceState(rawBytes: ByteArray): ParsedDeviceState? {
        val root = try {
            parseStrictJson(rawBytes) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        if (root.keySet() != deviceStateKeys
            || root.string("protocol_version") != "sync-v1"
            || root.string("schema_version") != "1.0.0"
            || root.string("normalization_version") != "normalization-v1"
        ) return null

        val deviceId = root.string("device_id")?.takeIf(::isUuidV4) ?: return null
        val stateRevision = root.safeLong("state_revision") ?: return null
        val snapshotId = root.string("snapshot_id")?.takeIf(::isUuidV4) ?: return null
        val status = root.string("status")?.takeIf { it == "ready" || it == "rejected" } ?: return null
        val result = root.string("registration_result")?.takeIf { it in registrationResults } ?: return null
        val createModeValue = root.nullableString("create_mode") ?: return null
        val locatorValue = root.nullableString("locator_capability") ?: return null
        val createMode = createModeValue.value
        val locator = locatorValue.value
        if (createMode != null && createMode !in setOf("uuid_verified", "conditional_create")) return null
        if (locator != null && locator !in setOf("chapter_only", "character_exact")) return null
        val registrationHash = root.string("registration_payload_sha256")
            ?.takeIf(sha256Pattern::matches) ?: return null
        val generatedAt = root.string("generated_at")?.takeIf(::isUtcDateTime) ?: return null

        if (status == "ready") {
            if (result !in setOf("registration_verified", "registration_recovered")
                || createMode !in setOf("uuid_verified", "conditional_create")
                || locator !in setOf("chapter_only", "character_exact")
            ) return null
        } else {
            if (result !in setOf(
                    "device_id_conflict",
                    "registration_parent_missing",
                    "unsupported_mode",
                    "invalid_registration"
                ) || createMode != null
            ) return null
            if (result == "invalid_registration" && locator != null) return null
        }
        return ParsedDeviceState(
            deviceId,
            stateRevision,
            snapshotId,
            status,
            result,
            createMode,
            locator,
            registrationHash,
            generatedAt
        )
    }

    fun parseCatalog(rawBytes: ByteArray): ParsedCatalog? {
        if (rawBytes.size > SYNC_V1_MAX_CATALOG_BYTES) return null
        val root = try {
            parseStrictJson(rawBytes) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        if (root.keySet() != catalogKeys
            || root.string("protocol_version") != "sync-v1"
            || root.string("schema_version") != "1.0.0"
            || root.string("normalization_version") != "normalization-v1"
        ) return null
        val deviceId = root.string("device_id")?.takeIf(::isUuidV4) ?: return null
        val revision = root.safeLong("catalog_revision") ?: return null
        val snapshotId = root.string("snapshot_id")?.takeIf(::isUuidV4) ?: return null
        val generatedAt = root.string("generated_at")?.takeIf(::isUtcDateTime) ?: return null
        val worksJson = root.get("works")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (worksJson.size() > SYNC_V1_MAX_CATALOG_WORKS) return null
        val workIds = HashSet<String>(worksJson.size())
        val works = ArrayList<ParsedCatalogWork>(worksJson.size())
        for (element in worksJson) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (obj.keySet() != catalogWorkKeys) return null
            val workId = obj.string("work_id")?.takeIf(::isUuidV4) ?: return null
            if (!workIds.add(workId)) return null
            val title = obj.string("title") ?: return null
            if (title.codePointCount(0, title.length) !in 1..512) return null
            val authors = obj.stringArray("authors") ?: return null
            if (authors.size > 10 || authors.any { it.codePointCount(0, it.length) > 256 }) return null
            val aliases = obj.stringArray("book_url_aliases") ?: return null
            if (aliases.size > 64 || aliases.distinct().size != aliases.size
                || aliases.any { !sha256Pattern.matches(it) }
            ) return null
            val tocRevision = obj.safeLong("toc_revision") ?: return null
            val progressRevision = obj.safeLong("progress_revision") ?: return null
            val updatedAt = obj.string("updated_at")?.takeIf(::isUtcDateTime) ?: return null
            works += ParsedCatalogWork(
                workId, title, authors, aliases, tocRevision, progressRevision, updatedAt
            )
        }
        return ParsedCatalog(deviceId, revision, snapshotId, generatedAt, works)
    }

    fun parseChapters(rawBytes: ByteArray): ParsedChapters? {
        if (rawBytes.size > SYNC_V1_MAX_CHAPTERS_BYTES) return null
        val root = try {
            parseStrictJson(rawBytes) as? JsonObject ?: return null
        } catch (_: Exception) {
            return null
        }
        if (root.keySet() != chaptersKeys
            || root.string("protocol_version") != "sync-v1"
            || root.string("schema_version") != "1.0.0"
        ) return null
        val deviceId = root.string("device_id")?.takeIf(::isUuidV4) ?: return null
        val workId = root.string("work_id")?.takeIf(::isUuidV4) ?: return null
        val revision = root.safeLong("toc_revision")?.takeIf { it > 0L } ?: return null
        val snapshotId = root.string("snapshot_id")?.takeIf(::isUuidV4) ?: return null
        val generatedAt = root.string("generated_at")?.takeIf(::isUtcDateTime) ?: return null
        val chapterArray = root.get("chapters")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (chapterArray.size() > SYNC_V1_MAX_CHAPTERS) return null
        val result = ArrayList<SyncV1ChapterRecord>(chapterArray.size())
        val seenKeys = HashSet<String>(chapterArray.size())
        var previousIndex = -1L
        for (element in chapterArray) {
            val chapter = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (chapter.keySet() != chapterKeys) return null
            val key = chapter.string("chapter_key")
                ?.takeIf { Regex("^ck1-[a-f0-9]{64}$").matches(it) } ?: return null
            val title = chapter.string("title") ?: return null
            if (!isValidChapterTitle(title)) return null
            val index = chapter.safeLong("local_toc_index") ?: return null
            if (index <= previousIndex || !seenKeys.add(key) || key != chapterKey(workId, index, title)) {
                return null
            }
            previousIndex = index
            result += SyncV1ChapterRecord(key, title, index)
        }
        return ParsedChapters(deviceId, workId, revision, snapshotId, generatedAt, result)
    }

    fun chapterKey(workId: String, localTocIndex: Long, rawTitle: String): String {
        require(isUuidV4(workId) && localTocIndex in 0..SYNC_V1_MAX_SAFE_INTEGER && isValidChapterTitle(rawTitle))
        val input = "chapter-key-v1\u001f$workId\u001f$localTocIndex\u001f$rawTitle"
        return "ck1-${sha256(input.toByteArray(Charsets.UTF_8))}"
    }

    fun isValidChapterTitle(title: String): Boolean {
        if (title.isEmpty()) return false
        var index = 0
        var codePoints = 0
        while (index < title.length) {
            val character = title[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= title.length || !Character.isLowSurrogate(title[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index++
            }
            codePoints++
            if (codePoints > 512) return false
        }
        return true
    }

    private fun invalid(
        deviceId: String,
        digest: String,
        code: SyncV1ErrorCode
    ) = RegistrationValidation.Rejected(
        deviceId = deviceId,
        payloadSha256 = digest,
        result = "invalid_registration",
        errorCode = code
    )

    private fun isUtcDateTime(value: String): Boolean {
        if (!utcDateTimePattern.matches(value)) return false
        return try {
            Instant.parse(value)
            true
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun parseStrictJson(bytes: ByteArray): JsonElement {
        val decoded = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val reader = JsonReader(StringReader(decoded)).apply { setStrictness(Strictness.STRICT) }
        val result = readElement(reader)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return result
    }

    private fun readElement(reader: JsonReader): JsonElement = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JsonObject().also { obj ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(!obj.has(name)) { "duplicate_json_key" }
                obj.add(name, readElement(reader))
            }
            reader.endObject()
        }

        JsonToken.BEGIN_ARRAY -> JsonArray().also { array ->
            reader.beginArray()
            while (reader.hasNext()) array.add(readElement(reader))
            reader.endArray()
        }

        JsonToken.STRING -> JsonPrimitive(reader.nextString())
        JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> JsonNull.INSTANCE.also { reader.nextNull() }
        else -> error("invalid_json_token")
    }

    private fun JsonObject.string(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.objectValue(name: String): JsonObject? = get(name)
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject

    private fun JsonObject.stringArray(name: String): List<String>? {
        val element = get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val result = ArrayList<String>(element.size())
        for (value in element) {
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
            result += value.asString
        }
        return result
    }

    private data class NullableStringValue(val value: String?)

    /** null means absent or wrong type; a wrapper with a null value means JSON null. */
    private fun JsonObject.nullableString(name: String): NullableStringValue? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return NullableStringValue(null)
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return NullableStringValue(element.asString)
    }

    private fun JsonObject.safeLong(name: String): Long? {
        val element = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
        val value = try {
            element.asBigDecimal.toBigIntegerExact().longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return value.takeIf { it in 0..SYNC_V1_MAX_SAFE_INTEGER }
    }

    private fun String?.validLength(min: Int, max: Int): Boolean {
        if (this == null) return false
        val length = codePointCount(0, this.length)
        return length in min..max
    }
}
