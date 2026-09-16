package io.legado.app.help.webView

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleWebMediaPolicyTest {

    @Test
    fun recognizesCommonVideoUrlsAndVideoAcceptHeaders() {
        assertTrue(VisibleWebMediaPolicy.isVideoRequest("https://example.com/clip.MP4?token=redacted"))
        assertTrue(VisibleWebMediaPolicy.isVideoRequest("https://example.com/clip.webm?quality=hd"))
        assertTrue(VisibleWebMediaPolicy.isVideoRequest("https://example.com/live.M3U8#fragment"))
        assertTrue(
            VisibleWebMediaPolicy.isVideoRequest(
                "https://example.com/stream",
                mapOf("Accept" to "text/html, video/*;q=0.9")
            )
        )
    }

    @Test
    fun recognizesCommonAudioUrlsAndAudioAcceptHeaders() {
        listOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi").forEach { extension ->
            assertTrue(VisibleWebMediaPolicy.isAudioRequest("https://example.com/sound.$extension?token=redacted"))
        }
        assertTrue(
            VisibleWebMediaPolicy.isAudioRequest(
                "https://example.com/stream",
                mapOf("Accept" to "text/html, audio/*;q=0.9")
            )
        )
    }

    @Test
    fun doesNotMisclassifyNormalResourcesOrTsFiles() {
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/index.html"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/image.png"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/app.js"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/data.json"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/font.woff2"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/module.ts"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/clip.mp4.json"))
        assertFalse(VisibleWebMediaPolicy.isMediaRequest("https://example.com/sound.mp3.html"))
    }

    @Test
    fun removesVideoAndAudioButPreservesPictureSource() {
        val html = """
            <html><body>
              <video autoplay><source src="clip.mp4"><track src="captions.vtt"></video>
              <picture><source srcset="cover.webp"><img src="cover.png"></picture>
              <audio controls><source src="sound.mp3"><track src="captions.vtt"></audio>
            </body></html>
        """.trimIndent()

        val filtered = VisibleWebMediaPolicy.filterRssHtml(html)

        assertFalse(filtered.contains("<video", ignoreCase = true))
        assertFalse(filtered.contains("clip.mp4"))
        assertTrue(filtered.contains("cover.webp"))
        assertTrue(filtered.contains("<picture", ignoreCase = true))
        assertFalse(filtered.contains("<audio", ignoreCase = true))
        assertFalse(filtered.contains("sound.mp3"))
    }

    @Test
    fun preservesOrdinaryHtmlAndPictureSources() {
        val html = "<p>text</p><picture><source srcset=\"cover.webp\"><img src=\"cover.png\"></picture>"
        val filtered = VisibleWebMediaPolicy.filterRssHtml(html)
        assertTrue(filtered.contains("<p>text</p>"))
        assertTrue(filtered.contains("cover.webp"))
        assertTrue(filtered.contains("cover.png"))
    }

    @Test
    fun blockerScriptTargetsVideoOnlyAndIsRepeatSafe() {
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("HTMLVideoElement"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("MutationObserver"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("__legadoVisibleWebMediaPolicy"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("HTMLAudioElement"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("__legadoAudioPolicyListener"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("removeAttribute('src')"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("srcObject = null"))
        assertTrue(MEDIA_BLOCKER_SCRIPT.contains("querySelectorAll('source,track')"))
        assertFalse(MEDIA_BLOCKER_SCRIPT.contains("HTMLMediaElement"))
        assertFalse(MEDIA_BLOCKER_SCRIPT.contains("fetch("))
        assertFalse(MEDIA_BLOCKER_SCRIPT.contains("XMLHttpRequest"))
        assertTrue(MEDIA_POLICY_CLEANUP_SCRIPT.contains("disconnect"))
    }
}
