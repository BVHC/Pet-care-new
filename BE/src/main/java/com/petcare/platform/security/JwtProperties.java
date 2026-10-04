package com.petcare.platform.security;

import java.nio.charset.StandardCharsets;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code jwt.secret} (biến môi trường {@code JWT_SECRET}): khóa HS256 ký access token (docs/adr/0003).
 * HS256 cần khóa ≥ 256 bit, nên secret ngắn hơn 32 byte UTF-8 làm app dừng ngay lúc khởi động.
 */
@ConfigurationProperties("jwt")
public record JwtProperties(String secret) {

    public static final int MIN_SECRET_BYTES = 32;

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES
                    + " bytes for HS256");
        }
    }
}
