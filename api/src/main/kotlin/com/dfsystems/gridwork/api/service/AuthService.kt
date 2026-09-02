package com.dfsystems.gridwork.api.service

import com.dfsystems.gridwork.core.persistence.UserEntity
import com.dfsystems.gridwork.core.persistence.UserRepository
import com.dfsystems.gridwork.api.security.JwtService
import com.dfsystems.gridwork.core.error.ApiException
import com.dfsystems.gridwork.core.error.ErrorKind
import com.dfsystems.gridwork.core.error.UnprocessableException
import com.dfsystems.gridwork.domain.UserId
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class AuthService(
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwt: JwtService,
) {

    @Transactional
    fun register(email: String, password: String, displayName: String): UserEntity {
        if (users.existsByEmailIgnoringCase(email)) {
            throw UnprocessableException("An account with that email already exists.")
        }
        return users.save(
            UserEntity(
                id = UUID.randomUUID(),
                email = email,
                passwordHash = passwordEncoder.encode(password),
                displayName = displayName,
                createdAt = Instant.now(),
            ),
        )
    }

    @Transactional(readOnly = true)
    fun login(email: String, password: String): JwtService.IssuedToken {
        val user = users.findByEmailIgnoringCase(email)
        if (user == null) {
            // Hash anyway before failing. Returning immediately for an unknown
            // email makes the response measurably faster than for a known one,
            // which turns login into an account enumeration oracle.
            passwordEncoder.encode(password)
            throw invalidCredentials()
        }
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw invalidCredentials()
        }
        return jwt.issue(UserId(user.id), user.email)
    }

    // One message for both "no such account" and "wrong password", for the
    // same reason.
    private fun invalidCredentials() =
        ApiException(ErrorKind.UNAUTHENTICATED, "Email or password is incorrect.")
}
