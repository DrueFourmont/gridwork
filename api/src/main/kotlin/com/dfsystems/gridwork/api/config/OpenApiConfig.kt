package com.dfsystems.gridwork.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun gridworkOpenApi(@Value("\${gridwork.version}") version: String): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Gridwork API")
                .version(version)
                .description(
                    "Sheets, versioned cells, and automations. " +
                        "Errors are RFC 7807 problem+json and carry the X-Request-Id of the call.",
                ),
        )
}
