package io.legado.app.lib.webdav

open class WebDavException(msg: String) : Exception(msg) {

    override fun fillInStackTrace(): Throwable {
        return this
    }

}

class WebDavHttpException(
    val code: Int,
    msg: String
) : WebDavException(msg)

class ObjectNotFoundException(msg: String) : WebDavException(msg)
