package com.petcare.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petcare.platform.model.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 403 cho từ chối ở tầng FilterSecurityInterceptor (authorizeHttpRequests theo
 * URL pattern) — errorCode "ACCESS_DENIED", phân biệt với "ACCESS_DENIED_SCOPE_MISMATCH"
 * (AccessDeniedScopeException — exception nghiệp vụ ném trong Service, xử lý ở
 * GlobalExceptionHandler). Từ chối do @PreAuthorize (method-level) ném trong lúc
 * controller đang chạy đi qua đường MVC bình thường và được GlobalExceptionHandler
 * bắt riêng — cả hai đường cùng tồn tại là chủ đích, không trùng lặp thừa.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException, ServletException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ErrorResponse.of("ACCESS_DENIED", "You do not have permission to access this resource",
                        HttpServletResponse.SC_FORBIDDEN, MDC.get("traceId"))));
    }
}
