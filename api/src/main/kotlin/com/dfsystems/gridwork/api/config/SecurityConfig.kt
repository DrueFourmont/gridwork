package com.dfsystems.gridwork.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * TODO(Phase 1): THIS IS SCAFFOLD WIRING, NOT A SECURITY POLICY.
 *
 * Every request is currently permitted. That is safe only because Phase 0
 * ships no endpoints beyond actuator and the OpenAPI UI, so there is nothing
 * to protect yet. Spring Security is on the classpath now so that Phase 1
 * does not have to restructure the app to add it, and because leaving the
 * default config in place would put HTTP basic auth with a generated password
 * in front of the health check.
 *
 * Phase 1 replaces permitAll with:
 *   - a JWT bearer token filter, short lived tokens, refresh by re-login,
 *     see docs/PLAN-SUMMARY.md, Auth row
 *   - authenticated() as the default for every path
 *   - an explicit permit list: the actuator health endpoints, the OpenAPI
 *     document, the Swagger UI, and the login endpoint
 *   - RFC 7807 problem+json for 401 and 403, carrying the request id
 *
 * Do not deploy this configuration anywhere public. Tracked in
 * docs/HANDOFF.md under "Known issues".
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // No browser form posts and no cookie session, so CSRF protection
            // has nothing to protect. Revisit if a cookie ever appears.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .authorizeHttpRequests { auth ->
                // TODO(Phase 1): replace with authenticated() plus a permit list.
                auth.anyRequest().permitAll()
            }
        return http.build()
    }
}
