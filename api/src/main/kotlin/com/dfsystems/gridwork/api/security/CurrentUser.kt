package com.dfsystems.gridwork.api.security

import com.dfsystems.gridwork.domain.UserId
import org.springframework.security.oauth2.jwt.Jwt

/**
 * Pulls the authenticated user id out of a validated token.
 *
 * Controllers take the Jwt as an @AuthenticationPrincipal parameter and call
 * this, rather than reaching into the SecurityContext by hand. The token has
 * already been verified by the resource server filter before any controller
 * runs, so the subject here is trustworthy.
 */
fun Jwt.userId(): UserId = JwtService.userIdOf(subject)
