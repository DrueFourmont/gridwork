package com.dfsystems.gridwork.api.realtime

import com.dfsystems.gridwork.core.realtime.Outbound
import com.dfsystems.gridwork.core.realtime.SheetEventPublisher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

/**
 * Which sockets on THIS replica are watching which sheet, and the Redis
 * subscription that feeds them.
 *
 * The map is deliberately per replica and in memory. That is not a violation
 * of "API replicas are stateless": nothing here needs to survive a restart. A
 * dropped socket is a dropped socket, the browser reconnects, and the replay
 * protocol fills the gap. State that must survive lives in Postgres.
 *
 * A replica subscribes to a sheet's Redis channel only while it has at least
 * one viewer, and unsubscribes when the last one leaves. Otherwise every
 * replica would wake for every change to every sheet in the product.
 */
@Component
class SheetSubscriptions(
    private val listenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper,
) : MessageListener {

    private val log = LoggerFactory.getLogger(javaClass)
    private val viewers = ConcurrentHashMap<String, MutableSet<WebSocketSession>>()
    private val topics = ConcurrentHashMap<String, ChannelTopic>()

    fun join(sheetId: String, session: WebSocketSession) {
        viewers.compute(sheetId) { _, existing ->
            val sessions = existing ?: ConcurrentHashMap.newKeySet()
            sessions.add(session)
            sessions
        }
        topics.computeIfAbsent(sheetId) { id ->
            val topic = ChannelTopic(SheetEventPublisher.channelFor(id))
            listenerContainer.addMessageListener(this, topic)
            log.debug("replica subscribed to {}", topic.topic)
            topic
        }
    }

    fun leave(sheetId: String, session: WebSocketSession) {
        val remaining = viewers.computeIfPresent(sheetId) { _, sessions ->
            sessions.remove(session)
            if (sessions.isEmpty()) null else sessions
        }
        if (remaining == null) {
            // Last viewer on this replica left. Stop listening so this replica
            // does no work for a sheet nobody here is watching.
            topics.remove(sheetId)?.let { topic ->
                listenerContainer.removeMessageListener(this, topic)
                log.debug("replica unsubscribed from {}", topic.topic)
            }
        }
    }

    /** A change arrived from Redis, published by this replica or any other. */
    override fun onMessage(message: Message, pattern: ByteArray?) {
        val channel = String(message.channel)
        val sheetId = channel.substringAfterLast(':')
        val sessions = viewers[sheetId] ?: return

        val changes: List<Outbound.CellChanged> = try {
            objectMapper.readValue(String(message.body))
        } catch (throwable: RuntimeException) {
            log.warn("could not read a live update on {}", channel, throwable)
            return
        }

        for (change in changes) {
            val payload = TextMessage(objectMapper.writeValueAsString(change))
            for (session in sessions) {
                send(session, payload)
            }
        }
    }

    private fun send(session: WebSocketSession, payload: TextMessage) {
        if (!session.isOpen) return
        try {
            // Sends on one session are synchronised because a WebSocketSession
            // is not safe for concurrent writes, and two sheets changing at
            // once would otherwise interleave frames and corrupt the stream.
            synchronized(session) { session.sendMessage(payload) }
        } catch (throwable: Exception) {
            log.debug("dropping a live update for a closing session", throwable)
        }
    }

    fun viewerCount(sheetId: String): Int = viewers[sheetId]?.size ?: 0
}
