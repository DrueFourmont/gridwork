package com.dfsystems.gridwork.api.security

import com.dfsystems.gridwork.domain.UserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Issues the bearer tokens the API accepts.
 *
 * HS256 with a shared secret, short lived, no refresh token. Per
 * docs/PLAN-SUMMARY.md the refresh story is "log in again", which is honest for
 * a portfolio piece and avoids building rotation, revocation, and a refresh
 * token table that nothing in the plan needs.
 *
 * The secret comes from JWT_SECRET in the environment and is never logged.
 */
@Service
class JwtService(
    private val encoder: JwtEncoder,
    @param:Value("\${gridwork.jwt.ttl}") private val ttl: Duration,
    @param:Value("\${gridwork.jwt.issuer}") private val issuer: String,
) {

    fun issue(userId: UserId, email: String): IssuedToken {
        val now = Instant.now()
        val expiresAt = now.plus(ttl)
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(now)
            .expiresAt(expiresAt)
            // Subject is the user id, not the email. Emails can change; the
            // identity a token points at must not.
            .subject(userId.value.toString())
            .claim("email", email)
            .build()
        val header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(token = token, expiresAt = expiresAt)
    }

    data class IssuedToken(val token: String, val expiresAt: Instant)

    companion object {
        /** Pulls the authenticated user id out of a validated token's subject. */
        fun userIdOf(subject: String): UserId = UserId(UUID.fromString(subject))
    }
}
