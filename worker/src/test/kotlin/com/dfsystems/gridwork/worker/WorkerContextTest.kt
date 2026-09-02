package com.dfsystems.gridwork.worker

import com.dfsystems.gridwork.core.service.CellService
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * The worker boots with the shared services wired in.
 *
 * This test changed shape in Phase 4. It used to assert that the worker
 * started with no database at all, which was true when the module did nothing.
 * Now the worker performs automation actions through the same services the API
 * uses, per CLAUDE.md, so it needs the same database those services talk to.
 * Needing a database is the point rather than a regression.
 *
 * It still has no web server, which is checked below, because a worker that
 * quietly grew a servlet container would be a different thing than the one the
 * plan describes.
 */
@SpringBootTest
class WorkerContextTest {

    @Autowired private lateinit var context: ApplicationContext

    @Test
    fun `the worker context starts`() {
        context shouldNotBe null
    }

    @Test
    fun `the worker shares the api's cell service rather than its own copy`() {
        // The whole reason core/ exists. If this resolves, an automation action
        // goes through the same versioning, history, and permission checks as a
        // human edit, because it is literally the same class.
        context.getBean(CellService::class.java) shouldNotBe null
    }

    @Test
    fun `the worker has no web server`() {
        val webBeans = context.beanDefinitionNames.filter {
            it.contains("tomcat", ignoreCase = true) || it.contains("dispatcherServlet", ignoreCase = true)
        }
        check(webBeans.isEmpty()) { "the worker should not have a servlet container, found $webBeans" }
    }

    @Test
    fun `the aws sqs client is on the classpath`() {
        Class.forName("software.amazon.awssdk.services.sqs.SqsClient") shouldNotBe null
    }

    companion object {
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @JvmStatic
        private val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.data.redis.url") {
                "redis://" + redis.host + ":" + redis.getMappedPort(6379).toString()
            }
            // The API owns migrations, so this schema is created by Flyway
            // running from the api module's test resources in a real
            // deployment. Here there is nothing to validate against yet, so
            // Hibernate is told not to try.
            registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
        }
    }
}
