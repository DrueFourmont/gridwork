package com.dfsystems.gridwork.core.outbox

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry

/**
 * Publishes outbox events to SQS.
 *
 * Batched ten at a time because that is the SQS limit, and because one call
 * per event would make the relay's throughput a function of network latency.
 *
 * The event id travels as a message attribute rather than only inside the
 * body, so the worker can deduplicate without parsing the payload, and so a
 * message sitting in the dead letter queue can be identified by eye.
 */
@Component
@ConditionalOnProperty(name = ["gridwork.sqs.queue-url"], matchIfMissing = false)
class SqsEventPublisher(
    private val sqs: SqsClient,
    @param:Value("\${gridwork.sqs.queue-url}") private val queueUrl: String,
) : EventPublisher {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(events: List<OutboxEvent>): List<Long> =
        events.chunked(SQS_BATCH_LIMIT).flatMap { chunk -> publishChunk(chunk) }

    private fun publishChunk(chunk: List<OutboxEvent>): List<Long> {
        val entries = chunk.map { event ->
            SendMessageBatchRequestEntry.builder()
                .id(event.id.toString())
                .messageBody(event.payload)
                .messageAttributes(
                    mapOf(
                        EVENT_ID_ATTRIBUTE to stringAttribute(event.id.toString()),
                        EVENT_TYPE_ATTRIBUTE to stringAttribute(event.eventType),
                    ),
                )
                .build()
        }

        return try {
            val response = sqs.sendMessageBatch(
                SendMessageBatchRequest.builder().queueUrl(queueUrl).entries(entries).build(),
            )
            if (response.hasFailed() && response.failed().isNotEmpty()) {
                // A partial failure is normal and survivable: the ones that
                // did not go stay unpublished and are retried next pass.
                log.warn("{} of {} messages failed to send", response.failed().size, entries.size)
            }
            response.successful().map { it.id().toLong() }
        } catch (throwable: RuntimeException) {
            // Nothing in this chunk is marked published, so all of it is
            // retried. That is the at-least-once half of the bargain; the
            // worker's dedupe is the other half.
            log.warn("sqs batch send failed, {} events will be retried", entries.size, throwable)
            emptyList()
        }
    }

    private fun stringAttribute(value: String) =
        software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
            .dataType("String")
            .stringValue(value)
            .build()

    companion object {
        /** SQS accepts at most ten messages per batch call. */
        const val SQS_BATCH_LIMIT = 10
        const val EVENT_ID_ATTRIBUTE = "gridworkEventId"
        const val EVENT_TYPE_ATTRIBUTE = "gridworkEventType"
    }
}
