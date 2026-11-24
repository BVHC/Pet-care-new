package com.petcare.module.identity.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.google.client-id} (docs/adr/0152): OAuth client ID loại Web, từ biến môi trường {@code GOOGLE_CLIENT_ID}.
 * Không phải bí mật nhưng không hard-code. Trống thì BE vẫn khởi động và {@code /api/auth/google} trả 503.
 */
@ConfigurationProperties("app.google")
public record GoogleProperties(String clientId) {

    public GoogleProperties {
        clientId = clientId == null ? "" : clientId.strip(); // .env CRLF trên Windows để lại \r làm aud lệch
    }
}
