package io.legado.app.ui.font

import android.content.Context
import android.graphics.Typeface
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal object FontUrlImporter {

    const val MAX_FONT_BYTES = 100L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5

    private val downloadClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun import(context: Context, rawUrl: String): File = runInterruptible(Dispatchers.IO) {
        val startUrl = FontImportFileUtils.parseHttpUrl(rawUrl)
            ?: throw FontImportException(FontImportFailure.INVALID_URL)
        val fontDirectory = ImportedFontStore.primaryDirectory(context)
        if ((!fontDirectory.exists() && !fontDirectory.mkdirs()) || !fontDirectory.isDirectory) {
            throw FontImportException(FontImportFailure.SAVE)
        }
        val stagingFile = try {
            File.createTempFile(".font-import-", ".tmp", fontDirectory)
        } catch (e: IOException) {
            throw FontImportException(FontImportFailure.SAVE, cause = e)
        }

        try {
            val download = download(startUrl, stagingFile)
            if (download.byteCount <= 0L || stagingFile.length() <= 0L) {
                throw FontImportException(FontImportFailure.INVALID_FONT)
            }
            try {
                check(Typeface.createFromFile(stagingFile)?.let { it !== Typeface.DEFAULT } == true)
            } catch (e: Exception) {
                throw FontImportException(FontImportFailure.INVALID_FONT, cause = e)
            }

            val finalName = FontImportFileUtils.safeFontFileName(
                download.suggestedName,
                "font_${System.currentTimeMillis()}"
            )
            val finalFile = FontImportFileUtils.nextAvailableFile(fontDirectory, finalName)
            if (!stagingFile.renameTo(finalFile)) {
                try {
                    stagingFile.inputStream().use {
                        FontImportFileUtils.copyToStagingFile(it, finalFile, MAX_FONT_BYTES)
                    }
                } catch (e: Exception) {
                    finalFile.delete()
                    throw FontImportException(FontImportFailure.SAVE, cause = e)
                }
            }
            ImportedFontStore.remember(context, listOf(finalFile))
            finalFile
        } finally {
            stagingFile.delete()
        }
    }

    private fun download(startUrl: HttpUrl, stagingFile: File): DownloadResult {
        var currentUrl = startUrl
        var redirectCount = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            val call = downloadClient.newCall(Request.Builder().url(currentUrl).get().build())
            val response = try {
                call.execute()
            } catch (e: SocketTimeoutException) {
                throw FontImportException(FontImportFailure.TIMEOUT, cause = e)
            } catch (e: InterruptedIOException) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                throw FontImportException(FontImportFailure.TIMEOUT, cause = e)
            } catch (e: IOException) {
                throw FontImportException(FontImportFailure.NETWORK, cause = e)
            }

            try {
                if (response.code in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw FontImportException(FontImportFailure.REDIRECT)
                    }
                    val location = response.header("Location")
                        ?: throw FontImportException(
                            FontImportFailure.HTTP,
                            httpCode = response.code
                        )
                    currentUrl = response.request.url.resolve(location)
                        ?.takeIf { it.scheme == "http" || it.scheme == "https" }
                        ?: throw FontImportException(FontImportFailure.REDIRECT)
                    redirectCount++
                    continue
                }
                if (!response.isSuccessful) {
                    throw FontImportException(FontImportFailure.HTTP, httpCode = response.code)
                }

                val contentLength = response.header("Content-Length")?.toLongOrNull()
                    ?: response.body.contentLength().takeIf { it >= 0L }
                if (contentLength != null && contentLength > MAX_FONT_BYTES) {
                    throw FontImportException(FontImportFailure.TOO_LARGE)
                }

                val suggestedName = FontImportFileUtils.responseFileName(
                    response.header("Content-Disposition"),
                    currentUrl
                )
                val byteCount = try {
                    response.body.byteStream().use {
                        FontImportFileUtils.copyToStagingFile(
                            it,
                            stagingFile,
                            MAX_FONT_BYTES
                        )
                    }
                } catch (e: FontSizeLimitException) {
                    throw FontImportException(FontImportFailure.TOO_LARGE, cause = e)
                } catch (e: FontStreamException) {
                    val failure = when {
                        Thread.currentThread().isInterrupted -> throw CancellationException()
                        e.failure == FontStreamFailure.WRITE -> FontImportFailure.SAVE
                        e.cause is SocketTimeoutException || e.cause is InterruptedIOException -> {
                            FontImportFailure.TIMEOUT
                        }
                        else -> FontImportFailure.NETWORK
                    }
                    throw FontImportException(failure, cause = e)
                } catch (e: InterruptedException) {
                    throw CancellationException()
                }
                return DownloadResult(suggestedName, byteCount)
            } finally {
                response.close()
            }
        }
    }

    private data class DownloadResult(
        val suggestedName: String?,
        val byteCount: Long
    )
}

internal enum class FontImportFailure {
    INVALID_URL,
    NETWORK,
    TIMEOUT,
    HTTP,
    REDIRECT,
    TOO_LARGE,
    SAVE,
    INVALID_FONT
}

internal class FontImportException(
    val failure: FontImportFailure,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(cause)
