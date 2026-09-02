package com.dfsystems.gridwork.core.realtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/** Raised by CellService inside the transaction. Published only once that transaction commits. */
data class CellsChangedEvent(val changes: List<Outbound.CellChanged>)

/**
 * Puts committed cell changes onto Redis so every API replica sees them.
 *
 * Two details carry the whole design.
 *
 * **AFTER_COMMIT, not during.** If this published while the transaction were
 * still open, another replica could receive the message, refetch the cell, and
 * read the old value, because the write is not visible yet. Worse, a
 * transaction that later rolls back would have already announced a change that
 * never happened. Spring's TransactionPhase.AFTER_COMMIT is exactly this
 * guarantee, and it is why the event is raised rather than published directly.
 *
 * **Best effort on purpose.** Redis pub/sub delivers to whoever is listening
 * and forgets everyone else. A dropped message is not a correctness problem
 * here, because the client can replay from cell_history on reconnect. Events
 * that genuinely must not be lost go through the outbox in Phase 4, which is a
 * different mechanism for a different guarantee. See ADR 0007.
 */
@Component
class SheetEventPublisher(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommitted(event: CellsChangedEvent) {
        publish(event)
    }

    /**
     * For the rare caller that is not inside a transaction. A plain
     * EventListener would otherwise silently swallow the event, because
     * TransactionalEventListener does nothing when there is no transaction.
     */
    @EventListener(condition = "false")
    fun never(@Suppress("UNUSED_PARAMETER") event: CellsChangedEvent) = Unit

    private fun publish(event: CellsChangedEvent) {
        for ((sheetId, changes) in event.changes.groupBy { it.sheetId }) {
            try {
                redis.convertAndSend(channelFor(sheetId), objectMapper.writeValueAsString(changes))
            } catch (throwable: RuntimeException) {
                // A failed fan-out must never fail the request. The write is
                // already committed and durable; the only casualty is that
                // other viewers find out on their next refetch instead of now.
                log.warn("live fan-out failed for sheet {}, clients will recover on replay", sheetId, throwable)
            }
        }
    }

    companion object {
        /**
         * One channel per sheet, so a replica with no viewers of a sheet does
         * no work for it. A single global channel would wake every replica for
         * every keystroke in the product.
         */
        fun channelFor(sheetId: String): String = "gridwork:sheet:$sheetId"
    }
}
