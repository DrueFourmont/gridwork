package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.api.service.IdempotencyService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * Turns an idempotency outcome into a response.
 *
 * A replay returns the stored body verbatim as a raw string, rather than
 * deserialising and re-serialising it. Round tripping through objects would
 * mean a later change to a DTO silently changes the answer a client already
 * received, which defeats the point of storing it.
 *
 * The `Idempotent-Replay: true` header is an extension, not a standard, but it
 * makes replays visible in a log and in a test without parsing the body.
 */
@Component
class IdempotentResponses {

    fun <T : Any> render(outcome: IdempotencyService.Outcome<T>): ResponseEntity<*> {
        val replayed = outcome.replayedBody
        return if (replayed != null) {
            ResponseEntity.status(outcome.status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAY_HEADER, "true")
                .body(replayed)
        } else {
            ResponseEntity.status(outcome.status).body(outcome.value)
        }
    }

    companion object {
        const val REPLAY_HEADER = "Idempotent-Replay"
    }
}
