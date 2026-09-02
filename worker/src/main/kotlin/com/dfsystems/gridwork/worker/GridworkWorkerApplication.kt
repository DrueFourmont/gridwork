package com.dfsystems.gridwork.worker

import org.slf4j.LoggerFactory
import com.dfsystems.gridwork.core.CoreConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component

/**
 * The automation worker. It consumes SQS and performs automation actions
 * through the same domain services the API uses, so versions, history,
 * permissions, and the outbox all apply to an automation action exactly as
 * they do to a human edit. It never writes to tables directly.
 *
 * Phase 0 ships the module, the SQS client on the classpath, and nothing
 * else. The SQS listener, the idempotency key check, the DLQ, and the loop
 * depth rule all arrive in Phase 4.
 */
@SpringBootApplication
@Import(CoreConfiguration::class)
class GridworkWorkerApplication

@Component
class WorkerStartupLogger {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        log.info("gridwork worker started, no queue consumer yet, see Phase 4")
    }
}

fun main(args: Array<String>) {
    runApplication<GridworkWorkerApplication>(*args)
}
