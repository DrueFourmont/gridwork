package com.dfsystems.gridwork.api.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Base class for the API integration tests.
 *
 * One Postgres container for the whole suite, started once and reused, with the
 * tables truncated between tests. Starting a container per test class would
 * turn a six second suite into a two minute one; sharing state between tests
 * would make them order dependent. Truncation is the middle path.
 *
 * These go through the full stack: the real filter chain, real security, real
 * Flyway migrations, real SQL. A test that mocks the repository cannot tell you
 * that the versioned UPDATE works, and that is the thing worth knowing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
abstract class ApiIntegrationTest {

    @Autowired protected lateinit var mockMvc: MockMvc
    @Autowired protected lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetDatabase() {
        // Not drop and recreate: Flyway would have to run again. Truncating the
        // data tables leaves the schema and the migration history intact.
        jdbc.execute(
            """
            truncate table cell_history, cells, rows, columns,
                           sheet_members, sheets, idempotency_keys, users
            restart identity cascade
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------ helpers ----

    protected fun register(
        email: String = "user-${UUID.randomUUID()}@example.com",
        password: String = "correct-horse-battery",
        displayName: String = "Test User",
    ): Account {
        perform(post("/api/v1/auth/register"), body = mapOf(
            "email" to email, "password" to password, "displayName" to displayName,
        )).andExpect { result -> require(result.response.status == 201) { result.response.contentAsString } }
        val login = perform(post("/api/v1/auth/login"), body = mapOf(
            "email" to email, "password" to password,
        )).andReturn().json()
        return Account(email = email, token = login["token"].asText())
    }

    protected fun perform(
        builder: MockHttpServletRequestBuilder,
        token: String? = null,
        body: Any? = null,
        idempotencyKey: String? = null,
    ) = mockMvc.perform(
        builder
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .apply { if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey) }
            .apply { if (body != null) content(objectMapper.writeValueAsString(body)) },
    )

    protected fun createSheet(account: Account, name: String = "Sheet"): String =
        perform(post("/api/v1/sheets"), account.token, mapOf("name" to name))
            .andReturn().json()["id"].asText()

    protected fun addColumn(account: Account, sheetId: String, name: String, type: String): String =
        perform(post("/api/v1/sheets/$sheetId/columns"), account.token, mapOf("name" to name, "type" to type))
            .andReturn().json()["id"].asText()

    protected fun addRow(account: Account, sheetId: String): String =
        perform(post("/api/v1/sheets/$sheetId/rows"), account.token)
            .andReturn().json()["id"].asText()

    protected fun readRows(account: Account, sheetId: String, query: String = ""): JsonNode =
        perform(get("/api/v1/sheets/$sheetId/rows$query"), account.token).andReturn().json()

    protected fun batchUpdate(
        account: Account,
        sheetId: String,
        updates: List<Map<String, Any?>>,
        idempotencyKey: String? = null,
    ) = perform(
        patch("/api/v1/sheets/$sheetId/cells:batchUpdate"),
        account.token,
        mapOf("updates" to updates),
        idempotencyKey,
    )

    protected fun update(rowId: String, columnId: String, value: String?, expectedVersion: Long) =
        mapOf(
            "rowId" to rowId,
            "columnId" to columnId,
            "value" to value,
            "expectedVersion" to expectedVersion,
        )

    protected fun MvcResult.json(): JsonNode = objectMapper.readTree(response.contentAsString)

    protected data class Account(val email: String, val token: String)

    companion object {
        @JvmStatic
        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
