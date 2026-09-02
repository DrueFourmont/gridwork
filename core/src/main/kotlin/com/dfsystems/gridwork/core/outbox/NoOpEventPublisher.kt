package com.dfsystems.gridwork.core.outbox

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * Used when no queue is configured, which is the default for a developer
 * running `make api` without LocalStack.
 *
 * It deliberately does NOT mark events published: it returns no ids, so events
 * accumulate in the outbox and are delivered once a queue appears. Silently
 * discarding them would make the local setup look like it works while quietly
 * dropping every automation.
 */
@Component
@ConditionalOnMissingBean(SqsEventPublisher::class)
class NoOpEventPublisher : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)
    private var warned = false

    override fun publish(events: List<OutboxEvent>): List<Long> {
        if (!warned && events.isNotEmpty()) {
            log.warn(
                "no SQS queue is configured, so {} outbox event(s) are waiting. " +
                    "Set gridwork.sqs.queue-url to deliver them.",
                events.size,
            )
            warned = true
        }
        return emptyList()
    }
}
