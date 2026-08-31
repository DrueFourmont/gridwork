package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import com.dfsystems.gridwork.api.web.RequestIdFilter
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Boots the whole application against a real Postgres: the datasource
 * resolves, Flyway runs every migration, Hibernate validates against the
 * migrated schema, the security chain lets the probes through, and the
 * actuator groups exist.
 *
 * Shares the container and the profile with the rest of the API tests, rather
 * than starting a second Postgres of its own.
 */
class ActuatorHealthIT : ApiIntegrationTest() {

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

    @Test
    fun `every phase 1 endpoint appears in the openapi document`() {
        // Swagger UI up, with the real contract in it, is one of this phase's
        // done-when criteria. An endpoint missing from the document is an
        // endpoint nobody can discover.
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/api/v1/auth/register']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/auth/login']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets/{sheetId}']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets/{sheetId}/members']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets/{sheetId}/columns']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets/{sheetId}/rows']").exists())
            .andExpect(jsonPath("$.paths['/api/v1/sheets/{sheetId}/cells:batchUpdate']").exists())
    }
}
