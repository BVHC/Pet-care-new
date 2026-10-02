package com.petcare.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Tài liệu OpenAPI ({@code /v3/api-docs}, {@code /swagger-ui.html}). Scheme {@value #BEARER_SCHEME} được khai báo
 * sẵn để controller tham chiếu bằng {@code @SecurityRequirement} khi có xác thực JWT.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI petcareOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Pet Care API").version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
