package com.dfsystems.gridwork.worker

import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * Phase 0 scaffold check. Proves the worker context starts with no database,
 * no web server, and no queue, which is what makes it a separate deployable
 * rather than a second copy of the API.
 */
@SpringBootTest
class WorkerContextTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `the worker context starts`() {
        context shouldNotBe null
    }

    @Test
    fun `the aws sqs client is on the classpath`() {
        Class.forName("software.amazon.awssdk.services.sqs.SqsClient") shouldNotBe null
    }
}
