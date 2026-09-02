package com.dfsystems.gridwork.core.service

import com.dfsystems.gridwork.core.persistence.IdempotencyRepository
import com.dfsystems.gridwork.core.error.ConflictException
import com.dfsystems.gridwork.core.error.UnprocessableException
import com.dfsystems.gridwork.domain.UserId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Makes an unsafe request safe to retry, per ADR 0003.
 *
 * The shape of the problem: a client POSTs, the connection drops, and the
 * client has no idea whether the sheet was created. It retries. Without this,
 * it gets two sheets.
 *
 * The protocol, in order:
 *
 *  1. Claim the key. `insert ... on conflict do nothing` decides the winner in
 *     the database, so two simultaneous requests cannot both proceed.
 *  2. If the claim failed, someone got there first. Either they are still
 *     running, which is a 409 telling the client to wait, or they finished,
 *     and their stored response is replayed verbatim.
 *  3. A replay whose body differs from the original is a 422. The same key
 *     meaning two different operations is a client bug, and quietly returning
 *     the first result would hide it.
 *
 * The claim and the completion are committed independently of the operation's
 * own transaction, which is why [claim] and [complete] run REQUIRES_NEW. If the
 * claim shared the caller's transaction it would be invisible to a concurrent
 * request until commit, which is exactly when it is needed.
 */
@Service
class IdempotencyService(
    private val repository: IdempotencyRepository,
    private val objectMapper: ObjectMapper,
) {

    data class Outcome<T>(val value: T?, val replayedBody: String?, val status: Int)

    fun <T : Any> execute(
        key: String?,
        userId: UserId,
        method: String,
        path: String,
        requestBody: Any?,
        successStatus: Int,
        operation: () -> T,
    ): Outcome<T> {
        // No key means the caller has not asked for the guarantee. Honest
        // behaviour is to just do the work rather than invent a key for them.
        if (key.isNullOrBlank()) {
            return Outcome(operation(), null, successStatus)
        }
        if (key.length > MAX_KEY_LENGTH) {
            throw UnprocessableException("Idempotency-Key must be at most $MAX_KEY_LENGTH characters.")
        }

        val fingerprint = fingerprint(requestBody)
        val claimed = claim(key, userId, method, path, fingerprint)

        if (!claimed) {
            val existing = repository.find(key, userId, method, path)
                ?: throw ConflictException("The idempotency key is in use. Retry shortly.")
            if (existing.fingerprint != fingerprint) {
                throw UnprocessableException(
                    "This Idempotency-Key was already used with a different request body.",
                )
            }
            if (existing.inFlight) {
                throw ConflictException(
                    "A request with this Idempotency-Key is still in progress. Retry shortly.",
                )
            }
            return Outcome(null, existing.responseBody, existing.responseStatus)
        }

        val result = try {
            operation()
        } catch (throwable: Throwable) {
            // Release, not complete. A failure must not be cached as if it were
            // the operation's answer, or a retry after a transient database
            // error would replay the failure forever.
            release(key, userId, method, path)
            throw throwable
        }
        complete(key, userId, method, path, successStatus, objectMapper.writeValueAsString(result))
        return Outcome(result, null, successStatus)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(key: String, userId: UserId, method: String, path: String, fingerprint: String): Boolean =
        repository.claim(key, userId, method, path, fingerprint)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(key: String, userId: UserId, method: String, path: String, status: Int, body: String) =
        repository.complete(key, userId, method, path, status, body)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun release(key: String, userId: UserId, method: String, path: String) =
        repository.release(key, userId, method, path)

    /**
     * A hash of the request body, so a replay can be checked without storing
     * the original request. SHA-256 because a collision here would let one
     * request return another's response.
     */
    private fun fingerprint(body: Any?): String {
        val json = if (body == null) "null" else objectMapper.writeValueAsString(body)
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
        return HexFormat.of().formatHex(digest)
    }

    companion object {
        const val MAX_KEY_LENGTH = 200
        const val HEADER = "Idempotency-Key"
    }
}
