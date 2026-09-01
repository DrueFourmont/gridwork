package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The headline claim of Phase 3: two API replicas, and a change written to one
 * reaches a browser connected to the other.
 *
 * This starts a SECOND, entirely separate Spring application context on its
 * own port, sharing the same Postgres and the same Redis as the one the test
 * class already runs. That is what makes the test meaningful. A single context
 * would pass even if Redis were removed entirely, because the publisher and
 * the subscriber would be the same object in the same JVM, and the whole point
 * is that they are not.
 */
class TwoReplicaRealtimeIT : ApiIntegrationTest() {

    /** Collects frames off a socket so a test can wait for one. */
    private class Collector : TextWebSocketHandler() {
        val messages: BlockingQueue<String> = LinkedBlockingQueue()
        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
            messages.add(message.payload)
        }
    }

    private fun connect(port: Int, token: String, sheetId: String, lastSeen: Long?): Pair<WebSocketSession, Collector> {
        val collector = Collector()
        val session = StandardWebSocketClient()
            .execute(collector, WebSocketHttpHeaders(), java.net.URI("ws://localhost:$port/ws/sheet"))
            .get(10, TimeUnit.SECONDS)
        val auth = buildString {
            append("""{"token":"$token","sheetId":"$sheetId"""")
            append(if (lastSeen == null) "}" else ""","lastSeen":$lastSeen}""")
        }
        session.sendMessage(TextMessage(auth))
        return session to collector
    }

    private fun Collector.awaitMessage(seconds: Long = 10): JsonNode {
        val payload = messages.poll(seconds, TimeUnit.SECONDS)
            ?: error("no websocket message arrived within ${seconds}s")
        return objectMapper.readTree(payload)
    }

    @Test
    fun `a write on replica one reaches a client connected to replica two`() {
        val user = register()
        val sheet = createSheet(user, "Cross replica")
        val column = addColumn(user, sheet, "Task", "TEXT")
        val row = addRow(user, sheet)

        val (session, collector) = connect(secondReplicaPort(), user.token, sheet, lastSeen = null)
        try {
            // A brand new client has no cursor, so it is told to resync rather
            // than replayed. That is the ReplayRule's NO_CURSOR case.
            val first = collector.awaitMessage()
            require(first["type"].asText() == "resync") { "expected resync, got $first" }

            // The write goes to the FIRST replica, over REST.
            batchUpdate(user, sheet, listOf(update(row, column, "written on replica one", 1)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)

            // It must arrive on the socket held by the SECOND replica, which
            // can only happen through Redis.
            val live = collector.awaitMessage()
            require(live["type"].asText() == "cellChanged") { "expected cellChanged, got $live" }
            require(live["value"].asText() == "written on replica one")
            require(live["rowId"].asText() == row)
            require(live["version"].asLong() == 2L)
            require(live["sequence"].asLong() > 0L) { "a live update must carry a replay sequence" }
        } finally {
            session.close(CloseStatus.NORMAL)
        }
    }

    @Test
    fun `a reconnecting client is replayed the changes it missed`() {
        val user = register()
        val sheet = createSheet(user, "Replay across replicas")
        val column = addColumn(user, sheet, "Task", "TEXT")
        val row = addRow(user, sheet)

        // Connect, learn the sequence, then disconnect and miss two writes.
        val (first, firstCollector) = connect(secondReplicaPort(), user.token, sheet, lastSeen = null)
        val ready = firstCollector.awaitMessage()
        val sequenceBefore = ready["sequence"].asLong()
        first.close(CloseStatus.NORMAL)

        batchUpdate(user, sheet, listOf(update(row, column, "missed one", 1)))
        batchUpdate(user, sheet, listOf(update(row, column, "missed two", 2)))

        val (second, secondCollector) = connect(secondReplicaPort(), user.token, sheet, lastSeen = sequenceBefore)
        try {
            val replayed = secondCollector.awaitMessage()
            require(replayed["type"].asText() == "replayed") { "expected replayed, got $replayed" }
            val changes = replayed["changes"]
            require(changes.size() == 2) { "expected 2 missed changes, got ${changes.size()}" }
            require(changes[0]["value"].asText() == "missed one")
            require(changes[1]["value"].asText() == "missed two")
            // Ordered oldest first, so a client applies them exactly as if it
            // had been connected the whole time.
            require(changes[0]["sequence"].asLong() < changes[1]["sequence"].asLong())
        } finally {
            second.close(CloseStatus.NORMAL)
        }
    }

    @Test
    fun `a socket with a bad token is rejected and closed`() {
        val user = register()
        val sheet = createSheet(user, "Bad token")
        val (session, collector) = connect(secondReplicaPort(), "not-a-real-token", sheet, null)
        try {
            val message = collector.awaitMessage()
            require(message["type"].asText() == "error") { "expected error, got $message" }
        } finally {
            session.close(CloseStatus.NORMAL)
        }
    }

    @Test
    fun `a socket cannot watch a sheet its user cannot read`() {
        val owner = register()
        val stranger = register()
        val sheet = createSheet(owner, "Private")

        val (session, collector) = connect(secondReplicaPort(), stranger.token, sheet, null)
        try {
            val message = collector.awaitMessage()
            // Reported as not found rather than forbidden, the same as REST,
            // so sheet ids cannot be enumerated over a socket either.
            require(message["type"].asText() == "error") { "expected error, got $message" }
        } finally {
            session.close(CloseStatus.NORMAL)
        }
    }

    companion object {
        private var second: ConfigurableApplicationContext? = null

        @JvmStatic
        fun secondReplicaPort(): Int {
            // Passed as command line arguments, not through .properties().
            // SpringApplicationBuilder.properties() registers DEFAULT
            // properties, which sit at the bottom of Spring's precedence order
            // and lose to application.yml. Command line arguments sit near the
            // top, which is what is needed to point this replica at the
            // containers the test started.
            val context = second ?: SpringApplicationBuilder(GridworkApiApplication::class.java)
                .run(
                    "--server.port=0",
                    "--spring.profiles.active=test",
                    "--spring.datasource.url=" + jdbcUrl(),
                    "--spring.datasource.username=" + dbUser(),
                    "--spring.datasource.password=" + dbPassword(),
                    "--spring.data.redis.url=" + redisUrl(),
                    // This replica must not race the first one running
                    // migrations against the same database.
                    "--spring.flyway.enabled=false",
                )
                .also { second = it }
            return context.environment.getProperty("local.server.port")!!.toInt()
        }

        @AfterAll
        @JvmStatic
        fun stopSecondReplica() {
            second?.close()
            second = null
        }
    }
}
