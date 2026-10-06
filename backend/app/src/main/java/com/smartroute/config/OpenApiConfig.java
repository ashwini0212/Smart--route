package com.smartroute.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at /v3/api-docs, Swagger UI at /swagger-ui.html. */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI smartRouteOpenApi() {
        return new OpenAPI().info(new Info()
                .title("SmartRoute API")
                .version("v1")
                .description("Logistics and route optimization platform. Every error uses the ApiError format.")
                .license(new License().name("MIT")));
    }
}
