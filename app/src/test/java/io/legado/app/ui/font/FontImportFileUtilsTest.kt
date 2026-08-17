package io.legado.app.ui.font

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class FontImportFileUtilsTest {

    @Test
    fun parseHttpUrl_acceptsHttpHttpsUnicodeAndTrimsInput() {
        val url = FontImportFileUtils.parseHttpUrl(
            "  https://example.com/字体.ttf?download=1  "
        )

        assertNotNull(url)
        assertEquals("https", url?.scheme)
        assertEquals("example.com", url?.host)
        assertNull(FontImportFileUtils.parseHttpUrl("ftp://example.com/font.ttf"))
        assertNull(FontImportFileUtils.parseHttpUrl("not a url"))
    }

    @Test
    fun responseFileName_prefersEncodedHeaderAndFallsBackToUrlPath() {
        val encoded = FontImportFileUtils.responseFileName(
            "attachment; filename*=UTF-8''%E6%80%9D%E6%BA%90.otf",
            "https://example.com/download?id=1".toHttpUrl()
        )
        val fallback = FontImportFileUtils.responseFileName(
            null,
            "https://example.com/fonts/test.ttf?download=1".toHttpUrl()
        )

        assertEquals("思源.otf", encoded)
        assertEquals("test.ttf", fallback)
    }

    @Test
    fun safeFontFileName_removesUnsafeCharactersAndAddsSupportedExtension() {
        assertEquals(
            "bad_name.otf",
            FontImportFileUtils.safeFontFileName("../bad:name.otf", "font_1")
        )
        assertEquals(
            "download.ttf",
            FontImportFileUtils.safeFontFileName("download", "font_1")
        )
        assertEquals(
            "font_1.ttf",
            FontImportFileUtils.safeFontFileName(".ttf", "font_1")
        )
    }

    @Test
    fun nextAvailableFile_addsSuffixWithoutOverwritingExistingFont() {
        withTempDirectory { directory ->
            File(directory, "font.ttf").writeText("old")
            File(directory, "font (1).ttf").writeText("old second")

            val next = FontImportFileUtils.nextAvailableFile(directory, "font.ttf")

            assertEquals("font (2).ttf", next.name)
            assertFalse(next.exists())
        }
    }

    @Test
    fun copyToStagingFile_enforcesLimitAndDeletesPartialFile() {
        withTempDirectory { directory ->
            val staging = File(directory, "font.tmp")

            val result = kotlin.runCatching {
                FontImportFileUtils.copyToStagingFile(
                    ByteArrayInputStream(ByteArray(11)),
                    staging,
                    10
                )
            }

            assertTrue(result.exceptionOrNull() is FontSizeLimitException)
            assertFalse(staging.exists())
        }
    }

    @Test
    fun copyToStagingFile_deletesPartialFileWhenDownloadStreamFails() {
        withTempDirectory { directory ->
            val staging = File(directory, "font.tmp")
            val brokenStream = object : InputStream() {
                private var readCount = 0

                override fun read(): Int {
                    if (readCount++ < 4) return 1
                    throw IOException("connection lost")
                }
            }

            val result = kotlin.runCatching {
                FontImportFileUtils.copyToStagingFile(brokenStream, staging, 100)
            }

            assertTrue(result.exceptionOrNull() is FontStreamException)
            assertFalse(staging.exists())
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("font-import-test").toFile()
        try {
            block(directory)
        } finally {
            directory.walkBottomUp().forEach(File::delete)
        }
    }
}
