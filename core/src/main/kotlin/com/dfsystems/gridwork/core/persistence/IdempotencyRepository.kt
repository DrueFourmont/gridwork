package com.dfsystems.gridwork.core.persistence

import com.dfsystems.gridwork.domain.UserId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class IdempotencyRecord(
    val fingerprint: String,
    val responseStatus: Int,
    val responseBody: String,
) {
    /** A reservation that no response has been written to yet. */
    val inFlight: Boolean get() = responseStatus == IN_FLIGHT

    companion object {
        const val IN_FLIGHT = 0
    }
}

@Repository
class IdempotencyRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Claims a key, returning true if this caller now owns the operation.
     *
     * `on conflict do nothing` makes the claim atomic: of two concurrent
     * requests carrying the same key, exactly one gets true back and the other
     * gets false, decided by Postgres rather than by application timing. This
     * is what stops a double click from creating two sheets.
     */
    fun claim(key: String, userId: UserId, method: String, path: String, fingerprint: String): Boolean =
        jdbc.update(
            """
            insert into idempotency_keys
                (key, user_id, method, path, request_fingerprint, response_status, response_body)
            values
                (:key, :userId, :method, :path, :fingerprint, ${IdempotencyRecord.IN_FLIGHT}, '')
            on conflict (user_id, method, path, key) do nothing
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("userId", userId.value)
                .addValue("method", method)
                .addValue("path", path)
                .addValue("fingerprint", fingerprint),
        ) == 1

    fun find(key: String, userId: UserId, method: String, path: String): IdempotencyRecord? =
        jdbc.query(
            """
            select request_fingerprint, response_status, response_body
            from idempotency_keys
            where user_id = :userId and method = :method and path = :path and key = :key
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("userId", userId.value)
                .addValue("method", method)
                .addValue("path", path),
        ) { rs, _ ->
            IdempotencyRecord(
                fingerprint = rs.getString("request_fingerprint"),
                responseStatus = rs.getInt("response_status"),
                responseBody = rs.getString("response_body"),
            )
        }.firstOrNull()

    fun complete(
        key: String,
        userId: UserId,
        method: String,
        path: String,
        status: Int,
        body: String,
    ) {
        jdbc.update(
            """
            update idempotency_keys
            set response_status = :status, response_body = :body
            where user_id = :userId and method = :method and path = :path and key = :key
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("userId", userId.value)
                .addValue("method", method)
                .addValue("path", path)
                .addValue("status", status)
                .addValue("body", body),
        )
    }

    /**
     * Drops a reservation whose operation failed, so the caller can retry.
     *
     * Without this, a request that errors would poison its key forever: every
     * retry would see an in-flight reservation that will never complete.
     */
    fun release(key: String, userId: UserId, method: String, path: String) {
        jdbc.update(
            """
            delete from idempotency_keys
            where user_id = :userId and method = :method and path = :path and key = :key
              and response_status = ${IdempotencyRecord.IN_FLIGHT}
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("key", key)
                .addValue("userId", userId.value)
                .addValue("method", method)
                .addValue("path", path),
        )
    }
}
