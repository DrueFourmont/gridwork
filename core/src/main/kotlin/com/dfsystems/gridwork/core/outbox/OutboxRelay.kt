package com.dfsystems.gridwork.core.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Where a claimed batch of events is sent. Implemented over SQS; faked in tests. */
interface EventPublisher {
    /** Returns the ids that were published successfully. */
    fun publish(events: List<OutboxEvent>): List<Long>
}

/**
 * Moves events from the outbox table to the queue.
 *
 * This is the half of the transactional outbox pattern that people skip, and
 * it is where the interesting failure modes live.
 *
 * **Exactly once is a claim about effects, not about deliveries.** This relay
 * is at-least-once: it can publish an event and then fail before recording
 * that it did, and the next pass will publish it again. That is unavoidable
 * without a distributed transaction across Postgres and SQS, which is exactly
 * what the outbox exists to avoid needing. The duplicate is made harmless at
 * the other end, by the worker refusing to process an event id twice. See
 * ADR 0009.
 *
 * **Every replica can run this.** `for update skip locked` means each pass
 * claims rows nobody else holds, and the others step over them rather than
 * waiting. No leader election, no scheduler lock, no single point of failure.
 *
 * **The publish happens inside the claiming transaction.** That holds the row
 * locks for the length of an SQS call, which is a real cost, and it buys the
 * guarantee that two relays cannot publish the same event concurrently. The
 * alternative, claiming and then publishing outside the transaction, releases
 * the lock while the message is in flight.
 */
@Component
@ConditionalOnProperty(name = ["gridwork.outbox.relay.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelay(
    private val outbox: OutboxRepository,
    private val publisher: EventPublisher,
    @param:Value("\${gridwork.outbox.relay.batch-size:100}") private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${gridwork.outbox.relay.interval:1000}")
    fun drain() {
        try {
            var published: Int
            // Keep going while there is a full batch waiting, so a burst is
            // cleared promptly rather than at one batch per tick.
            do {
                published = drainOnce()
            } while (published >= batchSize)
        } catch (throwable: RuntimeException) {
            // A relay that dies takes the whole automation pipeline with it,
            // silently, because nothing else reports on it. Catch and log, and
            // let the next tick try again.
            log.error("outbox relay pass failed", throwable)
        }
    }

    @Transactional
    fun drainOnce(): Int {
        val claimed = outbox.claimUnpublished(batchSize)
        if (claimed.isEmpty()) return 0

        val publishedIds = publisher.publish(claimed)
        outbox.markPublished(publishedIds)

        val failed = claimed.map { it.id } - publishedIds.toSet()
        if (failed.isNotEmpty()) {
            outbox.markFailed(failed, "publish returned no id for these events")
            log.warn("{} outbox events failed to publish and will be retried", failed.size)
        }
        return publishedIds.size
    }
}
