package io.legado.app.lib.webdav

import io.legado.app.data.appDb
import io.legado.app.data.entities.Server.WebDavConfig
import okhttp3.Credentials
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class Authorization(
    val username: String,
    val password: String,
    val charset: Charset = StandardCharsets.ISO_8859_1
) {

    var name = "Authorization"
        private set

    var data: String = Credentials.basic(username, password, charset)
        private set

    override fun toString(): String {
        // Never expose the password (or the Authorization header) in logs or
        // diagnostics that may call toString().
        return "Authorization(redacted)"
    }

    constructor(serverID: Long) : this(
        appDb.serverDao.get(serverID)?.getWebDavConfig()
            ?: throw WebDavException("Unexpected WebDav Authorization")
    )

    constructor(webDavConfig: WebDavConfig) : this(webDavConfig.username, webDavConfig.password)

}
