package com.dfsystems.gridwork.api.config

import com.dfsystems.gridwork.api.realtime.SheetWebSocketHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val handler: SheetWebSocketHandler,
    /**
     * Websocket handshakes are not covered by CORS, so the browser will not
     * stop a page on any origin from opening one. This list is the only thing
     * that does, which is why it is an explicit setting rather than a wildcard.
     *
     * In development it is the Vite dev server. In production it is the one
     * domain the app is served from.
     */
    @param:Value("\${gridwork.websocket.allowed-origins}")
    private val allowedOrigins: List<String>,
) : WebSocketConfigurer {

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, SheetWebSocketHandler.PATH)
            .setAllowedOrigins(*allowedOrigins.toTypedArray())
    }
}
