package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.web.RequestIdFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Boots the whole application against a real Postgres in a container, so this
 * proves the things a mock cannot: the datasource resolves, Flyway runs the
 * baseline migration, Hibernate validates against the migrated schema, the
 * security chain lets the health check through, and the actuator readiness
 * and liveness groups exist.
 *
 * Requires Docker. GitHub runners have it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ActuatorHealthIT {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health reports UP`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `readiness reports UP and includes the database`() {
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
            .andExpect(jsonPath("$.components.db.status").value("UP"))
    }

    @Test
    fun `liveness reports UP`() {
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    fun `the request id filter is in the live filter chain`() {
        mockMvc.perform(get("/actuator/health").header(RequestIdFilter.HEADER, "integration-check"))
            .andExpect(status().isOk)
            .andExpect(header().string(RequestIdFilter.HEADER, "integration-check"))
    }

    @Test
    fun `a request with no id still gets one on the response`() {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(header().exists(RequestIdFilter.HEADER))
    }

    @Test
    fun `the openapi document is served`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.info.title").value("Gridwork API"))
    }

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
