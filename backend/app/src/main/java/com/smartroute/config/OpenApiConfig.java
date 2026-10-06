package com.smartroute.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document served at /v3/api-docs, Swagger UI at /swagger-ui.html. Every operation requires a
 * bearer token unless it says otherwise; use "Authorize" in Swagger UI with a token from /api/auth/login.
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI smartRouteOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .info(new Info()
                        .title("SmartRoute API")
                        .version("v1")
                        .description("Logistics and route optimization platform. Every error uses the ApiError format.")
                        .license(new License().name("MIT")));
    }
}
