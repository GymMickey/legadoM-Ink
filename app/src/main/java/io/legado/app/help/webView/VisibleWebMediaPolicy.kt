package io.legado.app.help.webView

import android.webkit.WebResourceResponse
import android.webkit.WebView
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale

/**
 * 视频和音频限制只作用于用户可见的网页 WebView，不改变 WebViewPool 的全局设置。
 */
class VisibleWebMediaPolicy(private val webView: WebView) {

    private var originalMediaPlaybackRequiresUserGesture: Boolean? = null
    private var installed = false
    private var restored = false

    fun install() {
        if (installed && !restored) return
        originalMediaPlaybackRequiresUserGesture =
            webView.settings.mediaPlaybackRequiresUserGesture
        webView.settings.mediaPlaybackRequiresUserGesture = true
        installed = true
        restored = false
    }

    fun injectMediaBlocker() {
        if (!installed || restored) return
        webView.evaluateJavascript(MEDIA_BLOCKER_SCRIPT, null)
    }

    fun restore() {
        if (!installed || restored) return
        runCatching { webView.evaluateJavascript(MEDIA_POLICY_CLEANUP_SCRIPT, null) }
        runCatching {
            originalMediaPlaybackRequiresUserGesture?.let {
                webView.settings.mediaPlaybackRequiresUserGesture = it
            }
        }
        restored = true
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "webm", "mov", "mkv", "avi", "flv", "3gp", "m3u8"
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid", "midi"
        )

        fun isMediaRequest(
            url: String,
            requestHeaders: Map<String, String> = emptyMap()
        ): Boolean = isVideoRequest(url, requestHeaders) || isAudioRequest(url, requestHeaders)

        fun isVideoRequest(
            url: String,
            requestHeaders: Map<String, String> = emptyMap()
        ): Boolean {
            if (acceptsType(requestHeaders, "video/")) return true
            return extensionOf(url) in VIDEO_EXTENSIONS
        }

        fun isAudioRequest(
            url: String,
            requestHeaders: Map<String, String> = emptyMap()
        ): Boolean {
            if (acceptsType(requestHeaders, "audio/")) return true
            return extensionOf(url) in AUDIO_EXTENSIONS
        }

        private fun acceptsType(requestHeaders: Map<String, String>, typePrefix: String): Boolean {
            return requestHeaders.any { (name, value) ->
                name.equals("Accept", ignoreCase = true) && value.split(',').any {
                    it.trim().substringBefore(';').startsWith(typePrefix, ignoreCase = true)
                }
            }
        }

        private fun extensionOf(url: String): String {
            val urlWithoutQuery = url.substringBefore('#').substringBefore('?')
            val path = runCatching {
                URI(urlWithoutQuery).path ?: urlWithoutQuery
            }.getOrDefault(urlWithoutQuery)
            val extension = path.substringAfterLast('/', "")
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT)
            return extension
        }

        fun blockedResponse(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }

        fun filterRssHtml(html: String): String {
            return Jsoup.parse(html).apply {
                outputSettings().prettyPrint(false)
            }.also { document ->
                document.select("video, audio").remove()
            }.outerHtml()
        }
    }
}

internal val MEDIA_BLOCKER_SCRIPT = """
(function() {
  if (window.__legadoVisibleWebMediaPolicy) return;

  function disableVideo(video) {
    if (!(video instanceof HTMLVideoElement) || video.__legadoVideoDisabled) return;
    video.__legadoVideoDisabled = true;
    try { video.pause(); } catch (_) {}
    try { video.removeAttribute('autoplay'); } catch (_) {}
    try { video.removeAttribute('src'); } catch (_) {}
    try { video.srcObject = null; } catch (_) {}
    try {
      video.querySelectorAll('source,track').forEach(function(node) { node.remove(); });
    } catch (_) {}
    try {
      video.play = function() {
        return Promise.reject(new DOMException('Video playback disabled', 'NotAllowedError'));
      };
    } catch (_) {}
    try { video.load(); } catch (_) {}
  }

  function disableAudio(audio) {
    if (!(audio instanceof HTMLAudioElement) || audio.__legadoAudioDisabled) return;
    audio.__legadoAudioDisabled = true;
    try { audio.pause(); } catch (_) {}
    try { audio.removeAttribute('autoplay'); } catch (_) {}
    try { audio.removeAttribute('controls'); } catch (_) {}
    try { audio.removeAttribute('src'); } catch (_) {}
    try { audio.srcObject = null; } catch (_) {}
    try {
      audio.querySelectorAll('source,track').forEach(function(node) { node.remove(); });
    } catch (_) {}
    var preventAudio = function(event) {
      event.preventDefault();
      event.stopImmediatePropagation();
    };
    audio.addEventListener('play', preventAudio, true);
    audio.addEventListener('click', preventAudio, true);
    audio.addEventListener('pointerdown', preventAudio, true);
    audio.__legadoAudioPolicyListener = preventAudio;
    try { audio.load(); } catch (_) {}
  }

  function scan(node) {
    if (!node || node.nodeType !== 1) return;
    if (node.tagName && node.tagName.toLowerCase() === 'video') disableVideo(node);
    if (node.tagName && node.tagName.toLowerCase() === 'audio') disableAudio(node);
    if (node.querySelectorAll) {
      node.querySelectorAll('video').forEach(disableVideo);
      node.querySelectorAll('audio').forEach(disableAudio);
    }
  }

  scan(document.documentElement);
  var observer = new MutationObserver(function(mutations) {
    mutations.forEach(function(mutation) {
      mutation.addedNodes.forEach(scan);
    });
  });
  observer.observe(document.documentElement || document, { childList: true, subtree: true });
  window.__legadoVisibleWebMediaPolicy = {
    observer: observer
  };
})();
""".trimIndent()

internal val MEDIA_POLICY_CLEANUP_SCRIPT = """
(function() {
  var policy = window.__legadoVisibleWebMediaPolicy;
  if (!policy) return;
  try { policy.observer && policy.observer.disconnect(); } catch (_) {}
  document.querySelectorAll('audio').forEach(function(audio) {
    var listener = audio.__legadoAudioPolicyListener;
    if (!listener) return;
    ['play', 'click', 'pointerdown'].forEach(function(type) {
      try { audio.removeEventListener(type, listener, true); } catch (_) {}
    });
    try { delete audio.__legadoAudioPolicyListener; } catch (_) {}
  });
  try { delete window.__legadoVisibleWebMediaPolicy; } catch (_) {}
})();
""".trimIndent()
