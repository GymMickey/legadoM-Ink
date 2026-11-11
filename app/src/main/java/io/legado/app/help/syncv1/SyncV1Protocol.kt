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
internal const val SYNC_V1_MAX_CATALOG_BYTES = 1024 * 1024
internal const val SYNC_V1_MAX_CATALOG_WORKS = 2500
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
    private val registrationResults = setOf(
        "registration_verified",
        "registration_recovered",
        "device_id_conflict",
        "registration_parent_missing",
        "unsupported_mode",
        "invalid_registration"
    )

    fun isUuidV4(value: String): Boolean = uuidV4Pattern.matches(value)

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
