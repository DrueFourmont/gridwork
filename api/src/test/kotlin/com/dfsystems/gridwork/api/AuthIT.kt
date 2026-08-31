package com.dfsystems.gridwork.api

import com.dfsystems.gridwork.api.support.ApiIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Authentication, and the closing of Known issue 1 from Phase 0: the API no
 * longer permits everything.
 */
class AuthIT : ApiIntegrationTest() {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `an unauthenticated request to a real endpoint is 401 problem+json`() {
        mockMvc.perform(get("/api/v1/sheets"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            // The Phase 0 scaffold would have returned 200 here, and a bare
            // 401 from Spring would have had no body and no request id.
            .andExpect(jsonPath("$.requestId").exists())
    }

    @Test
    fun `a garbage token is 401 rather than 500`() {
        perform(get("/api/v1/sheets"), token = "not-a-jwt")
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.requestId").exists())
    }

    @Test
    fun `a token signed with the wrong secret is rejected`() {
        // Forged with a different key, using the same Nimbus library the
        // application signs with. If this passed, the signature check would be
        // decorative and anyone could mint a token for any user id.
        val claims = com.nimbusds.jwt.JWTClaimsSet.Builder()
            .subject(java.util.UUID.randomUUID().toString())
            .issuer("gridwork")
            .expirationTime(java.util.Date(System.currentTimeMillis() + 600_000))
            .build()
        val forged = com.nimbusds.jwt.SignedJWT(
            com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256),
            claims,
        ).apply {
            sign(com.nimbusds.jose.crypto.MACSigner(
                "a-completely-different-secret-key-32-bytes-long".toByteArray()))
        }.serialize()

        perform(get("/api/v1/sheets"), token = forged).andExpect(status().isUnauthorized)
    }

    @Test
    fun `health stays public so the probes and the web app keep working`() {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk)
    }

    @Test
    fun `the openapi document stays public so swagger ui can load it`() {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk)
    }

    @Test
    fun `prometheus is not reachable from outside, signed in or not`() {
        // Metrics are an internal surface. In Kubernetes the scraper reaches
        // them on the pod network, not through the ingress.
        //
        // Anonymous gets 401 and a real user gets 403. That is Spring's
        // denyAll: with no credentials it cannot tell "you may not" from "who
        // are you", so it asks for credentials first. Both are refusals, and
        // asserting both is what proves a valid token does not open it.
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized)

        val user = register()
        perform(get("/actuator/prometheus"), user.token).andExpect(status().isForbidden)
    }

    @Test
    fun `the password is stored as a bcrypt hash and never in the clear`() {
        val password = "correct-horse-battery-staple"
        register(email = "hash-check@example.com", password = password)
        val stored = jdbc.queryForObject(
            "select password_hash from users where email = 'hash-check@example.com'",
            String::class.java,
        )!!
        require(stored != password) { "the password was stored in the clear" }
        require(stored.startsWith("\$2")) { "expected a bcrypt hash, got $stored" }
    }

    @Test
    fun `wrong password and unknown account give the same answer`() {
        // Different messages here would turn login into an oracle for which
        // email addresses have accounts.
        register(email = "known@example.com", password = "correct-horse-battery")

        val wrongPassword = perform(post("/api/v1/auth/login"), body = mapOf(
            "email" to "known@example.com", "password" to "wrong-password-entirely",
        )).andExpect(status().isUnauthorized).andReturn().json()

        val unknownAccount = perform(post("/api/v1/auth/login"), body = mapOf(
            "email" to "nobody@example.com", "password" to "wrong-password-entirely",
        )).andExpect(status().isUnauthorized).andReturn().json()

        require(wrongPassword["detail"].asText() == unknownAccount["detail"].asText()) {
            "login leaks whether an account exists"
        }
    }

    @Test
    fun `registering the same email twice is refused, case insensitively`() {
        register(email = "dup@example.com")
        perform(post("/api/v1/auth/register"), body = mapOf(
            "email" to "DUP@example.com", "password" to "correct-horse-battery", "displayName" to "Other",
        )).andExpect(status().isUnprocessableEntity)
    }

    @Test
    fun `a short password is refused with a field level error`() {
        perform(post("/api/v1/auth/register"), body = mapOf(
            "email" to "short@example.com", "password" to "short", "displayName" to "Short",
        ))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errors[0].field").value("password"))
    }
}
