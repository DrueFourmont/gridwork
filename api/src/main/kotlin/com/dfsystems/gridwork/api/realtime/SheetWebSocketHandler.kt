package com.dfsystems.gridwork.api.realtime

import com.dfsystems.gridwork.core.realtime.Inbound
import com.dfsystems.gridwork.core.realtime.Outbound

import com.dfsystems.gridwork.core.persistence.CellHistoryRepository
import com.dfsystems.gridwork.core.service.AccessService
import com.dfsystems.gridwork.core.error.ApiException
import com.dfsystems.gridwork.domain.ReplayDecision
import com.dfsystems.gridwork.domain.ReplayRule
import com.dfsystems.gridwork.domain.Sequence
import com.dfsystems.gridwork.domain.SheetId
import com.dfsystems.gridwork.domain.UserId
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID

/**
 * One socket, one sheet.
 *
 * The connection is not trusted until its first frame authenticates it. A
 * browser cannot set an Authorization header on a websocket handshake, and the
 * usual workaround, a token in the query string, puts a live credential into
 * access logs and proxy logs. So the token arrives in the body of the first
 * message, and an unauthenticated socket is allowed to do nothing else.
 *
 * Authorisation reuses AccessService, the same check the REST endpoints go
 * through. A separate permission path for websockets is how one of them ends
 * up wrong.
 */
@Component
class SheetWebSocketHandler(
    private val jwtDecoder: JwtDecoder,
    private val access: AccessService,
    private val history: CellHistoryRepository,
    private val subscriptions: SheetSubscriptions,
    private val objectMapper: ObjectMapper,
) : TextWebSocketHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        if (session.attributes[SHEET_ID] != null) {
            // Already authenticated. This socket is one way: the server pushes
            // changes, and writes go through the REST API so they get the same
            // validation, versioning, and idempotency as any other write.
            return
        }
        try {
            authenticate(session, message)
        } catch (exception: ApiException) {
            reject(session, exception.message)
        } catch (exception: Exception) {
            log.debug("websocket authentication failed", exception)
            reject(session, "Authentication failed.")
        }
    }

    private fun authenticate(session: WebSocketSession, message: TextMessage) {
        val request: Inbound.Authenticate = objectMapper.readValue(message.payload)

        // Throws if the signature is wrong or the token has expired, which is
        // the same check the REST filter chain performs.
        val jwt = jwtDecoder.decode(request.token)
        val userId = UserId(UUID.fromString(jwt.subject))
        val sheetId = SheetId(UUID.fromString(request.sheetId))

        // Reuses the REST permission rules, including reporting a sheet the
        // caller cannot see as absent rather than forbidden.
        access.requireRead(sheetId, userId)

        session.attributes[SHEET_ID] = request.sheetId
        session.attributes[USER_ID] = userId.toString()

        val latest = history.latestSequence(sheetId)
        val decision = ReplayRule.decide(
            lastSeen = request.lastSeen?.let { Sequence(it) },
            latest = latest,
            maxReplay = ReplayRule.DEFAULT_MAX_REPLAY,
        )

        // Subscribe before sending the catch up, not after. In the other order
        // a change committed between the two would fall in the gap: too late
        // for the replay, too early for the subscription.
        subscriptions.join(request.sheetId, session)

        when (decision) {
            is ReplayDecision.Live ->
                send(session, Outbound.Ready(request.sheetId, latest.value))

            is ReplayDecision.Resync ->
                send(session, Outbound.Resync(decision.reason, latest.value))

            is ReplayDecision.Replay -> {
                val changes = history.changesAfter(sheetId, decision.from, ReplayRule.DEFAULT_MAX_REPLAY)
                send(session, Outbound.Replayed(changes, latest.value))
            }
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        (session.attributes[SHEET_ID] as? String)?.let { sheetId ->
            subscriptions.leave(sheetId, session)
        }
    }

    private fun send(session: WebSocketSession, payload: Outbound) {
        synchronized(session) {
            session.sendMessage(TextMessage(objectMapper.writeValueAsString(payload)))
        }
    }

    private fun reject(session: WebSocketSession, reason: String?) {
        try {
            send(session, Outbound.Failed(reason ?: "Authentication failed."))
            session.close(CloseStatus.POLICY_VIOLATION)
        } catch (exception: Exception) {
            log.debug("could not close a rejected websocket cleanly", exception)
        }
    }

    companion object {
        const val SHEET_ID = "sheetId"
        const val USER_ID = "userId"
        const val PATH = "/ws/sheet"
    }
}
