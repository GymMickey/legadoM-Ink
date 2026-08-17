package io.legado.app.ui.font

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

internal object FontImportFileUtils {

    private val invalidFileNameChars = Regex("""[\u0000-\u001f\u007f<>:"/\\|?*]""")
    private val encodedFileNameRegex = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)")
    private val fileNameRegex = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*(?:\\\"([^\\\"]*)\\\"|([^;]*))")

    fun parseHttpUrl(rawUrl: String): HttpUrl? {
        val url = rawUrl.trim().toHttpUrlOrNull() ?: return null
        return url.takeIf { it.scheme == "http" || it.scheme == "https" }
    }

    fun responseFileName(contentDisposition: String?, finalUrl: HttpUrl): String? {
        contentDispositionFileName(contentDisposition)?.let { return it }
        return finalUrl.pathSegments.lastOrNull()?.takeIf { it.isNotBlank() }
    }

    fun contentDispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        encodedFileNameRegex.find(contentDisposition)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            ?.substringAfter("''", missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
            ?.let { encodedName ->
                kotlin.runCatching {
                    URLDecoder.decode(encodedName.replace("+", "%2B"), Charsets.UTF_8.name())
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        return fileNameRegex.find(contentDisposition)?.let { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] })
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }

    fun safeFontFileName(candidate: String?, fallbackStem: String): String {
        val leafName = candidate
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(invalidFileNameChars, "_")
            ?.trim()
            .orEmpty()

        val sourceExtension = leafName.substringAfterLast('.', "").lowercase()
        val extension = sourceExtension.takeIf { it == "ttf" || it == "otf" } ?: "ttf"
        val sourceStem = if (sourceExtension.isNotEmpty()) {
            leafName.substringBeforeLast('.')
        } else {
            leafName
        }
        val safeFallback = fallbackStem
            .replace(invalidFileNameChars, "_")
            .trim('.', ' ')
            .ifBlank { "font" }
        val stem = sourceStem
            .replace(invalidFileNameChars, "_")
            .trim('.', ' ')
            .ifBlank { safeFallback }
            .take(80)
        return "$stem.$extension"
    }

    fun nextAvailableFile(directory: File, fileName: String): File {
        val first = File(directory, fileName)
        if (!first.exists()) return first
        val extension = fileName.substringAfterLast('.', "")
        val stem = fileName.substringBeforeLast('.', fileName)
        var index = 1
        while (true) {
            val candidateName = if (extension.isEmpty()) {
                "$stem ($index)"
            } else {
                "$stem ($index).$extension"
            }
            File(directory, candidateName).let {
                if (!it.exists()) return it
            }
            index++
        }
    }

    /**
     * Copies a response stream into a staging file and removes the staging file on every failure.
     */
    @Throws(FontStreamException::class, FontSizeLimitException::class)
    fun copyToStagingFile(
        input: InputStream,
        target: File,
        maxBytes: Long
    ): Long {
        var completed = false
        try {
            val output = try {
                FileOutputStream(target, false)
            } catch (e: IOException) {
                throw FontStreamException(FontStreamFailure.WRITE, e)
            }
            output.use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Font download cancelled")
                    }
                    val count = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        throw FontStreamException(FontStreamFailure.READ, e)
                    }
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw FontSizeLimitException()
                    try {
                        out.write(buffer, 0, count)
                    } catch (e: IOException) {
                        throw FontStreamException(FontStreamFailure.WRITE, e)
                    }
                }
                try {
                    out.flush()
                    out.fd.sync()
                } catch (e: IOException) {
                    throw FontStreamException(FontStreamFailure.WRITE, e)
                }
                completed = true
                return total
            }
        } finally {
            if (!completed) target.delete()
        }
    }
}

internal enum class FontStreamFailure {
    READ,
    WRITE
}

internal class FontStreamException(
    val failure: FontStreamFailure,
    cause: IOException
) : IOException(cause)

internal class FontSizeLimitException : IOException()
