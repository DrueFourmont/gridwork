package com.dfsystems.gridwork.api.config

import com.dfsystems.gridwork.api.web.ProblemFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.ImmutableSecret
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import javax.crypto.spec.SecretKeySpec

/**
 * The real security policy. This replaces the Phase 0 `permitAll` scaffold,
 * which was Known issue 1 in docs/HANDOFF.md.
 *
 * Everything is authenticated by default. The permit list is deliberately
 * short: health probes, the OpenAPI document and its UI, and the two auth
 * endpoints that necessarily precede having a token.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val problems: ProblemFactory,
    private val objectMapper: ObjectMapper,
) {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // No cookie session and no browser form posts, so there is no
            // CSRF surface. Revisit the moment a cookie appears.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(
                    "/actuator/health",
                    "/actuator/health/**",
                    "/actuator/info",
                ).permitAll()
                auth.requestMatchers(
                    "/v3/api-docs",
                    "/v3/api-docs/**",
                    "/swagger-ui.html",
                    "/swagger-ui/**",
                ).permitAll()
                auth.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                // Metrics are an internal surface. In Kubernetes the scraper
                // reaches them on the pod network, not through the ingress.
                auth.requestMatchers("/actuator/prometheus").denyAll()
                auth.anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { }
                // Without these, a rejected token produces an empty body and a
                // WWW-Authenticate header, and the caller gets no request id to
                // quote. Every error in this API is problem+json. See ADR 0002.
                oauth2.authenticationEntryPoint(::writeUnauthorized)
                oauth2.accessDeniedHandler { request, response, _ -> writeForbidden(request, response) }
            }
            .exceptionHandling { handling ->
                handling.authenticationEntryPoint(::writeUnauthorized)
                handling.accessDeniedHandler { request, response, _ -> writeForbidden(request, response) }
            }
        return http.build()
    }

    private fun writeUnauthorized(
        request: HttpServletRequest,
        response: HttpServletResponse,
        @Suppress("UNUSED_PARAMETER") exception: Exception,
    ) = write(request, response, HttpStatus.UNAUTHORIZED, "Authentication is required.")

    private fun writeForbidden(request: HttpServletRequest, response: HttpServletResponse) =
        write(request, response, HttpStatus.FORBIDDEN, "You do not have access to this resource.")

    private fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        status: HttpStatus,
        detail: String,
    ) {
        val problem = problems.of(status, detail, request.requestURI)
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(problem))
    }

    /**
     * bcrypt, per docs/PLAN-SUMMARY.md. Strength 12 rather than the default 10:
     * roughly four times the work per attempt, still tens of milliseconds, and
     * the difference matters only to someone brute forcing a stolen dump.
     */
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun jwtDecoder(@Value("\${gridwork.jwt.secret}") secret: String): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(secretKey(secret))
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    @Bean
    fun jwtEncoder(@Value("\${gridwork.jwt.secret}") secret: String): JwtEncoder =
        NimbusJwtEncoder(ImmutableSecret(secretKey(secret)))

    private fun secretKey(secret: String): SecretKeySpec {
        // HS256 with a key shorter than the 256 bit output is a real weakness,
        // and it is the kind that never announces itself. Fail at startup.
        require(secret.toByteArray().size >= 32) {
            "gridwork.jwt.secret must be at least 32 bytes for HS256"
        }
        return SecretKeySpec(secret.toByteArray(), "HmacSHA256")
    }
}
