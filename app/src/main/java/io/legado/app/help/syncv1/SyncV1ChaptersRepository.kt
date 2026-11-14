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

internal object SyncV1ChapterSourceClassifier {
    fun fromPersistedDirectory(
        persistedTotalCount: Int,
        chapters: List<SyncV1ChapterInput>
    ): SyncV1ChapterSourceResult = when {
        chapters.isEmpty() -> SyncV1ChapterSourceResult(SyncV1ChapterSourceState.UNAVAILABLE)
        persistedTotalCount <= 0 || chapters.size != persistedTotalCount ->
            SyncV1ChapterSourceResult(SyncV1ChapterSourceState.UNAVAILABLE)
        else -> SyncV1ChapterSourceResult(SyncV1ChapterSourceState.AVAILABLE, chapters)
    }
}

internal class SyncV1ChaptersRepository(
    private val catalogStore: SyncV1CatalogStore,
    private val chaptersStore: SyncV1ChaptersStore,
    private val sessionProvider: suspend () -> SyncV1Session,
    private val catalogRepository: SyncV1CatalogRepository,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val uuidProvider: () -> UUID = UUID::randomUUID
) {
    private val operationMutex = PROCESS_OPERATION_MUTEX
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    suspend fun publish(inputs: List<SyncV1ChapterWorkInput>): SyncV1ChaptersOutcome =
        publish { inputs }

    suspend fun publish(
        inputProvider: suspend () -> List<SyncV1ChapterWorkInput>
    ): SyncV1ChaptersOutcome = operationMutex.withLock {
        var inputs: List<SyncV1ChapterWorkInput> = emptyList()
        var counts: Map<SyncV1ChapterSourceState, Int> = emptyMap()
        val outcomes = linkedMapOf<String, SyncV1ChapterWorkOutcome>()
        var published = 0
        var adopted = 0
        var unchanged = 0
        var catalogPublished = false
        var catalogError: SyncV1ErrorCode? = null
        try {
            if (!catalogStore.hasReadyDevice()) {
                return@withLock outcome(
                    inputs, counts, outcomes, published, adopted, unchanged, false,
                    SyncV1ErrorCode.NO_READY_DEVICE
                )
            }
            val session = sessionProvider()
            requireCurrent(session)

            // An older catalog intent owns its exact bytes and must be settled before A3 creates new work.
            val pendingCatalog = catalogRepository.resumePendingIntent(session)
            if (pendingCatalog?.errorCode != null) {
                return@withLock outcome(
                    inputs, counts, outcomes, published, adopted, unchanged, false,
                    pendingCatalog.errorCode
                )
            }

            session.transport.ensureDirectory(SyncV1CatalogRepository.CATALOG_DIRECTORY)
            // Some WebDAV servers do not create missing ancestors for MKCOL. Validate/create
            // the shared parent before any per-work directory or pending intent is handled.
            session.transport.ensureDirectory(CHAPTERS_DIRECTORY)
            val publisherId = catalogStore.androidPublisherDeviceId(uuidProvider)

            // Resume exact persisted chapter intents before inspecting current source data.
            for (intent in chaptersStore.pendingChapterIntents(session.serviceIdentity, session.rootIdentity)) {
                requireCurrent(session)
                session.transport.ensureDirectory("$CHAPTERS_DIRECTORY${intent.workId}/")
                requireCurrent(session)
                publishIntent(session, intent, publisherId)
                outcomes[intent.workId] = SyncV1ChapterWorkOutcome(
                    sourceState = SyncV1ChapterSourceState.UNAVAILABLE,
                    phase = SyncV1ChaptersPhase.PUBLISHED,
                    chapterCount = SyncV1Protocol.parseChapters(intent.rawJsonBytes)?.chapters?.size ?: 0,
                    tocRevision = intent.tocRevision
                )
                published++
            }

            // Recovery above owns the original bytes; only now read the current cached TOC inputs.
            inputs = inputProvider()
            counts = inputs.groupingBy { it.source.state }.eachCount()
            val catalogBooks = inputs.distinctBy { it.bookUrl }
                .map { SyncV1CatalogBook(it.bookUrl, it.title, it.author) }
            val workIds = catalogRepository.resolveWorkIdentities(catalogBooks, session)
            val inputsByWork = inputs.distinctBy { it.bookUrl }
                .mapNotNull { input -> workIds[input.bookUrl]?.let { it to input } }
                .groupBy({ it.first }, { it.second })

            for ((workId, sameWorkInputs) in inputsByWork.toSortedMap()) {
                val input = sameWorkInputs.first()
                if (sameWorkInputs.any {
                        it.title != input.title || it.author != input.author || it.source != input.source
                    }
                ) {
                    outcomes[workId] = SyncV1ChapterWorkOutcome(
                        input.source.state, SyncV1ChaptersPhase.FAILED,
                        errorCode = SyncV1ErrorCode.IDENTITY_CONFLICT
                    )
                    continue
                }
                val source = input.source
                if (source.state == SyncV1ChapterSourceState.UNAVAILABLE || source.state == SyncV1ChapterSourceState.FAILED) {
                    outcomes[workId] = SyncV1ChapterWorkOutcome(
                        source.state, SyncV1ChaptersPhase.FAILED, errorCode = source.errorCode
                    )
                    continue
                }
                try {
                    val desired = buildDesiredChapters(workId, source)
                    val directory = "$CHAPTERS_DIRECTORY$workId/"
                    session.transport.ensureDirectory(directory)
                    requireCurrent(session)
                    val remote = scanRemoteChapters(session, directory, workId, publisherId)
                    val local = chaptersStore.chapterPublicationState(
                        session.serviceIdentity, session.rootIdentity, workId
                    )
                    validateSavedState(local, session, workId)
                    if (local != null && remote.latest?.parsed?.tocRevision == local.tocRevision
                        && (remote.latest.parsed.snapshotId != local.snapshotId.toString()
                            || !remote.latest.rawBytes.contentEquals(local.rawJsonBytes)
                            || remote.latest.rawSha256 != local.rawSha256)
                    ) throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)

                    when {
                        local != null && local.semanticSha256 == semanticSha256(workId, desired)
                            && (remote.latest == null || remote.latest.parsed.tocRevision <= local.tocRevision) -> {
                            val response = session.transport.get(local.remotePath, SYNC_V1_MAX_CHAPTERS_BYTES, true)
                            requireCurrent(session)
                            when {
                                response.statusCode == 404 -> {
                                    // Repair a missing immutable object with the saved bytes and original path.
                                    val repair = newIntent(
                                        session, workId, local.tocRevision, local.snapshotId, local.remotePath,
                                        local.rawJsonBytes, local.semanticSha256, putAttempts = 0
                                    )
                                    val durable = chaptersStore.createChapterIntentIfNoPending(repair)
                                    publishIntent(session, durable, publisherId)
                                }
                                response.statusCode != 200 -> throw httpFailure(response.statusCode)
                                !sameObject(local.rawJsonBytes, local.rawSha256, response) ->
                                    throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                            }
                            outcomes[workId] = SyncV1ChapterWorkOutcome(
                                source.state, SyncV1ChaptersPhase.UNCHANGED, desired.size, local.tocRevision
                            )
                            unchanged++
                        }
                        remote.latest != null && remote.latest.parsed.chapters == desired
                            && (local == null || remote.latest.parsed.tocRevision >= local.tocRevision) -> {
                            val candidate = remote.latest
                            val intent = newIntent(
                                session, workId, candidate.parsed.tocRevision,
                                UUID.fromString(candidate.parsed.snapshotId), candidate.path,
                                candidate.rawBytes, semanticSha256(workId, desired), putAttempts = 0
                            )
                            val durable = chaptersStore.createChapterIntentIfNoPending(intent)
                            publishIntent(session, durable, publisherId)
                            outcomes[workId] = SyncV1ChapterWorkOutcome(
                                source.state, SyncV1ChaptersPhase.UNCHANGED, desired.size, candidate.parsed.tocRevision
                            )
                            adopted++
                        }
                        else -> {
                            val maxRevision = maxOf(
                                local?.tocRevision ?: 0L,
                                remote.latest?.parsed?.tocRevision ?: 0L
                            )
                            val nextRevision = maxRevision + 1L
                            if (nextRevision > SYNC_V1_MAX_SAFE_INTEGER) {
                                throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
                            }
                            val snapshotId = uuidProvider()
                            if (!SyncV1Protocol.isUuidV4(snapshotId.toString())) {
                                throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
                            }
                            val bytes = serializeChapters(
                                publisherId, workId, nextRevision, snapshotId.toString(),
                                Instant.ofEpochMilli(nowMillis()).toString(), desired
                            )
                            if (bytes.size > SYNC_V1_MAX_CHAPTERS_BYTES) {
                                throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_TOO_LARGE)
                            }
                            val path = "$directory$nextRevision-$snapshotId.json"
                            checkNoRevisionConflict(remote, nextRevision, snapshotId.toString())
                            val intent = newIntent(
                                session, workId, nextRevision, snapshotId, path, bytes,
                                semanticSha256(workId, desired), putAttempts = 0
                            )
                            val durable = chaptersStore.createChapterIntentIfNoPending(intent)
                            publishIntent(session, durable, publisherId)
                            outcomes[workId] = SyncV1ChapterWorkOutcome(
                                source.state, SyncV1ChaptersPhase.PUBLISHED, desired.size, nextRevision
                            )
                            published++
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    outcomes[workId] = SyncV1ChapterWorkOutcome(
                        source.state, SyncV1ChaptersPhase.FAILED,
                        errorCode = failure.asChaptersError()
                    )
                }
            }

            val catalogDirty = chaptersStore.isChapterCatalogDirty(session.serviceIdentity, session.rootIdentity)
            if (catalogDirty) {
                val revisions = chaptersStore.confirmedChapterRevisions(session.serviceIdentity, session.rootIdentity)
                val catalogOutcome = catalogRepository.publish(
                    books = catalogBooks,
                    confirmedTocRevisions = revisions,
                    sessionOverride = session,
                    includedWorkIds = revisions.keys
                )
                if (catalogOutcome.errorCode == null) {
                    chaptersStore.clearChapterCatalogDirty(session.serviceIdentity, session.rootIdentity)
                    catalogPublished = true
                } else {
                    catalogError = catalogOutcome.errorCode
                }
            }
            val failed = (counts[SyncV1ChapterSourceState.FAILED] ?: 0) +
                (counts[SyncV1ChapterSourceState.UNAVAILABLE] ?: 0) + outcomes.values.count {
                    it.phase == SyncV1ChaptersPhase.FAILED
                        && it.sourceState != SyncV1ChapterSourceState.FAILED
                        && it.sourceState != SyncV1ChapterSourceState.UNAVAILABLE
                }
            val phase = when {
                (failed > 0 || catalogError != null) && published + adopted + unchanged > 0 -> SyncV1ChaptersPhase.PARTIAL_SUCCESS
                failed > 0 || catalogError != null -> SyncV1ChaptersPhase.FAILED
                published > 0 -> SyncV1ChaptersPhase.PUBLISHED
                else -> SyncV1ChaptersPhase.UNCHANGED
            }
            val displayError = catalogError ?: outcomes.values.firstNotNullOfOrNull { it.errorCode }
            outcome(inputs, counts, outcomes, published, adopted, unchanged, catalogPublished, displayError, phase)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            outcome(inputs, counts, outcomes, published, adopted, unchanged, catalogPublished, failure.asChaptersError())
        }
    }

    private fun buildDesiredChapters(
        workId: String,
        source: SyncV1ChapterSourceResult
    ): List<SyncV1ChapterRecord> {
        if (source.state == SyncV1ChapterSourceState.KNOWN_EMPTY && source.chapters.isNotEmpty()) {
            throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        }
        if (source.state == SyncV1ChapterSourceState.AVAILABLE && source.chapters.isEmpty()) {
            throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        }
        if (source.chapters.size > SYNC_V1_MAX_CHAPTERS) {
            throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_LIMIT_EXCEEDED)
        }
        var previous = -1L
        val result = source.chapters.map { chapter ->
            if (chapter.localTocIndex !in 0..SYNC_V1_MAX_SAFE_INTEGER || chapter.localTocIndex <= previous) {
                throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
            }
            if (!SyncV1Protocol.isValidChapterTitle(chapter.title)) {
                throw SyncV1Failure(SyncV1ErrorCode.CHAPTER_TITLE_INVALID)
            }
            previous = chapter.localTocIndex
            SyncV1ChapterRecord(
                SyncV1Protocol.chapterKey(workId, chapter.localTocIndex, chapter.title),
                chapter.title,
                chapter.localTocIndex
            )
        }
        if (source.state == SyncV1ChapterSourceState.KNOWN_EMPTY && result.isNotEmpty()) {
            throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        }
        return result
    }

    private suspend fun scanRemoteChapters(
        session: SyncV1Session,
        directory: String,
        workId: String,
        publisherId: String
    ): RemoteChapters {
        requireCurrent(session)
        val files = session.transport.listDirectChildren(directory).asSequence()
            .filterNot { it.isDirectory }
            .mapNotNull { parseChapterPath(directory, it.name) }
            .distinctBy { it.path }
            .toList()
        requireCurrent(session)
        if (files.groupBy { it.revision }.values.any { group -> group.map { it.snapshotId }.distinct().size > 1 }) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
        for (file in files.sortedByDescending { it.revision }) {
            val response = session.transport.get(file.path, SYNC_V1_MAX_CHAPTERS_BYTES, true)
            requireCurrent(session)
            if (response.statusCode == 404) continue
            if (response.statusCode !in 200..299) throw httpFailure(response.statusCode)
            if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_CHAPTERS_BYTES) continue
            val digest = SyncV1Protocol.sha256(response.body)
            if (response.rawSha256 != null && response.rawSha256 != digest) continue
            val parsed = SyncV1Protocol.parseChapters(response.body) ?: continue
            if (parsed.deviceId != publisherId || parsed.workId != workId
                || parsed.tocRevision != file.revision || parsed.snapshotId != file.snapshotId
            ) continue
            return RemoteChapters(RemoteChapter(file.path, response.body.copyOf(), digest, parsed), files)
        }
        return RemoteChapters(null, files)
    }

    private fun parseChapterPath(directory: String, fileName: String): ChapterPath? {
        val match = CHAPTER_FILE_PATTERN.matchEntire(fileName) ?: return null
        val revision = match.groupValues[1].toLongOrNull()
            ?.takeIf { it in 1..SYNC_V1_MAX_SAFE_INTEGER } ?: return null
        val snapshot = match.groupValues[2]
        if (!SyncV1Protocol.isUuidV4(snapshot)) return null
        return ChapterPath(revision, snapshot, "$directory$fileName")
    }

    private suspend fun publishIntent(
        session: SyncV1Session,
        initialIntent: SyncV1ChapterPublicationIntent,
        publisherId: String
    ) {
        var intent = initialIntent
        val expectedPath = "$CHAPTERS_DIRECTORY${intent.workId}/${intent.tocRevision}-${intent.snapshotId}.json"
        val parsed = SyncV1Protocol.parseChapters(intent.rawJsonBytes)
        if (intent.remotePath != expectedPath || parsed?.deviceId != publisherId || parsed.workId != intent.workId
            || parsed.tocRevision != intent.tocRevision || parsed.snapshotId != intent.snapshotId.toString()
            || SyncV1Protocol.sha256(intent.rawJsonBytes) != intent.rawSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        validateIntent(session, intent)
        while (true) {
            requireCurrent(session)
            val existing = session.transport.get(intent.remotePath, SYNC_V1_MAX_CHAPTERS_BYTES, true)
            requireCurrent(session)
            when {
                existing.statusCode == 200 -> {
                    if (!sameObject(intent.rawJsonBytes, intent.rawSha256, existing)) {
                        throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
                    }
                    chaptersStore.markChapterIntentPublished(intent, nowMillis())
                    return
                }
                existing.statusCode != 404 -> throw httpFailure(existing.statusCode)
            }
            if (intent.putAttemptCount >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
            chaptersStore.incrementChapterPutAttempt(intent, nowMillis())
            var putError: Throwable? = null
            try {
                val response = session.transport.put(intent.remotePath, intent.rawJsonBytes.copyOf())
                requireCurrent(session)
                if (response.statusCode !in 200..299) {
                    val failure = httpFailure(response.statusCode)
                    if (response.statusCode in 400..499 && response.statusCode !in setOf(408, 409)) throw failure
                    putError = failure
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (failure is SyncV1Failure && failure.errorCode in setOf(
                        SyncV1ErrorCode.CONFIG_CHANGED,
                        SyncV1ErrorCode.AUTHENTICATION_FAILED,
                        SyncV1ErrorCode.HTTP_CLIENT_ERROR,
                        SyncV1ErrorCode.HTTP_NOT_FOUND
                    )
                ) throw failure
                putError = failure
            }
            val readback = try {
                session.transport.get(intent.remotePath, SYNC_V1_MAX_CHAPTERS_BYTES, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                throw putError?.asChaptersException() ?: failure
            }
            requireCurrent(session)
            when {
                readback.statusCode == 200 && sameObject(intent.rawJsonBytes, intent.rawSha256, readback) -> {
                    chaptersStore.markChapterIntentPublished(intent, nowMillis())
                    return
                }
                readback.statusCode == 200 -> throw SyncV1Failure(SyncV1ErrorCode.READBACK_MISMATCH)
                readback.statusCode == 404 -> {
                    if (intent.putAttemptCount + 1 >= 2) throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
                }
                else -> throw httpFailure(readback.statusCode)
            }
            intent = chaptersStore.pendingChapterIntents(session.serviceIdentity, session.rootIdentity)
                .firstOrNull { it.remotePath == intent.remotePath }
                ?: throw SyncV1Failure(SyncV1ErrorCode.RETRY_LIMIT)
        }
    }

    private fun validateSavedState(state: SyncV1ChapterPublicationState?, session: SyncV1Session, workId: String) {
        if (state == null) return
        if (state.serviceIdentity != session.serviceIdentity || state.rootIdentity != session.rootIdentity
            || state.workId != workId || SyncV1Protocol.sha256(state.rawJsonBytes) != state.rawSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        val parsed = SyncV1Protocol.parseChapters(state.rawJsonBytes)
            ?: throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
        if (parsed.workId != workId || parsed.tocRevision != state.tocRevision
            || parsed.snapshotId != state.snapshotId.toString()
            || state.remotePath != "$CHAPTERS_DIRECTORY$workId/${state.tocRevision}-${state.snapshotId}.json"
            || semanticSha256(workId, parsed.chapters) != state.semanticSha256
        ) throw SyncV1Failure(SyncV1ErrorCode.CHAPTERS_INVALID)
    }

    private fun newIntent(
        session: SyncV1Session,
        workId: String,
        revision: Long,
        snapshotId: UUID,
        path: String,
        bytes: ByteArray,
        semanticSha: String,
        putAttempts: Int
    ) = SyncV1ChapterPublicationIntent(
        workId, revision, snapshotId, path, bytes, SyncV1Protocol.sha256(bytes), semanticSha,
        putAttempts, session.configIdentity, session.serviceIdentity, session.rootIdentity,
        "pending", nowMillis(), nowMillis()
    )

    private fun serializeChapters(
        deviceId: String,
        workId: String,
        revision: Long,
        snapshotId: String,
        generatedAt: String,
        chapters: List<SyncV1ChapterRecord>
    ): ByteArray {
        val root = JsonObject().apply {
            addProperty("protocol_version", "sync-v1")
            addProperty("schema_version", "1.0.0")
            addProperty("device_id", deviceId)
            addProperty("work_id", workId)
            addProperty("toc_revision", revision)
            addProperty("snapshot_id", snapshotId)
            addProperty("generated_at", generatedAt)
            add("chapters", JsonArray().apply {
                chapters.forEach { chapter ->
                    add(JsonObject().apply {
                        addProperty("chapter_key", chapter.chapterKey)
                        addProperty("title", chapter.title)
                        addProperty("local_toc_index", chapter.localTocIndex)
                    })
                }
            })
        }
        return gson.toJson(root).toByteArray(Charsets.UTF_8)
    }

    private fun semanticSha256(workId: String, chapters: List<SyncV1ChapterRecord>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        update(workId)
        chapters.forEach { update(it.chapterKey); update(it.title); update(it.localTocIndex.toString()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun checkNoRevisionConflict(remote: RemoteChapters, revision: Long, snapshot: String) {
        if (remote.listed.any { it.revision == revision && it.snapshotId != snapshot }) {
            throw SyncV1Failure(SyncV1ErrorCode.REVISION_CONFLICT)
        }
    }

    private fun sameObject(expected: ByteArray, expectedSha: String, response: SyncV1HttpResponse): Boolean {
        if (response.bodyTooLarge || response.body.size > SYNC_V1_MAX_CHAPTERS_BYTES) return false
        val actualSha = SyncV1Protocol.sha256(response.body)
        return response.body.contentEquals(expected) && actualSha == expectedSha
            && (response.rawSha256 == null || response.rawSha256 == actualSha)
    }

    private fun validateIntent(session: SyncV1Session, intent: SyncV1ChapterPublicationIntent) {
        if (intent.configIdentity != session.configIdentity || intent.serviceIdentity != session.serviceIdentity
            || intent.rootIdentity != session.rootIdentity || !session.isCurrent()
        ) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun requireCurrent(session: SyncV1Session) {
        if (!session.isCurrent()) throw SyncV1Failure(SyncV1ErrorCode.CONFIG_CHANGED)
    }

    private fun httpFailure(status: Int): SyncV1Failure = when (status) {
        401, 403 -> SyncV1Failure(SyncV1ErrorCode.AUTHENTICATION_FAILED)
        404 -> SyncV1Failure(SyncV1ErrorCode.HTTP_NOT_FOUND)
        409 -> SyncV1Failure(SyncV1ErrorCode.HTTP_CONFLICT)
        in 500..599 -> SyncV1Failure(SyncV1ErrorCode.HTTP_SERVER_ERROR)
        else -> SyncV1Failure(SyncV1ErrorCode.HTTP_CLIENT_ERROR)
    }

    private fun Throwable.asChaptersException(): Exception = if (this is Exception) this else IOException("chapters_put_failed")

    private fun Throwable.asChaptersError(): SyncV1ErrorCode = when (this) {
        is SyncV1Failure -> errorCode
        is SocketTimeoutException -> SyncV1ErrorCode.TIMEOUT
        is UnknownHostException -> SyncV1ErrorCode.DNS_ERROR
        is SSLException -> SyncV1ErrorCode.TLS_ERROR
        is IOException -> SyncV1ErrorCode.NETWORK_UNKNOWN
        else -> SyncV1ErrorCode.CHAPTERS_INVALID
    }

    private fun outcome(
        inputs: List<SyncV1ChapterWorkInput>,
        counts: Map<SyncV1ChapterSourceState, Int>,
        outcomes: Map<String, SyncV1ChapterWorkOutcome>,
        published: Int,
        adopted: Int,
        unchanged: Int,
        catalogPublished: Boolean,
        errorCode: SyncV1ErrorCode?,
        phaseOverride: SyncV1ChaptersPhase? = null
    ) = SyncV1ChaptersOutcome(
        phase = phaseOverride ?: when {
            errorCode != null && published + adopted + unchanged > 0 -> SyncV1ChaptersPhase.PARTIAL_SUCCESS
            errorCode != null -> SyncV1ChaptersPhase.FAILED
            published > 0 -> SyncV1ChaptersPhase.PUBLISHED
            else -> SyncV1ChaptersPhase.UNCHANGED
        },
        totalWorks = inputs.size,
        available = counts[SyncV1ChapterSourceState.AVAILABLE] ?: 0,
        knownEmpty = counts[SyncV1ChapterSourceState.KNOWN_EMPTY] ?: 0,
        unavailable = counts[SyncV1ChapterSourceState.UNAVAILABLE] ?: 0,
        failed = (counts[SyncV1ChapterSourceState.FAILED] ?: 0) + outcomes.values.count {
            it.phase == SyncV1ChaptersPhase.FAILED
                && it.sourceState != SyncV1ChapterSourceState.FAILED
                && it.sourceState != SyncV1ChapterSourceState.UNAVAILABLE
        },
        published = published,
        adopted = adopted,
        unchanged = unchanged,
        catalogPublished = catalogPublished,
        workOutcomes = outcomes.values.toList(),
        errorCode = errorCode
    )

    private data class ChapterPath(val revision: Long, val snapshotId: String, val path: String)
    private data class RemoteChapter(
        val path: String,
        val rawBytes: ByteArray,
        val rawSha256: String,
        val parsed: ParsedChapters
    )
    private data class RemoteChapters(val latest: RemoteChapter?, val listed: List<ChapterPath>)

    private companion object {
        val PROCESS_OPERATION_MUTEX = Mutex()
        const val CHAPTERS_DIRECTORY = "legadom_ink_sync/v1/android/chapters/"
        val CHAPTER_FILE_PATTERN = Regex("^(0|[1-9][0-9]*)-([0-9a-f-]{36})\\.json$")
    }
}
