package com.petcare.platform.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi — Swagger UI tại /swagger-ui.html, JSON spec tại
 * /v3/api-docs (permitAll ở SecurityConfig). Khai báo scheme "bearerAuth" để
 * nút Authorize gửi kèm header Authorization: Bearer <token> — KHÔNG áp
 * dụng global (hầu hết endpoint Module 01 hiện tại là public); từng
 * Controller tự đánh dấu {@code @SecurityRequirement(name = "bearerAuth")}
 * trên đúng endpoint cần xác thực (vd AuthController#logout).
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI petcareOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Pet Care API")
                        .description("Pet Shop Management System — REST API (xem docs/api/ cho hợp đồng chi tiết từng module)")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME_NAME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
