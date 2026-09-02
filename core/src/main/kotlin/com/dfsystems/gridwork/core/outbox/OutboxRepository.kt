package com.dfsystems.gridwork.core.outbox

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

data class OutboxEvent(
    val id: Long,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val attempts: Int,
)

@Repository
class OutboxRepository(private val jdbc: NamedParameterJdbcTemplate) {

    /**
     * Appends events. Called from inside the caller's transaction on purpose:
     * the event and the data it describes commit together or not at all.
     */
    fun append(events: List<NewEvent>) {
        if (events.isEmpty()) return
        val batch = events.map { event ->
            MapSqlParameterSource()
                .addValue("aggregateType", event.aggregateType)
                .addValue("aggregateId", event.aggregateId)
                .addValue("eventType", event.eventType)
                .addValue("payload", event.payload)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            insert into outbox_events (aggregate_type, aggregate_id, event_type, payload)
            values (:aggregateType, :aggregateId, :eventType, cast(:payload as jsonb))
            """.trimIndent(),
            batch,
        )
    }

    /**
     * Claims a batch of unpublished events for this relay instance.
     *
     * `for update skip locked` is the entire reason more than one API replica
     * can run a relay safely. Each replica locks the rows it takes, and the
     * others step over them instead of blocking. Without SKIP LOCKED the
     * replicas would serialise behind each other, and without FOR UPDATE they
     * would all publish the same events.
     *
     * Runs inside the caller's transaction, so the locks are held until the
     * publish result is written and the transaction commits.
     */
    fun claimUnpublished(limit: Int): List<OutboxEvent> = jdbc.query(
        """
        select id, aggregate_type, aggregate_id, event_type, payload::text as payload, attempts
        from outbox_events
        where published_at is null
        order by id
        limit :limit
        for update skip locked
        """.trimIndent(),
        MapSqlParameterSource("limit", limit),
    ) { rs, _ ->
        OutboxEvent(
            id = rs.getLong("id"),
            aggregateType = rs.getString("aggregate_type"),
            aggregateId = rs.getObject("aggregate_id", UUID::class.java),
            eventType = rs.getString("event_type"),
            payload = rs.getString("payload"),
            attempts = rs.getInt("attempts"),
        )
    }

    fun markPublished(ids: List<Long>) {
        if (ids.isEmpty()) return
        jdbc.update(
            "update outbox_events set published_at = now() where id in (:ids)",
            MapSqlParameterSource("ids", ids),
        )
    }

    /**
     * Records a failed publish without marking the event done, so the next
     * pass tries again. The attempt count and the error are kept because an
     * event stuck at forty attempts is an operational fact somebody needs.
     */
    fun markFailed(ids: List<Long>, error: String) {
        if (ids.isEmpty()) return
        jdbc.update(
            """
            update outbox_events
            set attempts = attempts + 1, last_error = :error
            where id in (:ids)
            """.trimIndent(),
            MapSqlParameterSource().addValue("ids", ids).addValue("error", error.take(1000)),
        )
    }

    fun unpublishedCount(): Long =
        jdbc.queryForObject(
            "select count(*) from outbox_events where published_at is null",
            MapSqlParameterSource(),
            Long::class.java,
        ) ?: 0

    data class NewEvent(
        val aggregateType: String,
        val aggregateId: UUID,
        val eventType: String,
        val payload: String,
    )
}
