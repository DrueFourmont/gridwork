package com.dfsystems.gridwork.api.web

import com.dfsystems.gridwork.api.service.AuthService
import com.dfsystems.gridwork.api.web.dto.LoginRequest
import com.dfsystems.gridwork.api.web.dto.LoginResponse
import com.dfsystems.gridwork.api.web.dto.RegisterRequest
import com.dfsystems.gridwork.api.web.dto.RegisterResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register and obtain a bearer token.")
class AuthController(private val auth: AuthService) {

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Created."),
        ApiResponse(responseCode = "422", description = "Email already taken, or the body failed validation."),
    )
    fun register(@Valid @RequestBody request: RegisterRequest): RegisterResponse {
        val user = auth.register(request.email, request.password, request.displayName)
        return RegisterResponse(
            userId = user.id.toString(),
            email = user.email,
            displayName = user.displayName,
        )
    }

    @PostMapping("/login")
    @Operation(
        summary = "Exchange email and password for a short lived bearer token.",
        description = "There is no refresh token. When the token expires, log in again.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Token issued."),
        ApiResponse(responseCode = "401", description = "Email or password is incorrect."),
    )
    fun login(@Valid @RequestBody request: LoginRequest): LoginResponse {
        val issued = auth.login(request.email, request.password)
        return LoginResponse(token = issued.token, expiresAt = issued.expiresAt)
    }
}
