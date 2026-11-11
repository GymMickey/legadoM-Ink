package io.legado.app.help.syncv1

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.net.ssl.SSLException

internal data class SyncV1CatalogBook(
    val bookUrl: String,
    val title: String,
    val author: String
) {
    override fun toString(): String = "SyncV1CatalogBook(title=$title, bookUrl=[redacted])"
}

enum class SyncV1CatalogPhase { PUBLISHED, UNCHANGED, FAILED }

data class SyncV1CatalogOutcome(
    val phase: SyncV1CatalogPhase,
    val workCount: Int = 0,
    val catalogRevision: Long? = null,
    val snapshotId: String? = null,
    val errorCode: SyncV1ErrorCode? = null
)

internal class SyncV1CatalogRepository(
    private val store: SyncV1CatalogStore,
    private val sessionProvider: suspend () -> SyncV1Session,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> UUID = UUID::randomUUID
) {
    private val operationMutex = Mutex()
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    suspend fun publish(books: List<SyncV1CatalogBook>): SyncV1CatalogOutcome = operationMutex.withLock {
        try {
            if (!store.hasReadyDevice()) {
                return@withLock SyncV1CatalogOutcome(
                    SyncV1CatalogPhase.FAILED,
                    errorCode = SyncV1ErrorCode.NO_READY_DEVICE
                )
            }
            val session = sessionProvider()
            requireCurrent(session)
            session.transport.ensureDirectory(CATALOG_DIRECTORY)
            requireCurrent(session)

            val pending = store.pendingCatalogIntent(session.serviceIdentity, session.rootIdentity)
            if (pending != null) {
                validateIntent(session, pending)
                val remote = scanRemoteCatalogs(session)
                checkNoRevisionConflict(remote, pending.catalogRevision, pending.snapshotId.toString())
                publishIntent(session, pending)
                return@withLock SyncV1CatalogOutcome(
                    SyncV1CatalogPhase.PUBLISHED,
                    parseCatalogOrFail(pending.rawJsonBytes).works.size,
                    pending.catalogRevision,
                    pending.snapshotId.toString()
                )
            }

            val remoteCatalog = scanRemoteCatalogs(session)
            val latestRemote = remoteCatalog.latest
            val aliasOwners = latestRemote?.catalog?.works.orEmpty()
                .flatMap { work -> work.bookUrlAliases.map { it to work.workId } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }

            val publisherDeviceId = store.androidPublisherDeviceId(uuidProvider)
            val semanticWorks = resolveAndBuildWorks(books, aliasOwners, latestRemote?.catalog)
            val semanticSha = catalogSemanticSha256(publisherDeviceId, semanticWorks)
            val persisted = store.catalogPublicationState(session.serviceIdentity, session.rootIdentity)
            val previousPublished = store.latestPublishedCatalogIntent(session.serviceIdentity, session.rootIdentity)

            if (persisted?.semanticSha256 == semanticSha && previousPublished != null) {
                validateIntent(session, previousPublished)
                val saved = parseCatalogOrFail(previousPublished.rawJsonBytes)
                if (saved.catalogRevision == persisted.catalogRevision
                    && saved.snapshotId == persisted.snapshotId.toString()
                    && SyncV1Protocol.sha256(previousPublished.rawJsonBytes) == previousPublished.rawSha256
                ) {
                    return@withLock SyncV1CatalogOutcome(
                        SyncV1CatalogPhase.UNCHANGED,
                        semanticWorks.size,
                        persisted.catalogRevision,
                        persisted.snapshotId.toString()
                    )
                }
            }

            if (latestRemote != null
                && latestRemote.catalog.deviceId == publisherDeviceId
                && latestRemote.semanticSha256 == semanticSha
            ) {
                val adopted = SyncV1CatalogPublicationIntent(
                    catalogRevision = latestRemote.catalog.catalogRevision,
                    snapshotId = UUID.fromString(latestRemote.catalog.snapshotId),
                    remotePath = latestRemote.path,
                    rawJsonBytes = latestRemote.rawBytes,
                    rawSha256 = latestRemote.rawSha256,
                    semanticSha256 = latestRemote.semanticSha256,
                    putAttemptCount = 0,
                    configIdentity = session.configIdentity,
                    serviceIdentity = session.serviceIdentity,
                    rootIdentity = session.rootIdentity,
                    publicationStatus = "pending",
                    createdAt = nowMillis(),
                    updatedAt = nowMillis()
                )
                val durable = store.createCatalogIntentIfNoPending(adopted)
                validateIntent(session, durable)
                publishIntent(session, durable)
                return@withLock SyncV1CatalogOutcome(
                    SyncV1CatalogPhase.UNCHANGED,
                    semanticWorks.size,
                    durable.catalogRevision,
                    durable.snapshotId.toString()
                )
            }

            val nextRevision = maxOf(
                persisted?.catalogRevision ?: 0L,
                latestRemote?.catalog?.catalogRevision ?: 0L
            ) + 1
            if (nextRevision > SYNC_V1_MAX_SAFE_INTEGER) {
                throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
            }
            val snapshotId = uuidProvider()
            if (!SyncV1Protocol.isUuidV4(snapshotId.toString())) {
                throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
            }
            val generatedAt = Instant.ofEpochMilli(nowMillis()).toString()
            val raw = serializeCatalog(
                publisherDeviceId = publisherDeviceId,
                revision = nextRevision,
                snapshotId = snapshotId.toString(),
                generatedAt = generatedAt,
                works = semanticWorks
            )
            if (raw.size > SYNC_V1_MAX_CATALOG_BYTES) {
                throw SyncV1Failure(SyncV1ErrorCode.CATALOG_TOO_LARGE)
            }
            val path = "$CATALOG_DIRECTORY$nextRevision-${snapshotId}.json"
            val intent = SyncV1CatalogPublicationIntent(
                catalogRevision = nextRevision,
                snapshotId = snapshotId,
                remotePath = path,
                rawJsonBytes = raw,
                rawSha256 = SyncV1Protocol.sha256(raw),
                semanticSha256 = semanticSha,
                putAttemptCount = 0,
                configIdentity = session.configIdentity,
                serviceIdentity = session.serviceIdentity,
                rootIdentity = session.rootIdentity,
                publicationStatus = "pending",
                createdAt = nowMillis(),
                updatedAt = nowMillis()
            )
            val durable = store.createCatalogIntentIfNoPending(intent)
            validateIntent(session, durable)
            checkNoRevisionConflict(remoteCatalog, durable.catalogRevision, durable.snapshotId.toString())
            publishIntent(session, durable)
            SyncV1CatalogOutcome(
                SyncV1CatalogPhase.PUBLISHED,
                semanticWorks.size,
                durable.catalogRevision,
                durable.snapshotId.toString()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SyncV1CatalogOutcome(
                phase = SyncV1CatalogPhase.FAILED,
                errorCode = failure.asCatalogError()
            )
        }
    }

    private suspend fun resolveAndBuildWorks(
        books: List<SyncV1CatalogBook>,
        aliasOwners: Map<String, Set<String>>,
        remoteCatalog: ParsedCatalog?
    ): List<CatalogWork> {
        val uniqueBooks = books.distinctBy { it.bookUrl }
        val identityRepository = SyncV1WorkIdentityRepository(store, uuidProvider)
        val byWork = linkedMapOf<String, SyncV1CatalogBook>()
        for (book in uniqueBooks) {
            val resolution = identityRepository.resolve(book.bookUrl, aliasOwners)
            val identity = (resolution as? SyncV1WorkIdentityResolution.Resolved)?.identity
                ?: throw SyncV1Failure(SyncV1ErrorCode.IDENTITY_CONFLICT)
            val previous = byWork.putIfAbsent(identity.workId, book)
            if (previous != null && (previous.title != book.title || previous.author != book.author)) {
                throw SyncV1Failure(SyncV1ErrorCode.IDENTITY_CONFLICT)
            }
        }
        if (byWork.size > SYNC_V1_MAX_CATALOG_WORKS) {
            throw SyncV1Failure(SyncV1ErrorCode.CATALOG_WORK_LIMIT)
        }

        val aliasHashes = store.workAliases().groupBy({ it.workId }, { it.bookUrl })
            .mapValues { (_, urls) -> urls.mapNotNull(SyncV1BookUrlAlias::digestIfSafe).distinct().sorted() }
        val remoteWorks = remoteCatalog?.works?.associateBy { it.workId }.orEmpty()
        val output = ArrayList<CatalogWork>(byWork.size)
        for ((workId, book) in byWork.toSortedMap()) {
            val title = book.title
            val authors = book.author.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            if (title.codePointCount(0, title.length) !in 1..512
                || authors.any { it.codePointCount(0, it.length) > 256 }
            ) throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
            val aliases = aliasHashes[workId].orEmpty()
            if (aliases.size > 64) throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)
            val identity = store.resolveWorkIdentity(book.bookUrl, emptySet(), uuidProvider)
                .let { (it as? SyncV1WorkIdentityResolution.Resolved)?.identity }
                ?: throw SyncV1Failure(SyncV1ErrorCode.IDENTITY_CONFLICT)
            val tocRevision = identity.tocRevision
            val progressRevision = identity.progressRevision
            val fieldsHash = workFieldsSha256(workId, title, authors, aliases, tocRevision, progressRevision)
            val matchingRemote = remoteWorks[workId]?.takeIf {
                workFieldsSha256(
                    it.workId, it.title, it.authors, it.bookUrlAliases,
                    it.tocRevision, it.progressRevision
                ) == fieldsHash
            }
            val remoteUpdatedAt = matchingRemote?.updatedAt?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            val stable = store.updateWorkCatalogFields(identity, fieldsHash, nowMillis(), remoteUpdatedAt)
            output += CatalogWork(
                workId,
                title,
                authors,
                aliases,
                tocRevision,
                progressRevision,
                Instant.ofEpochMilli(stable.updatedAt).toString()
            )
        }
        return output
    }

    private suspend fun scanRemoteCatalogs(session: SyncV1Session): RemoteCatalogScan {
        requireCurrent(session)
        val entries = session.transport.listDirectChildren(CATALOG_DIRECTORY)
        requireCurrent(session)
        val files = entries.asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { entry -> parseCatalogPath(entry.name) }
            .distinctBy { it.path }
            .toList()
        val revisions = files.groupBy { it.revision }
        if (revisions.values.any { group -> group.map { it.snapshotId }.distinct().size > 1 }) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
        for (file in files.sortedByDescending { it.revision }) {
            val response = session.transport.get(file.path, SYNC_V1_MAX_CATALOG_BYTES, computeFullSha256 = true)
            requireCurrent(session)
            if (response.statusCode == 404) continue
            if (response.statusCode !in 200..299) throw httpFailure(response.statusCode)
            if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_CATALOG_BYTES) continue
            val digest = SyncV1Protocol.sha256(response.body)
            if (response.rawSha256 != null && response.rawSha256 != digest) continue
            val catalog = SyncV1Protocol.parseCatalog(response.body) ?: continue
            if (catalog.catalogRevision != file.revision || catalog.snapshotId != file.snapshotId) continue
            return RemoteCatalogScan(
                latest = RemoteCatalog(file.path, response.body.copyOf(), digest, catalog, catalogSemanticSha256(catalog)),
                listed = files
            )
        }
        return RemoteCatalogScan(null, files)
    }

    private suspend fun publishIntent(session: SyncV1Session, intent: SyncV1CatalogPublicationIntent) {
        val catalog = parseCatalogOrFail(intent.rawJsonBytes)
        validateIntent(session, intent)
        val expectedPath = "$CATALOG_DIRECTORY${intent.catalogRevision}-${intent.snapshotId}.json"
        if (intent.remotePath != expectedPath
            || catalog.catalogRevision != intent.catalogRevision
            || catalog.snapshotId != intent.snapshotId.toString()
            || SyncV1Protocol.sha256(intent.rawJsonBytes) != intent.rawSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)

        var currentIntent = intent
        while (true) {
            requireCurrent(session)
            val existing = session.transport.get(currentIntent.remotePath, SYNC_V1_MAX_CATALOG_BYTES, computeFullSha256 = true)
            requireCurrent(session)
            when {
                existing.statusCode == 200 -> {
                    if (!sameObject(currentIntent.rawJsonBytes, currentIntent.rawSha256, existing)) {
                        throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                    }
                    store.markCatalogIntentPublished(currentIntent)
                    return
                }
                existing.statusCode != 404 -> throw httpFailure(existing.statusCode)
            }
            if (currentIntent.putAttemptCount >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            store.incrementCatalogPutAttempt(currentIntent, nowMillis())
            var putError: Throwable? = null
            try {
                val response = session.transport.put(currentIntent.remotePath, currentIntent.rawJsonBytes.copyOf())
                requireCurrent(session)
                if (response.statusCode !in 200..299) {
                    val failure = httpFailure(response.statusCode)
                    if (response.statusCode in 400..499 && response.statusCode != 409) throw failure
                    putError = failure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (error is SyncV1Failure && error.errorCode in setOf(
                        SyncV1ErrorCode.CONFIG_CHANGED,
                        SyncV1ErrorCode.AUTHENTICATION_FAILED,
                        SyncV1ErrorCode.HTTP_CLIENT_ERROR,
                        SyncV1ErrorCode.HTTP_NOT_FOUND
                    )
                ) throw error
                putError = error
            }

            // A PUT result may be unknown. Always resolve it with GET before any retry.
            val readback = try {
                session.transport.get(currentIntent.remotePath, SYNC_V1_MAX_CATALOG_BYTES, computeFullSha256 = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (putError != null) throw putError.asCatalogException()
                throw error
            }
            requireCurrent(session)
            when {
                readback.statusCode == 200 && sameObject(currentIntent.rawJsonBytes, currentIntent.rawSha256, readback) -> {
                    store.markCatalogIntentPublished(currentIntent)
                    return
                }
                readback.statusCode == 200 -> throw SyncV1Failure(SyncV1ErrorCode.READBACK_MISMATCH)
                readback.statusCode == 404 && putError == null -> {
                    if (currentIntent.putAttemptCount + 1 >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                }
                readback.statusCode == 404 -> {
                    if (currentIntent.putAttemptCount + 1 >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                }
                else -> throw httpFailure(readback.statusCode)
            }
            // Refresh the persisted retry count before the next GET/PUT cycle.
            currentIntent = store.pendingCatalogIntent(session.serviceIdentity, session.rootIdentity)
                ?: throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            if (currentIntent.putAttemptCount >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
        }
    }

    private fun validateIntent(session: SyncV1Session, intent: SyncV1CatalogPublicationIntent) {
        if (intent.configIdentity != session.configIdentity
            || intent.serviceIdentity != session.serviceIdentity
            || intent.rootIdentity != session.rootIdentity
            || !session.isCurrent()
        ) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun checkNoRevisionConflict(scan: RemoteCatalogScan, revision: Long, snapshotId: String) {
        if (scan.listed.any { it.revision == revision && it.snapshotId != snapshotId }) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
    }

    private fun sameObject(expected: ByteArray, expectedSha: String, response: SyncV1HttpResponse): Boolean {
        if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_CATALOG_BYTES) return false
        val actualSha = SyncV1Protocol.sha256(response.body)
        return response.body.contentEquals(expected)
            && actualSha == expectedSha
            && (response.rawSha256 == null || response.rawSha256 == actualSha)
    }

    private fun serializeCatalog(
        publisherDeviceId: String,
        revision: Long,
        snapshotId: String,
        generatedAt: String,
        works: List<CatalogWork>
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("normalization_version", "normalization-v1")
            addProperty("device_id", publisherDeviceId)
            addProperty("catalog_revision", revision)
            addProperty("snapshot_id", snapshotId)
            addProperty("generated_at", generatedAt)
            add("works", JsonArray().apply { works.forEach { add(it.toJson()) } })
        }
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private fun CatalogWork.toJson() = JsonObject().apply {
        addProperty("work_id", workId)
        addProperty("title", title)
        add("authors", JsonArray().apply { authors.forEach(::add) })
        add("book_url_aliases", JsonArray().apply { aliases.forEach(::add) })
        addProperty("toc_revision", tocRevision)
        addProperty("progress_revision", progressRevision)
        addProperty("updated_at", updatedAt)
    }

    private fun catalogSemanticSha256(deviceId: String, works: List<CatalogWork>): String {
        val fields = buildList {
            add("sync-v1")
            add("1.0.0")
            add("normalization-v1")
            add(deviceId)
            works.forEach { work ->
                add(work.workId)
                add(work.title)
                add(work.authors.size.toString())
                addAll(work.authors)
                add(work.aliases.size.toString())
                addAll(work.aliases)
                add(work.tocRevision.toString())
                add(work.progressRevision.toString())
                add(work.updatedAt)
            }
        }
        return sha256LengthPrefixed(fields)
    }

    private fun catalogSemanticSha256(catalog: ParsedCatalog): String =
        catalogSemanticSha256(
            catalog.deviceId,
            catalog.works.map {
                CatalogWork(
                    it.workId, it.title, it.authors, it.bookUrlAliases,
                    it.tocRevision, it.progressRevision, it.updatedAt
                )
            }
        )

    private fun workFieldsSha256(
        workId: String,
        title: String,
        authors: List<String>,
        aliases: List<String>,
        tocRevision: Long,
        progressRevision: Long
    ): String = sha256LengthPrefixed(buildList {
        add(workId)
        add(title)
        add(authors.size.toString())
        addAll(authors)
        add(aliases.size.toString())
        addAll(aliases)
        add(tocRevision.toString())
        add(progressRevision.toString())
    })

    private fun sha256LengthPrefixed(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseCatalogPath(fileName: String): CatalogPath? {
        val match = CATALOG_FILE_PATTERN.matchEntire(fileName) ?: return null
        val revision = match.groupValues[1].toLongOrNull()
            ?.takeIf { it in 0..SYNC_V1_MAX_SAFE_INTEGER } ?: return null
        val snapshot = match.groupValues[2]
        if (!SyncV1Protocol.isUuidV4(snapshot)) return null
        return CatalogPath(revision, snapshot, "$CATALOG_DIRECTORY$fileName")
    }

    private fun parseCatalogOrFail(bytes: ByteArray): ParsedCatalog =
        SyncV1Protocol.parseCatalog(bytes) ?: throw SyncV1Failure(SyncV1ErrorCode.CATALOG_INVALID)

    private fun httpFailure(status: Int): SyncV1Failure = when (status) {
        401, 403 -> SyncV1Failure(SyncV1ErrorCode.AUTHENTICATION_FAILED)
        404 -> SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
        409 -> SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
        in 500..599 -> SyncV1Failure(SyncV1ErrorCode.HTTP_SERVER_ERROR)
        else -> SyncV1Failure(SyncV1ErrorCode.HTTP_CLIENT_ERROR)
    }

    private fun Throwable.asCatalogException(): Exception =
        if (this is Exception) this else IOException("catalog_put_failed")

    private fun Throwable.asCatalogError(): SyncV1ErrorCode = when (this) {
        is SyncV1Failure -> errorCode
        is SocketTimeoutException -> SyncV1ErrorCode.TIMEOUT
        is UnknownHostException -> SyncV1ErrorCode.DNS_ERROR
        is SSLException -> SyncV1ErrorCode.TLS_ERROR
        is IOException -> SyncV1ErrorCode.NETWORK_UNKNOWN
        else -> SyncV1ErrorCode.NETWORK_UNKNOWN
    }

    private fun requireCurrent(session: SyncV1Session) {
        if (!session.isCurrent()) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private data class CatalogWork(
        val workId: String,
        val title: String,
        val authors: List<String>,
        val aliases: List<String>,
        val tocRevision: Long,
        val progressRevision: Long,
        val updatedAt: String
    )

    private data class CatalogPath(val revision: Long, val snapshotId: String, val path: String)
    private data class RemoteCatalog(
        val path: String,
        val rawBytes: ByteArray,
        val rawSha256: String,
        val catalog: ParsedCatalog,
        val semanticSha256: String
    )
    private data class RemoteCatalogScan(val latest: RemoteCatalog?, val listed: List<CatalogPath>)

    private companion object {
        const val CATALOG_DIRECTORY = "legadom_ink_sync/v1/android/catalog/"
        val CATALOG_FILE_PATTERN = Regex("^([0-9]+)-([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\\.json$")
    }
}
