package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import io.legado.app.web.socket.*

class WebSocketServer(port: Int) : NanoWSD(port) {

    override fun serve(session: IHTTPSession): NanoHTTPD.Response {
        if (isWebsocketRequested(session) && !WebServiceAuth.check(session.headers).authenticated) {
            return newFixedLengthResponse(
                NanoHTTPD.Response.Status.UNAUTHORIZED,
                "text/plain",
                "Unauthorized"
            ).apply {
                addHeader("WWW-Authenticate", "Bearer realm=\"legado\"")
                addHeader("Set-Cookie", WebServiceAuth.clearCookieHeader())
            }
        }
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        if (!WebServiceAuth.check(handshake.headers).authenticated) {
            return null
        }
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                BookSourceDebugWebSocket(handshake)
            }
            "/rssSourceDebug" -> {
                RssSourceDebugWebSocket(handshake)
            }
            "/searchBook" -> {
                BookSearchWebSocket(handshake)
            }
            else -> null
        }
    }
}
