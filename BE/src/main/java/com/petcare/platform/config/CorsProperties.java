package com.petcare.platform.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.cors.allowed-origins}: danh sách origin, phân tách bằng dấu phẩy. */
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
