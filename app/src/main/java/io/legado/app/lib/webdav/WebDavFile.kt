package io.legado.app.lib.webdav

/**
 * webDavFile
 */
@Suppress("unused")
class WebDavFile(
    urlStr: String,
    authorization: Authorization,
    val displayName: String,
    val urlName: String,
    val size: Long,
    val contentType: String,
    val resourceType: String,
    val lastModify: Long,
    allowInsecure: Boolean = false
) : WebDav(urlStr, authorization, allowInsecure = allowInsecure) {

    val isDir by lazy {
        isDir(contentType, resourceType)
    }

    companion object {
        fun isDir(contentType: String, resourceType: String): Boolean {
            return contentType == "httpd/unix-directory"
                    || resourceType.lowercase().contains("collection")
        }
    }

}
