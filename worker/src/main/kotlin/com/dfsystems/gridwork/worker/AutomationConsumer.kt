package com.dfsystems.gridwork.worker

import com.dfsystems.gridwork.core.automation.AutomationRunner
import com.dfsystems.gridwork.core.automation.RunResult
import com.dfsystems.gridwork.core.outbox.CellChangedPayload
import com.dfsystems.gridwork.core.outbox.SqsEventPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.concurrent.atomic.AtomicLong

/**
 * Pulls cell change events off SQS and runs the automations they trigger.
 *
 * Long polling rather than a tight receive loop: a twenty second wait costs
 * one request instead of hundreds, and it delivers a message the moment one
 * arrives rather than up to a poll interval later.
 *
 * **A message is deleted only after it has been handled.** If this process is
 * killed mid-work, the visibility timeout expires and SQS redelivers. That is
 * the at-least-once contract, and it is why AutomationRunner claims the event
 * id in the same transaction as its writes.
 *
 * **Nothing is deleted on failure.** A failed message becomes visible again,
 * is retried, and after the redrive policy's maximum receives SQS moves it to
 * the dead letter queue by itself. Writing that retry logic here would
 * duplicate something the queue already does properly.
 */
@Component
@ConditionalOnProperty(name = ["gridwork.sqs.queue-url"], matchIfMissing = false)
class AutomationConsumer(
    private val sqs: SqsClient,
    private val runner: AutomationRunner,
    private val objectMapper: ObjectMapper,
    @param:Value("\${gridwork.sqs.queue-url}") private val queueUrl: String,
    @param:Value("\${gridwork.sqs.wait-seconds:20}") private val waitSeconds: Int,
    @param:Value("\${gridwork.sqs.batch-size:10}") private val batchSize: Int,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val handled = AtomicLong()
    private val skipped = AtomicLong()
    private val duplicates = AtomicLong()
    private val failed = AtomicLong()

    @Volatile private var running = true

    @PreDestroy
    fun stop() {
        // Lets an in flight poll finish rather than leaving a receive hanging
        // when the pod is being drained.
        running = false
    }

    @Scheduled(fixedDelayString = "\${gridwork.sqs.poll-interval:100}")
    fun poll() {
        if (!running) return
        try {
            receive().forEach { handle(it) }
        } catch (throwable: RuntimeException) {
            // A consumer that dies stops every automation in the product and
            // says nothing. Log and let the next tick try again.
            log.error("automation consumer poll failed", throwable)
        }
    }

    private fun receive(): List<Message> = sqs.receiveMessage(
        ReceiveMessageRequest.builder()
            .queueUrl(queueUrl)
            .maxNumberOfMessages(batchSize)
            .waitTimeSeconds(waitSeconds)
            .messageAttributeNames("All")
            .build(),
    ).messages()

    private fun handle(message: Message) {
        val eventId = message.messageAttributes()[SqsEventPublisher.EVENT_ID_ATTRIBUTE]
            ?.stringValue()
            ?.toLongOrNull()

        if (eventId == null) {
            // Unparseable, and retrying will not change that. Delete it so it
            // does not block the queue forever; the log line is the record.
            log.error("message has no usable {} attribute, dropping", SqsEventPublisher.EVENT_ID_ATTRIBUTE)
            delete(message)
            return
        }

        val payload = try {
            objectMapper.readValue(message.body(), CellChangedPayload::class.java)
        } catch (throwable: RuntimeException) {
            log.error("event {} has an unreadable payload, dropping", eventId, throwable)
            delete(message)
            return
        }

        when (val result = runner.handle(eventId, payload)) {
            is RunResult.Applied -> {
                handled.incrementAndGet()
                log.debug("event {} applied {} action(s)", eventId, result.actions)
                delete(message)
            }
            is RunResult.AlreadyProcessed -> {
                // The at-least-once contract meeting an idempotent consumer.
                // This is the system working, not a problem.
                duplicates.incrementAndGet()
                log.debug("event {} was already processed, acknowledging the duplicate", eventId)
                delete(message)
            }
            is RunResult.Skipped -> {
                skipped.incrementAndGet()
                delete(message)
            }
            is RunResult.Failed -> {
                failed.incrementAndGet()
                if (result.retryable) {
                    // Not deleted. SQS makes it visible again and eventually
                    // sends it to the dead letter queue on its own.
                    log.warn("event {} failed and will be retried: {}", eventId, result.reason)
                } else {
                    log.error("event {} failed permanently: {}", eventId, result.reason)
                    delete(message)
                }
            }
        }
    }

    private fun delete(message: Message) {
        sqs.deleteMessage(
            DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build(),
        )
    }

    /** Exposed for the integration tests and, later, for metrics. */
    fun counters(): Map<String, Long> = mapOf(
        "handled" to handled.get(),
        "duplicates" to duplicates.get(),
        "skipped" to skipped.get(),
        "failed" to failed.get(),
    )
}
