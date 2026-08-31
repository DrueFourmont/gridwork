package com.dfsystems.gridwork.api.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun gridworkOpenApi(@Value("\${gridwork.version}") version: String): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Gridwork API")
                    .version(version)
                    .description(
                        "Sheets, versioned cells, and automations. " +
                            "Errors are RFC 7807 problem+json and carry the X-Request-Id of the call.\n\n" +
                            "To try anything other than register and login: call POST /api/v1/auth/register, " +
                            "then POST /api/v1/auth/login, copy the `token` from the response, click " +
                            "Authorize at the top right, and paste it in. Every other endpoint needs it.",
                    ),
            )
            // Without this the document says nothing about authentication, so
            // Swagger UI shows no Authorize button and every protected endpoint
            // answers 401 the moment you press Try it out. The API worked; the
            // document simply failed to mention how to get in.
            .components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste the `token` from POST /api/v1/auth/login. Do not include the word Bearer."),
                ),
            )
            // Applied to every operation. The two auth endpoints opt out with
            // @SecurityRequirements, because requiring a token to obtain a
            // token is a loop.
            .addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))

    companion object {
        const val BEARER_SCHEME = "bearerAuth"
    }
}
