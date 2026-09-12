package com.petcare.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.platform.model.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 cho request thiếu/hết hạn/bị thu hồi JWT tại endpoint cần auth. Đọc
 * request attribute {@code auth.error} do JwtAuthenticationFilter set để trả
 * errorCode cụ thể (REQ-USE-001 — lý do từ chối cụ thể, không phải lỗi chung
 * chung). Ghi thẳng JSON vì lỗi xảy ra trước DispatcherServlet nên không đi qua
 * GlobalExceptionHandler được.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException, ServletException {
        String reason = (String) request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
        String errorCode = reason != null ? reason : "UNAUTHORIZED";
        String message = switch (errorCode) {
            case "TOKEN_EXPIRED" -> "Access token has expired";
            case "TOKEN_REVOKED" -> "Access token has been revoked";
            case "INVALID_TOKEN_TYPE" -> "A refresh token cannot be used as an access token";
            case "INVALID_TOKEN" -> "Access token is malformed or invalid";
            default -> "Authentication is required to access this resource";
        };

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of(errorCode, message, HttpServletResponse.SC_UNAUTHORIZED, MDC.get("traceId"))));
    }
}
